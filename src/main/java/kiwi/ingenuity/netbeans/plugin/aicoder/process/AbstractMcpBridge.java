package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.HiddenToolTextFilter;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;

public abstract class AbstractMcpBridge {

    private static JsonObject deepCopy(JsonObject source) {
        if (source == null) {
            return new JsonObject();
        }
        return JsonParser.parseString(source.toString()).getAsJsonObject();
    }

    private volatile String sessionId;
    private volatile String secretKey;
    private final AiTypeEnum aiType;

    protected AbstractMcpBridge(AiTypeEnum aiType) {
        this.aiType = aiType;
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

    public final Set<McpInstructionOptionEnum> options() {
        return aiType.getMcpOptions();
    }

    public final JsonArray listToolsForModel(Collection<? extends McpToolInterface> handlers) {
        return listToolsForModel(handlers, Set.of());
    }

    public final JsonArray listToolsForModel(Collection<? extends McpToolInterface> handlers,
                                             Set<McpToolEnum> hiddenTools) {
        JsonArray tools = new JsonArray();
        if (handlers == null) {
            return tools;
        }
        for (McpToolInterface handler : handlers) {
            if (handler != null) {
                JsonObject schema = handler.schema(aiType.getMcpOptions());
                tools.add(HiddenToolTextFilter.omitHiddenReferencesInDescriptions(schema, hiddenTools));
            }
        }
        return tools;
    }

    /**
     * Runs a tool and returns its text only. Images a tool returns are dropped; a caller that can show them uses
     * {@link #invokeToolResult}.
     */
    public final String invokeTool(String toolName, JsonObject argsFromModel) {
        return invokeToolResult(toolName, argsFromModel).text();
    }

    /**
     * Runs a tool with this session's credentials added, returning its text and any images. Every refusal and failure
     * is text only.
     */
    public final McpToolResult invokeToolResult(String toolName, JsonObject argsFromModel) {
        if (sessionId == null || sessionId.isBlank() || secretKey == null || secretKey.isBlank()) {
            return McpToolResult.text("Error: session credentials are not set");
        }
        if (toolName == null || toolName.isBlank()) {
            return McpToolResult.text("Error: toolName must not be blank");
        }
        JsonObject argsWithAuth = deepCopy(argsFromModel);
        argsWithAuth.addProperty(McpToolPropertyEnum.SESSION_ID.key(), sessionId);
        argsWithAuth.addProperty(McpToolPropertyEnum.SECRET_KEY.key(), secretKey);
        try {
            McpToolResult result = executeToolResult(toolName, argsWithAuth);
            return result == null ? McpToolResult.text(null) : result;
        }
        catch (RuntimeException ex) {
            return McpToolResult.text(
                    "Error: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    protected abstract String executeTool(String toolName, JsonObject argsWithAuth);

    /**
     * Runs a tool returning text and images. The default wraps {@link #executeTool}, so a bridge that never shows
     * images need not override it.
     */
    protected McpToolResult executeToolResult(String toolName, JsonObject argsWithAuth) {
        return McpToolResult.text(executeTool(toolName, argsWithAuth));
    }
}
