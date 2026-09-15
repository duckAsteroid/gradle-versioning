package io.github.duckasteroid.gradle.versioning;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Recreates the exact scenario from
 * <a href="https://github.com/duckAsteroid/gradle-versioning/issues/2">issue #2</a>: two
 * independently tagged modules, {@code dorsair-spring} with a project dependency on {@code
 * dorsair-client}, published from the commit tagged {@code dorsair-spring/v1.0.1}. At that commit
 * {@code dorsair-client}'s own {@code project.version} is decorated with {@code -SNAPSHOT} (HEAD
 * isn't exactly on its tag), but the generated POM must reference {@code dorsair-client}'s last
 * final release version, {@code 0.1.1}, not that decorated build version.
 */
public class ProjectDependencyVersionRewriteFunctionalTest {

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
  void publishedProjectDependencyUsesLastFinalVersionNotDecoratedSnapshot() throws Exception {
    Files.writeString(
        tempDir.resolve("settings.gradle"),
        "rootProject.name = 'dorsair'\ninclude 'dorsair-client', 'dorsair-spring'\n");
    Files.writeString(tempDir.resolve("build.gradle"), "allprojects { group = 'com.example' }\n");

    Files.createDirectories(tempDir.resolve("dorsair-client"));
    Files.writeString(
        tempDir.resolve("dorsair-client/build.gradle"),
        "plugins {\n"
            + "    id 'java'\n"
            + "    id 'io.github.duckasteroid.version'\n"
            + "    id 'maven-publish'\n"
            + "}\n"
            + "publishing {\n"
            + "    publications {\n"
            + "        mavenJava(MavenPublication) { from components.java }\n"
            + "    }\n"
            + "}\n");

    Files.createDirectories(tempDir.resolve("dorsair-spring"));
    Files.writeString(
        tempDir.resolve("dorsair-spring/build.gradle"),
        "plugins {\n"
            + "    id 'java'\n"
            + "    id 'io.github.duckasteroid.version'\n"
            + "    id 'maven-publish'\n"
            + "}\n"
            + "dependencies {\n"
            + "    implementation project(':dorsair-client')\n"
            + "}\n"
            + "publishing {\n"
            + "    publications {\n"
            + "        mavenJava(MavenPublication) { from components.java }\n"
            + "    }\n"
            + "}\n");

    git("add", "-A");
    git("commit", "-q", "-m", "chore: init");
    // dorsair-client's last final release - HEAD moves on past this commit below, so at that
    // later HEAD dorsair-client's own project.version resolves to a decorated "0.1.1-...-SNAPSHOT".
    git("tag", "-a", "dorsair-client/v0.1.1", "-m", "dorsair-client 0.1.1");

    git("commit", "--allow-empty", "-q", "-m", "feat: release dorsair-spring");
    git("tag", "-a", "dorsair-spring/v1.0.1", "-m", "dorsair-spring 1.0.1");

    BuildResult result =
        GradleRunner.create()
            .withProjectDir(repo)
            .withPluginClasspath()
            .withArguments(":dorsair-spring:generatePomFileForMavenJavaPublication", "--stacktrace")
            .build();

    File pom = new File(repo, "dorsair-spring/build/publications/mavenJava/pom-default.xml");
    assertTrue(pom.exists(), "expected a generated POM: " + result.getOutput());
    String pomXml = Files.readString(pom.toPath());

    assertTrue(
        pomXml.contains("<artifactId>dorsair-client</artifactId>"),
        "expected a dorsair-client dependency in the POM: " + pomXml);
    assertTrue(
        pomXml.contains("<version>0.1.1</version>"),
        "expected the project dependency to publish dorsair-client's last final release version: " + pomXml);
    assertFalse(
        pomXml.contains("SNAPSHOT"),
        "the published POM must never reference a sibling project's decorated build version: " + pomXml);
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
