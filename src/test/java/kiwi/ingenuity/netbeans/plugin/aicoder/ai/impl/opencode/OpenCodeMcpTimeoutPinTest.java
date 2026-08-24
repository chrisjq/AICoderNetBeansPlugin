package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeMcpTimeoutPinner.McpTimeoutPinOutcome;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeMcpTimeoutPinner.McpTimeoutPinResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks in the opt-in experimental.mcp_timeout pin for OpenCode: the surgical JSONC edit preserves every other byte
 * (comments included), an existing value is never overridden, unrecognised structure is refused rather than rewritten,
 * and the written value derives from the shared mutation-lock bound.
 */
class OpenCodeMcpTimeoutPinTest {

    private static final long TIMEOUT_MILLIS = TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS;
    private static final String TIMEOUT_MEMBER = "\"mcp_timeout\": " + TIMEOUT_MILLIS;

    private static JsonObject strictParse(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    void pinFlag_isOffByDefault() {
        assertFalse(OpenCodeMcpTimeoutPinner.PIN_MCP_TIMEOUT);
    }

    @Test
    void pinMcpTimeout_insertsIntoExperimentalObject_preservingEverythingElseByteForByte() {
        String in = "{\n"
                + "  // user preferences\n"
                + "  \"$schema\": \"https://opencode.ai/config.json\",\n"
                + "  \"model\": \"anthropic/claude-sonnet-4-5\", // chosen per session\n"
                + "  \"experimental\": {\n"
                + "    // experimental toggles live here\n"
                + "    \"handle_mcp_tools\": true,\n"
                + "  },\n"
                + "}\n";
        McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
        assertEquals(McpTimeoutPinOutcome.INSERTED_INTO_EXPERIMENTAL, result.outcome());
        assertEquals("{\n"
                + "  // user preferences\n"
                + "  \"$schema\": \"https://opencode.ai/config.json\",\n"
                + "  \"model\": \"anthropic/claude-sonnet-4-5\", // chosen per session\n"
                + "  \"experimental\": {\n"
                + "    // experimental toggles live here\n"
                + "    \"handle_mcp_tools\": true,\n"
                + "    " + TIMEOUT_MEMBER + "\n"
                + "  },\n"
                + "}\n", result.jsonc());
    }

    @Test
    void pinMcpTimeout_addsExperimentalBlock_whenMissing_preservingComments() {
        String in = "{\n"
                + "  // top comment\n"
                + "  \"$schema\": \"https://opencode.ai/config.json\",\n"
                + "}\n";
        McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
        assertEquals(McpTimeoutPinOutcome.ADDED_EXPERIMENTAL_BLOCK, result.outcome());
        assertEquals("{\n"
                + "  // top comment\n"
                + "  \"$schema\": \"https://opencode.ai/config.json\",\n"
                + "  \"experimental\": {" + TIMEOUT_MEMBER + "}\n"
                + "}\n", result.jsonc());
    }

    @Test
    void pinMcpTimeout_leavesExistingValueAlone_regardlessOfItsValue() {
        String in = "{\"experimental\": {\"mcp_timeout\": 999}}";
        McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
        assertEquals(McpTimeoutPinOutcome.UNCHANGED_ALREADY_SET, result.outcome());
        assertSame(in, result.jsonc());

        String commented = "{\"experimental\": { /* keep */ \"mcp_timeout\": 42 }}";
        McpTimeoutPinResult r2 = OpenCodeMcpTimeoutPinner.pinMcpTimeout(commented, TIMEOUT_MILLIS);
        assertEquals(McpTimeoutPinOutcome.UNCHANGED_ALREADY_SET, r2.outcome());
        assertSame(commented, r2.jsonc());
    }

    @Test
    void pinMcpTimeout_refuses_whenExperimentalIsNotAnObject() {
        for (String value : new String[]{"true", "\"x\"", "[]", "null"}) {
            String in = "{\"experimental\": " + value + "}";
            McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
            assertEquals(McpTimeoutPinOutcome.REFUSED_MALFORMED, result.outcome(), value);
            assertSame(in, result.jsonc(), value);
        }
    }

    @Test
    void pinMcpTimeout_refuses_whenRootIsNotAnObject_orBlank() {
        for (String in : new String[]{"[]", "", "   ", "\"just a string\"", null}) {
            McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
            assertEquals(McpTimeoutPinOutcome.REFUSED_MALFORMED, result.outcome(), String.valueOf(in));
            assertSame(in, result.jsonc(), String.valueOf(in));
        }
    }

    @Test
    void pinMcpTimeout_refuses_whenBracesAreUnbalanced() {
        for (String in : new String[]{"{\"experimental\": {\"a\": 1}", "{\"a\": 1", "{"}) {
            McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
            assertEquals(McpTimeoutPinOutcome.REFUSED_MALFORMED, result.outcome(), in);
            assertSame(in, result.jsonc(), in);
        }
    }

    @Test
    void pinMcpTimeout_handlesEmptyRootObject() {
        McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout("{}", TIMEOUT_MILLIS);
        assertEquals(McpTimeoutPinOutcome.ADDED_EXPERIMENTAL_BLOCK, result.outcome());
        assertEquals("{\"experimental\": {" + TIMEOUT_MEMBER + "}}", result.jsonc());
    }

    @Test
    void pinMcpTimeout_handlesMultilineEmptyExperimentalObject() {
        String in = "{\n  \"experimental\": {\n  },\n}\n";
        McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
        assertEquals(McpTimeoutPinOutcome.INSERTED_INTO_EXPERIMENTAL, result.outcome());
        assertEquals("{\n  \"experimental\": {\n  " + TIMEOUT_MEMBER + "\n  },\n}\n", result.jsonc());
    }

    @Test
    void pinMcpTimeout_slashesAndBracesInsideStrings_areNotStructural() {
        String in = "{\n"
                + "  \"url\": \"https://example.com//a?x={y}\", // real comment\n"
                + "  \"experimental\": {}\n"
                + "}\n";
        McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
        assertEquals(McpTimeoutPinOutcome.INSERTED_INTO_EXPERIMENTAL, result.outcome());
        assertEquals("{\n"
                + "  \"url\": \"https://example.com//a?x={y}\", // real comment\n"
                + "  \"experimental\": {" + TIMEOUT_MEMBER + "}\n"
                + "}\n", result.jsonc());
    }

    @Test
    void pinMcpTimeout_blockCommentsAroundMembers_surviveTheSplice() {
        String in = "{\n  \"experimental\": { /* c1 */ \"a\": 1 /* c2 */ }\n}\n";
        McpTimeoutPinResult result = OpenCodeMcpTimeoutPinner.pinMcpTimeout(in, TIMEOUT_MILLIS);
        assertEquals(McpTimeoutPinOutcome.INSERTED_INTO_EXPERIMENTAL, result.outcome());
        assertEquals("{\n  \"experimental\": { /* c1 */ \"a\": 1, "
                + TIMEOUT_MEMBER + " /* c2 */ }\n}\n", result.jsonc());
    }

    @Test
    void pinOutput_parsesAsStrictJson_whenInputWasPlainJson() throws IOException {
        JsonObject inserted = strictParse(OpenCodeMcpTimeoutPinner.pinMcpTimeout(
                "{\"experimental\": {\"a\": 1}}", TIMEOUT_MILLIS).jsonc());
        assertEquals(TIMEOUT_MILLIS,
                inserted.getAsJsonObject("experimental").get("mcp_timeout").getAsLong());

        JsonObject added = strictParse(OpenCodeMcpTimeoutPinner.pinMcpTimeout(
                "{\"name\": \"x\"}", TIMEOUT_MILLIS).jsonc());
        assertEquals(TIMEOUT_MILLIS,
                added.getAsJsonObject("experimental").get("mcp_timeout").getAsLong());
    }

    @Test
    void pinMcpTimeout_trailingCommaInInput_yieldsStrictValidOutput() throws IOException {
        String out = OpenCodeMcpTimeoutPinner.pinMcpTimeout(
                "{\"experimental\": {\"a\": 1,}}", TIMEOUT_MILLIS).jsonc();
        assertEquals("{\"experimental\": {\"a\": 1, " + TIMEOUT_MEMBER + "}}", out);
        strictParse(out);
    }

    @Test
    void applyMcpTimeoutToFile_pinsDerivedValue_andSecondRunIsByteIdentical(@TempDir Path dir) throws IOException {
        Path cfg = dir.resolve("opencode.jsonc");
        Files.writeString(cfg, "{\n  // my tuning\n  \"$schema\": \"https://opencode.ai/config.json\"\n}\n");

        OpenCodeMcpTimeoutPinner.applyMcpTimeoutToFile(cfg);
        byte[] afterFirst = Files.readAllBytes(cfg);
        String text = new String(afterFirst, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"mcp_timeout\": " + TIMEOUT_MILLIS));
        assertTrue(text.contains("// my tuning"));

        OpenCodeMcpTimeoutPinner.applyMcpTimeoutToFile(cfg);
        assertArrayEquals(afterFirst, Files.readAllBytes(cfg));
    }

    @Test
    void applyMcpTimeoutToFile_createsCleanJson_whenNoConfigExists(@TempDir Path dir) throws IOException {
        Path cfg = OpenCodeMcpTimeoutPinner.configFileIn(dir);
        assertEquals("opencode.json", cfg.getFileName().toString());

        OpenCodeMcpTimeoutPinner.applyMcpTimeoutToFile(cfg);
        JsonObject parsed = strictParse(Files.readString(cfg));
        assertEquals(TIMEOUT_MILLIS,
                parsed.getAsJsonObject("experimental").get("mcp_timeout").getAsLong());
    }

    @Test
    void configFileIn_prefersExistingJsonc_overJson(@TempDir Path dir) throws IOException {
        assertEquals("opencode.json", OpenCodeMcpTimeoutPinner.configFileIn(dir).getFileName().toString());

        Files.writeString(dir.resolve("opencode.json"), "{}");
        assertEquals("opencode.json", OpenCodeMcpTimeoutPinner.configFileIn(dir).getFileName().toString());

        Files.writeString(dir.resolve("opencode.jsonc"), "{}");
        assertEquals("opencode.jsonc", OpenCodeMcpTimeoutPinner.configFileIn(dir).getFileName().toString());
    }

}
