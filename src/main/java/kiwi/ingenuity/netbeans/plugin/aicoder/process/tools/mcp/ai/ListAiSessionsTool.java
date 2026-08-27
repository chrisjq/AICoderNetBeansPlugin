package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.MailDeliveryTimingEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.MailDeliveryTimingEnum.AFTER_TURN;
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

    /**
     * Shared by the constructor and {@link #schema}, which both publish it. Kept as one constant because the two copies
     * previously drifted — a clause added to one was missed on the other.
     */
    private static final String TOOL_DESCRIPTION
            = "List all active AI sessions (excluding caller). Each entry includes active=true if"
            + " the session is busy processing a turn, active=false if idle. Both idle and busy"
            + " sessions can receive " + McpToolEnum.SEND_AI_MESSAGE.toolName() + "."
            + " Each entry also reports mailDelivery: when that peer will actually read your"
            + " message, and whether " + SendAiMessageParamEnum.IMPORTANT.key() + "=true changes"
            + " it. Where mailDelivery says the peer reads at end of turn, it cannot be reached"
            + " sooner and " + SendAiMessageParamEnum.IMPORTANT.key() + "=true is silently"
            + " ignored for it — either that backend has no mid-turn channel, or the peer's"
            + " session does not permit interruption. Read mailDelivery before setting "
            + SendAiMessageParamEnum.IMPORTANT.key() + ".";

    public ListAiSessionsTool() {
        super(McpSectionEnum.PLUGIN,
                McpToolEnum.LIST_AI_SESSIONS.toolName(),
                TOOL_DESCRIPTION,
                McpToolEnum.LIST_AI_SESSIONS.toolName() + " -> discover peer AI sessions; call before " + McpToolEnum.SEND_AI_MESSAGE.toolName() + " to find session IDs and to see when each peer will read your message");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.LIST_AI_SESSIONS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), TOOL_DESCRIPTION);
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
            AbstractAiSession abstractSession = SessionRegistry.get(s.id());
            AiTypeEnum aiType = s.aiType();
            if (aiType != null) {
                obj.addProperty(ToolResponseKeyEnum.AI_TYPE.key(), aiType.displayName());

                // Two things must both hold for a message to reach a peer sooner: the peer's
                // session must permit interruption, and its backend must have a mid-turn
                // channel. Either one missing and important=true is silently inert, so a sender
                // that could not see this set the flag blind and had no way to tell.
                //
                // Asked of the live session rather than the type, because for OpenCode it is not a property of the
                // type: mail goes over the HTTP API its agent runs beside ACP, and whether that route exists depends
                // on the build actually spawned. Falls back to the type's declared timing when the session is not
                // registered (not yet started, or already gone).
                MailDeliveryTimingEnum timing = abstractSession != null
                        ? abstractSession.getMailDeliveryTiming()
                        : aiType.mailDeliveryTiming();
                if (s.allowsImportantMessages() && timing != AFTER_TURN) {
                    obj.addProperty(ToolResponseKeyEnum.MAIL_DELIVERY.key(),
                            "Read " + timing.description() + " when "
                            + SendAiMessageParamEnum.IMPORTANT.key() + "=true, otherwise at "
                            + AFTER_TURN.description() + ".");
                }
                else {
                    obj.addProperty(ToolResponseKeyEnum.MAIL_DELIVERY.key(),
                            "Read at " + AFTER_TURN.description() + ".");
                }
            }
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
