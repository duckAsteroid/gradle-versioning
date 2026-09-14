package io.github.duckasteroid.gradle.versioning;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Java port of the semantic-release commit-analyzer concept
 * (https://github.com/semantic-release/commit-analyzer), scoped to the default
 * Conventional Commits (https://www.conventionalcommits.org/) / Angular rule set only - there is
 * deliberately no configurable "preset" system here, unlike the JS original, since this plugin only
 * ever needs the one rule set. The default rules:
 *
 * <pre>
 *   feat                                  -&gt; MINOR
 *   fix, perf                             -&gt; PATCH
 *   docs, style, refactor, test,
 *   chore, build, ci                      -&gt; NONE  (known, deliberately-no-release types)
 *   anything that doesn't conform to the
 *   "type: description" shape at all, or
 *   uses a type outside the lists above    -&gt; PATCH, and a warning is printed to stderr
 * </pre>
 *
 * <p>...except that a '!' after the type/scope (e.g. "feat!:" or "feat(api)!:"), OR a
 * "BREAKING CHANGE:" (or "BREAKING-CHANGE:") footer anywhere in the message, always forces MAJOR
 * regardless of the type - a breaking fix is still a breaking change.
 *
 * <p>The non-conforming rule (-&gt; PATCH + warning, rather than silently NONE) is a deliberate
 * departure from the semantic-release original, which just ignores anything it doesn't recognize:
 * here, a commit that isn't in Conventional Commits form at all - a typo'd type, a merge commit's
 * default message, a commit predating this convention's adoption, someone not bothering to follow
 * the convention - is treated as a small, defensive PATCH bump instead. The rationale is that
 * <em>silently</em> ignoring an unrecognized commit risks silently ignoring a real, released
 * change; a PATCH bump (the smallest possible) plus a visible warning errs towards "the version
 * moved, and you were told why" rather than "nothing happened and nobody noticed".
 *
 * <h2>Configuring which types map to which bump</h2>
 *
 * <p>Which type-word maps to which {@link Bump} is data, not hardcoded logic: it's a
 * {@code Map<Bump, Set<String>>} (see {@link #DEFAULT_TYPE_RULES}), optionally passed to
 * {@link #analyze(List, Map)} / {@link #analyzeOne(String, Map)} to override or extend it.
 * Consumers of the Gradle plugin do this via the {@code commitAnalyzer { }} extension registered by
 * {@link VersionPlugin} (see {@link CommitAnalyzerExtension}), which exposes one
 * {@code SetProperty<String>} per bump level ({@code majorTypes}, {@code minorTypes},
 * {@code patchTypes}, {@code noBumpTypes}) - each can be appended to
 * ({@code .add(...)}/{@code .addAll(...)}) or replaced outright ({@code .set(...)}) independently
 * of the others.
 *
 * <p>{@code majorTypes} defaults to <em>empty</em>, since MAJOR is normally driven by the
 * structural '!'/BREAKING CHANGE detection above rather than by type at all - it exists so a
 * project can ALSO force specific types (e.g. {@code security}) to always be MAJOR on their own,
 * without needing every such commit to remember the '!' marker.
 *
 * <p>When classifying a single type, the four levels are checked from <strong>most to least
 * severe</strong> (MAJOR, MINOR, PATCH, NONE - see {@link #analyzeOne(String, Map)}), so if a type
 * is (mis)configured into more than one set, the highest-severity match wins: a type present in
 * both {@code majorTypes} and {@code patchTypes} resolves to MAJOR.
 *
 * <p>See {@link VersionResolver} for how the result of analyzing a batch of commits gets turned
 * into an actual version number, and {@code ChangelogGenerator} (release-flow plugin) for how
 * {@link #parse(String)} - the syntactic parse, independent of typeRules - gets turned into release
 * notes.
 */
public final class CommitAnalyzer {

    private CommitAnalyzer() {
    }

    /**
     * The four possible outcomes of analyzing a commit (or a batch of commits), in <em>ascending</em>
     * severity. Deliberately ordered this way so that "highest bump wins across a batch" can be
     * implemented as a plain {@code ordinal()} comparison (see {@link #analyze(List, Map)}) rather
     * than a hand-written precedence table.
     */
    public enum Bump {
        NONE, PATCH, MINOR, MAJOR
    }

    /**
     * The syntactic parse of a commit message, independent of any type -&gt; bump configuration -
     * see {@link #parse(String)}. Used by the release-flow plugin's changelog generator to render
     * release notes; also used internally by {@link #analyzeOne(String, Map)}.
     */
    public static final class ParsedCommit {
        /** True if there was nothing to parse at all (a null/blank message, e.g. --allow-empty-message). */
        private final boolean empty;
        /** True if the message matched the "type[(scope)][!]: description" subject-line shape. */
        private final boolean conforms;
        /** Lowercase type word (e.g. "feat"), or null if !conforms or empty. */
        private final String type;
        /** Scope text without parens (e.g. "api" from "feat(api):"), or null if absent/!conforms/empty. */
        private final String scope;
        /** The subject-line description after the colon; the trimmed first line verbatim if !conforms. */
        private final String description;
        /** '!' marker on the subject line, or a BREAKING CHANGE/BREAKING-CHANGE footer anywhere in the body. */
        private final boolean breaking;

        public ParsedCommit(boolean empty, boolean conforms, String type, String scope, String description,
                             boolean breaking) {
            this.empty = empty;
            this.conforms = conforms;
            this.type = type;
            this.scope = scope;
            this.description = description;
            this.breaking = breaking;
        }

        public boolean isEmpty() {
            return empty;
        }

        public boolean isConforms() {
            return conforms;
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
    }

    /**
     * The built-in type -&gt; bump mapping, used whenever a caller doesn't supply its own. See the
     * class doc for why MAJOR's default set is empty.
     */
    public static final Map<Bump, Set<String>> DEFAULT_TYPE_RULES;

    static {
        Map<Bump, Set<String>> rules = new EnumMap<>(Bump.class);
        rules.put(Bump.MAJOR, Collections.emptySet());
        rules.put(Bump.MINOR, Set.of("feat"));
        rules.put(Bump.PATCH, Set.of("fix", "perf"));
        rules.put(Bump.NONE, Set.of("docs", "style", "refactor", "test", "chore", "build", "ci"));
        DEFAULT_TYPE_RULES = Collections.unmodifiableMap(rules);
    }

    // Bump levels in the order they're checked against a type: most severe first, so that a type
    // present in more than one set (through misconfiguration) resolves to the highest one.
    private static final List<Bump> SEVERITY_DESCENDING = List.of(Bump.MAJOR, Bump.MINOR, Bump.PATCH, Bump.NONE);

    // Matches "<type>[(<scope>)][!]: <description>" against just the SUBJECT LINE (the first line
    // of the message) - group 1 = type, group 3 = scope text (without parens), group 4 = the
    // optional '!', group 5 = the description text. Deliberately scoped to the first line only
    // (unlike the message as a whole) so the captured description doesn't swallow the body/footer.
    private static final Pattern SUBJECT_PATTERN = Pattern.compile("^(\\w+)(\\(([^)]+)\\))?(!)?:\\s*(.*)$");

    // The Conventional Commits spec defines a BREAKING CHANGE footer as a line starting with
    // literally "BREAKING CHANGE:" or "BREAKING-CHANGE:" (case-sensitive), which can appear
    // anywhere in the message body/footer, not just as the first line - hence MULTILINE + '^'
    // rather than anchoring to the start of the whole message.
    private static final Pattern BREAKING_FOOTER_PATTERN = Pattern.compile("^BREAKING[ -]CHANGE:", Pattern.MULTILINE);

    /** Equivalent to {@link #analyze(List, Map)} with {@link #DEFAULT_TYPE_RULES}. */
    public static Bump analyze(List<String> messages) {
        return analyze(messages, DEFAULT_TYPE_RULES);
    }

    /**
     * The overall bump for a range of commits (e.g. every commit since the last release): the
     * highest individual {@link #analyzeOne(String, Map)} result across the whole batch wins, so a
     * single {@code feat!:} commit among ten {@code chore:} commits still means MAJOR. An empty
     * batch is {@code Bump.NONE}, meaning "nothing here warrants a new version" - the caller then
     * decides what to do with that ({@link VersionResolver} leaves the base version unchanged).
     *
     * @param typeRules see {@link #DEFAULT_TYPE_RULES}
     */
    public static Bump analyze(List<String> messages, Map<Bump, Set<String>> typeRules) {
        Bump result = Bump.NONE;
        for (String message : messages) {
            Bump bump = analyzeOne(message, typeRules);
            if (bump.ordinal() > result.ordinal()) {
                result = bump;
            }
        }
        return result;
    }

    /** Equivalent to {@link #analyzeOne(String, Map)} with {@link #DEFAULT_TYPE_RULES}. */
    public static Bump analyzeOne(String message) {
        return analyzeOne(message, DEFAULT_TYPE_RULES);
    }

    /**
     * Classifies a single full commit message (subject + blank line + body/footer, if any).
     *
     * @param typeRules see {@link #DEFAULT_TYPE_RULES}
     */
    public static Bump analyzeOne(String message, Map<Bump, Set<String>> typeRules) {
        ParsedCommit parsed = parse(message);
        if (parsed.isEmpty()) {
            // Nothing to analyze at all - not the same thing as a non-conforming message, so no
            // warning and no defensive bump here.
            return Bump.NONE;
        }
        if (parsed.isBreaking()) {
            // Breaking-ness always wins over the type-rule mapping below, per spec: "fix!:" or a
            // fix with a BREAKING CHANGE footer is MAJOR, not PATCH - unconditionally, regardless
            // of what typeRules says, since this is the one structural (non-type-based) signal.
            return Bump.MAJOR;
        }
        if (parsed.isConforms()) {
            for (Bump bump : SEVERITY_DESCENDING) {
                if (typeRules.getOrDefault(bump, Collections.emptySet()).contains(parsed.getType())) {
                    return bump;
                }
            }
        }
        // Either didn't conform to "type: description" at all, or conformed but used a type that
        // isn't in any configured set (a typo, or a team convention this ruleset doesn't know
        // about) - equally non-conforming either way.
        return warnNonConforming(message.trim());
    }

    /**
     * The syntactic parse of a commit message - type/scope/description/breaking-ness - independent
     * of any type -&gt; bump configuration. See {@link ParsedCommit}.
     */
    public static ParsedCommit parse(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new ParsedCommit(true, false, null, null, "", false);
        }
        String trimmed = message.trim();
        int newlineIndex = trimmed.indexOf('\n');
        String subjectLine = (newlineIndex == -1 ? trimmed : trimmed.substring(0, newlineIndex)).trim();
        boolean breakingFooter = BREAKING_FOOTER_PATTERN.matcher(trimmed).find();

        Matcher matcher = SUBJECT_PATTERN.matcher(subjectLine);
        if (!matcher.matches()) {
            return new ParsedCommit(false, false, null, null, subjectLine, breakingFooter);
        }
        String type = matcher.group(1).toLowerCase();
        String scope = matcher.group(3);
        boolean breakingMarker = "!".equals(matcher.group(4));
        String description = matcher.group(5);
        return new ParsedCommit(false, true, type, scope, description, breakingMarker || breakingFooter);
    }

    private static Bump warnNonConforming(String message) {
        String firstLine = message.lines().filter(line -> !line.trim().isEmpty()).findFirst().orElse(message);
        System.err.println(
                "WARNING: commit message does not follow Conventional Commits - treating as a PATCH bump: "
                        + firstLine);
        return Bump.PATCH;
    }
}
