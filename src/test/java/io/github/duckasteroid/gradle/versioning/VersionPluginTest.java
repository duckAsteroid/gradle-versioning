package io.github.duckasteroid.gradle.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/**
 * Structural unit test for {@link VersionPlugin}: extension registration and the
 * {@code project.ext.tagPrefix}/{@code modulePath} exports, applied with no {@code java} plugin at
 * all - this plugin has zero dependency on it. See {@code VersionPluginFunctionalTest} for the
 * end-to-end Conventional-Commits computation against a real git repo.
 */
public class VersionPluginTest {

  @Test
  void appliesWithoutJavaAndRegistersCommitAnalyzerExtensionAndTagPrefix() {
    Project project = ProjectBuilder.builder().withName("test").build();
    project.getPluginManager().apply("io.github.duckasteroid.version");

    assertFalse(project.getPluginManager().hasPlugin("java"), "no java plugin should be applied");
    assertEquals("v", project.getExtensions().getExtraProperties().get("tagPrefix"));
    assertEquals("", project.getExtensions().getExtraProperties().get("modulePath"));

    CommitAnalyzerExtension extension = project.getExtensions().getByType(CommitAnalyzerExtension.class);
    assertEquals(CommitAnalyzer.DEFAULT_TYPE_RULES, extension.toTypeRules());
  }
}
