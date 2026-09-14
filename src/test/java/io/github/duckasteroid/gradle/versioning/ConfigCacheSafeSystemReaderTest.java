package io.github.duckasteroid.gradle.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins down the SystemReader wiring itself so a future refactor of ConfigCacheSafeSystemReader
 * that breaks it fails fast, without needing a full Gradle build to notice. In particular it would
 * have caught the first (broken) version of this fix, which overrode
 * getenv("GIT_CONFIG_NOSYSTEM") instead of openSystemConfig directly -
 * SystemReader.Delegate#openSystemConfig forwards straight to the wrapped reader rather than
 * calling back through the wrapper's getenv, so that override was silently never consulted.
 */
public class ConfigCacheSafeSystemReaderTest {

  private SystemReader originalSystemReader;

  @BeforeEach
  void captureOriginalSystemReader() {
    originalSystemReader = SystemReader.getInstance();
  }

  @AfterEach
  void restoreOriginalSystemReader() {
    SystemReader.setInstance(originalSystemReader);
  }

  @Test
  void installReplacesSystemConfigWithAFileFreeNoOpConfig() throws Exception {
    ConfigCacheSafeSystemReader.install();

    FileBasedConfig systemConfig =
        SystemReader.getInstance().openSystemConfig(null, FS.DETECTED);
    // No backing file at all (not even a real-but-missing path) - proves this isn't just an
    // ordinary FileBasedConfig pointed at a nonexistent location, but one that can never trigger
    // FS.discoverGitSystemConfig()'s subprocess-based path lookup in the first place.
    assertNull(systemConfig.getFile());

    systemConfig.load();
    assertTrue(systemConfig.getSections().isEmpty());
  }

  @Test
  void installIsIdempotent() {
    ConfigCacheSafeSystemReader.install();
    SystemReader afterFirstInstall = SystemReader.getInstance();
    ConfigCacheSafeSystemReader.install();
    assertEquals(afterFirstInstall, SystemReader.getInstance());
  }

  @Test
  void installPreservesOtherBehaviorFromThePreviousReader() {
    SystemReader.setInstance(
        new SystemReader.Delegate(originalSystemReader) {
          @Override
          public String getenv(String variable) {
            return "SOME_UNRELATED_VAR".equals(variable) ? "expected-value" : super.getenv(variable);
          }
        });

    ConfigCacheSafeSystemReader.install();

    assertEquals("expected-value", SystemReader.getInstance().getenv("SOME_UNRELATED_VAR"));
  }
}
