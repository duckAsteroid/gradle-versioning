package io.github.duckasteroid.gradle.versioning.releaseflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/**
 * Exercises the releaseCandidates { } extension registered by {@link ReleaseFlowPlugin}:
 * pruneSuperseded defaults to true, retain defaults to 0, and both are plain overridable
 * Property&lt;T&gt;s (no SetProperty append-vs-replace gotcha here, unlike CommitAnalyzerExtension).
 */
public class ReleaseCandidatesExtensionTest {

  private ReleaseCandidatesExtension extensionFor(Project project) {
    project.getPluginManager().apply("io.github.duckasteroid.version");
    project.getPluginManager().apply("io.github.duckasteroid.release-flow");
    return project.getExtensions().getByType(ReleaseCandidatesExtension.class);
  }

  @Test
  void defaultsPruneEverythingExceptTheNewRc() {
    Project project = ProjectBuilder.builder().withName("test").build();
    ReleaseCandidatesExtension extension = extensionFor(project);

    assertTrue(extension.getPruneSuperseded().get());
    assertEquals(0, extension.getRetain().get());
  }

  @Test
  void pruneSuperseededCanBeDisabled() {
    Project project = ProjectBuilder.builder().withName("test").build();
    ReleaseCandidatesExtension extension = extensionFor(project);

    extension.getPruneSuperseded().set(false);

    assertEquals(false, extension.getPruneSuperseded().get());
  }

  @Test
  void retainCanBeOverridden() {
    Project project = ProjectBuilder.builder().withName("test").build();
    ReleaseCandidatesExtension extension = extensionFor(project);

    extension.getRetain().set(2);

    assertEquals(2, extension.getRetain().get());
  }
}
