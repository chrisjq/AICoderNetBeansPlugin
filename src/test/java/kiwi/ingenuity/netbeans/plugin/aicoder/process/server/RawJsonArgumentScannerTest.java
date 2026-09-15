package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class RawJsonArgumentScannerTest {

    @Test
    void gsonSilentlyKeepsTheLastDuplicateValue() {
        JsonObject parsed = JsonParser.parseString("{\"pattern\":\"first\",\"pattern\":\"last\"}")
                .getAsJsonObject();

        assertEquals("last", parsed.get("pattern").getAsString());
    }

    @Test
    void reportsDuplicatesRegardlessOfTheirValues() {
        assertEquals(Map.of("pattern", 2),
                     RawJsonArgumentScanner.duplicateTopLevelKeys("{\"pattern\":\"a\",\"pattern\":\"b\"}"));
        assertEquals(Map.of("maxDepth", 2),
                     RawJsonArgumentScanner.duplicateTopLevelKeys("{\"maxDepth\":1,\"maxDepth\":1}"));
    }

    @Test
    void ignoresNestedRepeatedKeys() {
        assertTrue(RawJsonArgumentScanner.duplicateTopLevelKeys(
                "{\"parameters\":{\"name\":\"one\",\"name\":\"two\"}}").isEmpty());
    }

    @Test
    void scansOnlyArgumentsAtTheRequestedEnvelopePath() {
        assertEquals(Map.of("pattern", 2), RawJsonArgumentScanner.duplicateKeys(
                     "{\"params\":{\"arguments\":{\"pattern\":\"one\",\"pattern\":\"two\"}}}",
                     "params", "arguments"));
    }

    @Test
    void reportsDuplicateCredentials() {
        assertEquals(Map.of("sessionId", 2, "secretKey", 2),
                     RawJsonArgumentScanner.duplicateTopLevelKeys(
                             "{\"sessionId\":\"one\",\"sessionId\":\"two\","
                             + "\"secretKey\":\"one\",\"secretKey\":\"two\"}"));
    }

    @Test
    void failsOpenForArrayWhereObjectExpectedAndAbsentPath() {
        assertTrue(RawJsonArgumentScanner.duplicateKeys(
                "{\"params\":{\"arguments\":[]}}", "params", "arguments").isEmpty());
        assertTrue(RawJsonArgumentScanner.duplicateKeys(
                "{\"params\":{\"other\":{}}}", "params", "arguments").isEmpty());
    }

    @Test
    void scansLargePayloadWithoutLosingDuplicateCount() {
        StringBuilder json = new StringBuilder("{\"params\":{\"arguments\":{\"prefix\":\"x\"");
        for (int i = 0; i < 2000; i++) {
            json.append(",\"field").append(i).append("\":").append(i);
        }
        json.append(",\"pattern\":\"one\",\"pattern\":\"two\"}}}");
        assertEquals(Map.of("pattern", 2),
                     RawJsonArgumentScanner.duplicateKeys(json.toString(), "params", "arguments"));
    }
}
