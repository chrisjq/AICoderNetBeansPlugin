package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.SearchResultFormatter;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.SearchResultFormatter.Hit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SearchResultFormatterTest {

    @Test
    void groupByFile_printsEachPathOnceWithIndentedMatches() {
        List<Hit> hits = List.of(
                new Hit("/proj/src/Foo.java", 12, "int a = 1;"),
                new Hit("/proj/src/Foo.java", 45, "return a;"),
                new Hit("/proj/src/Bar.java", 8, "void x() {}")
        );

        String out = SearchResultFormatter.groupByFile(hits, 3, 200);

        // Each file path appears exactly once (no per-line repetition).
        assertEquals(1, countOccurrences(out, "/proj/src/Foo.java"));
        assertEquals(1, countOccurrences(out, "/proj/src/Bar.java"));
        // Header reports total matches and distinct file count.
        assertTrue(out.startsWith("Found 3 match(es) in 2 file(s):"), out);
        // Matches are indented under their file with line numbers.
        assertTrue(out.contains("\n  12: int a = 1;"), out);
        assertTrue(out.contains("\n  45: return a;"), out);
        assertTrue(out.contains("\n  8: void x() {}"), out);
    }

    @Test
    void groupByFile_appendsTruncationNoticeWhenCapped() {
        List<Hit> hits = List.of(new Hit("/proj/A.java", 1, "x"));
        // 500 total found, only 1 shown because maxShown = 1
        String out = SearchResultFormatter.groupByFile(hits, 500, 1);
        assertTrue(out.startsWith("Found 500 match(es) in 1 file(s) (showing first 1):"), out);
    }

    @Test
    void groupByFile_noTruncationNoticeWhenAllShown() {
        List<Hit> hits = List.of(new Hit("/proj/A.java", 1, "x"));
        String out = SearchResultFormatter.groupByFile(hits, 1, 200);
        assertTrue(out.startsWith("Found 1 match(es) in 1 file(s):"), out);
        assertTrue(!out.contains("showing first"), out);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
