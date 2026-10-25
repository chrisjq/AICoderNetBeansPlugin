package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Repair of streamed argument names. A key is matched to the longest accepted parameter name it starts with, so stray
 * text ({@code isRegex=false}, {@code isRegext}, {@code filePattern»}) is repaired. The helper tests pin the rule; the
 * invoke tests pin that the repaired object is what validation checks and what the handler receives.
 */
@Timeout(10)
class ToolArgumentNameRepairTest {

    private static final Set<String> ACCEPTED = Set.of(
            "query", "isRegex", "caseSensitive", "maxMatches", "filePattern", "sessionId", "secretKey");

    private static final JsonObject SCHEMA = schema();

    private static JsonObject schema() {
        JsonObject input = new JsonObject();
        input.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        input.add(ToolSchemaKeyEnum.PROPERTIES.key(), JsonParser.parseString(
                  "{\"query\":{\"type\":\"string\"},\"isRegex\":{\"type\":\"boolean\"},"
                  + "\"caseSensitive\":{\"type\":\"boolean\"},\"maxMatches\":{\"type\":\"integer\"},"
                  + "\"filePattern\":{\"type\":\"string\"}}"));
        JsonArray required = new JsonArray();
        required.add("query");
        input.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        JsonObject tool = new JsonObject();
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), input);
        return tool;
    }

    /**
     * A search-section handler that echoes its query, so a test can see what the tool received.
     */
    private static final class SearchStub implements McpToolInterface {

        @Override
        public McpSectionEnum section() {
            return McpSectionEnum.SEARCH;
        }

        @Override
        public String instruction(Set<McpInstructionOptionEnum> options) {
            return null;
        }

        @Override
        public JsonObject schema(Set<McpInstructionOptionEnum> options) {
            return SCHEMA;
        }

        @Override
        public String handle(ToolRequestArguments args, AbstractAiSession session) {
            return "ok:" + args.str("query");
        }
    }

    // ---- the repair rule -------------------------------------------------
    @Test
    void repairsTheStrayTrailingCharacterVariants() {
        JsonObject args = new JsonObject();
        args.addProperty("isRegext", false);
        args.addProperty("isRegexT", true);
        args.addProperty("caseSensitivet", true);
        args.addProperty("maxMatchest", 200);

        ToolArgumentNameRepair.repairInPlace(args, ACCEPTED);

        assertFalse(args.get("isRegex").getAsBoolean());
        assertTrue(args.get("caseSensitive").getAsBoolean());
        assertEquals(200, args.get("maxMatches").getAsInt());
        assertFalse(args.has("isRegext"));
        assertFalse(args.has("maxMatchest"));
    }

    @Test
    void repairsTheGluedValueVariants() {
        JsonObject args = new JsonObject();
        args.addProperty("isRegex=false", false);
        args.addProperty("caseSensitive=true", true);
        args.addProperty("maxMatches=200", 200);
        args.add("filePattern»", null);

        ToolArgumentNameRepair.repairInPlace(args, ACCEPTED);

        assertFalse(args.get("isRegex").getAsBoolean());
        assertTrue(args.get("caseSensitive").getAsBoolean());
        assertEquals(200, args.get("maxMatches").getAsInt());
        assertFalse(args.has("filePattern"), "a null value with no usable suffix is dropped");
        assertFalse(args.has("filePattern»"));
    }

    @Test
    void aBlankValueTakesTheTextAfterAFollowingSeparator() {
        JsonObject args = new JsonObject();
        args.addProperty("isRegex=true", "");
        args.addProperty("query: foo", "");
        args.add("maxMatches: 5", null);

        ToolArgumentNameRepair.repairInPlace(args, ACCEPTED);

        assertTrue(args.get("isRegex").getAsBoolean(), "\"true\" from the suffix becomes a boolean");
        assertEquals("foo", args.get("query").getAsString());
        assertEquals(5, args.get("maxMatches").getAsInt(), "a numeric suffix becomes a number");
    }

    @Test
    void theLongestAcceptedNameWins() {
        Set<String> accepted = Set.of("file", "filePath");
        JsonObject args = new JsonObject();
        args.addProperty("filePathx", "a");
        args.addProperty("filex", "b");

        ToolArgumentNameRepair.repairInPlace(args, accepted);

        assertEquals("a", args.get("filePath").getAsString(), "filePathx must map to filePath, never to file");
        assertEquals("b", args.get("file").getAsString());
    }

    @Test
    void aRealArgumentIsNeverOverwritten() {
        JsonObject args = new JsonObject();
        args.addProperty("isRegex", true);
        args.addProperty("isRegext", false);

        ToolArgumentNameRepair.repairInPlace(args, ACCEPTED);

        assertTrue(args.get("isRegex").getAsBoolean(), "the real value must win");
        assertTrue(args.has("isRegext"), "the bad key is left for validation to reject");
    }

    @Test
    void keysWithNoAcceptedPrefixAreLeftAlone() {
        JsonObject args = new JsonObject();
        args.addProperty("bogus=1", 1);
        args.addProperty("xisRegex", true);

        ToolArgumentNameRepair.repairInPlace(args, ACCEPTED);

        assertTrue(args.has("bogus=1"));
        assertTrue(args.has("xisRegex"));
        assertFalse(args.has("isRegex"));
    }

    // ---- through the real invoke path ----------------------------------
    @Test
    void invokeHandsTheRepairedArgumentToTheHandler() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("query=foo", "foo");

        String result = McpToolInvoker.invoke(McpToolEnum.FILTER_FILE_CONTENT, new SearchStub(), args, null);

        assertEquals("ok:foo", result, "the required query is satisfied by the repaired name");
    }

    @Test
    void invokeStillRejectsAKeyWhoseRealNameIsPresent() {
        JsonObject args = new JsonObject();
        args.addProperty("query", "foo");
        args.addProperty("query=bar", "bar");

        McpArgumentException error = assertThrows(McpArgumentException.class,
                                                  () -> McpToolInvoker.invoke(McpToolEnum.FILTER_FILE_CONTENT, new SearchStub(), args, null));

        assertTrue(error.getMessage().contains("query=bar"), error.getMessage());
    }

    @Test
    void invokeStillRejectsAGenuinelyUnknownParameter() {
        JsonObject args = new JsonObject();
        args.addProperty("query", "foo");
        args.addProperty("bogus=1", 1);

        McpArgumentException error = assertThrows(McpArgumentException.class,
                                                  () -> McpToolInvoker.invoke(McpToolEnum.FILTER_FILE_CONTENT, new SearchStub(), args, null));

        assertTrue(error.getMessage().contains("bogus=1"), error.getMessage());
    }
}
