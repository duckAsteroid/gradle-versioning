package io.github.duckasteroid.gradle.versioning.releaseflow;

import groovy.json.JsonOutput;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code build/release-manifest.json} file written by the {@code tagReleaseCandidates} /
 * {@code promoteReleaseCandidates} aggregator tasks ({@link ReleaseFlowPlugin}) - one entry per
 * project that actually got tagged on a given run, read back by the bundled GitHub Actions workflow
 * to turn into one {@code gh release create} per entry, instead of guessing a single tag via
 * {@code git describe --tags --exact-match HEAD} (which only ever worked when exactly one project
 * released per push).
 *
 * <p>A plain class with no Gradle dependency, like {@code VersionResolver}/{@link ChangelogGenerator}
 * - kept separate from the aggregator tasks themselves so the JSON shape is independently testable.
 * Uses Groovy's {@link JsonOutput} for serialization (available transitively via the Gradle API,
 * same as {@code groovy.lang.Closure} elsewhere in this project) rather than pulling in a separate
 * JSON library for this one small, flat shape.
 */
public final class ReleaseManifest {

    public static final class Entry {
        private final String module;
        private final String tag;
        private final String changelog;
        private final List<String> supersededTags;
        private final String artifactsDir;

        public Entry(String module, String tag, String changelog, List<String> supersededTags, String artifactsDir) {
            this.module = module;
            this.tag = tag;
            this.changelog = changelog;
            this.supersededTags = supersededTags;
            this.artifactsDir = artifactsDir;
        }

        public Map<String, Object> toMap() {
            // LinkedHashMap (insertion order preserved) so the JSON key order always matches the
            // documented example: module, tag, changelog, supersededTags, artifactsDir.
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("module", module);
            map.put("tag", tag);
            map.put("changelog", changelog);
            map.put("supersededTags", supersededTags);
            map.put("artifactsDir", artifactsDir);
            return map;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * @param module the Gradle project path, e.g. ":" for the root or ":api" for a subproject
     * @param tag the full tag name that was just created, including its prefix
     * @param changelog the generated changelog's path, relative to the repo root
     * @param supersededTags this cycle's previous release-candidate tags whose GitHub Release
     *        should be deleted now that {@code tag} supersedes them (see the
     *        {@code releaseCandidates { } } extension) - empty when pruning is disabled or nothing
     *        qualifies. The tags themselves are never deleted, only their GitHub Release.
     * @param artifactsDir this project's own {@code build/libs} directory, relative to the repo
     *        root, for the workflow to glob jars out of and attach to the GitHub Release - see the
     *        note on why this is a directory to glob rather than literal file names in
     *        {@link ReleaseFlowPlugin} where it's computed.
     */
    public void add(String module, String tag, String changelog, List<String> supersededTags, String artifactsDir) {
        entries.add(new Entry(module, tag, changelog, supersededTags, artifactsDir));
    }

    /** Equivalent to {@link #add(String, String, String, List, String)} with no supersededTags/artifactsDir. */
    public void add(String module, String tag, String changelog) {
        add(module, tag, changelog, List.of(), "");
    }

    /** Equivalent to {@link #add(String, String, String, List, String)} with no artifactsDir. */
    public void add(String module, String tag, String changelog, List<String> supersededTags) {
        add(module, tag, changelog, supersededTags, "");
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public String toJson() {
        // JsonOutput.prettyPrint("[]") renders as "[\n    \n]" (a blank indented line inside the
        // brackets) rather than the compact "[]" one might expect - harmless to jq either way, but
        // special-cased here so an empty manifest reads cleanly.
        if (entries.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Entry entry : entries) {
            maps.add(entry.toMap());
        }
        return JsonOutput.prettyPrint(JsonOutput.toJson(maps));
    }

    /** Writes this manifest as JSON to file, creating parent directories as needed. */
    public void writeTo(File file) {
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            Files.writeString(file.toPath(), toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
