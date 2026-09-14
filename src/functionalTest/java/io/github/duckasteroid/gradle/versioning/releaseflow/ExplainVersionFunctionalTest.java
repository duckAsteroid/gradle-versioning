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
 * Functional (real Gradle process) coverage for the explainVersion/explainVersions tasks -
 * VersionReportTest already covers VersionReport.buildEntry itself in isolation; this exercises the
 * actual task wiring (console output, build/version-report.json, the versionReport { } extension,
 * and - for explainVersions - the multi-module aggregator/skip-tag decision), none of which
 * ProjectBuilder-based ReleaseFlowPluginTest can reach since it never runs doLast blocks. No
 * {@code java} plugin is applied anywhere here - neither plugin needs it.
 */
public class ExplainVersionFunctionalTest {

  @TempDir Path tempDir;
  private File repo;

  @BeforeEach
  void initRepo() throws IOException, InterruptedException {
    repo = tempDir.toFile();
    git(repo, "init", "-q");
    git(repo, "config", "user.email", "test@example.com");
    git(repo, "config", "user.name", "Test");
  }

  @Test
  void explainVersionPrintsBreakdownAndWritesJsonReportWithoutTaggingAnything() throws Exception {
    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'explain-fixture'\n");
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n    id 'io.github.duckasteroid.release-flow'\n}\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "chore: initial commit");
    git(repo, "tag", "-a", "v1.0.0", "-m", "v1.0.0");
    Files.writeString(tempDir.resolve("Feature.txt"), "a change\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "feat: add a thing");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("explainVersion", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":explainVersion").getOutcome());
    assertTrue(result.getOutput().contains("last final release v1.0.0"));
    assertTrue(result.getOutput().contains("[MINOR] feat: add a thing"));
    assertTrue(result.getOutput().contains("candidate: v1.1.0"));
    assertTrue(result.getOutput().contains("next release-candidate tag: v1.1.0-RC1"));

    File reportFile = new File(repo, "build/version-report.json");
    assertTrue(reportFile.exists());
    String json = Files.readString(reportFile.toPath()).replaceAll("\\s+", "");
    assertTrue(json.contains("\"candidate\":\"1.1.0\""));
    assertTrue(json.contains("\"nextReleaseCandidateTag\":\"v1.1.0-RC1\""));
    assertFalse(json.contains("\"action\""), "explainVersion has nothing to decide, unlike explainVersions");

    // Read-only - no tag should have actually been created.
    assertFalse(tagExists(repo, "v1.1.0-RC1"));
  }

  @Test
  void versionReportExtensionCanDisableTheJsonFile() throws Exception {
    Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'explain-fixture'\n");
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n    id 'io.github.duckasteroid.release-flow'\n}\n"
            + "versionReport {\n    enabled = false\n}\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "chore: initial commit");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("explainVersion", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":explainVersion").getOutcome());
    assertFalse(new File(repo, "build/version-report.json").exists());
  }

  @Test
  void explainVersionsReportsOneEntryPerApplyingProjectWithItsOwnSkipOrTagDecision() throws Exception {
    Files.writeString(
        tempDir.resolve("settings.gradle"),
        "rootProject.name = 'explain-multi-fixture'\ninclude 'api', 'web'\n");
    Files.writeString(
        tempDir.resolve("build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n    id 'io.github.duckasteroid.release-flow'\n}\n");
    Files.createDirectories(tempDir.resolve("api"));
    Files.writeString(
        tempDir.resolve("api/build.gradle"),
        "plugins {\n    id 'io.github.duckasteroid.version'\n    id 'io.github.duckasteroid.release-flow'\n}\n");
    Files.createDirectories(tempDir.resolve("web"));
    Files.writeString(tempDir.resolve("web/build.gradle"), "plugins {\n    id 'io.github.duckasteroid.version'\n}\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "chore: initial commit");
    git(repo, "tag", "-a", "v1.0.0", "-m", "v1.0.0");
    git(repo, "tag", "-a", "api/v1.0.0", "-m", "api/v1.0.0");

    Files.writeString(tempDir.resolve("api/Feature.txt"), "a change\n");
    git(repo, "add", "-A");
    git(repo, "commit", "-q", "-m", "fix(api): a bug");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments("explainVersions", "--stacktrace")
            .build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":explainVersions").getOutcome());
    // :api has a qualifying commit under its own path since api/v1.0.0.
    assertTrue(result.getOutput().contains(":api - TAG (would mint api/v1.0.1-RC1)"));
    // root only applies the release-flow plugin itself; its commit range excludes :api's own
    // modulePath (mixed-mode exclusion), so the api-only commit above doesn't count.
    assertTrue(result.getOutput().contains(": - SKIP"));
    // :web never applies the release-flow plugin, so explainVersions shouldn't report on it at all
    // (unlike ":api"/":web" substrings, which legitimately appear in ordinary Gradle task-path log
    // lines for every subproject regardless of which plugins it applies).
    assertFalse(result.getOutput().contains("explainVersions: :web"));

    File reportFile = new File(repo, "build/version-report.json");
    String json = Files.readString(reportFile.toPath()).replaceAll("\\s+", "");
    assertTrue(json.contains("\"module\":\":api\""));
    assertTrue(json.contains("\"action\":\"TAG\""));
    assertTrue(json.contains("\"action\":\"SKIP\""));

    // Read-only - no tags should have actually been created for either module.
    assertFalse(tagExists(repo, "api/v1.0.1-RC1"));
  }

  private boolean tagExists(File dir, String tag) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder("git", "tag", "-l", tag).directory(dir).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    process.waitFor();
    return !output.trim().isEmpty();
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
