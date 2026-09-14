package io.github.duckasteroid.gradle.versioning;

import org.gradle.api.provider.SetProperty;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Gradle-facing configuration for {@link CommitAnalyzer}'s type-to-bump mapping. Registered by
 * {@link VersionPlugin} as the {@code commitAnalyzer { }} extension, with each property
 * <em>pre-populated</em> (via {@code addAll(...)}, not {@code convention(...)} - see the note
 * below) with the corresponding entry of {@link CommitAnalyzer#DEFAULT_TYPE_RULES}. This is an
 * abstract class rather than a plain one deliberately - Gradle auto-implements the abstract
 * SetProperty getters (its "managed properties" mechanism).
 *
 * <p>Consumers configure it like:
 *
 * <pre>
 * commitAnalyzer {
 *     minorTypes.add('perf2')        // append 'perf2' to the default minor-bump types
 *     majorTypes.add('security')     // always treat "security: ..." commits as breaking
 *     noBumpTypes.set(['docs'])      // REPLACE the default no-bump set entirely (not append)
 * }
 * </pre>
 *
 * <p>{@code .add(...)}/{@code .addAll(...)} appends to whatever's already in the property;
 * {@code .set(...)} replaces it outright. Note this is why {@link VersionPlugin} seeds the
 * defaults with {@code addAll(...)} rather than {@code convention(...)}: a {@code Property}'s
 * convention is only used as a fallback while nothing has been explicitly added/set - the moment
 * {@code .add(...)} is called, Gradle discards the convention rather than appending to it, so
 * seeding with {@code convention(...)} would make {@code minorTypes.add('perf2')} silently end up
 * as just {@code ['perf2']}, losing the default {@code 'feat'}.
 */
public abstract class CommitAnalyzerExtension {

    public abstract SetProperty<String> getMajorTypes();

    public abstract SetProperty<String> getMinorTypes();

    public abstract SetProperty<String> getPatchTypes();

    public abstract SetProperty<String> getNoBumpTypes();

    /** Materializes the four properties into the {@code Map<Bump, Set<String>>} CommitAnalyzer expects. */
    public Map<CommitAnalyzer.Bump, Set<String>> toTypeRules() {
        Map<CommitAnalyzer.Bump, Set<String>> rules = new EnumMap<>(CommitAnalyzer.Bump.class);
        rules.put(CommitAnalyzer.Bump.MAJOR, getMajorTypes().get());
        rules.put(CommitAnalyzer.Bump.MINOR, getMinorTypes().get());
        rules.put(CommitAnalyzer.Bump.PATCH, getPatchTypes().get());
        rules.put(CommitAnalyzer.Bump.NONE, getNoBumpTypes().get());
        return rules;
    }
}
