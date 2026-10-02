package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * {@code filePath} was previously missing from the schema's {@code required} array, and its description
 * falsely claimed omitting it falls back to "the first open project's source root" —
 * {@code SearchProvider.requireFilePathForLineLookup} actually refuses a blank path.
 */
class FindDeclarationToolTest {

    @Test
    void filePathAndLineAreBothRequired() {
        JsonObject schema = new FindDeclarationTool().schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        Set<String> keys = new HashSet<>();
        required.forEach(e -> keys.add(e.getAsString()));
        assertEquals(Set.of(FindDeclarationParamEnum.FILE_PATH.key(), FindDeclarationParamEnum.LINE.key()), keys);
    }
}
