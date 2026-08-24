package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Fix 13: the schema description used to present the global {@code PluginSettings} row limit as if it were the enforced
 * number, while {@code handle()} always enforces the session's effective (possibly overridden) limit. The description
 * must describe the session-effective limit rather than assert the global default as authoritative.
 */
class DatabaseRowLimitDescriptionTest {

    @Test
    void executeSqlQueryDescribesSessionEffectiveLimit() {
        JsonObject tool = new ExecuteSqlQueryTool().schema(Set.of());
        String description = tool.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString();
        assertTrue(description.contains("effective database row limit"), description);
    }

    @Test
    void getTableDataDescribesSessionEffectiveLimit() {
        JsonObject tool = new GetTableDataTool().schema(Set.of());
        JsonObject props = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key())
                .getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        String limitDescription = props.getAsJsonObject(DatabaseParamEnum.LIMIT.key())
                .get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString();
        assertTrue(limitDescription.contains("effective database row limit"), limitDescription);
    }
}
