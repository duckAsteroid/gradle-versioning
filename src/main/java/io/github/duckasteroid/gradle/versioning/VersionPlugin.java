package io.github.duckasteroid.gradle.versioning;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import pl.allegro.tech.build.axion.release.domain.VersionConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes {@code project.version} from git history plus Conventional Commits - a small
 * semantic-release commit-analyzer port ({@link VersionResolver}/{@link CommitAnalyzer}). Has no
 * dependency on the {@code java} plugin at all, so it works standalone on any Gradle project, not
 * just Java ones - apply the release-flow plugin alongside this one directly if you don't need a
 * Java toolchain/POM/publishing conventions plugin too.
 *
 * <p>Still applies and configures <a href="https://github.com/allegro/axion-release-plugin">axion-release</a>
 * - it supplies the raw per-module tag-prefix scheme ({@code scmVersion.tag}) and is what runs
 * verbatim when {@code -Prelease.forceVersion} is set (see {@link #apply}). axion-release's own
 * <em>default</em> version computation - nearest tag, {@code -SNAPSHOT} if not exactly on it -
 * doesn't know anything about <em>why</em> a release is being made, so every commit bumps the
 * version the same way regardless of whether it was a bug fix or a breaking API change. Tying the
 * bump to Conventional Commits means the version number itself carries real information about the
 * size of the change, computed automatically.
 *
 * <p>Exposes {@code project.ext.tagPrefix}/{@code modulePath} (this project's own tag scheme) for
 * the release-flow plugin to reuse rather than recompute, and the {@code commitAnalyzer { }}
 * extension ({@link CommitAnalyzerExtension}) for customizing which commit types map to which
 * version bump.
 */
public class VersionPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("pl.allegro.tech.build.axion-release");

        // Custom per-module tag prefix based on Gradle path (e.g. 'sub/module/v', or plain 'v' at
        // root). Exposed via project.ext for the release-flow plugin (and anything else) to reuse
        // without recomputing.
        String customPrefix = customTagPrefix(project);
        project.getLogger().lifecycle("Project '{}' custom prefix: {}", project.getName(), customPrefix);

        // Multi-project friendly versioning strategy. NOTE: this scmVersion block configures
        // axion-release's OWN tag prefix/fallback/initial-version behavior, which is what runs when
        // release.forceVersion is set (see below) - axion-release is still applied and still fully
        // configured, it's just not what computes project.version on an ordinary build any more.
        // Keeping this block correct matters for the forceVersion backstop path even though
        // VersionResolver has its own, independently-implemented equivalent of the
        // prefix/fallbackPrefixes concepts below.
        VersionConfig scmVersion = project.getExtensions().getByType(VersionConfig.class);
        scmVersion.tag(tag -> {
            tag.getPrefix().set(customPrefix);
            // Fallback onto root project versions
            tag.getFallbackPrefixes().set(List.of("v"));
            // Absolute default
            tag.initialVersion((properties, position) -> "0.0.0+notag");
        });

        // Current branch, for folding a sanitized form into the -SNAPSHOT suffix on feature
        // branches (see resolveScmDerivedVersion below) - deliberately a small, separate git read
        // from a java-toolchain plugin's own POM-metadata git derivation (which this plugin has no
        // business needing - that's not a versioning concern), so this plugin works standalone with
        // no other duckasteroid-* plugin present at all.
        String branchName = currentBranch(project);

        // Gradle-facing configuration for which commit types map to which version bump - see
        // CommitAnalyzerExtension for the DSL. Each property is pre-populated with the
        // corresponding built-in default from CommitAnalyzer.DEFAULT_TYPE_RULES; a consumer can
        // append to any of them (commitAnalyzer { minorTypes.add('perf2') }) or replace one
        // outright (commitAnalyzer { noBumpTypes.set(['docs']) }).
        //
        // Deliberately uses addAll(...) here, NOT convention(...) - see CommitAnalyzerExtension's
        // own doc comment for why: a Property's convention is discarded (not appended to) the
        // moment a consumer calls .add(...), which would otherwise make
        // commitAnalyzer { minorTypes.add('perf2') } silently lose the default 'feat'.
        CommitAnalyzerExtension commitAnalyzerExtension =
                project.getExtensions().create("commitAnalyzer", CommitAnalyzerExtension.class);
        commitAnalyzerExtension.getMajorTypes().addAll(CommitAnalyzer.DEFAULT_TYPE_RULES.get(CommitAnalyzer.Bump.MAJOR));
        commitAnalyzerExtension.getMinorTypes().addAll(CommitAnalyzer.DEFAULT_TYPE_RULES.get(CommitAnalyzer.Bump.MINOR));
        commitAnalyzerExtension.getPatchTypes().addAll(CommitAnalyzer.DEFAULT_TYPE_RULES.get(CommitAnalyzer.Bump.PATCH));
        commitAnalyzerExtension.getNoBumpTypes().addAll(CommitAnalyzer.DEFAULT_TYPE_RULES.get(CommitAnalyzer.Bump.NONE));

        // The module's path relative to the repo root (e.g. "sub/module", "" at root) - commits are
        // only counted towards this project's version if they touched files under this path, so
        // unrelated modules in the same monorepo don't bump each other's version.
        String modulePath = project.getRootProject().getProjectDir().toPath()
                .relativize(project.getProjectDir().toPath()).toString();

        // Deferred to afterEvaluate: a consumer's own commitAnalyzer { } customization (in their
        // build.gradle, below the `plugins { id 'io.github.duckasteroid.version' }` line) hasn't
        // run yet at the point apply() returns - Gradle applies this plugin as part of processing
        // the consumer's `plugins { }` block, before the rest of their build.gradle even starts.
        // Reading commitAnalyzerExtension.toTypeRules() here directly (not in afterEvaluate) would
        // always see the untouched defaults, silently ignoring any commitAnalyzer { } block the
        // consumer wrote.
        project.afterEvaluate(p -> {
            Object forceVersion = p.findProperty("release.forceVersion");
            String version = forceVersion != null
                    ? scmVersion.getVersion()
                    : resolveScmDerivedVersion(p, customPrefix, branchName, modulePath,
                            commitAnalyzerExtension.toTypeRules(), scmVersion);
            p.setVersion(version);
        });

        // Exposed for the release-flow plugin (and anything else) that wants this project's tag
        // scheme without recomputing it.
        ExtraPropertiesExtension ext = project.getExtensions().getExtraProperties();
        ext.set("tagPrefix", customPrefix);
        ext.set("modulePath", modulePath);

        // The last final release version reachable from HEAD (plain "X.Y.Z", never decorated with
        // "-SNAPSHOT"/branch) - computed synchronously here (not deferred to afterEvaluate, unlike
        // project.version above) precisely so OTHER projects in the same build can read it back via
        // ext during their own configuration without an evaluation-order dependency. Used below to
        // fix https://github.com/duckAsteroid/gradle-versioning/issues/2: a project dependency on
        // this module from elsewhere in the build should publish this value, not this module's own
        // possibly-SNAPSHOT-decorated project.version.
        ext.set("releaseVersion", releaseVersion(project, customPrefix));

        // Reactive, not a hard dependency: only wires in if the consumer applies maven-publish
        // themselves (matches this plugin's "no dependency on java/publishing plugins" design - see
        // class doc comment). Every sibling project's ext.releaseVersion is captured as a plain
        // Map<String, String> from a rootProject.getGradle().projectsEvaluated(...) callback (the
        // same config-cache-safe snapshot pattern ReleaseFlowPlugin uses for its Target list) so the
        // POM-rewriting action below never closes over a live Project reference.
        project.getPluginManager().withPlugin("maven-publish", unused -> {
            Map<String, String> releaseVersionsByGav = new HashMap<>();
            project.getRootProject().getGradle().projectsEvaluated(gradle -> {
                for (Project sibling : project.getRootProject().getAllprojects()) {
                    ExtraPropertiesExtension siblingExt = sibling.getExtensions().getExtraProperties();
                    if (siblingExt.has("releaseVersion")) {
                        releaseVersionsByGav.put(sibling.getGroup() + ":" + sibling.getName(),
                                (String) siblingExt.get("releaseVersion"));
                    }
                }
            });
            project.getExtensions().configure(PublishingExtension.class, publishing ->
                    publishing.getPublications().withType(MavenPublication.class).configureEach(mavenPublication ->
                            mavenPublication.getPom().withXml(xml ->
                                    ProjectDependencyVersionRewriter.rewrite(xml.asElement(), releaseVersionsByGav))));
        });
    }

    private static String releaseVersion(Project project, String tagPrefix) {
        try {
            return VersionResolver.lastFinalVersion(project.getProjectDir(), tagPrefix, List.of("v"));
        } catch (Exception ignored) {
            // No real git checkout to read (e.g. an in-memory ProjectBuilder test project) - same
            // fallback rationale as resolveScmDerivedVersion below.
            return "0.0.0";
        }
    }

    private static String customTagPrefix(Project project) {
        String prefix = project.getPath().replace(':', '/') + "/v";
        while (prefix.startsWith("/")) {
            prefix = prefix.substring(1);
        }
        return prefix;
    }

    private static String currentBranch(Project project) {
        try {
            // Must run before any RepositoryBuilder().build() call - see
            // ConfigCacheSafeSystemReader's own doc comment for why constructing a Repository
            // otherwise shells out to the real git binary here, which the configuration cache
            // doesn't allow.
            ConfigCacheSafeSystemReader.install();
            try (Repository repository = new RepositoryBuilder().readEnvironment()
                    .findGitDir(project.getProjectDir()).build()) {
                return repository.getBranch() != null ? repository.getBranch() : "main";
            }
        } catch (Exception ignored) {
            // Not a git checkout - keep the fallback value.
            return "main";
        }
    }

    private static String resolveScmDerivedVersion(Project project, String tagPrefix, String branchName,
                                                     String modulePath,
                                                     Map<CommitAnalyzer.Bump, Set<String>> typeRules,
                                                     VersionConfig scmVersion) {
        try {
            // Falls back to the plain 'v' root prefix if this module has no tag of its own yet,
            // mirroring scmVersion.tag.fallbackPrefixes above.
            return VersionResolver.resolveBuildVersion(
                    project.getProjectDir(), tagPrefix, branchName, modulePath, List.of("v"), typeRules);
        } catch (Exception ignored) {
            // No real git checkout to read (e.g. an in-memory ProjectBuilder test project) - fall
            // back to axion's own resolution, same as the branch-detection fallback above.
            return scmVersion.getVersion();
        }
    }
}
