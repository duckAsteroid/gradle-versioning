package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/**
 * Confirms {@link ReleaseFlowPlugin} registers all ten of its tasks: the five original per-project
 * tasks, plus the tagReleaseCandidates/promoteReleaseCandidates/explainVersions aggregators and the
 * now-rootProject-scoped installReleaseWorkflows/checkReleaseWorkflows - task <em>actions</em>
 * aren't exercised here ({@code ProjectBuilder} doesn't run {@code doLast} actions, and the
 * aggregators' doLast wiring is deferred to a {@code projectsEvaluated} callback that never fires
 * under {@code ProjectBuilder} anyway; that logic is covered directly by
 * {@code ManagedFileInstallerTest}/{@code ManagedFileCheckerTest}/{@code ReleaseManifestTest}/
 * {@code VersionReportTest}/the functional tests instead), just that applying the plugin wires them
 * all up correctly. Only {@code io.github.duckasteroid.version} is applied alongside it, no
 * {@code java} plugin at all - this plugin has no dependency on one except for
 * {@code installReleaseWorkflows}'s own task action, not its registration. The project built here
 * has no parent, so it IS its own rootProject - the same tasks container the plugin registers the
 * root-scoped tasks on.
 */
public class ReleaseFlowPluginTest {

  @Test
  void registersAllTenReleaseTasks() {
    Project project = ProjectBuilder.builder().withName("test").build();
    project.getPluginManager().apply("io.github.duckasteroid.version");
    project.getPluginManager().apply("io.github.duckasteroid.release-flow");

    for (String taskName :
        new String[] {
          "tagReleaseCandidate",
          "promoteReleaseCandidate",
          "changelogForReleaseCandidate",
          "changelogForRelease",
          "explainVersion",
          "tagReleaseCandidates",
          "promoteReleaseCandidates",
          "explainVersions",
          "installReleaseWorkflows",
          "checkReleaseWorkflows"
        }) {
      Task task = project.getTasks().findByName(taskName);
      assertNotNull(task, taskName + " should be registered");
      assertEquals("release", task.getGroup(), taskName + " should be in the 'release' group");
    }
  }
}
