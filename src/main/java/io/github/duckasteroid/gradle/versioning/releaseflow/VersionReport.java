package io.github.duckasteroid.gradle.versioning.releaseflow;

import groovy.json.JsonOutput;
import io.github.duckasteroid.gradle.versioning.ChangelogScope;
import io.github.duckasteroid.gradle.versioning.CommitAnalyzer;
import io.github.duckasteroid.gradle.versioning.VersionResolver;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared per-module version-analysis snapshot behind explainVersion/explainVersions - both tasks'
 * console output and their build/version-report.json file are built from exactly the same
 * {@link Entry}, so the two can never drift from each other, and {@link #buildEntry} calls the
 * exact same {@link VersionResolver} methods tagReleaseCandidate(s) itself calls, so the preview
 * can never drift from what tagging would actually do either.
 *
 * <p>A plain class with no Gradle dependency, like {@link VersionResolver}/{@link CommitAnalyzer}/
 * {@link ReleaseManifest} - independently unit-testable against a real throwaway git repo.
 */
public final class VersionReport {

    private VersionReport() {
    }

    /** One commit's syntactic parse plus the bump it resolves to - see {@link CommitAnalyzer}. */
    public static final class CommitBreakdown {
        private final String type;
        private final String scope;
        private final String description;
        private final boolean breaking;
        private final String bump;

        public CommitBreakdown(String type, String scope, String description, boolean breaking, String bump) {
            this.type = type;
            this.scope = scope;
            this.description = description;
            this.breaking = breaking;
            this.bump = bump;
        }

        public String getType() {
            return type;
        }

        public String getScope() {
            return scope;
        }

        public String getDescription() {
            return description;
        }

        public boolean isBreaking() {
            return breaking;
        }

        public String getBump() {
            return bump;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("scope", scope);
            map.put("description", description);
            map.put("breaking", breaking);
            map.put("bump", bump);
            return map;
        }
    }

    /**
     * One project's full version-analysis breakdown. {@code action}/{@code reason} are left unset
     * (and omitted from the JSON) for a lone {@link #buildEntry} result - explainVersion has
     * nothing to decide, it just reports. explainVersions sets them after the fact to record its
     * own skip/tag decision for that module, mirroring tagReleaseCandidates' own decision so the
     * two tasks can never disagree about which modules qualify.
     */
    public static final class Entry {
        private final String module;
        private final String tagPrefix;
        private final String modulePath;
        private final String lastFinal;
        private final List<CommitBreakdown> commits;
        private final String bump;
        private final String candidate;
        private final String forceVersion;
        private final String nextReleaseCandidateTag;
        private final String buildVersion;
        private String action;
        private String reason;

        public Entry(String module, String tagPrefix, String modulePath, String lastFinal,
                      List<CommitBreakdown> commits, String bump, String candidate, String forceVersion,
                      String nextReleaseCandidateTag, String buildVersion) {
            this.module = module;
            this.tagPrefix = tagPrefix;
            this.modulePath = modulePath;
            this.lastFinal = lastFinal;
            this.commits = commits;
            this.bump = bump;
            this.candidate = candidate;
            this.forceVersion = forceVersion;
            this.nextReleaseCandidateTag = nextReleaseCandidateTag;
            this.buildVersion = buildVersion;
        }

        public String getModule() {
            return module;
        }

        public String getTagPrefix() {
            return tagPrefix;
        }

        public String getModulePath() {
            return modulePath;
        }

        public String getLastFinal() {
            return lastFinal;
        }

        public List<CommitBreakdown> getCommits() {
            return commits;
        }

        public String getBump() {
            return bump;
        }

        public String getCandidate() {
            return candidate;
        }

        public String getForceVersion() {
            return forceVersion;
        }

        public String getNextReleaseCandidateTag() {
            return nextReleaseCandidateTag;
        }

        public String getBuildVersion() {
            return buildVersion;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("module", module);
            map.put("tagPrefix", tagPrefix);
            map.put("modulePath", modulePath);
            map.put("lastFinal", lastFinal);
            List<Map<String, Object>> commitMaps = new ArrayList<>();
            for (CommitBreakdown commit : commits) {
                commitMaps.add(commit.toMap());
            }
            map.put("commits", commitMaps);
            map.put("bump", bump);
            map.put("candidate", candidate);
            map.put("forceVersion", forceVersion);
            map.put("nextReleaseCandidateTag", nextReleaseCandidateTag);
            map.put("buildVersion", buildVersion);
            if (action != null) {
                map.put("action", action);
                map.put("reason", reason);
            }
            return map;
        }

        /** Single-entry JSON, e.g. for explainVersion's build/version-report.json. */
        public String toJson() {
            return JsonOutput.prettyPrint(JsonOutput.toJson(toMap()));
        }

        /** Writes {@link #toJson()} to file, creating parent directories as needed. */
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

        /** Human-readable console breakdown printed by both explainVersion and explainVersions. */
        public String render() {
            StringBuilder out = new StringBuilder();
            out.append(module).append(": last final release ").append(tagPrefix).append(lastFinal).append('\n');
            if (commits.isEmpty()) {
                out.append("  no qualifying commits since ").append(tagPrefix).append(lastFinal).append('\n');
            } else {
                for (CommitBreakdown c : commits) {
                    String scopePart = c.getScope() != null && !c.getScope().isEmpty() ? "(" + c.getScope() + ")" : "";
                    String breakingMarker = c.isBreaking() ? "!" : "";
                    out.append("  - [").append(c.getBump()).append("] ")
                            .append(c.getType() != null ? c.getType() : "?").append(scopePart).append(breakingMarker)
                            .append(": ").append(c.getDescription()).append('\n');
                }
            }
            out.append("  bump: ").append(bump).append('\n');
            out.append("  candidate: ").append(tagPrefix).append(candidate);
            out.append(forceVersion != null && !forceVersion.isEmpty()
                    ? " (release.forceVersion=" + forceVersion + ")\n" : "\n");
            out.append("  next release-candidate tag: ").append(nextReleaseCandidateTag).append('\n');
            out.append("  ordinary build version right now: ").append(buildVersion).append('\n');
            return out.toString();
        }
    }

    /**
     * Builds one project's {@link Entry} by calling the exact same {@link VersionResolver} methods
     * tagReleaseCandidate(s) itself calls - see that task's doLast for the call sequence this
     * mirrors. Never tags, pushes, or writes anything other than the returned Entry.
     *
     * @param excludedModulePaths see {@code VersionResolver.resolveBuildVersion} - pass an empty
     *        list for a single-project computation (matching how the singular
     *        tagReleaseCandidate/ordinary-build version resolution never apply exclusions either),
     *        or the applying project's own exclusions (from releaseFlowTargets) for the
     *        multi-module aggregator.
     */
    public static Entry buildEntry(String module, File repoDir, String tagPrefix, String modulePath,
                                    List<String> fallbackPrefixes, Map<CommitAnalyzer.Bump, Set<String>> typeRules,
                                    List<String> excludedModulePaths, String forceVersion, String branchName) {
        String lastFinal = VersionResolver.lastFinalVersion(repoDir, tagPrefix, fallbackPrefixes);
        // The exact same commit range resolveCandidateVersion's own bump computation uses - see
        // VersionResolver.commitMessagesForChangelog's SINCE_LAST_RELEASE path vs.
        // resolveCandidateVersionIn's internal lookup, which locate the same "since" boundary the
        // same way and both end up calling commitMessagesSince with identical arguments.
        List<String> messages = VersionResolver.commitMessagesForChangelog(
                repoDir, tagPrefix, modulePath, fallbackPrefixes, ChangelogScope.SINCE_LAST_RELEASE, excludedModulePaths);
        CommitAnalyzer.Bump[] overallBumpHolder = {CommitAnalyzer.Bump.NONE};
        List<CommitBreakdown> commits = new ArrayList<>();
        for (String message : messages) {
            CommitAnalyzer.ParsedCommit parsed = CommitAnalyzer.parse(message);
            CommitAnalyzer.Bump bump = CommitAnalyzer.analyzeOne(message, typeRules);
            if (bump.ordinal() > overallBumpHolder[0].ordinal()) {
                overallBumpHolder[0] = bump;
            }
            commits.add(new CommitBreakdown(parsed.getType(), parsed.getScope(), parsed.getDescription(),
                    parsed.isBreaking(), bump.name()));
        }
        String candidate = forceVersion != null && !forceVersion.isEmpty()
                ? forceVersion
                : VersionResolver.resolveCandidateVersion(repoDir, tagPrefix, modulePath, fallbackPrefixes, typeRules,
                        excludedModulePaths);
        // Reused verbatim - see the class doc comment - so this can never report a tag that
        // tagReleaseCandidate(s) wouldn't actually mint right now.
        String nextTag = VersionResolver.nextReleaseCandidateTag(repoDir, tagPrefix, candidate);
        String buildVersion = VersionResolver.resolveBuildVersion(
                repoDir, tagPrefix, branchName, modulePath, fallbackPrefixes, typeRules);
        return new Entry(module, tagPrefix, modulePath, lastFinal, commits, overallBumpHolder[0].name(),
                candidate, forceVersion, nextTag, buildVersion);
    }

    /** JSON array of entries, e.g. for explainVersions' build/version-report.json - "[]" when empty. */
    public static String toJsonArray(List<Entry> entries) {
        if (entries.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Entry entry : entries) {
            maps.add(entry.toMap());
        }
        return JsonOutput.prettyPrint(JsonOutput.toJson(maps));
    }

    /** Writes {@link #toJsonArray} to file, creating parent directories as needed. */
    public static void writeJsonArray(List<Entry> entries, File file) {
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            Files.writeString(file.toPath(), toJsonArray(entries), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
