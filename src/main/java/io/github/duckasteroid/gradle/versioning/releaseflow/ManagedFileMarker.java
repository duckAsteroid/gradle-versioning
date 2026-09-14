package io.github.duckasteroid.gradle.versioning.releaseflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The "# duckasteroid-managed: componentId version sha256:hash" marker comment that a
 * {@code ManagedFileInstaller.install(...)} call stamps as the first line of every file it
 * installs, and that a matching {@code ManagedFileChecker.check(...)} call later re-parses to
 * detect staleness/tampering. The hash covers everything BELOW the marker line (the templated
 * body), never the marker line itself - a self-attestation, not a tamper-proof checksum: it only
 * needs to catch the realistic accident (editing a step without touching the marker comment), not
 * someone deliberately recomputing a matching hash.
 *
 * <p>{@code componentId} namespaces the marker by whichever plugin/feature installed the file (e.g.
 * {@code "release-flow"}) - {@code installReleaseWorkflows} is the only caller today, but
 * namespacing from the start means a second installer can never have its file misread as belonging
 * to a different component, or vice versa.
 */
public final class ManagedFileMarker {

    public static final String PREFIX = "# duckasteroid-managed: ";
    private static final String SHA_MARKER = " sha256:";

    private final String componentId;
    private final String version;
    private final String sha256;

    public ManagedFileMarker(String componentId, String version, String sha256) {
        this.componentId = componentId;
        this.version = version;
        this.sha256 = sha256;
    }

    public String getComponentId() {
        return componentId;
    }

    public String getVersion() {
        return version;
    }

    public String getSha256() {
        return sha256;
    }

    public String render() {
        return PREFIX + componentId + " " + version + SHA_MARKER + sha256;
    }

    public boolean matchesBody(String body) {
        return sha256.equals(sha256Of(body));
    }

    public static ManagedFileMarker forBody(String componentId, String version, String body) {
        return new ManagedFileMarker(componentId, version, sha256Of(body));
    }

    public static String sha256Of(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses a marker line, or returns null if it isn't one (foreign/hand-written file). */
    public static ManagedFileMarker parse(String firstLine) {
        if (firstLine == null || !firstLine.startsWith(PREFIX)) {
            return null;
        }
        String rest = firstLine.substring(PREFIX.length());
        int spaceIdx = rest.indexOf(' ');
        if (spaceIdx < 0) {
            return null;
        }
        String componentId = rest.substring(0, spaceIdx);
        String remainder = rest.substring(spaceIdx + 1);
        int shaIdx = remainder.indexOf(SHA_MARKER);
        if (shaIdx < 0) {
            return null;
        }
        return new ManagedFileMarker(componentId, remainder.substring(0, shaIdx),
                remainder.substring(shaIdx + SHA_MARKER.length()).trim());
    }
}
