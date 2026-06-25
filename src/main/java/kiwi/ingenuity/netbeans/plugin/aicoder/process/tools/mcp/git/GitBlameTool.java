package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

public class GitBlameTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.GIT;
    }

    @Override
    public String instruction() {
        return "GitBlame -> INSTEAD OF Bash git blame - shows per-line authorship and commit for a file";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GIT_BLAME.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Shows per-line commit hash, author, and content for a file. "
                + "Equivalent to: git blame <file>");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject file = new JsonObject();
        file.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        file.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute or project-relative path to the file.");
        props.add(GitBlameParamEnum.FILE.key(), file);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        com.google.gson.JsonArray req = new com.google.gson.JsonArray();
        req.add(GitBlameParamEnum.FILE.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), req);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        return GitProvider.gitBlame(args.require(GitBlameParamEnum.FILE.key()));
    }
}
