package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.RequiresLock;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import org.netbeans.modules.refactoring.java.api.ChangeParametersRefactoring.ParameterInfo;

@RequiresLock(LockTypeEnum.REFACTOR_LOCK)
public class ChangeMethodSignatureTool implements McpToolInterface {

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.REFACTORING;
    }

    @Override
    public String instruction() {
        return "ChangeMethodSignature -> INSTEAD OF manual editing - refactors method parameters and updates all callers";
    }

    @Override
    public JsonObject schema() {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.CHANGE_METHOD_SIGNATURE.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Changes a method's parameter list, name, return type, or creates an overload. "
                + "All existing call sites are updated. "
                + "parameters: the complete desired parameter list - omit to keep existing params. "
                + "For each parameter: set originalIndex to its index in the original method "
                + "(0-based) to preserve it, or -1 for a new parameter; "
                + "omit name/type to preserve the original name and type unchanged. "
                + "New parameters require a defaultValue inserted at existing call sites. "
                + "methodName: rename the method. returnType: change the return type. "
                + "overloadMethod: when true, adds a new overload instead of modifying the original. "
                + "parameters example: [{\"originalIndex\":0},{\"originalIndex\":1,\"name\":\"newName\"},{\"originalIndex\":-1,\"name\":\"extra\",\"type\":\"String\",\"defaultValue\":\"\\\"\\\"\"}]");
        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();

        JsonObject paramsArr = new JsonObject();
        paramsArr.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject paramItem = new JsonObject();
        paramItem.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject paramProps = new JsonObject();
        JsonObject nameProp = new JsonObject();
        nameProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        nameProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Parameter name. Omit to keep the original name.");
        paramProps.add("name", nameProp);
        JsonObject typeProp = new JsonObject();
        typeProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        typeProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Parameter type (e.g. String, int, List<String>). Omit to keep the original type.");
        paramProps.add("type", typeProp);
        JsonObject origIdx = new JsonObject();
        origIdx.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        origIdx.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "0-based index of this param in the original method. Use -1 for new params. "
                + "Omit to use the param's position in this array.");
        paramProps.add("originalIndex", origIdx);
        JsonObject defProp = new JsonObject();
        defProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        defProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Value inserted at existing call sites for new params (e.g. null, 0). "
                + "Required when originalIndex is -1.");
        paramProps.add("defaultValue", defProp);
        paramItem.add(ToolSchemaKeyEnum.PROPERTIES.key(), paramProps);
        paramsArr.add(ToolSchemaKeyEnum.ITEMS.key(), paramItem);
        props.add(ChangeMethodSignatureParamEnum.PARAMETERS.key(), paramsArr);

        JsonObject fp = new JsonObject();
        fp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        fp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Absolute path to the file. Omit to use current editor.");
        props.add(ChangeMethodSignatureParamEnum.FILE_PATH.key(), fp);
        JsonObject ln = new JsonObject();
        ln.addProperty(ToolSchemaKeyEnum.TYPE.key(), "integer");
        ln.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "1-based line of the method declaration. Omit to use cursor.");
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
        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return tool;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        ParameterInfo[] paramInfos = null;
        JsonArray paramsArr = args.array(ChangeMethodSignatureParamEnum.PARAMETERS.key());
        if (paramsArr != null) {
            List<ParameterInfo> paramList = new ArrayList<>();
            for (int i = 0; i < paramsArr.size(); i++) {
                JsonElement paramEl = paramsArr.get(i);
                if (!paramEl.isJsonObject()) {
                    continue;
                }
                JsonObject p = paramEl.getAsJsonObject();
                int origIdx = (p.has("originalIndex") && p.get("originalIndex").isJsonPrimitive())
                        ? p.get("originalIndex").getAsInt() : i;
                String pName = p.has("name") && p.get("name").isJsonPrimitive() ? p.get("name").getAsString() : null;
                String pType = p.has("type") && p.get("type").isJsonPrimitive() ? p.get("type").getAsString() : null;
                String pDefault = p.has("defaultValue") && p.get("defaultValue").isJsonPrimitive() ? p.get("defaultValue").getAsString() : null;
                if (origIdx == -1 && (pName == null || pType == null)) {
                    throw new McpArgumentException(-32602,
                            "parameters[" + i + "]: new parameters (originalIndex=-1) require both name and type");
                }
                paramList.add((pName != null && pType != null)
                        ? new ParameterInfo(origIdx, pName, pType, pDefault != null ? pDefault : "")
                        : new ParameterInfo(origIdx));
            }
            paramInfos = paramList.toArray(ParameterInfo[]::new);
        }
        Boolean overload = args.has(ChangeMethodSignatureParamEnum.OVERLOAD_METHOD.key()) ? args.bool(ChangeMethodSignatureParamEnum.OVERLOAD_METHOD.key()) : null;
        String fp = args.str(ChangeMethodSignatureParamEnum.FILE_PATH.key());
        if (fp != null) {
            McpHookServer server = McpServerRegistry.getServer();
            String sessionId = session.getId();
            if (server == null || sessionId == null || !server.isFileAllowed(sessionId, fp)) {
                return "Access denied: " + fp + " is outside the allowed project scope for this session.";
            }
        }
        return RefactoringProvider.changeMethodSignature(
                fp,
                args.intOr(ChangeMethodSignatureParamEnum.LINE.key(), 0),
                paramInfos,
                args.str(ChangeMethodSignatureParamEnum.METHOD_NAME.key()),
                args.str(ChangeMethodSignatureParamEnum.RETURN_TYPE.key()),
                overload);
    }
}
