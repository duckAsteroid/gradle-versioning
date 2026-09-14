package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the build/release-manifest.json shape itself - see "The manifest" in
 * MULTI_MODULE_RELEASE_FLOW.md for the format the bundled GitHub Actions workflow (jq) depends on.
 */
public class ReleaseManifestTest {

  @Test
  void emptyManifestSerializesToAnEmptyJsonArray() {
    ReleaseManifest manifest = new ReleaseManifest();

    assertTrue(manifest.isEmpty());
    assertEquals("[]", manifest.toJson());
  }

  @Test
  void entriesSerializeWithModuleTagChangelogKeysInOrder() {
    ReleaseManifest manifest = new ReleaseManifest();
    manifest.add(":api", "api/v1.4.1-RC1", "api/build/changelog.md");
    manifest.add(":", "v2.1.0-RC1", "build/changelog.md");

    assertEquals(2, manifest.size());
    String json = manifest.toJson();
    // Both entries present, in the order they were added, each with module/tag/changelog keys in
    // that order - the workflow's `jq -c '.[]' | ... jq -r '.tag'` step depends on this shape.
    int apiIndex = json.indexOf("\":api\"");
    int rootIndex = json.indexOf("\":\"");
    assertTrue(apiIndex >= 0 && rootIndex >= 0 && apiIndex < rootIndex, "expected :api entry before : entry");
    assertTrue(json.indexOf("\"module\"") < json.indexOf("\"tag\""), "module key should come before tag key");
    assertTrue(json.indexOf("\"tag\"") < json.indexOf("\"changelog\""), "tag key should come before changelog key");
    assertTrue(json.contains("\"api/v1.4.1-RC1\""));
    assertTrue(json.contains("\"api/build/changelog.md\""));
  }

  @Test
  void addWithoutSupersededTagsDefaultsToAnEmptyArray() {
    ReleaseManifest manifest = new ReleaseManifest();
    manifest.add(":", "v1.0.1-RC1", "build/changelog.md");

    String json = manifest.toJson();
    assertTrue(json.contains("\"supersededTags\""), "supersededTags key should always be present");
    assertTrue(
        json.indexOf("\"changelog\"") < json.indexOf("\"supersededTags\""),
        "supersededTags key should come after changelog");
    // An empty array, not a missing/null key - the workflow's jq loop can iterate it unconditionally.
    assertTrue(json.replaceAll("\\s+", "").contains("\"supersededTags\":[]"));
  }

  @Test
  void addWithSupersededTagsSerializesThemAsAnArray() {
    ReleaseManifest manifest = new ReleaseManifest();
    manifest.add(":", "v1.0.1-RC1", "build/changelog.md", List.of("v1.0.1-RC1-old", "v1.0.0-RC3"));

    String json = manifest.toJson().replaceAll("\\s+", "");
    assertTrue(json.contains("\"supersededTags\":[\"v1.0.1-RC1-old\",\"v1.0.0-RC3\"]"));
  }

  @Test
  void addWithoutArtifactsDirDefaultsToAnEmptyString() {
    ReleaseManifest manifest = new ReleaseManifest();
    manifest.add(":", "v1.0.1-RC1", "build/changelog.md");

    String json = manifest.toJson();
    assertTrue(json.contains("\"artifactsDir\""), "artifactsDir key should always be present");
    assertTrue(
        json.indexOf("\"supersededTags\"") < json.indexOf("\"artifactsDir\""),
        "artifactsDir key should come after supersededTags");
    assertTrue(json.replaceAll("\\s+", "").contains("\"artifactsDir\":\"\""));
  }

  @Test
  void addWithArtifactsDirSerializesItAsAString() {
    ReleaseManifest manifest = new ReleaseManifest();
    manifest.add(":api", "api/v1.4.1-RC1", "api/build/changelog.md", List.of(), "api/build/libs");

    String json = manifest.toJson().replaceAll("\\s+", "");
    assertTrue(json.contains("\"artifactsDir\":\"api/build/libs\""));
  }

  @Test
  void writeToCreatesParentDirectoriesAndWritesJson(@TempDir Path tempDir) throws IOException {
    ReleaseManifest manifest = new ReleaseManifest();
    manifest.add(":", "v1.0.1-RC1", "build/changelog.md");

    File target = tempDir.resolve("nested/release-manifest.json").toFile();
    manifest.writeTo(target);

    assertTrue(target.exists());
    String written = Files.readString(target.toPath());
    assertEquals(manifest.toJson(), written);
  }
}
