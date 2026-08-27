package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.netbeans.modules.refactoring.java.api.ChangeParametersRefactoring.ParameterInfo;

class ChangeMethodSignatureToolParameterParsingTest {

    @Test
    void nameOnlyEntryRetainsItsSuppliedName() throws Exception {
        JsonObject entry = new JsonObject();
        entry.addProperty(ChangeMethodSignatureParamEnum.ORIGINAL_INDEX.key(), 1);
        entry.addProperty(ChangeMethodSignatureParamEnum.NAME.key(), "newName");

        ParameterInfo[] infos = ChangeMethodSignatureTool.toParameterInfos(arrayOf(entry));

        assertEquals(1, infos.length);
        assertEquals(1, infos[0].getOriginalIndex());
        assertEquals("newName", infos[0].getName());
        assertNull(infos[0].getType());
    }

    @Test
    void typeOnlyEntryRetainsItsSuppliedType() throws Exception {
        JsonObject entry = new JsonObject();
        entry.addProperty(ChangeMethodSignatureParamEnum.ORIGINAL_INDEX.key(), 1);
        entry.addProperty(ChangeMethodSignatureParamEnum.TYPE.key(), "long");

        ParameterInfo[] infos = ChangeMethodSignatureTool.toParameterInfos(arrayOf(entry));

        assertEquals(1, infos.length);
        assertEquals(1, infos[0].getOriginalIndex());
        assertNull(infos[0].getName());
        assertEquals("long", infos[0].getType());
    }

    /**
     * A non-object entry used to be skipped with {@code continue}, which handed the refactoring a SHORTER list than the
     * caller supplied: the malformed entry's parameter was dropped from the method and every call site, and the tool
     * still reported success. It must refuse instead, naming the offending index.
     */
    @Test
    void nonObjectEntryIsRejectedRatherThanSkipped() {
        JsonObject valid = new JsonObject();
        valid.addProperty(ChangeMethodSignatureParamEnum.ORIGINAL_INDEX.key(), 0);
        JsonArray params = new JsonArray();
        params.add(valid);
        params.add("notAnObject");

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> ChangeMethodSignatureTool.toParameterInfos(params));

        assertEquals(-32602, ex.getCode(), "a malformed argument is an invalid-params error");
        assertTrue(ex.getMessage().contains("parameters[1]"),
                "the refusal must identify which entry was wrong: " + ex.getMessage());
    }

    @Test
    void emptyArrayParsesToAnEmptyListNotNull() throws Exception {
        // Distinct from an omitted parameters argument, which arrives as null. The merge layer relies on this
        // distinction to tell "remove every parameter" from "leave the parameters alone".
        ParameterInfo[] infos = ChangeMethodSignatureTool.toParameterInfos(new JsonArray());

        assertEquals(0, infos.length, "an empty array must survive as an empty list");
    }

    private static JsonArray arrayOf(JsonObject entry) {
        JsonArray result = new JsonArray();
        result.add(entry);
        return result;
    }
}
