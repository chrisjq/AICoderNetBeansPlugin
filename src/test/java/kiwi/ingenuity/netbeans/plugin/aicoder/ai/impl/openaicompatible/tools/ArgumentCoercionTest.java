package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ArgumentCoercionTest {

    @Test
    @Timeout(5)
    void stringIntegerBecomesNumber() {
        JsonObject schema = schema("line", "integer");
        JsonObject args = args("line", "\"42\"");

        JsonObject out = ArgumentCoercion.coerce(args, schema);

        assertEquals(42, out.get("line").getAsInt());
        assertTrue(out.get("line").getAsJsonPrimitive().isNumber());
    }

    @Test
    @Timeout(5)
    void integralStringWithDecimalPointBecomesInteger() {
        JsonObject out = ArgumentCoercion.coerce(args("line", "\"7.0\""), schema("line", "integer"));

        assertEquals(7, out.get("line").getAsInt());
    }

    @Test
    @Timeout(5)
    void stringNumberBecomesNumber() {
        JsonObject out = ArgumentCoercion.coerce(args("ratio", "\"0.5\""), schema("ratio", "number"));

        assertEquals(0.5, out.get("ratio").getAsDouble());
    }

    @Test
    @Timeout(5)
    void trueFalseStringsBecomeBooleans() {
        JsonObject schema = properties(new String[]{"async"}, new String[]{"boolean"});
        JsonObject out = ArgumentCoercion.coerce(args("async", "\"true\""), schema);

        assertTrue(out.get("async").getAsJsonPrimitive().isBoolean());
        assertTrue(out.get("async").getAsBoolean());

        JsonObject out2 = ArgumentCoercion.coerce(args("async", "\"false\""), schema);
        assertTrue(out2.get("async").getAsJsonPrimitive().isBoolean());
        assertEquals(false, out2.get("async").getAsBoolean());
    }

    @Test
    @Timeout(5)
    void jsonStringObjectIsParsedWhenSchemaWantsObject() {
        JsonObject out = ArgumentCoercion.coerce(args("config", "\"{\\\"a\\\":1}\""), schema("config", "object"));

        assertTrue(out.get("config").isJsonObject());
        assertEquals(1, out.getAsJsonObject("config").get("a").getAsInt());
    }

    @Test
    @Timeout(5)
    void jsonStringArrayIsParsedWhenSchemaWantsArray() {
        JsonObject out = ArgumentCoercion.coerce(args("items", "\"[1,2,3]\""), schema("items", "array"));

        assertTrue(out.get("items").isJsonArray());
        assertEquals(3, out.getAsJsonArray("items").size());
    }

    @Test
    @Timeout(5)
    void singleValueBecomesOneElementArray() {
        JsonObject out = ArgumentCoercion.coerce(args("paths", "\"/tmp/a\""), schema("paths", "array"));

        JsonArray array = out.getAsJsonArray("paths");
        assertEquals(1, array.size());
        assertEquals("/tmp/a", array.get(0).getAsString());
    }

    @Test
    @Timeout(5)
    void singleObjectBecomesOneElementArray() {
        JsonObject out = ArgumentCoercion.coerce(args("paths", "{\"path\":\"/tmp/a\"}"), schema("paths", "array"));

        JsonArray array = out.getAsJsonArray("paths");
        assertEquals(1, array.size());
        assertTrue(array.get(0).isJsonObject());
    }

    @Test
    @Timeout(5)
    void propertyNameMatchedCaseInsensitively() {
        JsonObject out = ArgumentCoercion.coerce(args("FilePath", "\"x\""), schema("filePath", "string"));

        assertTrue(out.has("filePath"));
        assertEquals("x", out.get("filePath").getAsString());
        assertEquals(1, out.size());
    }

    @Test
    @Timeout(5)
    void propertyNameMatchedAcrossUnderscoreAndDash() {
        JsonObject schema = schema("file_path", "string");

        assertEquals("x", ArgumentCoercion.coerce(args("filePath", "\"x\""), schema).get("file_path").getAsString());
        assertEquals("y", ArgumentCoercion.coerce(args("file-path", "\"y\""), schema).get("file_path").getAsString());
        assertEquals("z", ArgumentCoercion.coerce(args("FILE_PATH", "\"z\""), schema).get("file_path").getAsString());
    }

    @Test
    @Timeout(5)
    void unknownPropertiesAreKept() {
        JsonObject out = ArgumentCoercion.coerce(args("extra", "\"kept\""), schema("line", "integer"));

        assertEquals("kept", out.get("extra").getAsString());
    }

    @Test
    @Timeout(5)
    void alreadyCorrectValuesAreUnchanged() {
        JsonObject schema = properties(new String[]{"line", "async", "config"}, new String[]{"integer", "boolean", "object"});
        JsonObject args = new JsonObject();
        args.addProperty("line", 5);
        args.addProperty("async", true);
        args.add("config", JsonParser.parseString("{\"a\":1}"));

        JsonObject out = ArgumentCoercion.coerce(args, schema);

        assertEquals(5, out.get("line").getAsInt());
        assertTrue(out.get("async").getAsJsonPrimitive().isBoolean());
        assertTrue(out.get("config").isJsonObject());
    }

    @Test
    @Timeout(5)
    void nonNumericStringForIntegerIsLeftAlone() {
        JsonObject out = ArgumentCoercion.coerce(args("line", "\"abc\""), schema("line", "integer"));

        assertEquals("abc", out.get("line").getAsString());
    }

    @Test
    @Timeout(5)
    void malformedJsonStringIsLeftAlone() {
        JsonObject out = ArgumentCoercion.coerce(args("config", "\"{not json\""), schema("config", "object"));

        assertEquals("{not json", out.get("config").getAsString());
    }

    @Test
    @Timeout(5)
    void inputObjectIsNotMutated() {
        JsonObject args = args("line", "\"42\"");

        ArgumentCoercion.coerce(args, schema("line", "integer"));

        assertEquals("42", args.get("line").getAsString());
    }

    @Test
    @Timeout(5)
    void nullArgsYieldEmptyObject() {
        assertEquals(0, ArgumentCoercion.coerce(null, schema("line", "integer")).size());
    }

    @Test
    @Timeout(5)
    void noSchemaLeavesArgumentsUntouched() {
        JsonObject out = ArgumentCoercion.coerce(args("line", "\"42\""), new JsonObject());

        assertEquals("42", out.get("line").getAsString());
    }

    private static JsonObject schema(String name, String type) {
        return properties(new String[]{name}, new String[]{type});
    }

    private static JsonObject properties(String[] names, String[] types) {
        JsonObject properties = new JsonObject();
        for (int i = 0; i < names.length; i++) {
            JsonObject property = new JsonObject();
            property.addProperty("type", types[i]);
            properties.add(names[i], property);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        return schema;
    }

    private static JsonObject args(String name, String rawJsonValue) {
        JsonObject args = new JsonObject();
        JsonElement value = JsonParser.parseString(rawJsonValue);
        args.add(name, value);
        return args;
    }
}
