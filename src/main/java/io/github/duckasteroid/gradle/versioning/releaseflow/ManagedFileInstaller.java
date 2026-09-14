package io.github.duckasteroid.gradle.versioning.releaseflow;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;

/**
 * Safe-install logic behind "install a versioned, self-updating file" tasks (e.g.
 * {@code installReleaseWorkflows}) - never clobber a file it doesn't recognize as its own, or one
 * edited since install, unless forced. Plain File I/O, no Gradle dependency:
 *
 * <ul>
 *   <li>no file present -&gt; install it
 *   <li>file present, no marker, or a marker for a different componentId -&gt; foreign/hand-written -&gt; skip
 *   <li>file present, marker found for this componentId, hash matches -&gt; untouched since install -&gt; overwrite
 *   <li>file present, marker found for this componentId, hash mismatch -&gt; edited since install -&gt; skip, unless force
 * </ul>
 */
public final class ManagedFileInstaller {

    private ManagedFileInstaller() {
    }

    public enum Result {
        INSTALLED, OVERWRITTEN, FORCED, UP_TO_DATE, SKIPPED_FOREIGN, SKIPPED_MODIFIED
    }

    public static Result install(File target, String componentId, String version, String body, boolean force) {
        ManagedFileMarker marker = ManagedFileMarker.forBody(componentId, version, body);
        String newContent = marker.render() + "\n" + body;
        try {
            if (!target.exists()) {
                if (target.getParentFile() != null) {
                    target.getParentFile().mkdirs();
                }
                Files.writeString(target.toPath(), newContent, StandardCharsets.UTF_8);
                return Result.INSTALLED;
            }
            String existingContent = Files.readString(target.toPath(), StandardCharsets.UTF_8);
            if (existingContent.equals(newContent)) {
                return Result.UP_TO_DATE;
            }
            int newlineIdx = existingContent.indexOf('\n');
            String firstLine = newlineIdx < 0 ? existingContent : existingContent.substring(0, newlineIdx);
            ManagedFileMarker existingMarker = ManagedFileMarker.parse(firstLine);
            if (existingMarker == null || !Objects.equals(existingMarker.getComponentId(), componentId)) {
                return Result.SKIPPED_FOREIGN;
            }
            String existingBody = newlineIdx < 0 ? "" : existingContent.substring(newlineIdx + 1);
            boolean unmodified = existingMarker.matchesBody(existingBody);
            if (!unmodified && !force) {
                return Result.SKIPPED_MODIFIED;
            }
            Files.writeString(target.toPath(), newContent, StandardCharsets.UTF_8);
            return unmodified ? Result.OVERWRITTEN : Result.FORCED;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
