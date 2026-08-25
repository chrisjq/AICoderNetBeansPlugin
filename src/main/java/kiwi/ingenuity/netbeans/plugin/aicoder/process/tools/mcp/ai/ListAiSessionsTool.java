package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.MailDeliveryTimingEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolResponseKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class ListAiSessionsTool extends AbstractActionTool {

    public ListAiSessionsTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.LIST_AI_SESSIONS.toolName(),
                "List all active AI sessions (excluding caller). Each entry includes active=true if the session is busy processing a turn, active=false if idle. Both idle and busy sessions can receive " + McpToolEnum.SEND_AI_MESSAGE.toolName() + ". Each entry also reports how mail reaches that peer: interruptible=true means setting important=true on a message to it makes it read the message sooner, and mailDelivery says when. interruptible=false means that backend has no mid-turn channel, so important=true does NOTHING for it — the message waits for the turn to end regardless. Check this before setting important; the flag is silently ignored by peers that cannot be interrupted.",
                McpToolEnum.LIST_AI_SESSIONS.toolName() + " -> discover peer AI sessions; call before " + McpToolEnum.SEND_AI_MESSAGE.toolName() + " to find session IDs and to see whether a peer can be interrupted with important=true");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.LIST_AI_SESSIONS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "List all active AI sessions (excluding caller). Each entry includes active=true if the session is busy processing a turn, active=false if idle. Both idle and busy sessions can receive " + McpToolEnum.SEND_AI_MESSAGE.toolName() + ". Each entry also reports how mail reaches that peer: interruptible=true means setting important=true on a message to it makes it read the message sooner, and mailDelivery says when. interruptible=false means that backend has no mid-turn channel, so important=true does NOTHING for it — the message waits for the turn to end regardless. Check this before setting important; the flag is silently ignored by peers that cannot be interrupted. ");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        // Caller credentials are declared here rather than by
        // applyCredentialsIfRequested so they can carry richer descriptions.
        // Callers without CREDENTIALS reach this tool through a bridge that
        // injects the value server-side, so they must not be asked for it.
        if (options.contains(McpInstructionOptionEnum.CREDENTIALS)) {
            JsonObject sid = new JsonObject();
            sid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your own session ID (from your session identity block). Used to exclude yourself from the results.");
            props.add(ListAiSessionsParamEnum.SESSION_ID.key(), sid);
            required.add(ListAiSessionsParamEnum.SESSION_ID.key());
        }
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        String callerId = args.str(ListAiSessionsParamEnum.SESSION_ID.key());
        if (callerId == null) {
            return "Error: " + ListAiSessionsParamEnum.SESSION_ID.key() + " is required (pass your own session ID from the session identity block)";
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
            // Response fields, so these come from ToolResponseKeyEnum rather
            // than the param enum — what a tool returns is a separate contract
            // from what it accepts, even where the spellings coincide.
            obj.addProperty(ToolResponseKeyEnum.SESSION_ID.key(), s.id());
            obj.addProperty(ToolSchemaKeyEnum.NAME.key(), s.name());
            if (s.description() != null && !s.description().isBlank()) {
                obj.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), s.description());
            }
            AiTypeEnum aiType = s.aiType();
            if (aiType != null) {
                obj.addProperty(ToolResponseKeyEnum.AI_TYPE.key(), aiType.displayName());
                // Backends differ in whether they can be reached mid-turn at all. Without this a
                // sender sets important=true blind and cannot tell whether it did anything —
                // for AFTER_TURN backends the flag is silently inert.
                MailDeliveryTimingEnum timing = aiType.mailDeliveryTiming();
                obj.addProperty(ToolResponseKeyEnum.INTERRUPTIBLE.key(), timing.isInterruptible());

                if (timing.isInterruptible()) {
                    switch (aiType.mailDeliveryTiming()) {
                        case DURING_TURN:
                            obj.addProperty(ToolResponseKeyEnum.MAIL_DELIVERY.key(), MailDeliveryTimingEnum.DURING_TURN.description());
                            break;
                        case ABORTS_TURN:
                            obj.addProperty(ToolResponseKeyEnum.MAIL_DELIVERY.key(), MailDeliveryTimingEnum.ABORTS_TURN.description());
                            break;
                        case AFTER_TURN:
                            obj.addProperty(ToolResponseKeyEnum.MAIL_DELIVERY.key(), MailDeliveryTimingEnum.AFTER_TURN.description());
                            break;
                    }
                }
                else {
                    obj.addProperty(ToolResponseKeyEnum.MAIL_DELIVERY.key(), MailDeliveryTimingEnum.AFTER_TURN.description());
                }
            }
            AbstractAiSession abstractSession = SessionRegistry.get(s.id());
            if (abstractSession != null) {
                for (Map.Entry<String, String> e : abstractSession.getInfo().entrySet()) {
                    obj.addProperty(e.getKey(), e.getValue());
                }
            }
            obj.addProperty(ToolResponseKeyEnum.ACTIVE.key(), s.isRunning());
            arr.add(obj);
        }
        return arr.toString();
    }
}
