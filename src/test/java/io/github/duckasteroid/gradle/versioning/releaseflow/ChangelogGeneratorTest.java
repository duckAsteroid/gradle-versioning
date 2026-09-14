package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.duckasteroid.gradle.versioning.CommitAnalyzer;
import io.github.duckasteroid.gradle.versioning.CommitAnalyzer.Bump;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ChangelogGeneratorTest {

  @Test
  void singleFeatureCommitProducesAFeaturesSection() {
    String changelog = ChangelogGenerator.generate(List.of("feat: add support for widgets"));
    assertEquals("## Features\n- add support for widgets", changelog);
  }

  @Test
  void groupsBreakingFeaturesAndFixesInThatOrderRegardlessOfInputOrder() {
    List<String> messages = List.of(
        "fix: correct off-by-one error",
        "feat: add a thing",
        "feat!: drop support for old config format"
    );
    String changelog = ChangelogGenerator.generate(messages);
    assertEquals(
        "## Breaking Changes\n"
            + "- drop support for old config format\n\n"
            + "## Features\n"
            + "- add a thing\n\n"
            + "## Bug Fixes\n"
            + "- correct off-by-one error",
        changelog);
  }

  @Test
  void noBumpCommitsAreOmittedEntirely() {
    List<String> messages = List.of(
        "chore: bump dependency versions",
        "docs: fix typo in README",
        "feat: add a thing"
    );
    String changelog = ChangelogGenerator.generate(messages);
    assertEquals("## Features\n- add a thing", changelog);
    assertFalse(changelog.contains("chore"));
    assertFalse(changelog.contains("docs"));
  }

  @Test
  void emptyOrAllNoBumpBatchProducesNoChangesText() {
    assertEquals("No user-facing changes.", ChangelogGenerator.generate(List.of()));
    assertEquals(
        "No user-facing changes.",
        ChangelogGenerator.generate(List.of("chore: tidy up", "docs: update readme")));
  }

  @Test
  void includesScopeInBoldWhenPresent() {
    String changelog = ChangelogGenerator.generate(List.of("feat(api): add support for widgets"));
    assertEquals("## Features\n- **api:** add support for widgets", changelog);
  }

  @Test
  void nonConformingCommitAppearsUnderBugFixesWithRawFirstLine() {
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream()));
    String changelog;
    try {
      changelog = ChangelogGenerator.generate(List.of("Merge pull request #1 from foo/bar"));
    } finally {
      System.setErr(originalErr);
    }
    assertEquals("## Bug Fixes\n- Merge pull request #1 from foo/bar", changelog);
  }

  @Test
  void customMajorTypeWithoutMarkerStillGroupsUnderBreakingChanges() {
    Map<Bump, Set<String>> rules = new HashMap<>(CommitAnalyzer.DEFAULT_TYPE_RULES);
    rules.put(Bump.MAJOR, Set.of("security"));

    String changelog = ChangelogGenerator.generate(List.of("security: patch a CVE"), rules);
    assertEquals("## Breaking Changes\n- patch a CVE", changelog);
  }

  @Test
  void breakingChangeFooterDescriptionUsesSubjectLineNotTheFooterText() {
    String changelog = ChangelogGenerator.generate(
        List.of("fix: patch a bug\n\nBREAKING CHANGE: removes the old API entirely"));
    assertEquals("## Breaking Changes\n- patch a bug", changelog);
  }

  @Test
  void multipleCommitsInTheSameSectionAreAllListed() {
    List<String> messages = List.of("feat: first feature", "feat: second feature");
    String changelog = ChangelogGenerator.generate(messages);
    assertEquals("## Features\n- first feature\n- second feature", changelog);
  }

  @Test
  void emptyMessagesInTheBatchAreSkippedSilently() {
    String changelog = ChangelogGenerator.generate(java.util.Arrays.asList("", "feat: add a thing", null));
    assertEquals("## Features\n- add a thing", changelog);
  }

  @Test
  void customPatchTypeAppendedViaSetProperty() {
    Map<Bump, Set<String>> rules = new HashMap<>(CommitAnalyzer.DEFAULT_TYPE_RULES);
    Set<String> patchWithHotfix = new HashSet<>(CommitAnalyzer.DEFAULT_TYPE_RULES.get(Bump.PATCH));
    patchWithHotfix.add("hotfix");
    rules.put(Bump.PATCH, patchWithHotfix);

    String changelog = ChangelogGenerator.generate(List.of("hotfix: emergency patch"), rules);
    assertEquals("## Bug Fixes\n- emergency patch", changelog);
  }
}
