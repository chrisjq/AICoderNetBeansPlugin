package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class IsAiSessionActiveTool extends AbstractActionTool {

    public IsAiSessionActiveTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.IS_AI_SESSION_ACTIVE.toolName(),
                "Check whether a target AI session is open. active=false means idle (available to receive messages); active=true means busy processing a turn. All open sessions can receive SendAiMessage regardless of active state.",
                "IsAiSessionActive -> check before SendAiMessage if you need the session to respond promptly; active=false means idle (can still receive), active=true means busy");
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.IS_AI_SESSION_ACTIVE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Check whether a target AI session is open. active=false means idle (available to receive messages); active=true means busy processing a turn. All open sessions can receive SendAiMessage regardless of active state.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject tid = new JsonObject();
        tid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        tid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The sessionId of the AI session to check (from ListAiSessions or your session identity).");
        props.add(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key(), tid);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String targetSessionId = args.str(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key());
        if (targetSessionId == null || targetSessionId.isBlank()) {
            return "Error: targetSessionId is required";
        }
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        boolean open = broker.isActive(targetSessionId);
        if (!open) {
            return "Session " + targetSessionId + " is not open (session window closed or not registered).";
        }
        boolean processing = broker.isSessionRunning(targetSessionId);
        return processing
                ? "Session " + targetSessionId + " is open and currently processing a turn (busy — message will queue until turn completes)."
                : "Session " + targetSessionId + " is open and idle (ready to receive messages).";
    }
}
