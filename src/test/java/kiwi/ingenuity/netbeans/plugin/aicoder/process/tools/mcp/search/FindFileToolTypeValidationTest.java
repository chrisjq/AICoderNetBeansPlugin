package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import com.google.gson.JsonObject;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FindFileTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The refusal half of FindFile's {@code type} parameter, which lives in the tool layer rather than the provider.
 * <p>
 * {@code FindFileTypeEnum.from} returns null for a non-blank value it does not recognise, and the handler must turn
 * that into an explicit {@code -32602}. Defaulting instead would answer {@code type: "directory"} by searching FILES
 * and reporting a confident empty result, with nothing to tell the caller its argument was discarded — the
 * accepted-but-not-honoured shape this codebase keeps finding.
 * <p>
 * No MCP server is needed: with no {@code directoryPath} and no open projects the handler reaches the type check
 * without consulting one.
 */
class FindFileToolTypeValidationTest {

    private static ToolRequestArguments argsWithType(String type) {
        JsonObject o = new JsonObject();
        if (type != null) {
            o.addProperty(FindFileParamEnum.TYPE.key(), type);
        }
        return new ToolRequestArguments(o);
    }

    private final AbstractAiSession session = new AbstractAiSession(AiSession.create(null, AiTypeEnum.CLAUDE)) {
        @Override
        public String getId() {
            return "find-file-type-validation";
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    };

    @Test
    void unrecognisedTypeIsRefusedRatherThanSilentlyDefaultingToFile() {
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> new FindFileTool(null).handle(argsWithType("directory"), session));

        assertEquals(-32602, ex.getCode(), "a bad argument value is an invalid-params error");
        assertTrue(ex.getMessage().contains(FindFileParamEnum.TYPE.key()),
                "the refusal must name the offending parameter: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("directory"),
                "the refusal must quote back what was received: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(FindFileTypeEnum.FILE.type())
                && ex.getMessage().contains(FindFileTypeEnum.DIR.type()),
                "the refusal must list the values that ARE accepted: " + ex.getMessage());
    }

    /**
     * The negative control. Without this the assertion above would also pass if every type were refused, which would
     * break the parameter entirely. Omitted and valid values must get PAST validation — they then stop at "No projects
     * open", which is the correct next outcome with no project and no directoryPath.
     */
    @Test
    void omittedAndValidTypesAreAccepted() throws Exception {
        assertEquals("No projects open", new FindFileTool(null).handle(argsWithType(null), session),
                "omitting type must fall back to the default, not be refused");
        assertEquals("No projects open", new FindFileTool(null).handle(argsWithType("dir"), session));
        assertEquals("No projects open", new FindFileTool(null).handle(argsWithType(" File "), session),
                "case and surrounding space must be tolerated, matching FindFileTypeEnum.from");
    }
}
