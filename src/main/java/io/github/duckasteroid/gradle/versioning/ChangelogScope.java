package io.github.duckasteroid.gradle.versioning;

/**
 * How far back the release-flow plugin's changelog generator should look when generating notes for
 * a release candidate (the {@code changelogForReleaseCandidate} task, controlled via the
 * {@code changelog { rcScope = ... }} extension). Not used by {@code changelogForRelease} -
 * promoting an RC to a final release always covers everything since the last final release, since
 * that's the complete picture for the actual release, regardless of how many RCs it took to get
 * there or what rcScope is set to.
 *
 * <p>Lives in this package (not the release-flow plugin's own) because {@link VersionResolver}
 * itself takes a {@code ChangelogScope} parameter ({@link VersionResolver#commitMessagesForChangelog})
 * - the commit-listing logic is shared, so the enum it's parameterized by has to be visible from
 * here too.
 */
public enum ChangelogScope {
    /**
     * Every commit since the last <em>final</em> release (default) - the complete picture across
     * every RC in this cycle so far, not just what's new since the previous RC.
     */
    SINCE_LAST_RELEASE,

    /**
     * Only commits since the <em>previous</em> release-candidate tag - a delta, useful for
     * reviewers re-checking what changed in a bumped RC rather than re-reading the whole cycle
     * again. Falls back to {@link #SINCE_LAST_RELEASE} automatically when there is no previous RC
     * tag yet (the first RC in a cycle has nothing to diff against).
     */
    SINCE_PREVIOUS_RC
}
