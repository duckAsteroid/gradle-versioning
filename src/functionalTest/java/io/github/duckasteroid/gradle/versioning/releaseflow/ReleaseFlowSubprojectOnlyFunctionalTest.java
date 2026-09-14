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
 * Confirms applying {@code io.github.duckasteroid.release-flow} to a single subproject - never the
 * root - still produces a working {@code tagReleaseCandidates} on the root project, targeting
 * exactly that subproject. {@code ProjectBuilder}-based {@link ReleaseFlowPluginTest} can't
 * exercise this: the aggregator's doLast wiring is deferred to a {@code gradle.projectsEvaluated}
 * callback that never fires under {@code ProjectBuilder}, and a single-project {@code
 * ProjectBuilder} tree can't represent "applied to a subproject, not root" at all.
 */
public class ReleaseFlowSubprojectOnlyFunctionalTest {

  @TempDir Path tempDir;
  private File repo;
  private File origin;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    origin = Files.createTempDirectory("release-flow-origin").toFile();
    git(origin, "init", "-q", "--bare");

    Files.writeString(
        tempDir.resolve("settings.gradle"), "rootProject.name = 'multimod'\ninclude 'sub'\n");
    // Root deliberately applies neither plugin.
    Files.writeString(tempDir.resolve("build.gradle"), "");
    Files.createDirectories(tempDir.resolve("sub"));
    Files.writeString(
        tempDir.resolve("sub/build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n    id 'io.github.duckasteroid.release-flow'\n}\n");

    git(repo, "init", "-q");
    git(repo, "config", "user.email", "test@example.com");
    git(repo, "config", "user.name", "Test");
    git(repo, "remote", "add", "origin", origin.getAbsolutePath());
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "chore: initial commit");
    git(repo, "push", "-q", "origin", "HEAD:refs/heads/main");
  }

  @Test
  void aggregatorTargetsOnlyTheApplyingSubproject() throws Exception {
    Files.writeString(tempDir.resolve("sub/Feature.txt"), "a feature\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "feat: add a feature to sub");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("tagReleaseCandidates", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":tagReleaseCandidates").getOutcome());

    File manifest = new File(repo, "build/release-manifest.json");
    assertTrue(manifest.exists(), "manifest should be written to the root build directory");
    assertFalse(
        new File(repo, "sub/build/release-manifest.json").exists(),
        "manifest must not also land in the applying subproject's own build directory");

    String manifestJson = Files.readString(manifest.toPath());
    assertTrue(manifestJson.contains("\":sub\""), "manifest should contain the :sub module");
    assertTrue(
        manifestJson.contains("sub/v0.1.0-RC1"), "manifest should contain sub's minted RC tag");

    assertTrue(
        new File(repo, "sub/build/changelog.md").exists(),
        "sub's own changelog should be generated under its own build directory");

    assertTrue(
        gitOutput(repo, "tag", "-l").contains("sub/v0.1.0-RC1"),
        "the RC tag should actually have been created");
  }

  private void git(File dir, String... args) throws IOException, InterruptedException {
    gitOutput(dir, args);
  }

  private String gitOutput(File dir, String... args) throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    if (exit != 0) {
      throw new IOException("git " + String.join(" ", args) + " failed: " + output);
    }
    return output;
  }
}
