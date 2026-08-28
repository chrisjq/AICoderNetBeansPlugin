package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

/**
 * Finds files by their leaf name below one or more permitted directories. Matching deliberately shares
 * {@link LineMatcher}'s literal/regex, case, and timeout semantics with the text-search tools.
 */
public final class FindFileProvider {

    private static final Logger LOG = Logger.getLogger(FindFileProvider.class.getName());

    public static final int DEFAULT_MAX_MATCHES = 200;
    public static final int MAX_MATCHES = 5000;

    /**
     * Hard ceiling on how many directory levels a walk will descend, applied even when the caller asks for unlimited.
     * <p>
     * Two problems, one bound. Arithmetically, {@code maxDepth + 1} overflowed to {@code Integer.MIN_VALUE} at the top
     * of the int range and {@code Files.walk} rejects a negative depth, so a legal argument escaped as an internal
     * error. Practically, an unlimited walk is unbounded work: measured on this project, depth 0 took 23ms against
     * 853ms unlimited, and nothing stopped a pathological or looping tree from descending indefinitely.
     * <p>
     * 100 is far beyond any real source tree — deeply nested builds rarely pass 20 — so the ceiling is invisible in
     * normal use while making the walk's cost and its arithmetic both finite.
     */
    public static final int MAX_DEPTH_CEILING = 100;

    /**
     * Depth semantics exposed to callers: {@code 0} is the starting directory itself, {@code 1} adds one level below
     * it, and so on. {@link Files#walk(Path, int, java.nio.file.FileVisitOption...)} counts differently — its
     * {@code maxDepth} of 0 visits only the start directory and never its files — so a caller's depth is always
     * translated by {@link #walkDepth(int)} rather than passed through.
     */
    public static int walkDepth(int maxDepth) {
        // Clamp BEFORE the +1. Adding first and clamping after would already have overflowed: Integer.MAX_VALUE + 1
        // wraps to Integer.MIN_VALUE, Files.walk rejects a negative depth, and the walk's IOException-only catch let
        // that escape the tool as an internal error. Clamping first makes the arithmetic unconditionally safe.
        int requested = maxDepth < 0 ? MAX_DEPTH_CEILING : Math.min(maxDepth, MAX_DEPTH_CEILING);
        return requested + 1;
    }

    /**
     * True when a path should be skipped under {@code ignoreHidden}.
     * <p>
     * A leading-dot name is hidden on EVERY platform, Windows included. That is a deliberate product decision rather
     * than a portability accident: {@code .git}, {@code .env} and friends are conventionally hidden wherever the
     * checkout happens to sit, and a caller asking to skip hidden files does not expect them back just because the
     * repository was cloned on Windows.
     * <p>
     * The platform check is then additive, not an alternative. {@link Files#isHidden} reads the DOS hidden ATTRIBUTE on
     * Windows and ignores the name, so it catches attribute-hidden files whose names do NOT begin with a dot; on Unix
     * it reports only the leading dot, which the first check has already handled. Neither test subsumes the other, so
     * both run.
     */
    public static boolean isHiddenPath(Path path) {
        Path name = path.getFileName();
        if (name != null && name.toString().startsWith(".")) {
            return true;
        }
        try {
            return Files.isHidden(path);
        }
        catch (IOException e) {
            // Unreadable attributes are not evidence of hiddenness; leave the path visible rather than silently
            // dropping it from results.
            return false;
        }
    }

    public static String findFiles(List<Path> directories, String pattern, boolean isRegex,
            boolean caseSensitive, int maxMatches) {
        return findFiles(directories, pattern, isRegex, caseSensitive, maxMatches, true, -1, path -> true);
    }

    public static String findFiles(List<Path> directories, String pattern, boolean isRegex,
            boolean caseSensitive, int maxMatches, Predicate<Path> isAccessible) {
        return findFiles(directories, pattern, isRegex, caseSensitive, maxMatches, true, -1, isAccessible);
    }

    public static String findFiles(List<Path> directories, String pattern, boolean isRegex,
            boolean caseSensitive, int maxMatches, boolean ignoreHidden, int maxDepth,
            Predicate<Path> isAccessible) {
        return findFiles(directories, pattern, isRegex, caseSensitive, maxMatches, ignoreHidden, maxDepth,
                FindFileTypeEnum.DEFAULT, isAccessible);
    }

    public static String findFiles(List<Path> directories, String pattern, boolean isRegex,
            boolean caseSensitive, int maxMatches, boolean ignoreHidden, int maxDepth,
            FindFileTypeEnum type, Predicate<Path> isAccessible) {
        if (directories == null || directories.isEmpty()) {
            return "No projects open";
        }
        String query = pattern == null ? "" : pattern;
        Pattern compiled;
        try {
            compiled = LineMatcher.compile(query, isRegex, caseSensitive);
        }
        catch (PatternSyntaxException e) {
            return "Invalid regex: " + e.getMessage();
        }

        int cap = maxMatches <= 0 ? DEFAULT_MAX_MATCHES : Math.min(maxMatches, MAX_MATCHES);
        int depth = walkDepth(maxDepth);
        // Only MATCHES are collected and sorted, not every path walked. The previous stream.sorted() ordered the entire
        // tree before anything was filtered, which is work proportional to the directory rather than to the answer.
        List<Path> matches = new ArrayList<>();
        // Counted separately from the listing because the header reports the TRUE total while the listing is capped —
        // that honesty is the reason the walk cannot simply stop once the cap is full.
        int[] total = {0};
        // Set by the visitor when a regex times out; checked after each entry so the walk stops promptly.
        AtomicReference<String> failure = new AtomicReference<>();
        // Entries the filesystem refused. Reported rather than swallowed, so an incomplete answer says it is one.
        int[] skipped = {0};
        // Real (symlink-resolved) paths already visited, so the same file is never counted twice and a root that is
        // an alias of, or nested inside, another root is skipped rather than re-walked.
        //
        // The walk does NOT follow symlinks (no FOLLOW_LINKS option), so a link cycle inside one tree cannot hang this.
        // What IS reachable is the same content arriving through two different roots — a symlinked parent, or one open
        // project nested inside another — which silently inflates both the total and the listing. Keying on toRealPath
        // collapses those aliases, and it also keeps the walk safe if link-following is ever enabled.
        Set<Path> visited = new HashSet<>();
        for (Path directory : directories) {
            Path root = directory.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                return "Not a directory: " + root;
            }
            // A candidate that survives pruning: count it, and keep it if the listing is not yet full.
            Consumer<Path> consider = candidate -> {
                // The starting directory is never itself a result: the caller named it, so returning it as a match
                // tells them nothing and, at depth 0 with type=dir, it would otherwise be the ONLY row.
                if (candidate.equals(root) || !type.matches(candidate) || !visited.add(realPathOf(candidate))) {
                    return;
                }
                try {
                    if (isAccessible.test(candidate)
                            && LineMatcher.findWithTimeout(compiled, candidate.getFileName().toString())) {
                        total[0]++;
                        if (matches.size() < cap) {
                            matches.add(candidate);
                        }
                    }
                }
                catch (LineMatcher.RegexTimeoutException e) {
                    failure.compareAndSet(null, "Regex timed out after " + e.timeoutMillis()
                            + " ms — the pattern backtracks catastrophically; simplify it.");
                }
            };
            try {
                // walkFileTree rather than Files.walk, because only a visitor can PRUNE. Files.walk has no such hook,
                // so a hidden subtree was fully traversed and merely filtered from the output — measured at 630ms with
                // ignoreHidden=true against 632ms with it false, i.e. the flag shaped the answer without saving any of
                // the work. SKIP_SUBTREE means .git and friends are never entered at all.
                //
                // Pruning also makes the hidden test O(1) per directory instead of O(depth) per candidate: once an
                // ancestor is skipped nothing beneath it is offered, so each entry only has to answer for itself.
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(root)) {
                            // A root the caller named explicitly is searched even when hidden — naming it is a request.
                            return FileVisitResult.CONTINUE;
                        }
                        if (ignoreHidden && isHiddenPath(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        consider.accept(dir);
                        return failure.get() == null ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!ignoreHidden || !isHiddenPath(file)) {
                            consider.accept(file);
                        }
                        return failure.get() == null ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // One unreadable entry must not abort the whole search: returning what IS readable beats
                        // failing the call outright. But skipping SILENTLY is the defect this project keeps meeting —
                        // an unreadable directory takes its whole subtree with it, and a caller reading "Found N" has
                        // no way to know the answer is incomplete. So the skip is counted and disclosed in the header,
                        // the same honesty as the existing "(showing first N)".
                        skipped[0]++;
                        // Diagnostic only — the caller already learns from the header that entries were skipped, so
                        // this is behind the debug flag rather than written on every ordinary search.
                        if (PluginSettings.isDebugJson()) {
                            LOG.log(Level.FINE, "Skipping unreadable entry: {0}", file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            catch (IOException e) {
                return "Error reading directory: " + directory + " — " + e.getMessage();
            }
        }
        if (failure.get() != null) {
            return failure.get();
        }
        if (matches.isEmpty()) {
            // Without a pattern the tool lists everything, so there is nothing to quote back — saying
            // "matching: null" reported the absence of a filter as though it were the filter that failed.
            String none = query.isBlank()
                    ? "No " + type.pluralNoun() + " found"
                    : "No " + type.pluralNoun() + " found matching: " + query;
            // "Found nothing" and "found nothing, but could not read part of the tree" are different answers and the
            // caller has to be able to tell them apart before concluding the thing does not exist.
            return skipped[0] > 0 ? none + skippedNote(skipped[0]) : none;
        }
        matches.sort(Comparator.comparing(Path::toString));
        StringBuilder result = new StringBuilder("Found ").append(total[0]).append(" ").append(type.countedNoun());
        if (total[0] > cap) {
            result.append(" (showing first ").append(cap).append(")");
        }
        if (skipped[0] > 0) {
            result.append(skippedNote(skipped[0]));
        }
        result.append(":\n\n");
        matches.forEach(path -> result.append(path).append("\n"));
        return result.toString();
    }

    /**
     * The trailing note that admits an answer is incomplete.
     * <p>
     * Separated out so the empty-result and the found-something paths cannot drift into wording it differently — a
     * caller parsing one and not the other is how a disclosure quietly stops being read.
     */
    private static String skippedNote(int skippedCount) {
        return " — " + skippedCount + " unreadable entr" + (skippedCount == 1 ? "y" : "ies")
                + " skipped, so this result may be incomplete";
    }

    /**
     * The symlink-resolved identity of a path, falling back to the normalised path when it cannot be resolved — a
     * broken link or a permission failure must not abort the whole walk.
     */
    private static Path realPathOf(Path path) {
        try {
            return path.toRealPath();
        }
        catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private FindFileProvider() {
    }
}
