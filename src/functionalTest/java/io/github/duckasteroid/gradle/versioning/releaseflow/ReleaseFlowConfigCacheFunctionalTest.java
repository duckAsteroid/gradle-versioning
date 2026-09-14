package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Proves {@code tagReleaseCandidates} survives the configuration cache. The Groovy precompiled
 * script plugin this was ported from had a real bug here: its doLast closures called script-level
 * instance methods (writeChangelog/createAndPushTag/runGit) relying on Groovy's owner-chain
 * resolution back to the script instance, which broke once the configuration cache serialized the
 * closure and restored it at execution time with the task itself as delegate -
 * MissingMethodException. The Java port ({@link ReleaseFlowPlugin}) sidesteps that whole class of
 * bug by construction (plain Java method calls are never dynamically dispatched), but this test is
 * kept as the regression guard for the underlying discipline it was protecting: doLast actions must
 * only close over plain captured values, never touch the live Project at execution time.
 *
 * <p>{@link ReleaseFlowSubprojectOnlyFunctionalTest} exercises the same aggregator without
 * {@code --configuration-cache}, so it doesn't catch this. {@link ReleaseFlowPluginTest} only
 * checks task registration via {@code ProjectBuilder}, which never runs {@code doLast} at all.
 */
public class ReleaseFlowConfigCacheFunctionalTest {

  @TempDir Path tempDir;
  private File repo;
  private File origin;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    origin = Files.createTempDirectory("release-flow-cc-origin").toFile();
    git(origin, "init", "-q", "--bare");

    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'cc-fixture'\n");
    Files.writeString(
        tempDir.resolve("build.gradle"),
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
  void tagReleaseCandidatesSurvivesConfigurationCache() throws Exception {
    Files.writeString(tempDir.resolve("Feature.txt"), "a feature\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "feat: add a feature");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("tagReleaseCandidates", "--configuration-cache", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":tagReleaseCandidates").getOutcome());
    assertTrue(
        new File(repo, "build/release-manifest.json").exists(),
        "manifest should be written to the root build directory");
    assertTrue(
        gitOutput(repo, "tag", "-l").contains("v0.1.0-RC1"),
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
