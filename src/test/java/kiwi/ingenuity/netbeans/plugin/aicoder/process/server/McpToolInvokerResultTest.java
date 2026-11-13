package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetPluginVersionTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * {@link McpToolInvoker#invokeResult} hands a tool's images through, keeps every refusal text-only, and leaves
 * {@link McpToolInvoker#invoke} as its text part.
 */
class McpToolInvokerResultTest {

    private static final McpToolResult.ImagePart IMAGE = new McpToolResult.ImagePart("image/png", "QUJD", 4, 3);

    /**
     * A tool that returns an image; {@code handle} gives only the text part.
     */
    private static class ImageTool implements McpToolInterface {

        private final boolean mutating;

        ImageTool(boolean mutating) {
            this.mutating = mutating;
        }

        @Override
        public McpSectionEnum section() {
            return new GetPluginVersionTool().section();
        }

        @Override
        public String instruction(Set<McpInstructionOptionEnum> options) {
            return null;
        }

        @Override
        public JsonObject schema(Set<McpInstructionOptionEnum> options) {
            return new JsonObject();
        }

        @Override
        public boolean isMutating() {
            return mutating;
        }

        @Override
        public String handle(ToolRequestArguments args, AbstractAiSession session) {
            return "info";
        }

        @Override
        public McpToolResult handleResult(ToolRequestArguments args, AbstractAiSession session) {
            return McpToolResult.withImage("info", IMAGE);
        }
    }

    @Test
    void anImageReachesTheCaller() throws McpArgumentException {
        McpToolResult result = McpToolInvoker.invokeResult(
                McpToolEnum.GET_PLUGIN_VERSION, new ImageTool(false), new JsonObject(), null);

        assertEquals("info", result.text());
        assertEquals(List.of(IMAGE), result.images());
    }

    @Test
    void invokeIsTheTextOfInvokeResult() throws McpArgumentException {
        ImageTool tool = new ImageTool(false);

        String text = McpToolInvoker.invoke(McpToolEnum.GET_PLUGIN_VERSION, tool, new JsonObject(), null);
        McpToolResult result = McpToolInvoker.invokeResult(
                McpToolEnum.GET_PLUGIN_VERSION, tool, new JsonObject(), null);

        assertEquals(result.text(), text);
    }

    @Test
    void aToolWithoutImagesStaysTextOnly() throws McpArgumentException {
        McpToolResult result = McpToolInvoker.invokeResult(
                McpToolEnum.GET_PLUGIN_VERSION, new GetPluginVersionTool(), new JsonObject(), null);

        assertFalse(result.hasImages());
        assertFalse(result.text().isEmpty());
    }

    @Test
    void aNullResultBecomesEmptyText() throws McpArgumentException {
        McpToolInterface handler = new ImageTool(false) {
            @Override
            public McpToolResult handleResult(ToolRequestArguments args, AbstractAiSession session) {
                return null;
            }
        };

        McpToolResult result = McpToolInvoker.invokeResult(
                McpToolEnum.GET_PLUGIN_VERSION, handler, new JsonObject(), null);

        assertEquals("", result.text());
        assertFalse(result.hasImages());
    }

    @Test
    void theEdtRefusalIsTextOnlyEvenForAToolWithImages() throws Exception {
        AtomicReference<McpToolResult> refusal = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                refusal.set(McpToolInvoker.invokeResult(
                        McpToolEnum.GET_PLUGIN_VERSION, new ImageTool(true), new JsonObject(), null));
            }
            catch (McpArgumentException | RuntimeException e) {
                failure.set(e);
            }
        });

        assertNull(failure.get());
        assertEquals("Error: mutation lock cannot wait on the EDT.", refusal.get().text());
        assertFalse(refusal.get().hasImages());
    }

    @Test
    void withFileReadReturnsTheActionResultOfAnyType() {
        String sessionId = "result-test-session";
        Collection<String> paths = List.of(
                System.getProperty("java.io.tmpdir") + "/mcp-tool-invoker-result-test.png");

        McpToolResult result = McpToolInvoker.withFileRead(sessionId, paths,
                                                           () -> McpToolResult.withImage("seen", IMAGE), McpToolResult::text);

        assertTrue(result.hasImages());
        assertEquals("seen", result.text());
    }

    @Test
    void theStringWithFileReadStillTurnsNullIntoEmptyText() {
        String sessionId = "result-test-session";
        Collection<String> paths = List.of(
                System.getProperty("java.io.tmpdir") + "/mcp-tool-invoker-result-test.txt");

        String result = McpToolInvoker.withFileRead(sessionId, paths, () -> null);

        assertEquals("", result);
    }
}
