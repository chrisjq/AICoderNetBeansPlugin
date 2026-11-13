package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code invokeToolResult} keeps a tool's images; {@code invokeTool} returns the same text without them; a bridge that
 * only implements {@code executeTool} still works.
 */
class AbstractMcpBridgeResultTest {

    private static final McpToolResult PICTURE = McpToolResult.withImage(
            "a.png, PNG, 2×2", new McpToolResult.ImagePart("image/png", "QUJD", 2, 2));

    private static final class ImageBridge extends AbstractMcpBridge {

        ImageBridge() {
            super(AiTypeEnum.OPENAI_COMPATIBLE);
            setSessionCredentials("s", "k");
        }

        @Override
        protected String executeTool(String toolName, JsonObject argsWithAuth) {
            return executeToolResult(toolName, argsWithAuth).text();
        }

        @Override
        protected McpToolResult executeToolResult(String toolName, JsonObject argsWithAuth) {
            return PICTURE;
        }
    }

    private static final class TextBridge extends AbstractMcpBridge {

        TextBridge() {
            super(AiTypeEnum.OPENAI_COMPATIBLE);
            setSessionCredentials("s", "k");
        }

        @Override
        protected String executeTool(String toolName, JsonObject argsWithAuth) {
            return "text of " + toolName;
        }
    }

    @Test
    @Timeout(5)
    void theResultKeepsItsImagesAndTheTextCallDropsThem() {
        ImageBridge bridge = new ImageBridge();

        McpToolResult result = bridge.invokeToolResult("Read", new JsonObject());

        assertTrue(result.hasImages());
        assertEquals("QUJD", result.images().get(0).base64());
        assertEquals(result.text(), bridge.invokeTool("Read", new JsonObject()));
    }

    @Test
    @Timeout(5)
    void aTextOnlyBridgeAnswersTheResultCallWithText() {
        McpToolResult result = new TextBridge().invokeToolResult("Read", new JsonObject());

        assertEquals("text of Read", result.text());
        assertFalse(result.hasImages());
    }

    @Test
    @Timeout(5)
    void refusalsAreTextOnly() {
        McpToolResult result = new ImageBridge().invokeToolResult(" ", new JsonObject());

        assertEquals("Error: toolName must not be blank", result.text());
        assertFalse(result.hasImages());
    }
}
