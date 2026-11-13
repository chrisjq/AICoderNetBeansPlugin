package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class McpHookServerUtilMcpResultTest {

    private static JsonArray contentOf(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return root.getAsJsonObject("result").getAsJsonArray("content");
    }

    @Test
    void aTextOnlyResultIsOneTextItem() {
        String json = McpHookServerUtil.mcpResult(new JsonPrimitive(7), McpToolResult.text("hello"));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("2.0", root.get("jsonrpc").getAsString());
        assertEquals(7, root.get("id").getAsInt());
        JsonArray content = contentOf(json);
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("hello", content.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void theTextItemComesFirstThenOneImageItemPerImage() {
        McpToolResult result = new McpToolResult("info", List.of(
                                                 new McpToolResult.ImagePart("image/png", "QUJD", 4, 3),
                                                 new McpToolResult.ImagePart("image/jpeg", "REVG", 8, 6)));

        JsonArray content = contentOf(McpHookServerUtil.mcpResult(new JsonPrimitive(1), result));

        assertEquals(3, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        JsonObject first = content.get(1).getAsJsonObject();
        assertEquals("image", first.get("type").getAsString());
        assertEquals("QUJD", first.get("data").getAsString());
        assertEquals("image/png", first.get("mimeType").getAsString());
        JsonObject second = content.get(2).getAsJsonObject();
        assertEquals("image", second.get("type").getAsString());
        assertEquals("REVG", second.get("data").getAsString());
        assertEquals("image/jpeg", second.get("mimeType").getAsString());
    }

    @Test
    void anImageItemCarriesOnlyTypeDataAndMimeType() {
        McpToolResult result = McpToolResult.withImage("x", new McpToolResult.ImagePart("image/gif", "R0lG", 2, 2));

        JsonElement image = contentOf(McpHookServerUtil.mcpResult(new JsonPrimitive(1), result)).get(1);

        assertEquals(3, image.getAsJsonObject().size());
        assertFalse(image.getAsJsonObject().has("width"));
    }

    @Test
    void mcpTextResultIsTheSameAsMcpResultOfPlainText() {
        JsonElement id = new JsonPrimitive("abc");

        assertEquals(McpHookServerUtil.mcpResult(id, McpToolResult.text("same")),
                     McpHookServerUtil.mcpTextResult(id, "same"));
    }
}
