package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void moveClassRequiresOnlyTargetPackageUnconditionally() {
        // filePath and filePaths are exactly-one-of, enforced in handle() rather than the schema's required array —
        // requiring filePath unconditionally would mis-describe a filePaths-only call as invalid. line is also
        // genuinely optional: omitting it moves the whole file.
        assertEquals(Set.of(
                MoveClassParamEnum.TARGET_PACKAGE.key()),
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

    /**
     * commitWithWarning is shared by all four tools via {@code RefactoringProvider.runRefactoring} and must be exposed
     * uniformly: a boolean, and never unconditionally required (its whole point is that it is safe to omit — the
     * default, false, is today's existing refuse-on-any-problem behaviour).
     */
    @Test
    void allFourRefactoringToolsExposeCommitWithWarningAsAnOptionalBoolean() {
        List<McpToolInterface> tools = List.of(
                new RenameSymbolTool(), new MoveClassTool(), new InlineVariableTool(), new ChangeMethodSignatureTool());
        String key = MoveClassParamEnum.COMMIT_WITH_WARNING.key();
        for (McpToolInterface tool : tools) {
            JsonObject schema = tool.schema(Set.of()).getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
            JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
            assertTrue(props.has(key), tool.getClass().getSimpleName() + " must expose " + key);
            assertEquals("boolean", props.getAsJsonObject(key).get(ToolSchemaKeyEnum.TYPE.key()).getAsString(),
                    tool.getClass().getSimpleName() + "'s " + key + " must be a boolean");
            assertFalse(requiredKeys(tool).contains(key),
                    tool.getClass().getSimpleName() + " must not require " + key + " unconditionally");
        }
    }
}
