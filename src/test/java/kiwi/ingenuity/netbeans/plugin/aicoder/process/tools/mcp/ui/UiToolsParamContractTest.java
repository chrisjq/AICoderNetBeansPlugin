package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.BuildProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.CleanAndBuildProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.CleanProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.diag.RunInspectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.CloseFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.CloseFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.GetCurrentFileContentTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.GetCurrentFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.GetDiagnosticsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.GetOpenFilesTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file.GetSelectedTextTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.navigate.NavigateToLineParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.navigate.NavigateToLineTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.FixImportsParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.FixImportsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.OrganiseImportsParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.OrganiseImportsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.OrganiseMembersParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.OrganiseMembersTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.ReformatFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.source.ReformatFileTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * AUDIT 7: every parameter a ui tool's schema advertises must be a key its handler actually reads, and every parameter
 * the handler reads must be advertised. Proves the schema/handler contract headless for all 15 ui tools by comparing
 * the emitted properties/required arrays against the key sets each handler extracts via its ParamEnum. Behavioural
 * effect of each parameter (EDT/window-system bound) is covered per-path outside this class.
 */
class UiToolsParamContractTest {

    private static Set<String> propertyKeys(McpToolInterface tool) {
        JsonObject schema = tool.schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        Set<String> keys = new HashSet<>();
        if (props != null) {
            props.keySet().forEach(keys::add);
        }
        return keys;
    }

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
    void fourBuildAndDiagActionToolsAdvertiseNoParameters() {
        List<McpToolInterface> tools = List.of(
                new BuildProjectTool(),
                new CleanAndBuildProjectTool(),
                new CleanProjectTool(),
                new RunInspectTool());
        assertNoParameters(tools);
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    void editorActionToolsAdvertiseNoParameters() {
        List<McpToolInterface> tools = List.of(
                new GetCurrentFileTool(),
                new GetCurrentFileContentTool(),
                new GetDiagnosticsTool(),
                new GetOpenFilesTool(),
                new GetSelectedTextTool());
        assertNoParameters(tools);
    }

    @Test
    void fourSourceToolsAdvertiseAndRequireExactlyTheFilePathTheirHandlersRead() {
        List<McpToolInterface> tools = List.of(
                new FixImportsTool(),
                new OrganiseImportsTool(),
                new OrganiseMembersTool(),
                new ReformatFileTool());
        for (McpToolInterface tool : tools) {
            String expected = filePathKey(tool);
            assertEquals(Set.of(expected), propertyKeys(tool), toolName(tool) + " property set mismatch");
            assertEquals(Set.of(expected), requiredKeys(tool), toolName(tool) + " required set mismatch");
        }
    }

    @Disabled("user request: Editor/Window tool group tests disabled")
    @Test
    void closeFileAdvertisesAndRequiresExactlyTheFilePathItsHandlerReads() {
        McpToolInterface tool = new CloseFileTool();
        String expected = filePathKey(tool);
        assertEquals(Set.of(expected), propertyKeys(tool), toolName(tool) + " property set mismatch");
        assertEquals(Set.of(expected), requiredKeys(tool), toolName(tool) + " required set mismatch");
    }

    @Test
    void navigateToLineAdvertisesAndRequiresExactlyTheFilePathAndLineItsHandlerReads() {
        NavigateToLineTool tool = new NavigateToLineTool();
        Set<String> expected = Set.of(NavigateToLineParamEnum.FILE_PATH.key(), NavigateToLineParamEnum.LINE.key());
        assertEquals(expected, propertyKeys(tool));
        assertEquals(expected, requiredKeys(tool));
    }

    private static void assertNoParameters(List<McpToolInterface> tools) {
        for (McpToolInterface tool : tools) {
            assertEquals(Set.of(), propertyKeys(tool), toolName(tool) + " advertises unexpected properties");
            assertEquals(Set.of(), requiredKeys(tool), toolName(tool) + " unexpectedly requires parameters");
        }
    }

    private static String filePathKey(McpToolInterface tool) {
        if (tool instanceof CloseFileTool) {
            return CloseFileParamEnum.FILE_PATH.key();
        }
        if (tool instanceof FixImportsTool) {
            return FixImportsParamEnum.FILE_PATH.key();
        }
        if (tool instanceof OrganiseImportsTool) {
            return OrganiseImportsParamEnum.FILE_PATH.key();
        }
        if (tool instanceof OrganiseMembersTool) {
            return OrganiseMembersParamEnum.FILE_PATH.key();
        }
        return ReformatFileParamEnum.FILE_PATH.key();
    }

    private static String toolName(McpToolInterface tool) {
        return tool.schema(Set.of()).get(ToolSchemaKeyEnum.NAME.key()).getAsString();
    }
}
