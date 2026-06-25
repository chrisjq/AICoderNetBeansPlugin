package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class ListAiSessionsTool extends AbstractActionTool {

    public ListAiSessionsTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.LIST_AI_SESSIONS.toolName(),
                "List all active AI sessions (excluding caller). Each entry includes active=true if the session is busy processing a turn, active=false if idle. Both idle and busy sessions can receive SendAiMessage.",
                "ListAiSessions -> discover peer AI sessions; call before SendAiMessage to find session IDs");
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.LIST_AI_SESSIONS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "List all active AI sessions (excluding caller). Each entry includes active=true if the session is busy processing a turn, active=false if idle. Both idle and busy sessions can receive SendAiMessage.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject sid = new JsonObject();
        sid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        sid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID (from your session identity block). Used to exclude yourself from the results.");
        props.add(ListAiSessionsParamEnum.SESSION_ID.key(), sid);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(ListAiSessionsParamEnum.SESSION_ID.key());
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
        String callerId = args.str(ListAiSessionsParamEnum.SESSION_ID.key());
        if (callerId == null) {
            return "Error: sessionId is required (pass your own session ID from the session identity block)";
        }
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        if (broker.isActive(callerId) && !broker.isInterAiCommsAllowed(callerId)) {
            return "Error: inter-AI communication is disabled for this session";
        }
        List<AiSession> sessions = broker.listActive(callerId);
        if (sessions.isEmpty()) {
            return "No other active AI sessions.";
        }
        JsonArray arr = new JsonArray();
        for (AiSession s : sessions) {
            JsonObject obj = new JsonObject();
            obj.addProperty("sessionId", s.id());
            obj.addProperty(ToolSchemaKeyEnum.NAME.key(), s.name());
            if (s.description() != null && !s.description().isBlank()) {
                obj.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), s.description());
            }
            kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum aiType = s.aiType();
            if (aiType != null) {
                obj.addProperty("aiType", aiType.displayName());
            }
            AbstractAiSession abstractSession = SessionRegistry.get(s.id());
            if (abstractSession != null) {
                for (Map.Entry<String, String> e : abstractSession.getInfo().entrySet()) {
                    obj.addProperty(e.getKey(), e.getValue());
                }
            }
            obj.addProperty("active", s.isRunning());
            arr.add(obj);
        }
        return arr.toString();
    }
}
