package io.github.duckasteroid.gradle.versioning.releaseflow;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * Gradle-facing configuration for explainVersion/explainVersions' structured JSON report,
 * registered by {@link ReleaseFlowPlugin} as the {@code versionReport { } } extension. The console
 * breakdown these tasks print always happens regardless of this extension - it only controls the
 * {@code build/version-report.json} file.
 *
 * <p>Plain scalar Property&lt;T&gt;s, like {@link ChangelogExtension}/{@link ReleaseCandidatesExtension}
 * - no SetProperty append-vs-replace gotcha here, {@code .convention(...)} works exactly as expected.
 *
 * <pre>
 * versionReport {
 *     enabled = false                                                 // default: true
 *     outputFile = layout.buildDirectory.file('reports/version.json') // default: build/version-report.json
 * }
 * </pre>
 */
public abstract class VersionReportExtension {

    public abstract Property<Boolean> getEnabled();

    public abstract RegularFileProperty getOutputFile();
}
