package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServerUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class InjectSessionParamsTest {

    private static JsonObject toolWithEmptySchema() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", "SomeTool");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        tool.add("inputSchema", schema);
        return tool;
    }

    @Test
    void addsSessionIdAndSecretKeyAsRequiredStringProps() {
        JsonObject out = McpHookServerUtil.injectSessionParams(toolWithEmptySchema());
        JsonObject props = out.getAsJsonObject("inputSchema").getAsJsonObject("properties");

        assertTrue(props.has("sessionId"), "sessionId property present");
        assertTrue(props.has("secretKey"), "secretKey property present");
        assertEquals("string", props.getAsJsonObject("sessionId").get("type").getAsString());
        assertEquals("string", props.getAsJsonObject("secretKey").get("type").getAsString());

        JsonArray required = out.getAsJsonObject("inputSchema").getAsJsonArray("required");
        assertTrue(contains(required, "sessionId"), "sessionId required");
        assertTrue(contains(required, "secretKey"), "secretKey required");
    }

    @Test
    void descriptionsPointToIdentityBlock() {
        // These descriptions must tell the model WHERE to get the values
        // (the session identity block). Weaker models (e.g. Copilot) won't
        // call the tools without this pointer — trimming it broke them.
        JsonObject out = McpHookServerUtil.injectSessionParams(toolWithEmptySchema());
        JsonObject props = out.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        String sid = props.getAsJsonObject("sessionId").get("description").getAsString();
        String key = props.getAsJsonObject("secretKey").get("description").getAsString();
        assertTrue(sid.contains("session identity"), "sessionId desc must point to identity block: " + sid);
        assertTrue(key.contains("session identity"), "secretKey desc must point to identity block: " + key);
    }

    private static boolean contains(JsonArray arr, String value) {
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonPrimitive() && value.equals(arr.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }
}
