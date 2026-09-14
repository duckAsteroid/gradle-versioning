package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.gradle.versioning.CommitAnalyzer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises VersionReport.buildEntry against a real throwaway git repo, the same style as
 * VersionResolverTest - buildEntry is mostly a thin orchestration layer over VersionResolver/
 * CommitAnalyzer, so the interesting behaviour is "does it call them with the right arguments and
 * assemble the right Entry", not git plumbing already covered elsewhere.
 */
public class VersionReportTest {

  private static final String PREFIX = "v";
  private static final String BRANCH = "release";

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
  void noPriorTagsReportsZeroBaseAndPatchFallbackForNonConformingCommit() throws Exception {
    commit("first commit");

    VersionReport.Entry entry = VersionReport.buildEntry(
        ":", repo, PREFIX, "", List.of("v"), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of(), null, BRANCH);

    assertEquals("0.0.0", entry.getLastFinal());
    assertEquals(1, entry.getCommits().size());
    assertEquals("PATCH", entry.getCommits().get(0).getBump());
    assertEquals("PATCH", entry.getBump());
    assertEquals("0.0.1", entry.getCandidate());
    assertEquals("v0.0.1-RC1", entry.getNextReleaseCandidateTag());
    assertTrue(entry.getBuildVersion().endsWith("-SNAPSHOT"));
  }

  @Test
  void featCommitBumpsMinorAndParsesTypeScopeAndDescription() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat(api): add a thing");

    VersionReport.Entry entry = VersionReport.buildEntry(
        ":", repo, PREFIX, "", List.of("v"), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of(), null, BRANCH);

    assertEquals("1.0.0", entry.getLastFinal());
    assertEquals(1, entry.getCommits().size());
    VersionReport.CommitBreakdown commit = entry.getCommits().get(0);
    assertEquals("feat", commit.getType());
    assertEquals("api", commit.getScope());
    assertEquals("add a thing", commit.getDescription());
    assertFalse(commit.isBreaking());
    assertEquals("MINOR", commit.getBump());
    assertEquals("MINOR", entry.getBump());
    assertEquals("1.1.0", entry.getCandidate());
    assertEquals("v1.1.0-RC1", entry.getNextReleaseCandidateTag());
  }

  @Test
  void noQualifyingCommitsReportsUnchangedCandidateButStillListsTheNoBumpCommit() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("docs: update readme");

    VersionReport.Entry entry = VersionReport.buildEntry(
        ":", repo, PREFIX, "", List.of("v"), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of(), null, BRANCH);

    // The commit still shows up in the breakdown (with its own NONE classification) even though it
    // doesn't move the candidate - same "list everything, but only some of it counts" behaviour as
    // ChangelogGenerator, just without the "omit NONE-bump commits" filtering a changelog applies.
    assertEquals(1, entry.getCommits().size());
    assertEquals("NONE", entry.getCommits().get(0).getBump());
    assertEquals("NONE", entry.getBump());
    assertEquals("1.0.0", entry.getCandidate());
    assertEquals(entry.getCandidate(), entry.getLastFinal());
  }

  @Test
  void forceVersionOverridesCandidateAndNextTagButStillReportsCommits() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commit("feat: add a thing");

    VersionReport.Entry entry = VersionReport.buildEntry(
        ":", repo, PREFIX, "", List.of("v"), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of(), "9.9.9", BRANCH);

    assertEquals("9.9.9", entry.getForceVersion());
    assertEquals("9.9.9", entry.getCandidate());
    assertEquals("v9.9.9-RC1", entry.getNextReleaseCandidateTag());
    assertEquals(1, entry.getCommits().size(), "commits since last release should still be reported");
  }

  @Test
  void excludedModulePathsAreAppliedToTheCommitRange() throws Exception {
    commit("chore: init");
    tag("v1.0.0");
    commitFile("api/Foo.java", "class Foo {}", "feat(api): api-only change");

    VersionReport.Entry withoutExclusion = VersionReport.buildEntry(
        ":", repo, PREFIX, "", List.of("v"), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of(), null, BRANCH);
    VersionReport.Entry withExclusion = VersionReport.buildEntry(
        ":", repo, PREFIX, "", List.of("v"), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of("api"), null, BRANCH);

    assertEquals(1, withoutExclusion.getCommits().size());
    assertTrue(withExclusion.getCommits().isEmpty(), "the api/-scoped commit should be excluded");
    assertEquals("1.0.0", withExclusion.getCandidate());
  }

  @Test
  void toMapOmitsActionAndReasonUntilSet() throws Exception {
    commit("chore: init");

    VersionReport.Entry entry = VersionReport.buildEntry(
        ":", repo, PREFIX, "", List.of("v"), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of(), null, BRANCH);

    assertFalse(entry.toJson().contains("\"action\""));

    entry.setAction("SKIP");
    entry.setReason("no qualifying commits");

    String json = entry.toJson();
    assertTrue(json.contains("\"action\": \"SKIP\""));
    assertTrue(json.contains("\"reason\": \"no qualifying commits\""));
  }

  @Test
  void toJsonArrayOfMultipleEntriesRendersAsAnArray() throws Exception {
    commit("feat: first feature");

    VersionReport.Entry entry = VersionReport.buildEntry(
        ":", repo, PREFIX, "", List.of("v"), CommitAnalyzer.DEFAULT_TYPE_RULES, List.of(), null, BRANCH);

    String json = VersionReport.toJsonArray(List.of(entry)).replaceAll("\\s+", "");
    assertTrue(json.startsWith("[") && json.endsWith("]"));
    assertTrue(json.contains("\"module\":\":\""));

    assertEquals("[]", VersionReport.toJsonArray(List.of()));
  }

  private void commit(String message) throws IOException, InterruptedException {
    git("commit", "--allow-empty", "-m", message);
  }

  private void commitFile(String relativePath, String content, String message)
      throws IOException, InterruptedException {
    Path file = tempDir.resolve(relativePath);
    java.nio.file.Files.createDirectories(file.getParent());
    java.nio.file.Files.writeString(file, content);
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
