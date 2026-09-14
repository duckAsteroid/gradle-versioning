package io.github.duckasteroid.gradle.versioning.releaseflow;

import io.github.duckasteroid.gradle.versioning.ChangelogScope;
import io.github.duckasteroid.gradle.versioning.CommitAnalyzer;
import io.github.duckasteroid.gradle.versioning.CommitAnalyzerExtension;
import io.github.duckasteroid.gradle.versioning.VersionResolver;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in release-engineering tasks for a {@code develop -> release -> main} git flow. Apply
 * alongside {@code io.github.duckasteroid.version} (which computes {@code project.ext.tagPrefix} /
 * {@code modulePath} that these tasks reuse rather than recomputing) - this plugin has no
 * dependency on any Java-toolchain-configuring plugin, except {@code installReleaseWorkflows}
 * (see its own comment further down), which is the one task that genuinely needs one.
 *
 * <p>Ten tasks total. {@code tagReleaseCandidate}/{@code promoteReleaseCandidate} are the
 * release-time counterpart to the version plugin's ordinary-build version resolution (they don't
 * just <em>compute</em> a version, they actually create and push a tag for it) for the applying
 * project alone - handy for local, single-module work. {@code tagReleaseCandidates}/
 * {@code promoteReleaseCandidates} (plural) are what CI actually calls: they loop every project in
 * the build that applies this plugin, so the same mechanism covers a single-module repo, several
 * independently-versioned modules, or a shared root version, without any of those shapes needing
 * special-case configuration. {@code changelogForReleaseCandidate}/{@code changelogForRelease}
 * generate release notes for the applying project alone; {@code explainVersion}/
 * {@code explainVersions} print (and, by default, write to {@code build/version-report.json}) a
 * read-only preview of what {@code tagReleaseCandidate(s)} would actually do right now, without
 * tagging or pushing anything; {@code installReleaseWorkflows}/{@code checkReleaseWorkflows}
 * install/verify the GitHub Actions workflow files that invoke the plural tasks above. All ten are
 * plain Gradle tasks, runnable locally as well as from CI.
 *
 * <p>{@code tagReleaseCandidate}/{@code promoteReleaseCandidate} respect
 * {@code release.forceVersion} as the ultimate backstop: if set, it's used verbatim, no commit
 * analysis runs at all. This check is deliberately repeated at every call site rather than shared,
 * so the backstop can never be silently bypassed by one call site forgetting to check it.
 *
 * <p>Task actions below capture everything they need (tagPrefix, modulePath, projectDir, the
 * forceVersion property) as plain local values during task <em>configuration</em>, and shell out
 * via plain {@link ProcessBuilder} (see {@link ReleaseGitOps}) rather than any live-{@code Project}
 * access at <em>execution</em> time - accessing the live {@code Project} object from a task action
 * at execution time is unsupported under the configuration cache, so nothing in a {@code doLast}
 * block below may reference the enclosing {@code Project}. The plural aggregator tasks follow the
 * same rule: the list of applying projects is captured as plain data (a {@link Target} per project,
 * never a live {@code Project} reference) from a {@code projectsEvaluated} callback, which runs at
 * configuration time, not from inside their own {@code doLast} actions.
 *
 * <p>{@code releaseCandidates { } } controls what {@code tagReleaseCandidate(s)} does once a new RC
 * exists for a module: release candidates aren't permanent, so by default every previous RC's
 * GitHub Release (never its tag or published package) is deleted, regardless of whether the
 * candidate version itself changed mid-cycle. {@code tagReleaseCandidates} records what got
 * superseded per module in each {@code build/release-manifest.json} entry's {@code supersededTags}
 * field for the CI workflow to act on; the singular {@code tagReleaseCandidate} only prints the
 * leapfrog warning, since it has no GitHub integration point of its own to prune anything through.
 * {@code promoteReleaseCandidate(s)} never prunes - by the time a promotion happens there's only
 * ever one live RC for that module left to promote.
 */
public class ReleaseFlowPlugin implements Plugin<Project> {

    private static final String PLUGIN_ID = "io.github.duckasteroid.release-flow";
    private static final List<String> ROOT_FALLBACK = List.of("v");

    @Override
    public void apply(Project project) {
        ChangelogExtension changelogExtension = project.getExtensions().create("changelog", ChangelogExtension.class);
        changelogExtension.getRcScope().convention(ChangelogScope.SINCE_LAST_RELEASE);

        ReleaseCandidatesExtension releaseCandidatesExtension =
                project.getExtensions().create("releaseCandidates", ReleaseCandidatesExtension.class);
        releaseCandidatesExtension.getPruneSuperseded().convention(true);
        releaseCandidatesExtension.getRetain().convention(0);

        VersionReportExtension versionReportExtension =
                project.getExtensions().create("versionReport", VersionReportExtension.class);
        versionReportExtension.getEnabled().convention(true);
        versionReportExtension.getOutputFile().convention(
                project.getLayout().getBuildDirectory().file("version-report.json"));

        registerTagReleaseCandidate(project);
        registerPromoteReleaseCandidate(project);
        registerChangelogForReleaseCandidate(project, changelogExtension);
        registerChangelogForRelease(project);
        registerExplainVersion(project, versionReportExtension);

        registerTagReleaseCandidates(project);
        registerPromoteReleaseCandidates(project);
        registerExplainVersions(project, versionReportExtension);
        registerInstallReleaseWorkflows(project);
        registerCheckReleaseWorkflows(project);
    }

    // ==== single-project tasks ====

    private void registerTagReleaseCandidate(Project project) {
        project.getTasks().register("tagReleaseCandidate", task -> {
            task.setGroup("release");
            task.setDescription("Tags and pushes the next release-candidate version (auto-incrementing RC number), "
                    + "derived from conventional commits since the last final release, or release.forceVersion if set.");
            String tagPrefix = tagPrefix(project);
            String modulePath = modulePath(project);
            File repoDir = project.getProjectDir();
            String forceVersion = forceVersionProperty(project);
            Map<CommitAnalyzer.Bump, Set<String>> typeRules =
                    project.getExtensions().getByType(CommitAnalyzerExtension.class).toTypeRules();
            task.doLast(t -> {
                // Same candidate computation as an ordinary build (VersionResolver.resolveCandidateVersion,
                // sharing the fallback-to-root-'v' and path-scoped commit-filtering behavior), just
                // without the -SNAPSHOT decoration - this candidate is what actually gets tagged
                // below, not just displayed. Intended to run on every push to the `release` branch:
                // the first invocation for a given candidate version mints "-RC1", subsequent ones
                // on later commits mint "-RC2", "-RC3", etc., with no manual version bookkeeping
                // required anywhere.
                String candidate = forceVersion != null ? forceVersion
                        : VersionResolver.resolveCandidateVersion(repoDir, tagPrefix, modulePath, ROOT_FALLBACK, typeRules);
                warnIfLeapfrogged(t, "tagReleaseCandidate", repoDir, tagPrefix, candidate);
                String tag = VersionResolver.nextReleaseCandidateTag(repoDir, tagPrefix, candidate);
                ReleaseGitOps.createAndPushTag(repoDir, tag);
            });
        });
    }

    private void registerPromoteReleaseCandidate(Project project) {
        project.getTasks().register("promoteReleaseCandidate", task -> {
            task.setGroup("release");
            task.setDescription("Strips the -RCx suffix off the current release candidate and tags/pushes the final "
                    + "release version, or release.forceVersion if set.");
            String tagPrefix = tagPrefix(project);
            File repoDir = project.getProjectDir();
            String forceVersion = forceVersionProperty(project);
            task.doLast(t -> {
                // No modulePath/fallback needed here, unlike tagReleaseCandidate above: this doesn't
                // derive a version from commits at all, it just strips "-RCn" off whichever RC tag
                // is nearest reachable from HEAD - by the time you're promoting, that tag already
                // exists, already under this module's own tagPrefix.
                String tag = forceVersion != null ? tagPrefix + forceVersion : VersionResolver.promoteTag(repoDir, tagPrefix);
                ReleaseGitOps.createAndPushTag(repoDir, tag);
            });
        });
    }

    private void registerChangelogForReleaseCandidate(Project project, ChangelogExtension changelogExtension) {
        project.getTasks().register("changelogForReleaseCandidate", task -> {
            task.setGroup("release");
            task.setDescription("Generates release-candidate notes (Markdown, written to build/changelog.md), "
                    + "scoped by changelog.rcScope (default: everything since the last final release).");
            String tagPrefix = tagPrefix(project);
            String modulePath = modulePath(project);
            File repoDir = project.getProjectDir();
            Map<CommitAnalyzer.Bump, Set<String>> typeRules =
                    project.getExtensions().getByType(CommitAnalyzerExtension.class).toTypeRules();
            ChangelogScope rcScope = changelogExtension.getRcScope().get();
            File outputFile = project.getLayout().getBuildDirectory().file("changelog.md").get().getAsFile();
            task.doLast(t -> {
                List<String> messages =
                        VersionResolver.commitMessagesForChangelog(repoDir, tagPrefix, modulePath, ROOT_FALLBACK, rcScope);
                ReleaseGitOps.writeChangelog(outputFile, ChangelogGenerator.generate(messages, typeRules));
            });
        });
    }

    private void registerChangelogForRelease(Project project) {
        project.getTasks().register("changelogForRelease", task -> {
            task.setGroup("release");
            task.setDescription("Generates final release notes (Markdown, written to build/changelog.md), covering "
                    + "everything since the last final release regardless of changelog.rcScope.");
            String tagPrefix = tagPrefix(project);
            String modulePath = modulePath(project);
            File repoDir = project.getProjectDir();
            Map<CommitAnalyzer.Bump, Set<String>> typeRules =
                    project.getExtensions().getByType(CommitAnalyzerExtension.class).toTypeRules();
            File outputFile = project.getLayout().getBuildDirectory().file("changelog.md").get().getAsFile();
            task.doLast(t -> {
                List<String> messages = VersionResolver.commitMessagesForChangelog(
                        repoDir, tagPrefix, modulePath, ROOT_FALLBACK, ChangelogScope.SINCE_LAST_RELEASE);
                ReleaseGitOps.writeChangelog(outputFile, ChangelogGenerator.generate(messages, typeRules));
            });
        });
    }

    private void registerExplainVersion(Project project, VersionReportExtension versionReportExtension) {
        project.getTasks().register("explainVersion", task -> {
            task.setGroup("release");
            task.setDescription("Prints a breakdown of how this project's next release-candidate version would be "
                    + "computed right now (and, by default, writes it to build/version-report.json) - read-only, "
                    + "never tags or pushes anything. See versionReport { } to control the JSON file.");
            String module = project.getPath();
            String tagPrefix = tagPrefix(project);
            String modulePath = modulePath(project);
            File repoDir = project.getProjectDir();
            String forceVersion = forceVersionProperty(project);
            Map<CommitAnalyzer.Bump, Set<String>> typeRules =
                    project.getExtensions().getByType(CommitAnalyzerExtension.class).toTypeRules();
            boolean reportEnabled = versionReportExtension.getEnabled().get();
            File reportFile = versionReportExtension.getOutputFile().get().getAsFile();
            task.doLast(t -> {
                // No excludedModulePaths - same as the ordinary-build version resolution and the
                // singular tagReleaseCandidate above, sibling-exclusion is a
                // multi-module-aggregator-only concern (see releaseFlowTargets).
                String branch = ReleaseGitOps.currentBranch(repoDir);
                VersionReport.Entry entry = VersionReport.buildEntry(
                        module, repoDir, tagPrefix, modulePath, ROOT_FALLBACK, typeRules, List.of(), forceVersion, branch);
                System.out.println(entry.render());
                if (reportEnabled) {
                    entry.writeTo(reportFile);
                    System.out.println("explainVersion: wrote " + reportFile);
                }
            });
        });
    }

    // ==== multi-module aggregator tasks (registered once, on rootProject) ====

    /**
     * {@code tagReleaseCandidates}/{@code promoteReleaseCandidates}/{@code explainVersions} are
     * registered exactly once, on {@code rootProject}, no matter which (or how many) projects apply
     * this plugin - Gradle's own unqualified {@code ./gradlew <taskName>} matching already runs a
     * same-named task in <em>every</em> project that defines it, so registering these the same way
     * as the singular tasks above (once per applying project) would mean several independent
     * aggregator instances all running from one invocation, each redoing the full
     * enumerate/skip/tag/manifest-write cycle and racing on the same
     * {@code build/release-manifest.json}. The {@code findByName(...) == null} guard below makes
     * the first applying project's {@code apply()} call win; every subsequent one is a no-op.
     */
    private void registerTagReleaseCandidates(Project project) {
        Project root = project.getRootProject();
        if (root.getTasks().findByName("tagReleaseCandidates") != null) {
            return;
        }
        File manifestFile = root.getLayout().getBuildDirectory().file("release-manifest.json").get().getAsFile();
        File rootDir = root.getProjectDir();
        String forceVersion = forceVersionProperty(root);
        TaskProvider<Task> aggregator = root.getTasks().register("tagReleaseCandidates", task -> {
            task.setGroup("release");
            task.setDescription("Loops every project applying the release-flow plugin, tags/pushes the next "
                    + "release-candidate (and generates its changelog) for each one with qualifying commits "
                    + "since its last release, skips the rest, and writes build/release-manifest.json for CI "
                    + "to turn into GitHub Releases. Registered once on the root project regardless of which "
                    + "project(s) apply the release-flow plugin.");
        });
        // The list of applying projects is captured once, from rootProject.getGradle().projectsEvaluated
        // (fires after every project in the build has finished configuring) as plain data - never
        // live Project references - and closed over by the task's doLast action. Building that list
        // inside doLast itself instead would mean touching live Project objects at task execution
        // time, which is unsupported under the configuration cache.
        root.getGradle().projectsEvaluated(gradle -> {
            List<Target> targets = releaseFlowTargets(root);
            aggregator.configure(task -> task.doLast(t -> {
                ReleaseManifest manifest = new ReleaseManifest();
                for (Target target : targets) {
                    String candidate = forceVersion != null ? forceVersion
                            : VersionResolver.resolveCandidateVersion(target.projectDir, target.tagPrefix, target.modulePath,
                                    ROOT_FALLBACK, target.typeRules, target.excludedModulePaths);
                    String lastFinal = VersionResolver.lastFinalVersion(target.projectDir, target.tagPrefix, ROOT_FALLBACK);
                    if (candidate.equals(lastFinal)) {
                        System.out.println("tagReleaseCandidates: skipping " + target.module
                                + " - no qualifying commits since " + target.tagPrefix + lastFinal);
                        continue;
                    }
                    List<String> currentCycleTags = VersionResolver.currentCycleReleaseCandidateTags(
                            target.projectDir, target.tagPrefix, ROOT_FALLBACK);
                    warnIfLeapfrogged(t, "tagReleaseCandidates: " + target.module, currentCycleTags, target.tagPrefix, candidate);
                    List<String> messages = VersionResolver.commitMessagesForChangelog(target.projectDir, target.tagPrefix,
                            target.modulePath, ROOT_FALLBACK, target.rcScope, target.excludedModulePaths);
                    ReleaseGitOps.writeChangelog(target.changelogFile, ChangelogGenerator.generate(messages, target.typeRules));
                    String tag = VersionResolver.nextReleaseCandidateTag(target.projectDir, target.tagPrefix, candidate);
                    ReleaseGitOps.createAndPushTag(target.projectDir, tag);
                    // Every current-cycle RC (regardless of which candidate version it was minted
                    // under) is superseded by the one just tagged, except however many the
                    // releaseCandidates { } extension says to retain. Only the GitHub Release gets
                    // deleted (by the CI workflow, from this manifest entry) - the tag and its
                    // published package are left alone.
                    List<String> supersededTags = target.pruneSuperseded
                            ? new ArrayList<>(currentCycleTags.subList(
                                    Math.min(target.retain, currentCycleTags.size()), currentCycleTags.size()))
                            : List.of();
                    // A directory to glob at workflow time, not literal jar/sources/javadoc file
                    // names - see ReleaseManifest's own doc comment for why.
                    String artifactsDir = ReleaseGitOps.relativePath(rootDir, target.libsDir);
                    manifest.add(target.module, tag, ReleaseGitOps.relativePath(rootDir, target.changelogFile),
                            supersededTags, artifactsDir);
                }
                manifest.writeTo(manifestFile);
                System.out.println("tagReleaseCandidates: wrote " + manifestFile
                        + " (" + manifest.size() + " " + (manifest.size() == 1 ? "entry" : "entries") + ")");
            }));
        });
    }

    private void registerPromoteReleaseCandidates(Project project) {
        Project root = project.getRootProject();
        if (root.getTasks().findByName("promoteReleaseCandidates") != null) {
            return;
        }
        File manifestFile = root.getLayout().getBuildDirectory().file("release-manifest.json").get().getAsFile();
        File rootDir = root.getProjectDir();
        String forceVersion = forceVersionProperty(root);
        TaskProvider<Task> aggregator = root.getTasks().register("promoteReleaseCandidates", task -> {
            task.setGroup("release");
            task.setDescription("Loops every project applying the release-flow plugin, promotes each one's "
                    + "nearest reachable release-candidate to a final release (generating its release notes "
                    + "first), skips projects with no pending RC, and writes build/release-manifest.json for "
                    + "CI to turn into GitHub Releases. Registered once on the root project.");
        });
        root.getGradle().projectsEvaluated(gradle -> {
            List<Target> targets = releaseFlowTargets(root);
            aggregator.configure(task -> task.doLast(t -> {
                ReleaseManifest manifest = new ReleaseManifest();
                for (Target target : targets) {
                    String tag;
                    try {
                        tag = forceVersion != null ? target.tagPrefix + forceVersion
                                : VersionResolver.promoteTag(target.projectDir, target.tagPrefix);
                    } catch (IllegalStateException ignored) {
                        System.out.println("promoteReleaseCandidates: skipping " + target.module
                                + " - no release-candidate tag reachable from HEAD");
                        continue;
                    }
                    // Must run BEFORE createAndPushTag below - the changelog generator looks for the
                    // last *final* tag reachable from HEAD, which the about-to-be-created new tag
                    // would otherwise shadow.
                    List<String> messages = VersionResolver.commitMessagesForChangelog(target.projectDir, target.tagPrefix,
                            target.modulePath, ROOT_FALLBACK, ChangelogScope.SINCE_LAST_RELEASE, target.excludedModulePaths);
                    ReleaseGitOps.writeChangelog(target.changelogFile, ChangelogGenerator.generate(messages, target.typeRules));
                    ReleaseGitOps.createAndPushTag(target.projectDir, tag);
                    String artifactsDir = ReleaseGitOps.relativePath(rootDir, target.libsDir);
                    manifest.add(target.module, tag, ReleaseGitOps.relativePath(rootDir, target.changelogFile),
                            List.of(), artifactsDir);
                }
                manifest.writeTo(manifestFile);
                System.out.println("promoteReleaseCandidates: wrote " + manifestFile
                        + " (" + manifest.size() + " " + (manifest.size() == 1 ? "entry" : "entries") + ")");
            }));
        });
    }

    /**
     * The read-only, multi-module-aware preview counterpart to {@code tagReleaseCandidates} - same
     * {@link #releaseFlowTargets} enumeration, same skip/tag decision
     * ({@code candidate.equals(lastFinal)}), but never tags, pushes, or mints anything.
     * {@code versionReport { } } is read from whichever applying project's {@code apply()} call wins
     * the registration guard below (normally root), same "first applying project decides" rule
     * {@code installReleaseWorkflows} already uses for its Java-toolchain substitution.
     */
    private void registerExplainVersions(Project project, VersionReportExtension versionReportExtension) {
        Project root = project.getRootProject();
        if (root.getTasks().findByName("explainVersions") != null) {
            return;
        }
        String forceVersion = forceVersionProperty(root);
        boolean reportEnabled = versionReportExtension.getEnabled().get();
        File reportFile = versionReportExtension.getOutputFile().get().getAsFile();
        TaskProvider<Task> aggregator = root.getTasks().register("explainVersions", task -> {
            task.setGroup("release");
            task.setDescription("Loops every project applying the release-flow plugin and prints a breakdown "
                    + "of how each one's next release-candidate version would be computed right now (and, by "
                    + "default, writes them to build/version-report.json) - the read-only preview counterpart "
                    + "to tagReleaseCandidates. Registered once on the root project.");
        });
        root.getGradle().projectsEvaluated(gradle -> {
            List<Target> targets = releaseFlowTargets(root);
            aggregator.configure(task -> task.doLast(t -> {
                List<VersionReport.Entry> entries = new ArrayList<>();
                for (Target target : targets) {
                    String branch = ReleaseGitOps.currentBranch(target.projectDir);
                    VersionReport.Entry entry = VersionReport.buildEntry(target.module, target.projectDir,
                            target.tagPrefix, target.modulePath, ROOT_FALLBACK, target.typeRules,
                            target.excludedModulePaths, forceVersion, branch);
                    // Same skip/tag decision as tagReleaseCandidates above, so this preview can
                    // never disagree with what tagging would actually do.
                    if (entry.getCandidate().equals(entry.getLastFinal())) {
                        entry.setAction("SKIP");
                        entry.setReason("no qualifying commits since " + target.tagPrefix + entry.getLastFinal());
                    } else {
                        entry.setAction("TAG");
                        entry.setReason("would mint " + entry.getNextReleaseCandidateTag());
                    }
                    System.out.println("explainVersions: " + target.module + " - " + entry.getAction()
                            + " (" + entry.getReason() + ")");
                    entries.add(entry);
                }
                for (VersionReport.Entry entry : entries) {
                    System.out.println(entry.render());
                }
                if (reportEnabled) {
                    VersionReport.writeJsonArray(entries, reportFile);
                    System.out.println("explainVersions: wrote " + reportFile
                            + " (" + entries.size() + " " + (entries.size() == 1 ? "entry" : "entries") + ")");
                }
            }));
        });
    }

    /**
     * Installs/verifies the {@code release-candidate.yml}/{@code promote-release.yml} GitHub Actions
     * workflows bundled as plugin resources, templating the one variable bit (the Java toolchain
     * version) at install time via a plain {@code @@JAVA_VERSION@@} placeholder - deliberately NOT
     * Gradle's {@code expand()}/GString-equivalent syntax, since the workflow YAML itself contains
     * live {@code ${{ github.token }}}-style GitHub Actions expressions that must survive untouched.
     *
     * <p>There is exactly one workflow pair per repository, regardless of how many projects apply
     * this plugin - like the plural tasks above, registered once on {@code rootProject}. The Java
     * toolchain substituted into the template comes from whichever project's {@code apply()} call
     * wins the guard (normally root, if root applies a Java-toolchain-configuring plugin).
     *
     * <p>Uses {@code findByType}, not {@code getByType}: this plugin itself must stay applicable
     * with only {@code io.github.duckasteroid.version} present - a project with no
     * java-toolchain-configuring plugin at all simply has no {@link JavaPluginExtension} to read,
     * and that must not crash <em>applying the plugin</em>. {@code javaVersion} ends up null in that
     * case; the actual failure (a clear, deliberate one, not a mystifying
     * {@code UnknownDomainObjectException}) is deferred to this task's own {@code doLast} - the one
     * task that genuinely can't proceed without it.
     */
    private void registerInstallReleaseWorkflows(Project project) {
        Project root = project.getRootProject();
        if (root.getTasks().findByName("installReleaseWorkflows") != null) {
            return;
        }
        File workflowsDir = new File(root.getProjectDir(), ".github/workflows");
        JavaPluginExtension javaToolchain = project.getExtensions().findByType(JavaPluginExtension.class);
        String javaVersion = javaToolchain != null
                ? javaToolchain.getToolchain().getLanguageVersion().get().toString()
                : null;
        boolean force = root.hasProperty("duckasteroid.workflows.force");
        String pluginVersion = loadWorkflowResource("workflow-version.txt").trim();
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put("release-candidate.yml", loadWorkflowResource("release-candidate.yml"));
        templates.put("promote-release.yml", loadWorkflowResource("promote-release.yml"));

        root.getTasks().register("installReleaseWorkflows", task -> {
            task.setGroup("release");
            task.setDescription("Installs the release-candidate/promote-release GitHub Actions workflows bundled with "
                    + "this plugin into .github/workflows/, without clobbering files edited since a previous install "
                    + "(use -Pduckasteroid.workflows.force=true to override). Registered once on the root project "
                    + "regardless of which project(s) apply the release-flow plugin.");
            task.doLast(t -> {
                if (javaVersion == null) {
                    throw new GradleException("installReleaseWorkflows needs a java-toolchain-configuring plugin "
                            + "applied somewhere in this build, to know what Java version to put in the installed "
                            + "workflows' setup-java step - io.github.duckasteroid.version alone is not enough for "
                            + "this one task.");
                }
                for (Map.Entry<String, String> entry : templates.entrySet()) {
                    String body = entry.getValue().replace("@@JAVA_VERSION@@", javaVersion);
                    File target = new File(workflowsDir, entry.getKey());
                    ManagedFileInstaller.Result result = ManagedFileInstaller.install(target, "release-flow", pluginVersion, body, force);
                    switch (result) {
                        case INSTALLED, OVERWRITTEN, FORCED ->
                                System.out.println("installReleaseWorkflows: wrote " + target + " (" + result + ")");
                        case UP_TO_DATE ->
                                System.out.println("installReleaseWorkflows: " + target + " already up to date");
                        case SKIPPED_FOREIGN ->
                                t.getLogger().warn("installReleaseWorkflows: skipped " + target + " - no duckasteroid "
                                        + "marker found (foreign or hand-written file); not overwriting it.");
                        case SKIPPED_MODIFIED ->
                                t.getLogger().warn("installReleaseWorkflows: skipped " + target + " - edited since "
                                        + "install (its body no longer matches its marker hash); not overwriting it. "
                                        + "Re-run with -Pduckasteroid.workflows.force=true to discard those edits and "
                                        + "take the new version anyway.");
                    }
                }
            });
        });
    }

    private void registerCheckReleaseWorkflows(Project project) {
        Project root = project.getRootProject();
        if (root.getTasks().findByName("checkReleaseWorkflows") != null) {
            return;
        }
        File workflowsDir = new File(root.getProjectDir(), ".github/workflows");
        String pluginVersion = loadWorkflowResource("workflow-version.txt").trim();
        List<String> fileNames = List.of("release-candidate.yml", "promote-release.yml");
        root.getTasks().register("checkReleaseWorkflows", task -> {
            task.setGroup("release");
            task.setDescription("Warns (never fails) if the installed release workflows are missing, unmarked, edited "
                    + "since install, or older than the currently applied release-flow plugin version. Not wired into "
                    + "build/check, so it never adds noise to an ordinary CI run. Registered once on the root project.");
            task.doLast(t -> {
                for (String fileName : fileNames) {
                    File target = new File(workflowsDir, fileName);
                    ManagedFileChecker.CheckResult result = ManagedFileChecker.check(target, "release-flow", pluginVersion);
                    switch (result.getStatus()) {
                        case UP_TO_DATE ->
                                System.out.println("checkReleaseWorkflows: " + target + " is up to date (" + pluginVersion + ")");
                        case MISSING ->
                                t.getLogger().warn("checkReleaseWorkflows: " + target
                                        + " does not exist - run installReleaseWorkflows to install it.");
                        case NOT_OURS ->
                                t.getLogger().warn("checkReleaseWorkflows: " + target
                                        + " has no duckasteroid marker - it wasn't installed by installReleaseWorkflows.");
                        case TAMPERED ->
                                t.getLogger().warn("checkReleaseWorkflows: " + target + " has been edited since install "
                                        + "(its body no longer matches its marker hash from version "
                                        + result.getInstalledVersion() + ").");
                        case STALE ->
                                t.getLogger().warn("checkReleaseWorkflows: " + target + " was installed from the "
                                        + "release-flow plugin " + result.getInstalledVersion() + ", but " + pluginVersion
                                        + " is currently applied - run installReleaseWorkflows to update it.");
                    }
                }
            });
        });
    }

    // ==== shared helpers ====

    private static String tagPrefix(Project project) {
        return (String) project.getExtensions().getExtraProperties().get("tagPrefix");
    }

    private static String modulePath(Project project) {
        return (String) project.getExtensions().getExtraProperties().get("modulePath");
    }

    private static String forceVersionProperty(Project project) {
        Object value = project.findProperty("release.forceVersion");
        return value != null ? value.toString() : null;
    }

    private static void warnIfLeapfrogged(Task task, String taskLabel, File repoDir, String tagPrefix, String candidate) {
        List<String> currentCycleTags = VersionResolver.currentCycleReleaseCandidateTags(repoDir, tagPrefix, ROOT_FALLBACK);
        warnIfLeapfrogged(task, taskLabel, currentCycleTags, tagPrefix, candidate);
    }

    /**
     * Release candidates aren't permanent - a later commit can raise the bump mid-cycle and
     * "leapfrog" straight past whatever the most recent RC's version was (e.g. {@code v1.4.0-RC3}
     * followed by {@code v1.4.1-RC1}). Never blocks, just flags it - see the {@code releaseCandidates
     * { } } extension for what {@code tagReleaseCandidates} does about the superseded RC's GitHub
     * Release once this happens.
     */
    private static void warnIfLeapfrogged(Task task, String taskLabel, List<String> currentCycleTags,
                                           String tagPrefix, String candidate) {
        if (currentCycleTags.isEmpty()) {
            return;
        }
        String previousVersion = currentCycleTags.get(0).substring(tagPrefix.length()).replaceAll("-RC\\d+$", "");
        if (!previousVersion.equals(candidate)) {
            task.getLogger().warn(taskLabel + ": candidate jumped from " + tagPrefix + previousVersion + " to "
                    + tagPrefix + candidate + " - new qualifying commits raised the bump since " + currentCycleTags.get(0));
        }
    }

    /** Plain data snapshot of one applying project - see {@link #releaseFlowTargets}. */
    private static final class Target {
        final String module;
        final File projectDir;
        final String tagPrefix;
        final String modulePath;
        final Map<CommitAnalyzer.Bump, Set<String>> typeRules;
        final ChangelogScope rcScope;
        final File changelogFile;
        final boolean pruneSuperseded;
        final int retain;
        final File libsDir;
        List<String> excludedModulePaths = List.of();

        Target(String module, File projectDir, String tagPrefix, String modulePath,
               Map<CommitAnalyzer.Bump, Set<String>> typeRules, ChangelogScope rcScope, File changelogFile,
               boolean pruneSuperseded, int retain, File libsDir) {
            this.module = module;
            this.projectDir = projectDir;
            this.tagPrefix = tagPrefix;
            this.modulePath = modulePath;
            this.typeRules = typeRules;
            this.rcScope = rcScope;
            this.changelogFile = changelogFile;
            this.pruneSuperseded = pruneSuperseded;
            this.retain = retain;
            this.libsDir = libsDir;
        }
    }

    /**
     * Every project in root's build that has this plugin applied, as plain data (never live
     * {@code Project} references - see the class doc comment for why) for the plural aggregator
     * tasks to loop over. Must be called from a configuration-time hook (e.g.
     * {@code rootProject.getGradle().projectsEvaluated(...)}) after every project has finished
     * applying its plugins, not from inside a task's {@code doLast}.
     *
     * <p>Each target also carries its own {@code excludedModulePaths}: every OTHER target's
     * modulePath - without this, a root project's empty modulePath would also bump/release on a
     * commit that's entirely a sibling's own independently-versioned subproject; harmless for
     * siblings not actually nested under this target's own modulePath, since excluding a directory
     * this target's include-path never reached in the first place is a no-op. A sibling's own
     * empty-string root modulePath is naturally a no-op exclusion too. Computed here (configuration
     * time), not from inside a task's {@code doLast}, so the plural aggregator tasks stay
     * configuration-cache-safe.
     */
    private static List<Target> releaseFlowTargets(Project root) {
        List<Target> targets = new ArrayList<>();
        for (Project p : root.getAllprojects()) {
            if (p.getPluginManager().hasPlugin(PLUGIN_ID)) {
                targets.add(new Target(
                        p.getPath(),
                        p.getProjectDir(),
                        tagPrefix(p),
                        modulePath(p),
                        p.getExtensions().getByType(CommitAnalyzerExtension.class).toTypeRules(),
                        p.getExtensions().getByType(ChangelogExtension.class).getRcScope().get(),
                        p.getLayout().getBuildDirectory().file("changelog.md").get().getAsFile(),
                        p.getExtensions().getByType(ReleaseCandidatesExtension.class).getPruneSuperseded().get(),
                        p.getExtensions().getByType(ReleaseCandidatesExtension.class).getRetain().get(),
                        p.getLayout().getBuildDirectory().dir("libs").get().getAsFile()));
            }
        }
        for (Target target : targets) {
            List<String> excluded = new ArrayList<>();
            for (Target other : targets) {
                if (other != target) {
                    excluded.add(other.modulePath);
                }
            }
            target.excludedModulePaths = excluded;
        }
        return targets;
    }

    /** Reads a bundled workflow resource (see src/main/resources/.../releaseflow/workflows/) as text. */
    private static String loadWorkflowResource(String fileName) {
        String path = "io/github/duckasteroid/gradle/versioning/releaseflow/workflows/" + fileName;
        try (InputStream stream = ManagedFileInstaller.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
