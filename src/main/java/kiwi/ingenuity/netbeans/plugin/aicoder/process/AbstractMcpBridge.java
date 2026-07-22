package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

public abstract class AbstractMcpBridge {

    private volatile String sessionId;
    private volatile String secretKey;
    private volatile Set<McpInstructionOptionEnum> options = McpInstructionOptions.apiBackend();

    protected AbstractMcpBridge() {
    }

    public final void setSessionCredentials(String sessionId, String secretKey) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("secretKey must not be blank");
        }
        this.sessionId = sessionId;
        this.secretKey = secretKey;
    }

    public final void setOptions(Set<McpInstructionOptionEnum> options) {
        this.options = Set.copyOf(Objects.requireNonNull(options, "options"));
    }

    public final Set<McpInstructionOptionEnum> options() {
        return options;
    }

    public final JsonArray listToolsForModel(Collection<? extends McpToolInterface> handlers) {
        JsonArray tools = new JsonArray();
        if (handlers == null) {
            return tools;
        }
        for (McpToolInterface handler : handlers) {
            if (handler != null) {
                tools.add(handler.schema(options));
            }
        }
        return tools;
    }

    public final String invokeTool(String toolName, JsonObject argsFromModel) {
        if (sessionId == null || sessionId.isBlank() || secretKey == null || secretKey.isBlank()) {
            return "Error: session credentials are not set";
        }
        if (toolName == null || toolName.isBlank()) {
            return "Error: toolName must not be blank";
        }
        JsonObject argsWithAuth = deepCopy(argsFromModel);
        argsWithAuth.addProperty("sessionId", sessionId);
        argsWithAuth.addProperty("secretKey", secretKey);
        try {
            return executeTool(toolName, argsWithAuth);
        }
        catch (RuntimeException ex) {
            return "Error: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private static JsonObject deepCopy(JsonObject source) {
        if (source == null) {
            return new JsonObject();
        }
        return JsonParser.parseString(source.toString()).getAsJsonObject();
    }

    protected abstract String executeTool(String toolName, JsonObject argsWithAuth);
}
