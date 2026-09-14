package io.github.duckasteroid.gradle.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.gradle.versioning.CommitAnalyzer.Bump;
import io.github.duckasteroid.gradle.versioning.CommitAnalyzer.ParsedCommit;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class CommitAnalyzerTest {

  /** Copies DEFAULT_TYPE_RULES with one level's set replaced - simulates a Gradle noBumpTypes.set(...) override. */
  private static Map<Bump, Set<String>> withOverride(Bump bump, Set<String> types) {
    Map<Bump, Set<String>> rules = new HashMap<>(CommitAnalyzer.DEFAULT_TYPE_RULES);
    rules.put(bump, types);
    return rules;
  }

  /** Copies DEFAULT_TYPE_RULES with one extra type added to a level - simulates a Gradle xTypes.add(...) append. */
  private static Map<Bump, Set<String>> withAppended(Bump bump, String extraType) {
    Set<String> merged = new HashSet<>(CommitAnalyzer.DEFAULT_TYPE_RULES.get(bump));
    merged.add(extraType);
    return withOverride(bump, merged);
  }

  @ParameterizedTest
  @CsvSource({
      "fix: correct off-by-one error, PATCH",
      "perf: speed up parsing, PATCH",
      "feat: add support for widgets, MINOR",
      "feat(api): add support for widgets, MINOR",
      "feat!: drop support for old config format, MAJOR",
      "feat(api)!: drop support for old config format, MAJOR",
      "chore: bump dependency versions, NONE",
      "docs: fix typo in README, NONE",
      "style: reformat, NONE",
      "refactor: extract method, NONE",
      "test: add missing coverage, NONE",
      "build: bump gradle wrapper, NONE",
      "ci: tweak workflow, NONE",
      "Merge pull request #1 from foo/bar, PATCH",
      "wip: something not yet a recognized type, PATCH",
      "'', NONE",
  })
  void classifiesSingleCommitSubjectLine(String message, CommitAnalyzer.Bump expected) {
    assertEquals(expected, CommitAnalyzer.analyzeOne(message));
  }

  @Test
  void nonConformingMessageLogsAWarningToStderr() {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured));
    try {
      CommitAnalyzer.analyzeOne("Merge pull request #1 from foo/bar");
    } finally {
      System.setErr(originalErr);
    }
    String output = captured.toString();
    assertTrue(output.contains("WARNING"), "expected a warning to be printed, got: " + output);
    assertTrue(output.contains("Merge pull request #1 from foo/bar"));
  }

  @Test
  void detectsBreakingChangeFooterRegardlessOfType() {
    String message = "fix: patch a bug\n\nBREAKING CHANGE: removes the old API entirely";
    assertEquals(CommitAnalyzer.Bump.MAJOR, CommitAnalyzer.analyzeOne(message));
  }

  @Test
  void nullMessageIsNone() {
    assertEquals(CommitAnalyzer.Bump.NONE, CommitAnalyzer.analyzeOne(null));
  }

  @Test
  void batchTakesHighestSeverityAcrossCommits() {
    List<String> messages = Arrays.asList(
        "chore: tidy up",
        "fix: correct a bug",
        "feat: add a thing",
        "docs: update readme"
    );
    assertEquals(CommitAnalyzer.Bump.MINOR, CommitAnalyzer.analyze(messages));
  }

  @Test
  void batchWithBreakingChangeIsMajorEvenIfMostCommitsAreMinor() {
    List<String> messages = Arrays.asList(
        "feat: add a thing",
        "feat!: remove the old thing"
    );
    assertEquals(CommitAnalyzer.Bump.MAJOR, CommitAnalyzer.analyze(messages));
  }

  @Test
  void emptyBatchIsNone() {
    assertEquals(CommitAnalyzer.Bump.NONE, CommitAnalyzer.analyze(List.of()));
  }

  // ---- configurable type-rules (majorTypes/minorTypes/patchTypes/noBumpTypes) ----

  @Test
  void defaultTypeRulesMatchTheDocumentedDefaults() {
    assertEquals(Set.of(), CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.MAJOR));
    assertEquals(Set.of("feat"), CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.MINOR));
    assertEquals(Set.of("fix", "perf"), CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.PATCH));
    assertEquals(
        Set.of("docs", "style", "refactor", "test", "chore", "build", "ci"),
        CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.NONE));
  }

  @Test
  void appendingToMajorTypesForcesThatTypeToMajorEvenWithoutMarkerOrFooter() {
    Map<Bump, Set<String>> rules = withAppended(Bump.MAJOR, "security");
    assertEquals(Bump.MAJOR, CommitAnalyzer.analyzeOne("security: patch a CVE", rules));
    // Everything else in the default rules keeps behaving normally alongside the addition.
    assertEquals(Bump.MINOR, CommitAnalyzer.analyzeOne("feat: unrelated feature", rules));
  }

  @Test
  void appendingToMinorTypesAddsAnAdditionalMinorType() {
    Map<Bump, Set<String>> rules = withAppended(Bump.MINOR, "feat2");
    assertEquals(Bump.MINOR, CommitAnalyzer.analyzeOne("feat2: a second feature-ish type", rules));
    assertEquals(Bump.MINOR, CommitAnalyzer.analyzeOne("feat: still works", rules));
  }

  @Test
  void appendingToPatchTypesAddsAnAdditionalPatchType() {
    Map<Bump, Set<String>> rules = withAppended(Bump.PATCH, "hotfix");
    assertEquals(Bump.PATCH, CommitAnalyzer.analyzeOne("hotfix: emergency patch", rules));
    assertEquals(Bump.PATCH, CommitAnalyzer.analyzeOne("fix: still works", rules));
  }

  @Test
  void appendingToNoBumpTypesSuppressesAnAdditionalType() {
    Map<Bump, Set<String>> rules = withAppended(Bump.NONE, "deps");
    assertEquals(Bump.NONE, CommitAnalyzer.analyzeOne("deps: bump a dependency", rules));
  }

  @Test
  void overridingNoBumpTypesReplacesTheDefaultSetEntirely() {
    // .set(['docs']) semantics: only 'docs' is a no-bump type now, NOT chore/style/etc.
    Map<Bump, Set<String>> rules = withOverride(Bump.NONE, Set.of("docs"));
    assertEquals(Bump.NONE, CommitAnalyzer.analyzeOne("docs: still none", rules));
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream()));
    try {
      // 'chore' is no longer in ANY configured set, so it's now non-conforming -> PATCH + warning.
      assertEquals(Bump.PATCH, CommitAnalyzer.analyzeOne("chore: no longer recognized", rules));
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  void typeInBothMajorAndPatchSetsResolvesToMajor() {
    Map<Bump, Set<String>> rules = new HashMap<>(CommitAnalyzer.DEFAULT_TYPE_RULES);
    rules.put(Bump.MAJOR, Set.of("security"));
    Set<String> patchWithSecurity = new HashSet<>(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.PATCH));
    patchWithSecurity.add("security");
    rules.put(Bump.PATCH, patchWithSecurity);
    assertEquals(Bump.MAJOR, CommitAnalyzer.analyzeOne("security: patch a CVE", rules));
  }

  @Test
  void typeInBothMinorAndNoBumpSetsResolvesToMinor() {
    Map<Bump, Set<String>> rules = new HashMap<>(CommitAnalyzer.DEFAULT_TYPE_RULES);
    rules.put(Bump.MINOR, Set.of("chore"));
    // 'chore' is still in the default NONE set too - MINOR should win (checked first).
    assertEquals(Bump.MINOR, CommitAnalyzer.analyzeOne("chore: actually a feature this time", rules));
  }

  @Test
  void breakingMarkerWinsRegardlessOfCustomTypeRules() {
    // Even if a type is explicitly configured as NONE, a '!' marker still forces MAJOR - the
    // structural detection is never overridden by typeRules.
    Map<Bump, Set<String>> rules = withOverride(Bump.NONE, Set.of("feat"));
    assertEquals(Bump.MAJOR, CommitAnalyzer.analyzeOne("feat!: breaking despite being 'none'-configured", rules));
  }

  @Test
  void emptyMajorTypesIsTheDefaultSoOnlyMarkerOrFooterTriggersMajor() {
    assertEquals(Set.of(), CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.MAJOR));
    assertEquals(Bump.PATCH, CommitAnalyzer.analyzeOne("fix: not major by default"));
  }

  @Test
  void partialCustomRulesMapTreatsMissingLevelsAsEmpty() {
    // A map that only defines MINOR (e.g. hand-built rather than via CommitAnalyzerExtension,
    // which always supplies all four) shouldn't NPE for the other levels - fix/perf become
    // non-conforming (PATCH + warning) since PATCH isn't present in this map at all.
    Map<Bump, Set<String>> rules = Map.of(Bump.MINOR, Set.of("feat"));
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream()));
    try {
      assertEquals(Bump.PATCH, CommitAnalyzer.analyzeOne("fix: no patch level configured", rules));
    } finally {
      System.setErr(originalErr);
    }
    assertEquals(Bump.MINOR, CommitAnalyzer.analyzeOne("feat: still works", rules));
  }

  @Test
  void customRulesApplyAcrossAWholeBatchToo() {
    Map<Bump, Set<String>> rules = withAppended(Bump.MAJOR, "security");
    List<String> messages = Arrays.asList(
        "chore: tidy up",
        "security: patch a CVE",
        "feat: add a thing"
    );
    assertEquals(Bump.MAJOR, CommitAnalyzer.analyze(messages, rules));
  }

  // ---- parse() - the syntactic parse used by ChangelogGenerator ----

  @Test
  void parseExtractsTypeScopeAndDescription() {
    ParsedCommit parsed = CommitAnalyzer.parse("feat(api): add support for widgets");
    assertTrue(parsed.isConforms());
    assertFalse(parsed.isEmpty());
    assertEquals("feat", parsed.getType());
    assertEquals("api", parsed.getScope());
    assertEquals("add support for widgets", parsed.getDescription());
    assertFalse(parsed.isBreaking());
  }

  @Test
  void parseHasNullScopeWhenAbsent() {
    ParsedCommit parsed = CommitAnalyzer.parse("fix: correct off-by-one error");
    assertEquals("fix", parsed.getType());
    assertNull(parsed.getScope());
    assertEquals("correct off-by-one error", parsed.getDescription());
  }

  @Test
  void parseDetectsBreakingMarker() {
    ParsedCommit parsed = CommitAnalyzer.parse("feat(api)!: drop support for old format");
    assertTrue(parsed.isBreaking());
    assertEquals("feat", parsed.getType());
    assertEquals("api", parsed.getScope());
  }

  @Test
  void parseDetectsBreakingFooterEvenWithoutMarker() {
    ParsedCommit parsed = CommitAnalyzer.parse("fix: patch a bug\n\nBREAKING CHANGE: removes the old API");
    assertTrue(parsed.isBreaking());
    assertEquals("fix", parsed.getType());
    assertEquals("patch a bug", parsed.getDescription());
  }

  @Test
  void parseOnlyCapturesTheSubjectLineNotTheBody() {
    ParsedCommit parsed = CommitAnalyzer.parse("feat: add a thing\n\nSome body text describing the change in detail.");
    assertEquals("add a thing", parsed.getDescription());
  }

  @Test
  void parseNonConformingMessageIsNotConformingButHasTheFirstLineAsDescription() {
    ParsedCommit parsed = CommitAnalyzer.parse("Merge pull request #1 from foo/bar");
    assertFalse(parsed.isConforms());
    assertFalse(parsed.isEmpty());
    assertNull(parsed.getType());
    assertEquals("Merge pull request #1 from foo/bar", parsed.getDescription());
  }

  @Test
  void parseEmptyMessageIsMarkedEmptyNotNonConforming() {
    ParsedCommit parsed = CommitAnalyzer.parse("");
    assertTrue(parsed.isEmpty());
    assertFalse(parsed.isConforms());

    ParsedCommit parsedNull = CommitAnalyzer.parse(null);
    assertTrue(parsedNull.isEmpty());
  }

  @Test
  void parseIsIndependentOfTypeRulesUnlikeAnalyzeOne() {
    // 'chore' isn't in minorTypes/patchTypes/majorTypes, but parse() doesn't care - it's purely
    // syntactic, unaffected by any typeRules configuration.
    ParsedCommit parsed = CommitAnalyzer.parse("chore: bump a dependency");
    assertTrue(parsed.isConforms());
    assertEquals("chore", parsed.getType());
  }
}
