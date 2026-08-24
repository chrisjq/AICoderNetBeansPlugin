package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Fix 13: the four refactoring tools' schemas omitted or under-declared their {@code required} array despite their
 * descriptions calling filePath/line (and the tool's own primary argument) required.
 */
class RefactorToolRequiredSchemaTest {

    private static Set<String> requiredKeys(McpToolInterface tool) {
        JsonObject schema = tool.schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        Set<String> keys = new HashSet<>();
        if (required != null) {
            required.forEach(e -> keys.add(e.getAsString()));
        }
        return keys;
    }

    @Test
    void renameSymbolRequiresNewNameFilePathAndLine() {
        assertEquals(Set.of(
                RenameSymbolParamEnum.NEW_NAME.key(),
                RenameSymbolParamEnum.FILE_PATH.key(),
                RenameSymbolParamEnum.LINE.key()),
                requiredKeys(new RenameSymbolTool()));
    }

    @Test
    void moveClassRequiresTargetPackageAndFilePathButNotLine() {
        // line is genuinely optional: omitting it moves the whole file.
        assertEquals(Set.of(
                MoveClassParamEnum.TARGET_PACKAGE.key(),
                MoveClassParamEnum.FILE_PATH.key()),
                requiredKeys(new MoveClassTool()));
    }

    @Test
    void inlineVariableRequiresFilePathAndLine() {
        assertEquals(Set.of(
                InlineVariableParamEnum.FILE_PATH.key(),
                InlineVariableParamEnum.LINE.key()),
                requiredKeys(new InlineVariableTool()));
    }

    @Test
    void changeMethodSignatureRequiresFilePathAndLine() {
        assertEquals(Set.of(
                ChangeMethodSignatureParamEnum.FILE_PATH.key(),
                ChangeMethodSignatureParamEnum.LINE.key()),
                requiredKeys(new ChangeMethodSignatureTool()));
    }
}
