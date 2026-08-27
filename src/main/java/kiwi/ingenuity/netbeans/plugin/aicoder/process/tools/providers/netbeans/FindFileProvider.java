package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Finds files by their leaf name below one or more permitted directories. Matching deliberately shares
 * {@link LineMatcher}'s literal/regex, case, and timeout semantics with the text-search tools.
 */
public final class FindFileProvider {

    public static final int DEFAULT_MAX_MATCHES = 200;
    public static final int MAX_MATCHES = 5000;

    public static String findFiles(List<Path> directories, String pattern, boolean isRegex,
            boolean caseSensitive, int maxMatches) {
        return findFiles(directories, pattern, isRegex, caseSensitive, maxMatches, path -> true);
    }

    public static String findFiles(List<Path> directories, String pattern, boolean isRegex,
            boolean caseSensitive, int maxMatches, Predicate<Path> isAccessible) {
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
        List<Path> hits = new ArrayList<>();
        int total = 0;
        for (Path directory : directories) {
            Path root = directory.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                return "Not a directory: " + root;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path candidate : stream.filter(Files::isRegularFile).sorted().toList()) {
                    try {
                        if (isAccessible.test(candidate)
                                && LineMatcher.findWithTimeout(compiled, candidate.getFileName().toString())) {
                            total++;
                            if (hits.size() < cap) {
                                hits.add(root.resolve(root.relativize(candidate)));
                            }
                        }
                    }
                    catch (LineMatcher.RegexTimeoutException e) {
                        return "Regex timed out after " + e.timeoutMillis()
                                + " ms — the pattern backtracks catastrophically; simplify it.";
                    }
                }
            }
            catch (IOException e) {
                return "Error reading directory: " + directory + " — " + e.getMessage();
            }
        }
        if (hits.isEmpty()) {
            return "No files found matching: " + pattern;
        }
        hits.sort(Comparator.comparing(Path::toString));
        StringBuilder result = new StringBuilder("Found ").append(total).append(" file(s)");
        if (total > cap) {
            result.append(" (showing first ").append(cap).append(")");
        }
        result.append(":\n\n");
        hits.forEach(path -> result.append(path).append("\n"));
        return result.toString();
    }

    private FindFileProvider() {
    }
}
