package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Confirms the plugins survive the configuration cache in a multi-module layout where they're
 * applied to a subproject, not root - the shape that originally exposed a JGit
 * {@code SystemReader}-under-config-cache regression upstream (see
 * {@link ReleaseFlowConfigCacheFunctionalTest}). {@link ReleaseFlowConfigCacheFunctionalTest}
 * already covers the single-project case and passes, so this checks whether a failure would be
 * specific to the multi-module/subproject shape rather than to {@link
 * io.github.duckasteroid.gradle.versioning.ConfigCacheSafeSystemReader} itself.
 */
public class ReleaseFlowSubprojectConfigCacheFunctionalTest {

  @TempDir Path tempDir;
  private File repo;
  private File origin;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    origin = Files.createTempDirectory("release-flow-subcc-origin").toFile();
    git(origin, "init", "-q", "--bare");

    Files.writeString(
        tempDir.resolve("settings.gradle"), "rootProject.name = 'multimod-cc'\ninclude 'sub'\n");
    Files.writeString(tempDir.resolve("build.gradle"), "");
    Files.createDirectories(tempDir.resolve("sub"));
    Files.writeString(
        tempDir.resolve("sub/build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n    id 'io.github.duckasteroid.release-flow'\n}\n");

    git(repo, "init", "-q");
    git(repo, "config", "user.email", "test@example.com");
    git(repo, "config", "user.name", "Test");
    git(repo, "remote", "add", "origin", "https://github.com/duckAsteroid/multimod-cc-fixture.git");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "chore: initial commit");
  }

  @Test
  void subprojectGhOwnerDerivationSurvivesConfigurationCache() {
    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments(":sub:help", "--configuration-cache", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":sub:help").getOutcome());
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
