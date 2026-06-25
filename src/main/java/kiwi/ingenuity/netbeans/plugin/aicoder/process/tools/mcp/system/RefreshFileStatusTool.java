package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;

public class RefreshFileStatusTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.SYSTEM;
    }

    @Override
    public String instruction() {
        return "RefreshFileStatus -> call after every git commit, and after creating or modifying files outside the IDE, so NetBeans detects the changes immediately";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.REFRESH_NB_FILE_STATUS.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Refreshes NetBeans' view of the filesystem and VCS (git) status. "
                + "Call this after every git commit, and after creating or modifying files outside the IDE "
                + "(e.g. files written directly by AI Coder), so NetBeans detects new files and updates git decorations immediately. "
                + "Omit filePath to refresh all open projects; provide a file path to refresh only that file's project.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Any file in the project to refresh. Omit to refresh all open projects.");
        props.add(RefreshFileStatusParamEnum.FILE_PATH.key(), fp);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return GitProvider.refreshVcsStatus(args.str(RefreshFileStatusParamEnum.FILE_PATH.key()));
    }
}
