package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeToolHandlerFactory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Keeps AI-facing tool and parameter descriptions short: this is text the calling AI pays context budget to
 * read on every turn, not documentation. A ceiling here is what stops verbose wording from creeping back in.
 * <p>
 * {@link #TOOL_DESCRIPTION_CEILING} is relaxed for {@link #LONG_TOOL_DESCRIPTIONS}, tools whose length is
 * itself test-enforced elsewhere ({@code WebRequestToolTest}) for reasons specific to that tool (disclosing
 * every blocked address class and the full-response-file mechanism) rather than ordinary verbosity.
 */
class ToolDescriptionLengthTest {

    private static final int TOOL_DESCRIPTION_CEILING = 300;
    private static final int PARAM_DESCRIPTION_CEILING = 250;

    private static final Set<String> LONG_TOOL_DESCRIPTIONS = Set.of(
            McpToolEnum.WEB_REQUEST.toolName());

    private static Map<McpToolEnum, McpToolInterface> handlers() {
        return ClaudeToolHandlerFactory.build(() -> null, null);
    }

    @Test
    void everyToolDescriptionStaysUnderTheCeiling() {
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers().entrySet()) {
            if (LONG_TOOL_DESCRIPTIONS.contains(entry.getKey().toolName())) {
                continue;
            }
            JsonObject schema = entry.getValue().schema(Set.of());
            String description = schema.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString();
            assertTrue(description.length() <= TOOL_DESCRIPTION_CEILING,
                    entry.getKey() + " description is " + description.length() + " chars (ceiling "
                    + TOOL_DESCRIPTION_CEILING + "): " + description);
        }
    }

    @Test
    void everyParamDescriptionStaysUnderTheCeiling() {
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers().entrySet()) {
            JsonObject input = entry.getValue().schema(Set.of()).getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
            JsonObject properties = input == null ? null : input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
            if (properties == null) {
                continue;
            }
            for (String key : properties.keySet()) {
                JsonObject property = properties.getAsJsonObject(key);
                if (!property.has(ToolSchemaKeyEnum.DESCRIPTION.key())) {
                    continue;
                }
                String description = property.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString();
                assertTrue(description.length() <= PARAM_DESCRIPTION_CEILING,
                        entry.getKey() + "." + key + " description is " + description.length() + " chars (ceiling "
                        + PARAM_DESCRIPTION_CEILING + "): " + description);
            }
        }
    }
}
