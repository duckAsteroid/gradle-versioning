package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.gradle.versioning.releaseflow.ManagedFileInstaller.Result;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the safe-install skip/overwrite rules from issue #2 against real files - no file
 * present, foreign file, a different component's file, untouched-since-install,
 * edited-since-install (with and without force).
 */
public class ManagedFileInstallerTest {

  private static final String COMPONENT = "release-flow";

  @TempDir Path tempDir;

  @Test
  void installsWhenNoFileIsPresent() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();

    Result result = ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.INSTALLED, result);
    assertTrue(Files.readString(target.toPath()).startsWith(ManagedFileMarker.PREFIX + "release-flow 1.3.0 sha256:"));
    assertTrue(Files.readString(target.toPath()).endsWith("name: workflow\n"));
  }

  @Test
  void createsMissingParentDirectories() throws IOException {
    File target = tempDir.resolve("nested/dir/release-candidate.yml").toFile();

    Result result = ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.INSTALLED, result);
    assertTrue(target.exists());
  }

  @Test
  void skipsAFileWithNoMarkerRatherThanOverwritingIt() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    Files.writeString(target.toPath(), "name: someone elses hand-written workflow\n");

    Result result = ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.SKIPPED_FOREIGN, result);
    assertEquals("name: someone elses hand-written workflow\n", Files.readString(target.toPath()));
  }

  @Test
  void skipsAFileMarkedForADifferentComponentRatherThanOverwritingIt() throws IOException {
    File target = tempDir.resolve("action.yml").toFile();
    ManagedFileInstaller.install(target, "java-build-env", "1.3.0", "name: some other component\n", false);

    Result result = ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.SKIPPED_FOREIGN, result, "a different component's marker must read as foreign");
    assertTrue(Files.readString(target.toPath()).contains("name: some other component"));
  }

  @Test
  void overwritesAFileThatIsUnmodifiedSinceANOlderInstall() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    ManagedFileInstaller.install(target, COMPONENT, "1.2.0", "name: workflow\n", false);

    Result result = ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow v2\n", false);

    assertEquals(Result.OVERWRITTEN, result);
    assertTrue(Files.readString(target.toPath()).endsWith("name: workflow v2\n"));
  }

  @Test
  void reInstallingTheSameVersionAndBodyIsUpToDate() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow\n", false);

    Result result = ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow\n", false);

    assertEquals(Result.UP_TO_DATE, result);
  }

  @Test
  void skipsAFileEditedSinceInstallRatherThanClobberingTheEdit() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    ManagedFileInstaller.install(target, COMPONENT, "1.2.0", "name: workflow\n", false);
    Files.writeString(target.toPath(), Files.readString(target.toPath()) + "# a hand-added step\n");

    Result result = ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow v2\n", false);

    assertEquals(Result.SKIPPED_MODIFIED, result);
    assertTrue(Files.readString(target.toPath()).contains("# a hand-added step"));
  }

  @Test
  void forceOverwritesAFileEditedSinceInstall() throws IOException {
    File target = tempDir.resolve("release-candidate.yml").toFile();
    ManagedFileInstaller.install(target, COMPONENT, "1.2.0", "name: workflow\n", false);
    Files.writeString(target.toPath(), Files.readString(target.toPath()) + "# a hand-added step\n");

    Result result = ManagedFileInstaller.install(target, COMPONENT, "1.3.0", "name: workflow v2\n", true);

    assertEquals(Result.FORCED, result);
    assertTrue(Files.readString(target.toPath()).endsWith("name: workflow v2\n"));
  }
}
