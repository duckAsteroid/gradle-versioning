package io.github.duckasteroid.gradle.versioning.releaseflow;

import io.github.duckasteroid.gradle.versioning.CommitAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a list of commit messages into grouped Markdown release notes, the same idea implemented by
 * tools like conventional-changelog and git-cliff: group by the {@link CommitAnalyzer.Bump} each
 * commit resolves to (using the same {@code typeRules} the version bump itself uses), not by the
 * raw commit type word, so a custom {@code majorTypes} entry (e.g. {@code security}) ends up under
 * Breaking Changes exactly like a {@code !}-marked commit would, and a {@code noBumpTypes} entry
 * (docs/chore/etc.) is omitted exactly like it doesn't affect the version.
 *
 * <p>A non-conforming commit (see {@link CommitAnalyzer}) still gets a PATCH-bump classification
 * (and still prints its stderr warning, since {@link CommitAnalyzer#analyzeOne(String, Map)} is
 * what's called here) - it appears under Bug Fixes using its raw first line as the description,
 * consistent with it having actually bumped the version.
 *
 * <p>A plain class with no Gradle dependency, like {@link CommitAnalyzer} and
 * {@code io.github.duckasteroid.gradle.versioning.VersionResolver} - see that class's
 * {@code commitMessagesForChangelog} for how the commit list itself is obtained.
 */
public final class ChangelogGenerator {

    private static final String NO_CHANGES_TEXT = "No user-facing changes.";

    private ChangelogGenerator() {
    }

    /** Equivalent to {@link #generate(List, Map)} with {@link CommitAnalyzer#DEFAULT_TYPE_RULES}. */
    public static String generate(List<String> messages) {
        return generate(messages, CommitAnalyzer.DEFAULT_TYPE_RULES);
    }

    /**
     * @param messages full commit messages (subject + body/footer), typically from
     *        {@code VersionResolver.commitMessagesForChangelog}
     * @param typeRules see {@link CommitAnalyzer#DEFAULT_TYPE_RULES}; should be the same rules used
     *        for the version bump itself so the changelog and the version agree
     */
    public static String generate(List<String> messages, Map<CommitAnalyzer.Bump, Set<String>> typeRules) {
        List<CommitAnalyzer.ParsedCommit> breaking = new ArrayList<>();
        List<CommitAnalyzer.ParsedCommit> features = new ArrayList<>();
        List<CommitAnalyzer.ParsedCommit> fixes = new ArrayList<>();

        for (String message : messages) {
            // parse() (display text) and analyzeOne() (bump/section routing) each re-parse the
            // message rather than sharing one pass - deliberately kept simple/decoupled rather than
            // threading a richer "parsed + bump" result through, since commit counts per release
            // are small (dozens, not millions) and the extra parse is negligible. One side effect
            // worth knowing: if VersionResolver's own version computation ran in the same process
            // for the same commit range (as it does, back-to-back, in
            // tagReleaseCandidate/promoteReleaseCandidate), a non-conforming commit's stderr
            // warning prints twice - once from there, once from analyzeOne() here. Harmless, just
            // slightly noisy.
            CommitAnalyzer.ParsedCommit parsed = CommitAnalyzer.parse(message);
            if (parsed.isEmpty()) {
                continue;
            }
            switch (CommitAnalyzer.analyzeOne(message, typeRules)) {
                case MAJOR -> breaking.add(parsed);
                case MINOR -> features.add(parsed);
                case PATCH -> fixes.add(parsed);
                default -> {
                    // Bump.NONE - deliberately no-release types (docs/chore/etc.) don't get a line.
                }
            }
        }

        StringBuilder out = new StringBuilder();
        appendSection(out, "Breaking Changes", breaking);
        appendSection(out, "Features", features);
        appendSection(out, "Bug Fixes", fixes);

        String result = out.toString().trim();
        return result.isEmpty() ? NO_CHANGES_TEXT : result;
    }

    private static void appendSection(StringBuilder out, String title, List<CommitAnalyzer.ParsedCommit> commits) {
        if (commits.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            // Each commit line already ends with its own '\n', so only one more is needed here to
            // get a single blank line between sections, not two.
            out.append('\n');
        }
        out.append("## ").append(title).append('\n');
        for (CommitAnalyzer.ParsedCommit commit : commits) {
            out.append("- ");
            if (commit.getScope() != null && !commit.getScope().isEmpty()) {
                out.append("**").append(commit.getScope()).append(":** ");
            }
            out.append(commit.getDescription());
            out.append('\n');
        }
    }
}
