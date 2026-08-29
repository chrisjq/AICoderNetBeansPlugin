package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import org.netbeans.modules.refactoring.java.api.ChangeParametersRefactoring.ParameterInfo;

@RequiresLock(LockTypeEnum.REFACTOR_LOCK)
public class ChangeMethodSignatureTool implements McpToolInterface {

    static ParameterInfo[] toParameterInfos(JsonArray paramsArr) throws McpArgumentException {
        if (paramsArr == null) {
            return null;
        }
        List<ParameterInfo> paramList = new ArrayList<>();
        for (int i = 0; i < paramsArr.size(); i++) {
            JsonElement paramEl = paramsArr.get(i);
            if (!paramEl.isJsonObject()) {
                // Refuse rather than skip. Dropping the entry produced a SHORTER parameter list than the caller asked
                // for and said nothing about it, so a malformed entry silently removed a parameter from the method and
                // every call site — reported as a success. Rejecting matches how the other malformed-entry cases below
                // are handled, and the caller can see exactly which entry was wrong.
                throw new McpArgumentException(-32602,
                        "parameters[" + i + "]: each entry must be an object, got: " + paramEl);
            }
            JsonObject p = paramEl.getAsJsonObject();
            String origIdxKey = ChangeMethodSignatureParamEnum.ORIGINAL_INDEX.key();
            String nameKey = ChangeMethodSignatureParamEnum.NAME.key();
            String typeKey = ChangeMethodSignatureParamEnum.TYPE.key();
            String defaultKey = ChangeMethodSignatureParamEnum.DEFAULT_VALUE.key();
            int origIdx = (p.has(origIdxKey) && p.get(origIdxKey).isJsonPrimitive())
                    ? p.get(origIdxKey).getAsInt() : i;
            String pName = p.has(nameKey) && p.get(nameKey).isJsonPrimitive()
                    ? p.get(nameKey).getAsString() : null;
            String pType = p.has(typeKey) && p.get(typeKey).isJsonPrimitive()
                    ? p.get(typeKey).getAsString() : null;
            String pDefault = p.has(defaultKey) && p.get(defaultKey).isJsonPrimitive()
                    ? p.get(defaultKey).getAsString() : null;
            if (origIdx == -1 && (pName == null || pType == null)) {
                throw new McpArgumentException(-32602,
                        "parameters[" + i + "]: new parameters (originalIndex=-1) require both name and type");
            }
            if (origIdx == -1 && pDefault == null) {
                throw new McpArgumentException(-32602,
                        "parameters[" + i + "]: new parameters (originalIndex=-1) require defaultValue — "
                        + "it is inserted at every existing call site");
            }
            // Carry whatever was supplied, even if only one of name/type is present. The four-arg constructor takes
            // nulls for the fields the caller omitted, and RefactoringProvider.mergeParameterInfos restores each null
            // from the existing signature — which is exactly the documented "omit a field to keep it" contract.
            // Requiring BOTH before building the full ParameterInfo discarded a name-only or type-only edit here, one
            // layer above the merger, so the merger saw two nulls and dutifully restored both old values: the rename
            // was accepted, reported as applied, and silently did nothing.
            paramList.add((pName != null || pType != null || pDefault != null)
                    ? new ParameterInfo(origIdx, pName, pType, pDefault)
                    : new ParameterInfo(origIdx));
        }
        return paramList.toArray(ParameterInfo[]::new);
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.REFACTORING;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return McpToolEnum.CHANGE_METHOD_SIGNATURE.toolName() + " - refactors method parameters and updates all callers";
        }
        return McpToolEnum.CHANGE_METHOD_SIGNATURE.toolName() + " -> INSTEAD OF manual editing - refactors method parameters and updates all callers";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.CHANGE_METHOD_SIGNATURE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Changes a method's parameter list, name, return type, or adds an overload; all existing call sites are updated. "
                + ChangeMethodSignatureParamEnum.PARAMETERS.key() + ": the complete desired list, or [] to remove every parameter; see per-parameter rules below.");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();

        JsonObject paramsArr = new JsonObject();
        paramsArr.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        paramsArr.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "The complete desired parameter list. Omit to keep existing params, or [] to remove every parameter. "
                + "Example: [{\"originalIndex\":0},{\"originalIndex\":1,\"name\":\"newName\"},{\"originalIndex\":-1,\"name\":\"extra\",\"type\":\"String\",\"defaultValue\":\"\\\"\\\"\"}]");
        JsonObject paramItem = new JsonObject();
        paramItem.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject paramProps = new JsonObject();
        JsonObject nameProp = new JsonObject();
        nameProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        nameProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Parameter name. Omit to keep the original name.");
        paramProps.add(ChangeMethodSignatureParamEnum.NAME.key(), nameProp);
        JsonObject typeProp = new JsonObject();
        typeProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        typeProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Parameter type (e.g. String, int, List<String>). Omit to keep the original type.");
        paramProps.add(ChangeMethodSignatureParamEnum.TYPE.key(), typeProp);
        JsonObject origIdx = new JsonObject();
        origIdx.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        origIdx.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "0-based index in the original method; -1 for a new parameter. Default: this param's position in the array.");
        paramProps.add(ChangeMethodSignatureParamEnum.ORIGINAL_INDEX.key(), origIdx);
        JsonObject defProp = new JsonObject();
        defProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        defProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Value inserted at existing call sites for new params (e.g. null, 0). "
                + "Required when " + ChangeMethodSignatureParamEnum.ORIGINAL_INDEX.key() + " is -1.");
        paramProps.add(ChangeMethodSignatureParamEnum.DEFAULT_VALUE.key(), defProp);
        paramItem.add(ToolSchemaKeyEnum.PROPERTIES.key(), paramProps);
        paramsArr.add(ToolSchemaKeyEnum.ITEMS.key(), paramItem);
        props.add(ChangeMethodSignatureParamEnum.PARAMETERS.key(), paramsArr);

        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file. Required — this tool does not fall back to the focused editor. Call " + McpToolEnum.GET_CURRENT_FILE.toolName() + " if you want the file the user is looking at.");
        props.add(ChangeMethodSignatureParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "1-based line of the method declaration. Required — this tool does not follow the user's cursor.");
        props.add(ChangeMethodSignatureParamEnum.LINE.key(), ln);
        JsonObject mName = new JsonObject();
        mName.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        mName.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "New method name. Omit to keep the existing name.");
        props.add(ChangeMethodSignatureParamEnum.METHOD_NAME.key(), mName);
        JsonObject retType = new JsonObject();
        retType.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        retType.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "New return type (e.g. void, String, int). Omit to keep the existing return type.");
        props.add(ChangeMethodSignatureParamEnum.RETURN_TYPE.key(), retType);
        JsonObject overload = new JsonObject();
        overload.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        overload.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "When true, creates a new overload instead of modifying the original method.");
        props.add(ChangeMethodSignatureParamEnum.OVERLOAD_METHOD.key(), overload);
        JsonObject cw = new JsonObject();
        cw.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        cw.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "When a refactoring reports only non-fatal warnings, apply it anyway and report the warnings alongside the result instead of refusing. Fatal problems always refuse regardless of this flag — these tools apply changes immediately with no diff panel to review them in, so a fatal problem (the engine's own signal that the result would be broken) is never applied unreviewed. Default: false.");
        props.add(ChangeMethodSignatureParamEnum.COMMIT_WITH_WARNING.key(), cw);
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(ChangeMethodSignatureParamEnum.FILE_PATH.key());
        required.add(ChangeMethodSignatureParamEnum.LINE.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        ParameterInfo[] paramInfos = toParameterInfos(
                args.array(ChangeMethodSignatureParamEnum.PARAMETERS.key()));
        Boolean overload = args.has(ChangeMethodSignatureParamEnum.OVERLOAD_METHOD.key()) ? args.bool(ChangeMethodSignatureParamEnum.OVERLOAD_METHOD.key()) : null;
        String fp = args.str(ChangeMethodSignatureParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (!McpHookServer.isFileAccessible(server, sessionId, fp)) {
                return McpHookServer.fileAccessDeniedMessage(server, sessionId, fp);
            }
        }
        return RefactoringProvider.changeMethodSignature(
                fp,
                args.intOr(ChangeMethodSignatureParamEnum.LINE.key(), 0),
                paramInfos,
                args.str(ChangeMethodSignatureParamEnum.METHOD_NAME.key()),
                args.str(ChangeMethodSignatureParamEnum.RETURN_TYPE.key()),
                overload,
                args.bool(ChangeMethodSignatureParamEnum.COMMIT_WITH_WARNING.key()));
    }
}
