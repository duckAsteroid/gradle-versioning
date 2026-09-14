package io.github.duckasteroid.gradle.versioning;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives project versions from git history rather than a single nearest tag.
 *
 * <p>See the versioning documentation for the full picture (why this exists, the
 * develop/release/main flow, worked examples). In short, the algorithm for an ordinary build
 * ({@link #resolveBuildVersion}) is:
 *
 * <ol>
 *   <li>If HEAD is exactly on a real tag matching tagPrefix, use it verbatim - no computation
 *       needed.
 *   <li>Otherwise, find the last <em>final</em> release tag (a plain "X.Y.Z", no "-RC"/"-SNAPSHOT"
 *       suffix) reachable from HEAD under tagPrefix. If this module has no final tag of its own
 *       yet, retry each of fallbackPrefixes in turn (see {@link #lastFinalVersionLookup}) - e.g. a
 *       brand-new subproject in a multi-module build inherits the root project's version line
 *       instead of starting over at "0.0.0".
 *   <li>Walk the commits between that tag and HEAD, optionally restricted to ones that touched
 *       modulePath (see {@link #commitMessagesSince}), and classify them with
 *       {@link CommitAnalyzer} using typeRules (feat/fix/perf/BREAKING CHANGE -&gt;
 *       minor/patch/patch/major by default, but configurable via {@link CommitAnalyzerExtension}).
 *   <li>Bump the last-final version by the highest classification found (see {@link #bumpVersion}),
 *       and decorate it with "-SNAPSHOT" (plus the branch name, for feature branches) since we're
 *       not exactly on a tag.
 * </ol>
 *
 * <p>{@link #nextReleaseCandidateTag} and {@link #promoteTag} are the two release-engineering
 * operations built on the same primitives, used by the release-flow plugin's tasks rather than by
 * ordinary builds: the former mints the next "-RCn" tag for the computed candidate version, the
 * latter strips the "-RCn" suffix off the nearest reachable RC tag to produce the final release
 * tag.
 *
 * <p>{@link #commitMessagesForChangelog} is the equivalent supporting operation for release notes:
 * it reuses the same tag-lookup machinery to decide which commits are "new" for a changelog, just
 * with its own scope rules rather than the version-bump rules above.
 *
 * <p>Deliberately uses JGit rather than shelling out to the {@code git} CLI:
 * {@code resolveBuildVersion} is called from {@code version = ...} in {@link VersionPlugin} at
 * Gradle <em>configuration</em> time, and starting an external process during configuration is
 * incompatible with the Gradle configuration cache. JGit is a pure-Java implementation, but it is
 * NOT automatically config-cache-safe on its own - the first time any code in the JVM constructs a
 * {@code Repository}, JGit itself shells out to the real {@code git} binary to discover the
 * system-level git config (see {@link ConfigCacheSafeSystemReader}, which {@link #withRepo}
 * installs before every {@code RepositoryBuilder().build()} call in this class to suppress that).
 * The release-flow plugin's tasks that call into this class only do so from {@code doLast { }}
 * (task <em>execution</em> time, where external processes are fine), but they share the same
 * JGit-based code for simplicity and to avoid two parallel implementations of the same logic.
 *
 * <p>Every public method here that would otherwise propagate a checked JGit/IO exception wraps it
 * in an unchecked {@link RuntimeException} instead (see {@link #withRepo}) - callers (this
 * project's own {@link VersionPlugin}, and any consumer script) can catch a plain
 * {@code catch (Exception e)} around a call into this class exactly as they would in a
 * dynamically-typed script, without every call site needing its own checked-exception handling.
 */
public final class VersionResolver {

    private VersionResolver() {
    }

    // Strict "X.Y.Z" with nothing else - deliberately does NOT match "-RC1"/"-SNAPSHOT" tags, since
    // those must never be mistaken for a base to bump from (see lastFinalVersionLookup).
    private static final Pattern FINAL_VERSION_SUFFIX = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern RC_VERSION_SUFFIX = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)-RC(\\d+)$");
    private static final Pattern RC_SUFFIX = Pattern.compile("-RC\\d+$");
    private static final String TAG_REF_PREFIX = "refs/tags/";
    private static final Set<String> STEADY_STATE_BRANCHES = Set.of("main", "master", "release", "develop");

    /** (matchedPrefix, plain "X.Y.Z" version) - see {@link #lastFinalVersionLookup}. */
    private record PrefixAndVersion(String prefix, String version) {
    }

    // ==== resolveBuildVersion overloads (Groovy default-parameter equivalents) ====

    public static String resolveBuildVersion(File repoDir, String tagPrefix, String branchName) {
        return resolveBuildVersion(repoDir, tagPrefix, branchName, "");
    }

    public static String resolveBuildVersion(File repoDir, String tagPrefix, String branchName, String modulePath) {
        return resolveBuildVersion(repoDir, tagPrefix, branchName, modulePath, List.of());
    }

    public static String resolveBuildVersion(File repoDir, String tagPrefix, String branchName, String modulePath,
                                              List<String> fallbackPrefixes) {
        return resolveBuildVersion(repoDir, tagPrefix, branchName, modulePath, fallbackPrefixes,
                CommitAnalyzer.DEFAULT_TYPE_RULES);
    }

    public static String resolveBuildVersion(File repoDir, String tagPrefix, String branchName, String modulePath,
                                              List<String> fallbackPrefixes,
                                              Map<CommitAnalyzer.Bump, Set<String>> typeRules) {
        return resolveBuildVersion(repoDir, tagPrefix, branchName, modulePath, fallbackPrefixes, typeRules, List.of());
    }

    /**
     * The version to use for an ordinary build (e.g. {@code ./gradlew build},
     * {@code publishToMavenLocal}).
     *
     * @param repoDir any directory inside the git repo (JGit walks upward to find the .git dir)
     * @param tagPrefix this module's own tag prefix, e.g. "v" at the root or "sub/module/v"
     * @param branchName the current git branch, used to fold a sanitized form into the -SNAPSHOT
     *        suffix on feature branches (e.g. "1.0.1-cool-stuff-SNAPSHOT") so a build made while
     *        working on a feature branch is visibly distinguishable from one made on develop/release
     * @param modulePath this module's directory relative to the repo root ("" at the root); commits
     *        that didn't touch this path don't count towards this module's bump
     * @param fallbackPrefixes tried in order if tagPrefix has no final tag reachable from HEAD yet
     * @param typeRules see {@link CommitAnalyzer#DEFAULT_TYPE_RULES}; defaults to the built-in
     *        mapping. In the Gradle plugin this normally comes from the {@code commitAnalyzer { }}
     *        extension (see {@link CommitAnalyzerExtension#toTypeRules}), not the bare default.
     * @param excludedModulePaths directories to exclude from modulePath's scope even though they'd
     *        otherwise match - e.g. a root project (modulePath "") in "mixed mode" excluding the
     *        modulePaths of subprojects that independently apply the release-flow plugin, so root
     *        doesn't also bump/release on a commit that's entirely one of those subprojects' own. A
     *        commit only needs to touch one path outside every excluded directory to still count -
     *        excluding "api" doesn't hide a commit that touches both "api/" and a root-level file.
     */
    public static String resolveBuildVersion(File repoDir, String tagPrefix, String branchName, String modulePath,
                                              List<String> fallbackPrefixes,
                                              Map<CommitAnalyzer.Bump, Set<String>> typeRules,
                                              List<String> excludedModulePaths) {
        return withRepo(repoDir, repo -> {
            String exact = exactTagAt(repo, tagPrefix);
            if (exact != null) {
                // HEAD is a real, already-released commit (final or RC) - nothing to compute.
                return exact;
            }
            String candidate = resolveCandidateVersionIn(repo, tagPrefix, modulePath, fallbackPrefixes, typeRules,
                    excludedModulePaths);
            String sanitizedBranch = sanitizeBranch(branchName);
            return sanitizedBranch != null ? candidate + "-" + sanitizedBranch + "-SNAPSHOT" : candidate + "-SNAPSHOT";
        });
    }

    // ==== resolveCandidateVersion overloads ====

    public static String resolveCandidateVersion(File repoDir, String tagPrefix) {
        return resolveCandidateVersion(repoDir, tagPrefix, "");
    }

    public static String resolveCandidateVersion(File repoDir, String tagPrefix, String modulePath) {
        return resolveCandidateVersion(repoDir, tagPrefix, modulePath, List.of());
    }

    public static String resolveCandidateVersion(File repoDir, String tagPrefix, String modulePath,
                                                  List<String> fallbackPrefixes) {
        return resolveCandidateVersion(repoDir, tagPrefix, modulePath, fallbackPrefixes,
                CommitAnalyzer.DEFAULT_TYPE_RULES);
    }

    public static String resolveCandidateVersion(File repoDir, String tagPrefix, String modulePath,
                                                  List<String> fallbackPrefixes,
                                                  Map<CommitAnalyzer.Bump, Set<String>> typeRules) {
        return resolveCandidateVersion(repoDir, tagPrefix, modulePath, fallbackPrefixes, typeRules, List.of());
    }

    /**
     * The last final release version, bumped by the conventional commits since it - with no
     * "-SNAPSHOT"/branch decoration. This is what an actual release tag (RC or final) should be
     * based on; {@link #resolveBuildVersion} calls this too and then decorates the result for
     * ordinary (non-release) builds.
     */
    public static String resolveCandidateVersion(File repoDir, String tagPrefix, String modulePath,
                                                  List<String> fallbackPrefixes,
                                                  Map<CommitAnalyzer.Bump, Set<String>> typeRules,
                                                  List<String> excludedModulePaths) {
        return withRepo(repoDir, repo ->
                resolveCandidateVersionIn(repo, tagPrefix, modulePath, fallbackPrefixes, typeRules, excludedModulePaths));
    }

    // ==== lastFinalVersion overloads ====

    public static String lastFinalVersion(File repoDir, String tagPrefix) {
        return lastFinalVersion(repoDir, tagPrefix, List.of());
    }

    /**
     * Highest final release tag (prefix + strict X.Y.Z, no suffix) reachable from HEAD. If none
     * exists under tagPrefix, retries each of fallbackPrefixes in order (e.g. a brand-new module
     * inherits the root project's version line instead of starting over at "0.0.0"). Falls all the
     * way back to "0.0.0" if nothing matches under any prefix - a fresh repo with no releases yet.
     */
    public static String lastFinalVersion(File repoDir, String tagPrefix, List<String> fallbackPrefixes) {
        return withRepo(repoDir, repo -> lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes).version());
    }

    /**
     * The full tag name (with prefix) for the <em>next</em> release-candidate to mint, e.g.
     * "v1.1.0-RC2". Does not create or push anything itself - that's the caller's job (see the
     * release-flow plugin's {@code tagReleaseCandidate} task). candidateVersion is normally
     * whatever {@link #resolveCandidateVersion} just computed, but the caller may instead pass a
     * value straight from {@code -Prelease.forceVersion} to bypass commit analysis entirely.
     */
    public static String nextReleaseCandidateTag(File repoDir, String tagPrefix, String candidateVersion) {
        return withRepo(repoDir, repo -> {
            int next = 1;
            // Find every existing "<tagPrefix><candidateVersion>-RC<n>" tag reachable from HEAD and
            // pick next = highest n found + 1, so re-running this after a new commit (or
            // re-running it against the same commit, harmlessly) always advances the RC counter
            // rather than colliding with an existing tag.
            Pattern rcPattern = Pattern.compile("^" + Pattern.quote(tagPrefix + candidateVersion + "-RC") + "(\\d+)$");
            for (String tag : tagsMergedIntoHead(repo)) {
                Matcher m = rcPattern.matcher(tag);
                if (m.matches()) {
                    int n = Integer.parseInt(m.group(1)) + 1;
                    if (n > next) {
                        next = n;
                    }
                }
            }
            return tagPrefix + candidateVersion + "-RC" + next;
        });
    }

    /**
     * The full tag name (with prefix) for the final release, derived by stripping the "-RCn"
     * suffix off the nearest reachable release-candidate tag. Used by the release-flow plugin's
     * {@code promoteReleaseCandidate} task when an accepted RC on the {@code release} branch is
     * merged to {@code main}. Throws if there's no RC tag reachable from HEAD at all - promoting
     * only makes sense once at least one RC has actually been cut.
     */
    public static String promoteTag(File repoDir, String tagPrefix) {
        return withRepo(repoDir, repo -> {
            String rcTag = nearestReachableTag(repo, tagPrefix, RC_SUFFIX);
            if (rcTag == null) {
                throw new IllegalStateException(
                        "No release-candidate tag found reachable from HEAD matching '" + tagPrefix
                                + "*-RC*' - nothing to promote");
            }
            Matcher m = RC_VERSION_SUFFIX.matcher(rcTag.substring(tagPrefix.length()));
            if (!m.matches()) {
                throw new IllegalStateException("Tag '" + rcTag + "' does not look like a release candidate");
            }
            return tagPrefix + m.group(1) + "." + m.group(2) + "." + m.group(3);
        });
    }

    // ==== currentCycleReleaseCandidateTags overloads ====

    public static List<String> currentCycleReleaseCandidateTags(File repoDir, String tagPrefix) {
        return currentCycleReleaseCandidateTags(repoDir, tagPrefix, List.of());
    }

    /**
     * Every release-candidate tag (tagPrefix + "X.Y.Z-RCn") reachable from HEAD but not reachable
     * from the last final release tag's commit - i.e. every RC minted during the current release
     * cycle, regardless of which candidate version it was minted under (a later commit can raise
     * the bump mid-cycle, "leapfrogging" an earlier RC's version - both still belong to the same
     * cycle). Ordered nearest-to-HEAD first (most recently created). Used by the release-flow
     * plugin's {@code tagReleaseCandidates} to warn when the freshly computed candidate leapfrogs
     * the most recent RC's version, and to decide which of this cycle's RC GitHub Releases are
     * superseded and safe to prune.
     */
    public static List<String> currentCycleReleaseCandidateTags(File repoDir, String tagPrefix,
                                                                  List<String> fallbackPrefixes) {
        return withRepo(repoDir, repo -> {
            PrefixAndVersion lookup = lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes);
            ObjectId sinceCommit = commitForTag(repo, lookup.prefix() + lookup.version());
            Map<String, String> tagByCommit = new HashMap<>();
            for (Ref tagRef : allTagRefs(repo)) {
                String name = shortName(tagRef);
                if (name.startsWith(tagPrefix) && RC_VERSION_SUFFIX.matcher(name.substring(tagPrefix.length())).matches()) {
                    tagByCommit.put(peeledCommitId(repo, tagRef).getName(), name);
                }
            }
            ObjectId headId = repo.resolve("HEAD");
            if (headId == null || tagByCommit.isEmpty()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(headId));
                if (sinceCommit != null) {
                    walk.markUninteresting(walk.parseCommit(sinceCommit));
                }
                for (RevCommit commit : walk) {
                    String tag = tagByCommit.get(commit.getId().getName());
                    if (tag != null) {
                        result.add(tag);
                    }
                }
            }
            return result;
        });
    }

    // ==== commitMessagesForChangelog overloads ====

    public static List<String> commitMessagesForChangelog(File repoDir, String tagPrefix, String modulePath,
                                                            List<String> fallbackPrefixes, ChangelogScope scope) {
        return commitMessagesForChangelog(repoDir, tagPrefix, modulePath, fallbackPrefixes, scope, List.of());
    }

    /**
     * Full commit messages to build a changelog from. The "since" boundary depends on scope:
     *
     * <ul>
     *   <li>{@link ChangelogScope#SINCE_LAST_RELEASE}: the last final release tag (falling back
     *       through fallbackPrefixes exactly like {@link #resolveCandidateVersion}) - the complete
     *       picture for this release cycle so far.
     *   <li>{@link ChangelogScope#SINCE_PREVIOUS_RC}: the nearest reachable release-candidate tag -
     *       a delta since the last RC - falling back to the last final release tag if there is no
     *       previous RC yet (the first RC in a cycle has nothing to diff against).
     * </ul>
     *
     * <p>Same modulePath restriction as version resolution: only commits that touched this
     * module's own directory are included, so a changelog generated for one subproject in a
     * monorepo doesn't list changes from an unrelated sibling.
     *
     * <p><strong>Caller must run this BEFORE minting any new tag for the current HEAD</strong>
     * (see the release-flow plugin's {@code changelogForReleaseCandidate}/{@code changelogForRelease}
     * tasks, which always run before {@code tagReleaseCandidate}/{@code promoteReleaseCandidate} in
     * the same job). Both scopes locate their "since" boundary by looking for a tag reachable from
     * HEAD - if HEAD were already tagged with the release currently being prepared, that brand-new
     * tag would itself be "the nearest reachable RC tag" or "the last final release tag", making
     * the commit range (and therefore the generated changelog) come back empty.
     *
     * @param repoDir any directory inside the git repo (JGit walks upward to find the .git dir)
     * @param tagPrefix this module's own tag prefix, e.g. "v" at the root or "sub/module/v"
     * @param modulePath this module's directory relative to the repo root ("" at the root)
     * @param fallbackPrefixes tried in order if tagPrefix has no final tag reachable from HEAD yet
     *        (only relevant for the SINCE_LAST_RELEASE fallback path, same as resolveCandidateVersion)
     * @param scope see {@link ChangelogScope}
     * @param excludedModulePaths same exclusion semantics as {@link #resolveBuildVersion} - a
     *        changelog generated for one project shouldn't list commits that belong entirely to a
     *        sibling project it excludes.
     */
    public static List<String> commitMessagesForChangelog(File repoDir, String tagPrefix, String modulePath,
                                                            List<String> fallbackPrefixes, ChangelogScope scope,
                                                            List<String> excludedModulePaths) {
        return withRepo(repoDir, repo -> {
            ObjectId sinceCommit = null;
            if (scope == ChangelogScope.SINCE_PREVIOUS_RC) {
                String rcTag = nearestReachableTag(repo, tagPrefix, RC_SUFFIX);
                if (rcTag != null) {
                    sinceCommit = commitForTag(repo, rcTag);
                }
            }
            if (sinceCommit == null) {
                PrefixAndVersion lookup = lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes);
                sinceCommit = commitForTag(repo, lookup.prefix() + lookup.version());
            }
            return commitMessagesSince(repo, sinceCommit, modulePath, excludedModulePaths);
        });
    }

    // ---- internals: all take an already-open Repository ----

    private static String resolveCandidateVersionIn(Repository repo, String tagPrefix, String modulePath,
                                                      List<String> fallbackPrefixes,
                                                      Map<CommitAnalyzer.Bump, Set<String>> typeRules,
                                                      List<String> excludedModulePaths) throws Exception {
        PrefixAndVersion lookup = lastFinalVersionLookup(repo, tagPrefix, fallbackPrefixes);
        String matchedPrefix = lookup.prefix();
        String lastFinal = lookup.version();
        // matchedPrefix may be a fallback prefix rather than tagPrefix itself (see
        // lastFinalVersionLookup) - the *tag object* we walk commits from has to be looked up under
        // whichever prefix actually matched, even though the version we ultimately bump and re-tag
        // always uses tagPrefix (this module's own).
        ObjectId sinceCommit = commitForTag(repo, matchedPrefix + lastFinal);
        List<String> messages = commitMessagesSince(repo, sinceCommit, modulePath, excludedModulePaths);
        CommitAnalyzer.Bump bump = messages.isEmpty() ? CommitAnalyzer.Bump.NONE : CommitAnalyzer.analyze(messages, typeRules);
        return bumpVersion(lastFinal, bump);
    }

    /**
     * Highest final release tag under tagPrefix reachable from HEAD; if none, retries each of
     * fallbackPrefixes in order. Returns (matchedPrefix, version) rather than just the version,
     * because the prefix is needed by {@link #resolveCandidateVersionIn} to locate the actual tag
     * object bounding the commit walk - it may differ from tagPrefix when a fallback matched, and
     * getting this wrong would mean either double-counting commits already covered by the fallback
     * tag, or (worse) walking off the front of history looking for a tag under the wrong prefix.
     */
    private static PrefixAndVersion lastFinalVersionLookup(Repository repo, String tagPrefix,
                                                            List<String> fallbackPrefixes) throws Exception {
        List<String> tags = tagsMergedIntoHead(repo);
        // LinkedHashSet so passing tagPrefix as one of its own fallbacks (harmless, but easy to do
        // by accident from a caller) doesn't scan the tag list twice for the same prefix.
        List<String> allPrefixes = new ArrayList<>();
        allPrefixes.add(tagPrefix);
        allPrefixes.addAll(fallbackPrefixes);
        for (String candidatePrefix : new LinkedHashSet<>(allPrefixes)) {
            int[] best = null;
            for (String tag : tags) {
                if (!tag.startsWith(candidatePrefix)) {
                    continue;
                }
                // The startsWith() check above is just a cheap prefilter - the real gate is this
                // regex match against the remainder, which is what actually rules out tags that
                // merely happen to share a leading substring (e.g. a "versioning/v2/v1.0.0" tag
                // does NOT spuriously match a plain "v" prefix, because "ersioning/v2/v1.0.0"
                // doesn't match ^(\d+)\.(\d+)\.(\d+)$).
                Matcher m = FINAL_VERSION_SUFFIX.matcher(tag.substring(candidatePrefix.length()));
                if (m.matches()) {
                    int[] v = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
                    if (best == null || compareVersions(v, best) > 0) {
                        best = v;
                    }
                }
            }
            if (best != null) {
                return new PrefixAndVersion(candidatePrefix, best[0] + "." + best[1] + "." + best[2]);
            }
        }
        // Nothing found under tagPrefix or any fallback - brand new repo/module, no prior release.
        return new PrefixAndVersion(tagPrefix, "0.0.0");
    }

    /** Applies a single CommitAnalyzer.Bump to a plain "X.Y.Z" version, standard semver rules. */
    private static String bumpVersion(String baseVersion, CommitAnalyzer.Bump bump) {
        Matcher m = FINAL_VERSION_SUFFIX.matcher(baseVersion);
        if (!m.matches()) {
            throw new IllegalStateException("Not a plain X.Y.Z version: " + baseVersion);
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = Integer.parseInt(m.group(3));
        return switch (bump) {
            case MAJOR -> (major + 1) + ".0.0";
            case MINOR -> major + "." + (minor + 1) + ".0";
            case PATCH -> major + "." + minor + "." + (patch + 1);
            // Bump.NONE: nothing since the last release warrants a new version yet.
            default -> baseVersion;
        };
    }

    /** Lexicographic comparison of [major, minor, patch] triples. */
    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * Turns a branch name into a version-safe suffix fragment, or null for branches that shouldn't
     * be called out in the version at all (the "steady state" branches, where a bare "-SNAPSHOT" is
     * clearer than e.g. "-release-SNAPSHOT" on every build).
     */
    private static String sanitizeBranch(String branchName) {
        if (branchName == null || branchName.isEmpty() || STEADY_STATE_BRANCHES.contains(branchName)) {
            return null;
        }
        return branchName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    @FunctionalInterface
    private interface RepoFunction<T> {
        T apply(Repository repo) throws Exception;
    }

    /** Opens the repository containing repoDir, runs action against it, and always closes it after. */
    private static <T> T withRepo(File repoDir, RepoFunction<T> action) {
        // Must run before any RepositoryBuilder().build() call - see ConfigCacheSafeSystemReader's
        // own doc comment for why constructing a Repository otherwise shells out to the real git
        // binary here, which the configuration cache doesn't allow.
        ConfigCacheSafeSystemReader.install();
        try (Repository repo = new RepositoryBuilder().readEnvironment().findGitDir(repoDir).build()) {
            return action.apply(repo);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Short tag name (e.g. "v1.0.0"), not the full refs/tags/... form. */
    private static String shortName(Ref tagRef) {
        return tagRef.getName().substring(TAG_REF_PREFIX.length());
    }

    /**
     * The commit a tag ref points to. Lightweight tags point directly at a commit, but
     * <em>annotated</em> tags (the kind {@code git tag -a} creates, which is what the release-flow
     * plugin always creates) point at a separate tag object which itself points at the commit -
     * "peeling" follows that extra indirection down to the actual commit. Getting this wrong would
     * make every annotated tag silently invisible to
     * tagsMergedIntoHead/exactTagAt/nearestReachableTag below.
     */
    private static ObjectId peeledCommitId(Repository repo, Ref tagRef) throws Exception {
        Ref peeled = repo.getRefDatabase().peel(tagRef);
        return peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : peeled.getObjectId();
    }

    /** Looks up a tag by its exact full name (prefix + version) and returns the commit it points to. */
    private static ObjectId commitForTag(Repository repo, String tagName) throws Exception {
        Ref ref = repo.getRefDatabase().exactRef(TAG_REF_PREFIX + tagName);
        return ref == null ? null : peeledCommitId(repo, ref);
    }

    private static List<Ref> allTagRefs(Repository repo) throws Exception {
        return repo.getRefDatabase().getRefsByPrefix(TAG_REF_PREFIX);
    }

    /**
     * Short names of every tag in the repo whose commit is HEAD itself or an ancestor of it - the
     * JGit equivalent of {@code git tag --merged HEAD}. Deliberately checks <em>every</em> tag in
     * the repo rather than assuming linear history, since a tag created on a since-merged branch is
     * still reachable even though it isn't on the "main line".
     */
    private static List<String> tagsMergedIntoHead(Repository repo) throws Exception {
        ObjectId headId = repo.resolve("HEAD");
        if (headId == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit head = walk.parseCommit(headId);
            for (Ref tagRef : allTagRefs(repo)) {
                ObjectId commitId = peeledCommitId(repo, tagRef);
                try {
                    RevCommit commit = walk.parseCommit(commitId);
                    if (commit.equals(head) || walk.isMergedInto(commit, head)) {
                        result.add(shortName(tagRef));
                    }
                } catch (Exception ignored) {
                    // Tag doesn't point at a commit at all (e.g. it points at a blob or tree) -
                    // not something we can ever consider a "release tag", so just skip it.
                }
            }
        }
        return result;
    }

    /**
     * The version (tagPrefix stripped) exactly at HEAD matching this prefix, whether a final
     * release or an "-RCn" tag - both count as "HEAD is already a real, released version, nothing
     * to compute" for {@link #resolveBuildVersion}. Returns null if HEAD isn't tagged at all under
     * this prefix. Prefix is stripped here so the return value matches every other version string
     * this class produces (resolveCandidateVersionIn/bumpVersion never include tagPrefix either).
     */
    private static String exactTagAt(Repository repo, String tagPrefix) throws Exception {
        ObjectId headId = repo.resolve("HEAD");
        if (headId == null) {
            return null;
        }
        for (Ref tagRef : allTagRefs(repo)) {
            String name = shortName(tagRef);
            if (name.startsWith(tagPrefix) && peeledCommitId(repo, tagRef).equals(headId)) {
                return name.substring(tagPrefix.length());
            }
        }
        return null;
    }

    /**
     * The nearest tag (by commit distance, walking HEAD's ancestry) whose name matches tagPrefix +
     * suffixPattern - the JGit equivalent of {@code git describe --tags --match <glob> --abbrev=0},
     * except driven by a real regex instead of a shell glob so callers can express "ends in
     * -RC<digits>" precisely. Used by {@link #promoteTag} to find the RC being promoted.
     */
    private static String nearestReachableTag(Repository repo, String tagPrefix, Pattern suffixPattern) throws Exception {
        ObjectId headId = repo.resolve("HEAD");
        if (headId == null) {
            return null;
        }
        // Build a commit-id -> tag-name lookup once up front, then walk HEAD's ancestry checking
        // each commit against it - the walk naturally visits nearer commits first, so the first
        // hit is the nearest one, without needing to compare distances explicitly.
        Map<String, String> tagByCommit = new HashMap<>();
        for (Ref tagRef : allTagRefs(repo)) {
            String name = shortName(tagRef);
            if (name.startsWith(tagPrefix) && suffixPattern.matcher(name.substring(tagPrefix.length())).find()) {
                tagByCommit.put(peeledCommitId(repo, tagRef).getName(), name);
            }
        }
        if (tagByCommit.isEmpty()) {
            return null;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(headId));
            for (RevCommit commit : walk) {
                String tag = tagByCommit.get(commit.getId().getName());
                if (tag != null) {
                    return tag;
                }
            }
        }
        return null;
    }

    /**
     * Full commit messages (subject + body/footer, exactly what {@link CommitAnalyzer} needs to
     * spot a BREAKING CHANGE footer) for every commit reachable from HEAD but not from
     * sinceCommitOrNull (i.e. the commits <em>since</em> the last release) - the JGit equivalent of
     * {@code git log <sinceCommit>..HEAD}. sinceCommitOrNull is null for a fresh repo/module with no
     * prior release tag at all, in which case the whole history reachable from HEAD counts.
     *
     * <p>When modulePath is non-empty, further restricted to commits that actually touched that
     * path (equivalent to appending {@code -- modulePath} to the git log command above) - this is
     * what stops a change in one subproject of a multi-module build from bumping an unrelated
     * sibling's version. Uses JGit's {@code Git} porcelain {@code LogCommand.addPath(...)} rather
     * than hand-rolling an AndTreeFilter/PathFilterGroup combination, since that's exactly what
     * {@code addPath} already does internally, and doing it this way means we're relying on JGit's
     * own tested implementation of "log -- path" rather than a reimplementation of it.
     *
     * <p>Merge commits (parentCount &gt; 1) are excluded - their auto-generated "Merge branch
     * 'develop' into release" messages don't conform to Conventional Commits, which would otherwise
     * trigger the non-conforming-commit patch-bump fallback in {@link CommitAnalyzer} and add a
     * meaningless line to every changelog. This mirrors {@code git log --no-merges}.
     *
     * <p>When excludedModulePaths is also non-empty, each candidate commit found above is further
     * checked by {@link #touchesPathOutside} - a commit only counts if it changed something outside
     * every excluded directory too (equivalent to {@code git log -- modulePath ':!excluded1'
     * ':!excluded2' ...}), so a root project (modulePath "") can exclude the directories of
     * subprojects that independently apply the release-flow plugin (see
     * {@link #resolveBuildVersion}'s doc). Applied as a post-filter over addPath's result rather
     * than folded into a single combined TreeFilter (AndTreeFilter of an include PathFilterGroup
     * and a NotTreeFilter of the excludes): that combination looked correct but silently dropped
     * commits that only touched paths outside both the include and exclude sets, because
     * NotTreeFilter's recursive-descent pruning doesn't compose safely with a sibling
     * PathFilterGroup in the same AndTreeFilter. Diffing each candidate commit against its parent
     * directly, one candidate at a time, sidesteps that pitfall entirely.
     */
    private static List<String> commitMessagesSince(Repository repo, ObjectId sinceCommitOrNull, String modulePath,
                                                      List<String> excludedModulePaths) throws Exception {
        ObjectId headId = repo.resolve("HEAD");
        if (headId == null) {
            return List.of();
        }
        Git git = new Git(repo);
        LogCommand logCommand = git.log().add(headId);
        if (sinceCommitOrNull != null) {
            logCommand = logCommand.not(sinceCommitOrNull);
        }
        if (modulePath != null && !modulePath.isEmpty()) {
            logCommand = logCommand.addPath(modulePath);
        }
        List<String> excludes = new ArrayList<>();
        for (String path : excludedModulePaths) {
            if (path != null && !path.isEmpty()) {
                excludes.add(path);
            }
        }
        List<String> messages = new ArrayList<>();
        for (RevCommit commit : logCommand.call()) {
            if (commit.getParentCount() <= 1 && (excludes.isEmpty() || touchesPathOutside(repo, commit, excludes))) {
                messages.add(commit.getFullMessage());
            }
        }
        return messages;
    }

    /**
     * True if commit changed at least one path that isn't under any of excludedPaths - diffs commit
     * against its single parent (or an empty tree, for a root commit with no parent) using a plain
     * {@link TreeWalk} in recursive diff mode, the standard low-level JGit idiom for listing changed
     * paths. Only ever called for {@code commit.getParentCount() <= 1} - the caller's merge-commit
     * exclusion runs first (see commitMessagesSince), so there's no second parent to consider here.
     */
    private static boolean touchesPathOutside(Repository repo, RevCommit commit, List<String> excludedPaths)
            throws Exception {
        try (TreeWalk walk = new TreeWalk(repo)) {
            if (commit.getParentCount() == 0) {
                walk.addTree(commit.getTree());
            } else {
                ObjectId parentTree;
                try (RevWalk rw = new RevWalk(repo)) {
                    parentTree = rw.parseCommit(commit.getParent(0).getId()).getTree();
                }
                walk.addTree(parentTree);
                walk.addTree(commit.getTree());
            }
            walk.setRecursive(true);
            walk.setFilter(commit.getParentCount() == 0 ? TreeFilter.ALL : TreeFilter.ANY_DIFF);
            while (walk.next()) {
                String path = walk.getPathString();
                boolean outsideAllExcludes = true;
                for (String excl : excludedPaths) {
                    if (path.equals(excl) || path.startsWith(excl + "/")) {
                        outsideAllExcludes = false;
                        break;
                    }
                }
                if (outsideAllExcludes) {
                    return true;
                }
            }
            return false;
        }
    }
}
