package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.BuildSubmitter;

/**
 * The {@code async} option shared by the three IDE build actions. They extend {@code AbstractActionTool}, which is the
 * base for two dozen unrelated tools (GetClipboard, SendAiMessage, ApplyEdit and the rest), so the option cannot live
 * there without offering {@code async} to tools that have no build to queue.
 */
final class IdeActionSchema {

    static JsonObject asyncProperty() {
        JsonObject async = new JsonObject();
        async.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        async.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                          "Queues the IDE action and returns its id immediately; its result arrives later as a message"
                          + " (up to " + BuildSubmitter.ASYNC_LIMIT_TEXT + "). Otherwise this call waits for its turn"
                          + " and returns the result. An IDE"
                          + " action can be stopped only while it is still queued. Default: false.");
        return async;
    }

    private IdeActionSchema() {
    }
}
