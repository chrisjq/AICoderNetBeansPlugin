package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.file;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Fix 13: the description promised the "full text content" without disclosing that EditorContextProvider truncates at
 * 200,000 characters. The truncation itself was already disclosed in the output marker; this just makes the contract
 * (schema description) agree with it up front.
 */
class GetCurrentFileContentToolTest {

    @Test
    void descriptionDisclosesTruncationCap() {
        JsonObject tool = new GetCurrentFileContentTool().schema(Set.of());
        String description = tool.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString();
        assertTrue(description.contains("200,000"), description);
    }
}
