package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The schema used to omit {@code required} entirely, so {@code className} was only required by convention
 * (the word "Required" in its description) rather than by the schema the calling AI actually validates
 * against — a mismatched-type or missing call would reach {@code handle()} before being refused.
 */
class FindUsagesToolTest {

    @Test
    void classNameIsDeclaredRequired() {
        JsonObject schema = new FindUsagesTool().schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(1, required.size());
        assertEquals(FindUsagesParamEnum.CLASS_NAME.key(), required.get(0).getAsString());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(FindUsagesParamEnum.CLASS_NAME.key()));
    }
}
