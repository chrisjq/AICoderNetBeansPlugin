package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class DiagnosticsProviderTest {

    @Test
    void formatDiagnosticsResult_reportsFailedPathsInsteadOfNoDiagnostics() {
        assertEquals("Could not analyse diagnostics for: /work/A.java, /work/B.java",
                DiagnosticsProvider.formatDiagnosticsResult("", List.of("/work/A.java", "/work/B.java")));
    }

    @Test
    void formatDiagnosticsResult_preservesDiagnosticsAndAppendsFailures() {
        assertEquals("[ERROR] /work/A.java:4 — bad type\nCould not analyse diagnostics for: /work/B.java",
                DiagnosticsProvider.formatDiagnosticsResult(
                        "[ERROR] /work/A.java:4 — bad type\n",
                        List.of("/work/B.java")));
    }

    @Test
    void formatDiagnosticsResult_reportsNoDiagnosticsOnlyWhenAllFilesWereAnalysedCleanly() {
        assertEquals("No diagnostics found", DiagnosticsProvider.formatDiagnosticsResult("", List.of()));
    }
}
