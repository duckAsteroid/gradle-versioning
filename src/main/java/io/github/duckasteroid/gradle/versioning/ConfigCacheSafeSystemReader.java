package io.github.duckasteroid.gradle.versioning;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

/**
 * Constructing a JGit {@code Repository} (via {@code RepositoryBuilder().build()}) makes JGit
 * shell out to the real {@code git} binary ({@code git --version}, then
 * {@code git config --system --show-origin --list -z}) the first time it needs the system-level
 * git config - {@code SystemReader.getSystemConfig()} -&gt; {@code openSystemConfig()} -&gt;
 * {@code FS.discoverGitSystemConfig()} -&gt; {@code FS.readPipe()} - because there's no portable
 * file-path convention for where that config lives. That's an external process started during
 * Gradle <em>configuration</em> time wherever a Repository is built eagerly (as opposed to inside
 * a task's {@code doLast}), which the configuration cache rejects outright.
 *
 * <p>Neither {@link VersionPlugin} nor {@link VersionResolver} ever read system/global git config -
 * only {@code remote.origin.url} from the repo's own local config, plus refs/tags/HEAD - so it's
 * safe to tell JGit to skip that layer entirely.
 *
 * <p>JGit's own {@code SystemReader$Default#openSystemConfig} already has a fast path for this: it
 * checks the {@code GIT_CONFIG_NOSYSTEM} environment variable (the same one the real git CLI
 * honours) and, if set, skips straight to an empty, no-op config instead of shelling out. The
 * obvious way to reach that fast path would be overriding {@code getenv("GIT_CONFIG_NOSYSTEM")} on
 * a {@link SystemReader.Delegate} - but {@code Delegate#openSystemConfig} forwards directly to the
 * <em>wrapped</em> reader's {@code openSystemConfig}, which then calls {@code getenv} on itself,
 * not back up through the wrapper - so a {@code getenv} override alone is silently never consulted.
 * Overriding {@code openSystemConfig} itself, below, sidesteps that dispatch trap entirely and is
 * also just a more direct expression of "skip system config" than faking an environment variable to
 * trigger a side path.
 *
 * <p>{@code SystemReader.getInstance()}/{@code setInstance()} are JVM-wide, so this must be called
 * once, defensively, immediately before any code in this project constructs a
 * {@code RepositoryBuilder}-based Repository - it's idempotent and cheap to call more than once.
 */
public final class ConfigCacheSafeSystemReader {

    private ConfigCacheSafeSystemReader() {
    }

    public static synchronized void install() {
        if (SystemReader.getInstance() instanceof NoSystemConfigSystemReader) {
            return;
        }
        SystemReader.setInstance(new NoSystemConfigSystemReader(SystemReader.getInstance()));
    }

    private static final class NoSystemConfigSystemReader extends SystemReader.Delegate {
        NoSystemConfigSystemReader(SystemReader parent) {
            super(parent);
        }

        @Override
        public FileBasedConfig openSystemConfig(Config parent, FS fs) {
            // Mirrors SystemReader$Default's own GIT_CONFIG_NOSYSTEM=1 fast path: a FileBasedConfig
            // with no backing file whose load() is a no-op, so it always resolves as empty without
            // ever touching disk or a subprocess.
            return new FileBasedConfig(parent, null, fs) {
                @Override
                public void load() {
                    // Deliberately empty - see class doc comment above.
                }

                @Override
                public boolean isOutdated() {
                    return false;
                }
            };
        }
    }
}
