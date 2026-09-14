package io.github.duckasteroid.gradle.versioning.releaseflow;

import io.github.duckasteroid.gradle.versioning.ChangelogScope;
import org.gradle.api.provider.Property;

/**
 * Gradle-facing configuration for {@link ChangelogGenerator}, registered by {@link ReleaseFlowPlugin}
 * as the {@code changelog { }} extension. Only {@code rcScope} exists - there's no equivalent choice
 * for the final release, which always covers everything since the last release regardless (see
 * {@link ChangelogScope}).
 *
 * <p>Unlike {@code io.github.duckasteroid.gradle.versioning.CommitAnalyzerExtension}'s
 * SetProperty-s, this is a plain scalar {@code Property<ChangelogScope>}, so the usual
 * convention()-vs-addAll() gotcha doesn't apply here - there's no "append" operation for a single
 * value, {@code .convention(...)} works exactly as expected as a plain fallback.
 *
 * <pre>
 * changelog {
 *     rcScope = ChangelogScope.SINCE_PREVIOUS_RC   // default: SINCE_LAST_RELEASE
 * }
 * </pre>
 */
public abstract class ChangelogExtension {

    public abstract Property<ChangelogScope> getRcScope();
}
