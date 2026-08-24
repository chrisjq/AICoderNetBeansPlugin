package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetTypeHierarchyTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.ReformatFileTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Fix 13: {@code AbstractFileTool} and {@code AbstractClassNameTool} describe their sole field as "Required" in prose
 * but never emitted a {@code required} array, so every subclass that does not override schema() was affected. Exercised
 * through one concrete subclass of each base class.
 */
class AbstractFileAndClassNameToolRequiredSchemaTest {

    @Test
    void abstractFileToolSubclassRequiresFilePath() {
        JsonObject schema = new ReformatFileTool().schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(1, required.size());
        assertEquals(McpToolPropertyEnum.FILE_PATH.key(), required.get(0).getAsString());
    }

    @Test
    void abstractClassNameToolSubclassRequiresClassName() {
        JsonObject schema = new GetTypeHierarchyTool().schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(1, required.size());
        assertEquals(McpToolPropertyEnum.CLASS_NAME.key(), required.get(0).getAsString());
    }
}
