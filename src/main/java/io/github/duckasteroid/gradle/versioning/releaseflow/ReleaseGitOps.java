package io.github.duckasteroid.gradle.versioning.releaseflow;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-file/process helpers for {@link ReleaseFlowPlugin}'s doLast blocks - changelog writing,
 * tagging/pushing via the git CLI, and the manifest's relative-path formatting.
 *
 * <p>Under the configuration cache, a doLast action is serialized at store time and rebuilt at
 * execution time - capturing everything it needs as plain local values (File/String, never live
 * `Project` references) up front, and calling only into plain static methods like these, is what
 * keeps that safe. This mirrors the equivalent discipline in the original Groovy plugin, just
 * expressed as ordinary Java method calls from a lambda instead of a script's doLast closure.
 */
public final class ReleaseGitOps {

    private ReleaseGitOps() {
    }

    /** Writes generated changelog Markdown to outputFile, creating parent directories as needed. */
    public static void writeChangelog(File outputFile, String changelog) {
        try {
            if (outputFile.getParentFile() != null) {
                outputFile.getParentFile().mkdirs();
            }
            Files.writeString(outputFile.toPath(), changelog, StandardCharsets.UTF_8);
            System.out.println("Changelog written to " + outputFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates an annotated tag at HEAD and pushes it to {@code origin} - the only state-mutating step here. */
    public static void createAndPushTag(File repoDir, String tag) {
        runGit(repoDir, "tag", "-a", tag, "-m", "Release " + tag);
        runGit(repoDir, "push", "origin", tag);
    }

    /** file's path relative to base, with forward slashes regardless of OS - for the JSON manifest. */
    public static String relativePath(File base, File file) {
        return base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    /**
     * The current branch name (e.g. "release"), for explainVersion(s)' buildVersion computation
     * (see {@code VersionResolver.resolveBuildVersion}'s branchName parameter) - deliberately a
     * plain {@code git} CLI call rather than JGit's own {@code Repository.getBranch()}, since this
     * only ever runs from a doLast block (task execution time), where an external process is fine,
     * and reusing {@link #runGit} here means there's exactly one place that shells out to git in
     * this class.
     */
    public static String currentBranch(File repoDir) {
        return runGit(repoDir, "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    /**
     * Plain {@link ProcessBuilder} rather than a live {@code Project.exec} (unsupported under the
     * configuration cache from a task action at execution time) or JGit plumbing (pushing a tag
     * needs real push credentials - the {@code git} CLI transparently reuses whatever's already
     * configured, both locally and via {@code actions/checkout}'s persisted {@code GITHUB_TOKEN} in
     * CI, with no extra credential wiring needed here). Returns stdout+stderr (merged) so callers
     * that need the output (e.g. {@link #currentBranch}) don't need their own ProcessBuilder
     * plumbing.
     */
    public static String runGit(File repoDir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        for (String arg : args) {
            command.add(arg);
        }
        try {
            Process process = new ProcessBuilder(command).directory(repoDir).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new RuntimeException("git " + String.join(" ", args) + " failed (" + exit + "): " + output.trim());
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
