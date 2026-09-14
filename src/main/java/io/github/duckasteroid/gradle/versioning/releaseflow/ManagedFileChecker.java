package io.github.duckasteroid.gradle.versioning.releaseflow;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;

/**
 * {@code checkReleaseWorkflows}'s read-only counterpart to {@link ManagedFileInstaller}: parses the
 * marker left by a matching {@code install(...)} call and reports whether the installed file is
 * missing, foreign (no marker, or a marker for a different componentId), edited-since-install,
 * stale, or up to date. Never mutates anything. Plain File I/O, no Gradle dependency.
 */
public final class ManagedFileChecker {

    private ManagedFileChecker() {
    }

    public enum Status {
        MISSING, NOT_OURS, TAMPERED, STALE, UP_TO_DATE
    }

    public static final class CheckResult {
        private final Status status;
        private final String installedVersion;

        public CheckResult(Status status, String installedVersion) {
            this.status = status;
            this.installedVersion = installedVersion;
        }

        public Status getStatus() {
            return status;
        }

        public String getInstalledVersion() {
            return installedVersion;
        }
    }

    public static CheckResult check(File target, String componentId, String currentVersion) {
        if (!target.exists()) {
            return new CheckResult(Status.MISSING, null);
        }
        try {
            String content = Files.readString(target.toPath(), StandardCharsets.UTF_8);
            int newlineIdx = content.indexOf('\n');
            String firstLine = newlineIdx < 0 ? content : content.substring(0, newlineIdx);
            ManagedFileMarker marker = ManagedFileMarker.parse(firstLine);
            if (marker == null || !Objects.equals(marker.getComponentId(), componentId)) {
                return new CheckResult(Status.NOT_OURS, null);
            }
            String body = newlineIdx < 0 ? "" : content.substring(newlineIdx + 1);
            if (!marker.matchesBody(body)) {
                return new CheckResult(Status.TAMPERED, marker.getVersion());
            }
            if (!marker.getVersion().equals(currentVersion)) {
                return new CheckResult(Status.STALE, marker.getVersion());
            }
            return new CheckResult(Status.UP_TO_DATE, marker.getVersion());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
