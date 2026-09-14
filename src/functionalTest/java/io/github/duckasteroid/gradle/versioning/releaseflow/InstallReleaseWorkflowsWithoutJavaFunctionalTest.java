package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Neither {@code io.github.duckasteroid.version} nor {@code io.github.duckasteroid.release-flow}
 * depends on a {@code java} plugin - every other functional test in this module already proves
 * that by never applying one. {@code installReleaseWorkflows} is the one exception: it still needs
 * a java-toolchain-configuring plugin present to know what Java version to put in the installed
 * workflow's {@code setup-java} step, and reports that gap with a clear {@link
 * org.gradle.api.GradleException} rather than an opaque {@code UnknownDomainObjectException} (see
 * {@link ReleaseFlowPlugin}'s {@code registerInstallReleaseWorkflows}). This is a real, documented
 * limitation, not a bug - this test guards the error message stays actionable.
 */
public class InstallReleaseWorkflowsWithoutJavaFunctionalTest {

  @TempDir Path tempDir;
  private File repo;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    git(repo, "init", "-q");
    git(repo, "config", "user.email", "test@example.com");
    git(repo, "config", "user.name", "Test");
    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'no-java-fixture'\n");
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n    id 'io.github.duckasteroid.release-flow'\n}\n");
  }

  @Test
  void installReleaseWorkflowsStillFailsWithoutAJavaToolchainPluginPresent() throws Exception {
    git(repo, "commit", "--allow-empty", "-q", "-m", "chore: init");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("installReleaseWorkflows", "--stacktrace")
            .buildAndFail();

    assertTrue(
        result.getOutput().contains("needs a java-toolchain-configuring plugin"),
        "expected the known java-toolchain gap, got: " + result.getOutput());
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
