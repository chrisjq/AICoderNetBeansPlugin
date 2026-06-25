package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure formatting helpers for search tool results. Kept free of NetBeans APIs
 * so the output shape is unit-testable.
 */
public final class SearchResultFormatter {

    /**
     * A single text match: absolute file path, 1-based line number, and the
     * (already stripped) line content.
     */
    public record Hit(String path, int line, String text) {

    }

    /**
     * Groups hits by file so each absolute path is printed once as a clickable
     * header, with its matches indented beneath as " &lt;line&gt;:
     * &lt;text&gt;". This avoids repeating the (often very long) absolute path
     * on every match line. Hits are expected to be pre-sorted by path then
     * line.
     *
     * @param hits the matches being shown (already capped to maxShown)
     * @param totalHits total matches found across all files (may exceed
     * hits.size())
     * @param maxShown the cap applied to hits; used only to phrase the
     * truncation notice
     */
    public static String groupByFile(List<Hit> hits, int totalHits, int maxShown) {
        Map<String, List<Hit>> byFile = new LinkedHashMap<>();
        for (Hit h : hits) {
            byFile.computeIfAbsent(h.path(), k -> new java.util.ArrayList<>()).add(h);
        }

        StringBuilder sb = new StringBuilder("Found ").append(totalHits)
                .append(" match(es) in ").append(byFile.size()).append(" file(s)");
        if (totalHits > maxShown) {
            sb.append(" (showing first ").append(maxShown).append(")");
        }
        sb.append(":\n\n");

        for (Map.Entry<String, List<Hit>> e : byFile.entrySet()) {
            sb.append(e.getKey()).append("\n");
            for (Hit h : e.getValue()) {
                sb.append("  ").append(h.line()).append(": ").append(h.text()).append("\n");
            }
        }
        return sb.toString().strip();
    }

    private SearchResultFormatter() {
    }
}
