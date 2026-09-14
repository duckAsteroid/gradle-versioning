package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Exercises the marker line format itself: rendering, parsing, and the hash-over-body check that
 * installReleaseWorkflows/checkReleaseWorkflows (and, in future, any other duckasteroid-* plugin's
 * own install/check task) rely on to tell an untouched install from an edited one, and to tell
 * their own component's marker apart from a different component's - see issue #2.
 */
public class ManagedFileMarkerTest {

  @Test
  void rendersAndParsesRoundTrip() {
    ManagedFileMarker marker = ManagedFileMarker.forBody("release-flow", "1.3.0", "name: some-workflow\n");
    ManagedFileMarker parsed = ManagedFileMarker.parse(marker.render());

    assertEquals(marker.getComponentId(), parsed.getComponentId());
    assertEquals(marker.getVersion(), parsed.getVersion());
    assertEquals(marker.getSha256(), parsed.getSha256());
  }

  @Test
  void matchesBodyIsTrueOnlyForTheExactBodyItWasComputedFrom() {
    ManagedFileMarker marker = ManagedFileMarker.forBody("release-flow", "1.3.0", "name: some-workflow\n");

    assertTrue(marker.matchesBody("name: some-workflow\n"));
    assertTrue(!marker.matchesBody("name: a-different-workflow\n"));
  }

  @Test
  void parseReturnsNullForLinesWithoutTheMarkerPrefix() {
    assertNull(ManagedFileMarker.parse("name: Tag and publish a release candidate"));
    assertNull(ManagedFileMarker.parse(null));
  }

  @Test
  void parseReturnsNullWhenPrefixPresentButShaMarkerMissing() {
    assertNull(ManagedFileMarker.parse(ManagedFileMarker.PREFIX + "release-flow 1.3.0"));
  }

  @Test
  void parseReturnsNullWhenPrefixPresentButNoComponentId() {
    assertNull(ManagedFileMarker.parse(ManagedFileMarker.PREFIX + "1.3.0-sha256:deadbeef"));
  }

  @Test
  void renderFormatMatchesTheDocumentedMarkerLine() {
    ManagedFileMarker marker = new ManagedFileMarker("release-flow", "1.3.0", "deadbeef");
    assertEquals("# duckasteroid-managed: release-flow 1.3.0 sha256:deadbeef", marker.render());
  }

  @Test
  void parsedMarkerCarriesTheComponentIdSoCallersCanDetectAMismatch() {
    ManagedFileMarker marker = ManagedFileMarker.parse("# duckasteroid-managed: java-build-env 2.0.0 sha256:deadbeef");

    assertEquals("java-build-env", marker.getComponentId());
    assertEquals("2.0.0", marker.getVersion());
    assertEquals("deadbeef", marker.getSha256());
  }
}
