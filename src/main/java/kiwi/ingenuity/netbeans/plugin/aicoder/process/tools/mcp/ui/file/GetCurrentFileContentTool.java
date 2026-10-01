package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.EditorContextProvider;

public class GetCurrentFileContentTool extends AbstractActionTool {

    public GetCurrentFileContentTool() {
        super(McpSectionEnum.UI_FILES,
                McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName(),
                "Returns the active editor's full content, prefixed with its absolute path; truncates after "
                + "200,000 characters with a marker. The default output must not be written back as file "
                + "content; pass " + GetCurrentFileContentParamEnum.RAW.key()
                + "=true for the exact file text (decoded with the file's encoding).",
                McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName() + " -> INSTEAD OF Read tool when you need the active editor's full text",
                "" + McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName() + " - get the active editor's full text");
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Returns the active editor's full content, prefixed "
                                                              + "with its absolute path; truncates after 200,000 characters with a marker. Pass "
                                                              + GetCurrentFileContentParamEnum.RAW.key()
                                                              + "=true for the exact file text (decoded with the file's encoding) with no header.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();
        JsonObject raw = new JsonObject();
        raw.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        raw.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "When true, return the exact file text (decoded "
                                                             + "with the file's encoding): no line-number header. Default false.");
        props.add(GetCurrentFileContentParamEnum.RAW.key(), raw);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) {
        return EditorContextProvider.getCurrentFileContent(args.bool(GetCurrentFileContentParamEnum.RAW.key()));
    }
}
