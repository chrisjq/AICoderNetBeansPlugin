package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetPluginVersionTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolResultTest {

    private static final McpToolResult.ImagePart IMAGE = new McpToolResult.ImagePart("image/png", "QUJD", 4, 3);

    @Test
    void textResultHasNoImages() {
        McpToolResult result = McpToolResult.text("hello");

        assertEquals("hello", result.text());
        assertTrue(result.images().isEmpty());
        assertFalse(result.hasImages());
    }

    @Test
    void withImageCarriesTheTextAndTheImage() {
        McpToolResult result = McpToolResult.withImage("a picture", IMAGE);

        assertEquals("a picture", result.text());
        assertEquals(List.of(IMAGE), result.images());
        assertTrue(result.hasImages());
    }

    @Test
    void nullTextBecomesEmptyAndNullImagesBecomeNone() {
        McpToolResult result = new McpToolResult(null, null);

        assertEquals("", result.text());
        assertEquals(List.of(), result.images());
        assertEquals("", McpToolResult.text(null).text());
    }

    @Test
    void withImageAndNoImageIsTextOnly() {
        assertFalse(McpToolResult.withImage("x", null).hasImages());
    }

    @Test
    void imagesAreCopiedAndUnmodifiable() {
        List<McpToolResult.ImagePart> source = new ArrayList<>(List.of(IMAGE));
        McpToolResult result = new McpToolResult("x", source);

        source.clear();

        assertEquals(1, result.images().size());
        assertThrows(UnsupportedOperationException.class, () -> result.images().add(IMAGE));
    }

    @Test
    void theImagePartKeepsItsFields() {
        assertEquals("image/png", IMAGE.mimeType());
        assertEquals("QUJD", IMAGE.base64());
        assertEquals(4, IMAGE.width());
        assertEquals(3, IMAGE.height());
    }

    @Test
    void theDefaultHandleResultWrapsHandleText() throws Exception {
        McpToolInterface tool = new GetPluginVersionTool();

        McpToolResult result = tool.handleResult(new ToolRequestArguments(new JsonObject()), null);

        assertFalse(result.hasImages());
        assertEquals(tool.handle(new ToolRequestArguments(new JsonObject()), null), result.text());
    }
}
