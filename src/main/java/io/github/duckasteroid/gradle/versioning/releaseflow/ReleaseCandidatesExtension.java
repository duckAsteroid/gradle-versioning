package io.github.duckasteroid.gradle.versioning.releaseflow;

import org.gradle.api.provider.Property;

/**
 * Gradle-facing configuration for how the tagReleaseCandidates/tagReleaseCandidate tasks treat
 * previous release candidates once a new one is minted, registered by {@link ReleaseFlowPlugin} as
 * the {@code releaseCandidates { } } extension. Release candidates aren't permanent artifacts -
 * only the one that eventually gets promoted matters, and any earlier one is either absorbed into a
 * later RC or simply abandoned - so by default every RC's GitHub Release is deleted (not the
 * underlying git tag or published package - see
 * {@code VersionResolver#currentCycleReleaseCandidateTags}) as soon as a newer one exists.
 *
 * <p>Plain scalar Property&lt;T&gt;s, like {@link ChangelogExtension} - no SetProperty
 * append-vs-replace gotcha here, {@code .convention(...)} works exactly as expected.
 *
 * <pre>
 * releaseCandidates {
 *     pruneSuperseded = false   // default: true - keep every RC's GitHub Release forever instead
 *     retain = 2                // default: 0 - also keep the 2 most recent RCs besides the new one
 * }
 * </pre>
 */
public abstract class ReleaseCandidatesExtension {

    public abstract Property<Boolean> getPruneSuperseded();

    public abstract Property<Integer> getRetain();
}
