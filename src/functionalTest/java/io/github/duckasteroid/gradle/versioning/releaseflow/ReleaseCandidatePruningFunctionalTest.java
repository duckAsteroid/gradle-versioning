package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Functional (real Gradle process) coverage for tagReleaseCandidates recording which of a cycle's
 * previous release-candidate tags are superseded by the one it just cut, honoring the
 * releaseCandidates { } extension's pruneSuperseded/retain settings. VersionResolverTest already
 * covers currentCycleReleaseCandidateTags itself in isolation - this exercises the full
 * commit -> tag -> manifest wiring across successive real RC cuts, which no other test does.
 *
 * <p>All three scenarios share a deterministic version history: "feat: first feature" bumps 0.0.0 to
 * 0.1.0 (v0.1.0-RC1); every commit after that is a "fix:", which doesn't raise the bump further, so
 * each subsequent cut auto-increments the RC number under the same v0.1.0 candidate (v0.1.0-RC2,
 * v0.1.0-RC3, ...) rather than leapfrogging to a new version - keeping the expected tag names fixed
 * literals instead of needing to parse them back out of the manifest.
 */
public class ReleaseCandidatePruningFunctionalTest {

  @TempDir Path tempDir;
  private File repo;
  private File origin;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    origin = Files.createTempDirectory("release-candidate-pruning-origin").toFile();
    git(origin, "init", "-q", "--bare");

    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'pruning-fixture'\n");
    writeBuildGradle("");

    git(repo, "init", "-q");
    git(repo, "config", "user.email", "test@example.com");
    git(repo, "config", "user.name", "Test");
    git(repo, "remote", "add", "origin", origin.getAbsolutePath());
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "chore: initial commit");
    git(repo, "push", "-q", "origin", "HEAD:refs/heads/main");
  }

  @Test
  void defaultPruningSupersedesThePreviousRcOnTheNextCut() throws Exception {
    commitAndCutRc("feat: first feature", "Feature1.txt");
    commitAndCutRc("fix: a follow-up fix", "Fix1.txt");

    String manifest = readManifest();
    String flat = manifest.replaceAll("\\s+", "");
    assertTrue(flat.contains("\"tag\":\"v0.1.0-RC2\""));
    assertTrue(
        flat.contains("\"supersededTags\":[\"v0.1.0-RC1\"]"),
        "second RC should supersede the first: " + manifest);
  }

  @Test
  void pruneSupersededFalseKeepsEveryPreviousRc() throws Exception {
    writeBuildGradle("releaseCandidates {\n    pruneSuperseded = false\n}\n");

    commitAndCutRc("feat: first feature", "Feature1.txt");
    commitAndCutRc("fix: a follow-up fix", "Fix1.txt");

    String manifest = readManifest();
    String flat = manifest.replaceAll("\\s+", "");
    assertTrue(flat.contains("\"tag\":\"v0.1.0-RC2\""));
    assertTrue(
        flat.contains("\"supersededTags\":[]"),
        "pruning disabled - nothing should be marked superseded: " + manifest);
  }

  @Test
  void retainKeepsTheMostRecentPreviousRcsBesidesTheNewOne() throws Exception {
    writeBuildGradle("releaseCandidates {\n    retain = 1\n}\n");

    commitAndCutRc("feat: first feature", "Feature1.txt");
    commitAndCutRc("fix: a follow-up fix", "Fix1.txt");
    commitAndCutRc("fix: another follow-up", "Fix2.txt");

    String manifest = readManifest();
    String flat = manifest.replaceAll("\\s+", "");
    assertTrue(flat.contains("\"tag\":\"v0.1.0-RC3\""));
    assertTrue(
        flat.contains("\"supersededTags\":[\"v0.1.0-RC1\"]"),
        "retain=1 should keep RC2 (most recent previous) and only supersede RC1: " + manifest);
    assertFalse(flat.contains("v0.1.0-RC2"), "RC2 should not appear as superseded: " + manifest);
  }

  private void commitAndCutRc(String message, String fileName) throws Exception {
    Files.writeString(tempDir.resolve(fileName), "content for " + fileName + "\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", message);

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("tagReleaseCandidates", "--stacktrace")
            .build();
    assertEquals(TaskOutcome.SUCCESS, result.task(":tagReleaseCandidates").getOutcome());
  }

  private String readManifest() throws IOException {
    return Files.readString(new File(repo, "build/release-manifest.json").toPath());
  }

  private void writeBuildGradle(String releaseCandidatesBlock) throws IOException {
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n    id 'io.github.duckasteroid.release-flow'\n}\n"
            + releaseCandidatesBlock);
  }

  private void git(File dir, String... args) throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed: " + output);
    }
  }
}
