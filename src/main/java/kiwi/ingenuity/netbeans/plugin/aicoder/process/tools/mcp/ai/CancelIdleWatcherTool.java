package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcherRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public class CancelIdleWatcherTool extends AbstractActionTool {

    public CancelIdleWatcherTool() {
        super(McpSectionEnum.PLUGIN,
              McpToolEnum.CANCEL_IDLE_WATCHER.toolName(),
              "Cancel one of this session's idle watcher timers.",
              McpToolEnum.CANCEL_IDLE_WATCHER.toolName()
              + " -> cancels the named idle watcher owned by this session");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.CANCEL_IDLE_WATCHER.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Cancel one of this session's idle watcher timers.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject wid = new JsonObject();
        wid.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        wid.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                        "Idle watcher ID from " + McpToolEnum.LIST_IDLE_WATCHERS.toolName()
                        + " or the reply to " + McpToolEnum.CREATE_IDLE_WATCHER.toolName() + ".");
        props.add(CancelIdleWatcherParamEnum.WATCHER_ID.key(), wid);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(CancelIdleWatcherParamEnum.WATCHER_ID.key());
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
        if (session == null || !session.getAiSession().allowsIdleWatcherTimer()) {
            return IdleWatcherTools.DISABLED_MESSAGE;
        }
        String typeError = args.requireStringIfPresent(CancelIdleWatcherParamEnum.WATCHER_ID.key());
        if (typeError != null) {
            return "Error: " + typeError;
        }
        String watcherId = args.str(CancelIdleWatcherParamEnum.WATCHER_ID.key());
        if (watcherId == null || watcherId.isBlank()) {
            return "Error: " + CancelIdleWatcherParamEnum.WATCHER_ID.key() + " is required";
        }
        boolean cancelled = IdleWatcherRegistry.getInstance().cancel(session.getId(), watcherId);
        if (!cancelled) {
            return "No idle watcher " + watcherId
                    + " belongs to you (it may have already fired as a oneshot, been cancelled, or its target session closed).";
        }
        return "Idle watcher " + watcherId + " cancelled.";
    }
}
