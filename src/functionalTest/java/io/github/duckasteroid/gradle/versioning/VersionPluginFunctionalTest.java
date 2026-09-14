package io.github.duckasteroid.gradle.versioning;

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
 * Proves {@link VersionPlugin} computes a real Conventional-Commits version end-to-end against a
 * real git repo, with no {@code java} plugin present at all.
 */
public class VersionPluginFunctionalTest {

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
  void computesARealConventionalCommitsVersionWithNoJavaPluginPresent() throws Exception {
    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'version-only-fixture'\n");
    // A single-purpose task that prints just project.version - deliberately NOT the built-in
    // `properties` task, which would dump every project property into this test's captured output.
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n}\n"
            + "tasks.register('printVersion') {\n    doLast { println \"VERSION=${project.version}\" }\n}\n");
    git("add", "-A");
    git("commit", "-q", "-m", "chore: init");
    git("tag", "-a", "v1.0.0", "-m", "v1.0.0");
    git("commit", "--allow-empty", "-q", "-m", "feat: add a thing");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("printVersion", "-q", "--stacktrace")
            .build();

    assertTrue(result.getOutput().contains("VERSION=1.1.0-SNAPSHOT"), "expected a minor bump: " + result.getOutput());
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
