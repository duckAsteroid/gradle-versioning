package io.github.duckasteroid.gradle.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.gradle.versioning.CommitAnalyzer.Bump;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises VersionResolver against a real throwaway git repo rather than mocking git - the whole
 * point of this class is git plumbing, so a fixture repo is the honest way to test it.
 */
public class VersionResolverTest {

  private static final String PREFIX = "v";

  @TempDir Path tempDir;
  private File repo;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    git("init", "-q");
    git("config", "user.email", "test@example.com");
    git("config", "user.name", "Test");
  }

  @Test
  void lastFinalVersionIsZeroWhenNoTagsExist() throws Exception {
    commit("feat: first commit");
    assertEquals("0.0.0", VersionResolver.lastFinalVersion(repo, PREFIX));
  }

  @Test
  void lastFinalVersionIgnoresReleaseCandidateTags() throws Exception {
    commit("feat: first commit");
    tag("v1.0.0");
    commit("feat: second feature");
    tag("v1.1.0-RC1");
    assertEquals("1.0.0", VersionResolver.lastFinalVersion(repo, PREFIX));
  }

  @Test
  void candidateVersionBumpsMinorForFeat() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    assertEquals("1.1.0", VersionResolver.resolveCandidateVersion(repo, PREFIX));
  }

  @Test
  void candidateVersionTakesHighestBumpAcrossCommitsSinceLastRelease() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("fix: patch a bug");
    commit("feat!: breaking change");
    assertEquals("2.0.0", VersionResolver.resolveCandidateVersion(repo, PREFIX));
  }

  @Test
  void candidateVersionUnchangedWhenNoQualifyingCommits() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("docs: update readme");
    assertEquals("1.0.0", VersionResolver.resolveCandidateVersion(repo, PREFIX));
  }

  @Test
  void mergeCommitsAreExcludedFromVersionBumpAndChangelog() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    git("checkout", "-b", "feature");
    commit("chore: internal note");
    git("checkout", "-");
    git("merge", "--no-ff", "-m", "Merge branch 'feature'", "feature");

    // The auto-generated merge commit message doesn't conform to Conventional Commits. If it were
    // counted, the non-conforming-commit fallback in CommitAnalyzer would bump this to 1.0.1 even
    // though the only real commit ("chore: internal note") maps to no bump at all.
    assertEquals("1.0.0", VersionResolver.resolveCandidateVersion(repo, PREFIX));

    List<String> messages = VersionResolver.commitMessagesForChangelog(
        repo, PREFIX, "", List.of(), ChangelogScope.SINCE_LAST_RELEASE);
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("internal note"));
  }

  @Test
  void buildVersionReturnsExactTagStrippedOfPrefixWhenHeadIsOnOne() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    assertEquals("1.0.0", VersionResolver.resolveBuildVersion(repo, PREFIX, "main"));
  }

  @Test
  void buildVersionAppendsSnapshotWhenNotExactlyOnATag() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    assertEquals("1.1.0-SNAPSHOT", VersionResolver.resolveBuildVersion(repo, PREFIX, "release"));
  }

  @Test
  void buildVersionEmbedsSanitizedBranchNameForFeatureBranches() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("fix: patch a bug");
    assertEquals(
        "1.0.1-feature-cool-stuff-SNAPSHOT",
        VersionResolver.resolveBuildVersion(repo, PREFIX, "feature/cool-stuff"));
  }

  @Test
  void nextReleaseCandidateTagStartsAtOneThenIncrements() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    assertEquals("v1.1.0-RC1", VersionResolver.nextReleaseCandidateTag(repo, PREFIX, "1.1.0"));
    tag("v1.1.0-RC1");
    assertEquals("v1.1.0-RC2", VersionResolver.nextReleaseCandidateTag(repo, PREFIX, "1.1.0"));
  }

  @Test
  void promoteTagStripsRcSuffixFromNearestReachableRcTag() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    tag("v1.1.0-RC1");
    commit("fix: small follow-up");
    tag("v1.1.0-RC2");
    assertEquals("v1.1.0", VersionResolver.promoteTag(repo, PREFIX));
  }

  @Test
  void promoteTagThrowsWhenNoRcTagIsReachable() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    Exception ex =
        assertThrows(IllegalStateException.class, () -> VersionResolver.promoteTag(repo, PREFIX));
    assertTrue(ex.getMessage().contains("nothing to promote"));
  }

  @Test
  void lastFinalVersionFallsBackToRootPrefixWhenModuleHasNoTagYet() throws Exception {
    commit("chore: init");
    tag("v2.0.0");
    // "newmod/v" has no tag of its own - should inherit the root "v" line via fallback.
    assertEquals("2.0.0", VersionResolver.lastFinalVersion(repo, "newmod/v", List.of("v")));
    assertEquals("0.0.0", VersionResolver.lastFinalVersion(repo, "newmod/v"));
  }

  @Test
  void candidateVersionUsesFallbackPrefixVersionAsBase() throws Exception {
    commit("chore: init");
    tag("v2.0.0");
    commit("feat: new module's first feature");
    assertEquals(
        "2.1.0", VersionResolver.resolveCandidateVersion(repo, "newmod/v", "", List.of("v")));
  }

  @Test
  void candidateVersionOnlyCountsCommitsTouchingModulePath() throws Exception {
    commitFile("root.txt", "init", "chore: init");
    tag("v1.0.0");
    commitFile("moduleA/file.txt", "a", "fix: bug in module A");
    commitFile("moduleB/file.txt", "b", "feat: big feature in module B");

    assertEquals("1.0.1", VersionResolver.resolveCandidateVersion(repo, PREFIX, "moduleA", List.of()));
    assertEquals("1.1.0", VersionResolver.resolveCandidateVersion(repo, PREFIX, "moduleB", List.of()));
    // Unscoped (root, no modulePath): highest bump across ALL commits wins, as before.
    assertEquals("1.1.0", VersionResolver.resolveCandidateVersion(repo, PREFIX, "", List.of()));
  }

  @Test
  void candidateVersionExcludesCommitsUnderExcludedModulePaths() throws Exception {
    commitFile("root.txt", "init", "chore: init");
    tag("v1.0.0");
    commitFile("api/file.txt", "a", "fix: bug in api");

    // Root's unrestricted modulePath ("") would normally see this commit too (see issue #8) -
    // excluding "api" should keep root at 1.0.0 since the only qualifying commit is entirely
    // under the excluded path.
    assertEquals(
        "1.0.0",
        VersionResolver.resolveCandidateVersion(
            repo, PREFIX, "", List.of(), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of("api")));
    // Without the exclusion, root still sees it - existing behavior, unchanged.
    assertEquals("1.0.1", VersionResolver.resolveCandidateVersion(repo, PREFIX, "", List.of()));
  }

  @Test
  void candidateVersionExclusionDoesNotSuppressCommitsOutsideTheExcludedPath() throws Exception {
    commitFile("root.txt", "init", "chore: init");
    tag("v1.0.0");
    commitFile("root2.txt", "change", "fix: root-level fix");

    assertEquals(
        "1.0.1",
        VersionResolver.resolveCandidateVersion(
            repo, PREFIX, "", List.of(), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of("api")));
  }

  @Test
  void changelogForRootExcludesCommitsUnderExcludedModulePaths() throws Exception {
    commitFile("root.txt", "init", "chore: init");
    tag("v1.0.0");
    commitFile("api/file.txt", "a", "fix: bug in api");
    commitFile("root2.txt", "b", "fix: root-level fix");

    List<String> messages =
        VersionResolver.commitMessagesForChangelog(
            repo, PREFIX, "", List.of(), ChangelogScope.SINCE_LAST_RELEASE, List.of("api"));
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("root-level fix"));
  }

  @Test
  void resolveCandidateVersionUsesCustomMajorTypes() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("security: patch a CVE");

    Map<Bump, Set<String>> defaultRules = CommitAnalyzer.DEFAULT_TYPE_RULES;
    assertEquals(
        "1.0.1",
        VersionResolver.resolveCandidateVersion(repo, PREFIX, "", List.of(), defaultRules),
        "without configuring 'security' as a major type, it's non-conforming -> PATCH");

    Map<Bump, Set<String>> withSecurityAsMajor = new HashMap<>(defaultRules);
    withSecurityAsMajor.put(Bump.MAJOR, Set.of("security"));
    assertEquals(
        "2.0.0",
        VersionResolver.resolveCandidateVersion(repo, PREFIX, "", List.of(), withSecurityAsMajor));
  }

  @Test
  void resolveBuildVersionUsesCustomNoBumpTypesOverride() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("chore: dependency bump");

    // Default: 'chore' is a no-bump type, so the build version doesn't move past the tag.
    assertEquals("1.0.0-SNAPSHOT", VersionResolver.resolveBuildVersion(repo, PREFIX, "release"));

    // Override noBumpTypes to just ['docs'] - 'chore' is no longer recognized at all, so it
    // becomes non-conforming -> PATCH bump instead.
    Map<Bump, Set<String>> customRules = new HashMap<>(CommitAnalyzer.DEFAULT_TYPE_RULES);
    customRules.put(Bump.NONE, Set.of("docs"));
    assertEquals(
        "1.0.1-SNAPSHOT",
        VersionResolver.resolveBuildVersion(repo, PREFIX, "release", "", List.of(), customRules));
  }

  @Test
  void resolveCandidateVersionTypeInBothMajorAndPatchSetsResolvesToMajor() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("security: patch a CVE");

    Map<Bump, Set<String>> rules = new HashMap<>(CommitAnalyzer.DEFAULT_TYPE_RULES);
    rules.put(Bump.MAJOR, Set.of("security"));
    Set<String> patchWithSecurity = new HashSet<>(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.PATCH));
    patchWithSecurity.add("security");
    rules.put(Bump.PATCH, patchWithSecurity);

    assertEquals("2.0.0", VersionResolver.resolveCandidateVersion(repo, PREFIX, "", List.of(), rules));
  }

  // ---- commitMessagesForChangelog ----

  @Test
  void changelogSinceLastReleaseCoversEverythingSinceTheFinalTag() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    tag("v1.1.0-RC1");
    commit("fix: a follow-up fix");
    tag("v1.1.0-RC2");

    List<String> messages = VersionResolver.commitMessagesForChangelog(
        repo, PREFIX, "", List.of(), ChangelogScope.SINCE_LAST_RELEASE);
    assertEquals(2, messages.size());
    assertTrue(messages.stream().anyMatch(m -> m.contains("add a thing")));
    assertTrue(messages.stream().anyMatch(m -> m.contains("a follow-up fix")));
  }

  @Test
  void changelogSincePreviousRcOnlyCoversTheDeltaWhenAPreviousRcExists() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    tag("v1.1.0-RC1");
    commit("fix: a follow-up fix");
    // Deliberately NOT tagging RC2 yet - in real usage, tagReleaseCandidate generates the changelog
    // BEFORE minting the new RC tag, so "nearest reachable RC tag" from HEAD still finds RC1.

    List<String> messages = VersionResolver.commitMessagesForChangelog(
        repo, PREFIX, "", List.of(), ChangelogScope.SINCE_PREVIOUS_RC);
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("a follow-up fix"));
  }

  @Test
  void changelogSincePreviousRcFallsBackToLastReleaseWhenNoPreviousRcExists() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: the first RC's only commit");

    List<String> messages = VersionResolver.commitMessagesForChangelog(
        repo, PREFIX, "", List.of(), ChangelogScope.SINCE_PREVIOUS_RC);
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("the first RC's only commit"));
  }

  // ---- currentCycleReleaseCandidateTags ----

  @Test
  void currentCycleReleaseCandidateTagsEmptyWhenNoneExist() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    assertEquals(List.of(), VersionResolver.currentCycleReleaseCandidateTags(repo, PREFIX));
  }

  @Test
  void currentCycleReleaseCandidateTagsOrderedNearestToHeadFirst() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    tag("v1.1.0-RC1");
    commit("fix: a follow-up fix");
    tag("v1.1.0-RC2");
    commit("fix: another follow-up");
    tag("v1.1.0-RC3");

    assertEquals(
        List.of("v1.1.0-RC3", "v1.1.0-RC2", "v1.1.0-RC1"),
        VersionResolver.currentCycleReleaseCandidateTags(repo, PREFIX));
  }

  @Test
  void currentCycleReleaseCandidateTagsIncludesLeapfroggedVersions() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("fix: a patch");
    tag("v1.0.1-RC1");
    commit("feat: a feature lands mid-cycle");
    tag("v1.1.0-RC1");

    // Both belong to the same cycle (since v1.0.0) even though their base versions differ - a
    // later commit raised the bump, "leapfrogging" v1.0.1-RC1's version.
    assertEquals(
        List.of("v1.1.0-RC1", "v1.0.1-RC1"),
        VersionResolver.currentCycleReleaseCandidateTags(repo, PREFIX));
  }

  @Test
  void currentCycleReleaseCandidateTagsExcludesTagsFromAnAlreadyFinalizedCycle() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");
    tag("v1.1.0-RC1");
    tag("v1.1.0"); // promoted - v1.1.0-RC1 now belongs to a finalized, previous cycle
    commit("fix: next cycle's first fix");
    tag("v1.1.1-RC1");

    assertEquals(
        List.of("v1.1.1-RC1"), VersionResolver.currentCycleReleaseCandidateTags(repo, PREFIX));
  }

  @Test
  void changelogRespectsFallbackPrefixesForANewModule() throws Exception {
    commit("chore: init");
    tag("v2.0.0");
    commit("feat: new module's first feature");

    List<String> messages = VersionResolver.commitMessagesForChangelog(
        repo, "newmod/v", "", List.of("v"), ChangelogScope.SINCE_LAST_RELEASE);
    assertEquals(1, messages.size());
    assertTrue(messages.get(0).contains("new module's first feature"));
  }

  private void commit(String message) throws IOException, InterruptedException {
    git("commit", "--allow-empty", "-m", message);
  }

  private void commitFile(String relativePath, String content, String message)
      throws IOException, InterruptedException {
    Path file = tempDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    git("add", relativePath);
    git("commit", "-m", message);
  }

  private void tag(String name) throws IOException, InterruptedException {
    git("tag", "-a", name, "-m", name);
  }

  private void git(String... args) throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(repo).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed: " + output);
    }
  }
}
