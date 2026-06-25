package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class JsonUtilsTest {

    @Test
    void getString_presentKey_returnsValue() {
        JsonObject o = new JsonObject();
        o.addProperty("key", "value");
        assertEquals("value", JsonUtils.getString(o, "key"));
    }

    @Test
    void getString_missingKey_returnsNull() {
        assertNull(JsonUtils.getString(new JsonObject(), "missing"));
    }

    @Test
    void getString_nullValue_returnsNull() {
        JsonObject o = new JsonObject();
        o.addProperty("key", (String) null);
        assertNull(JsonUtils.getString(o, "key"));
    }

    @Test
    void getLong_presentKey_returnsValue() {
        JsonObject o = new JsonObject();
        o.addProperty("n", 42L);
        assertEquals(42L, JsonUtils.getLong(o, "n"));
    }

    @Test
    void getLong_missingKey_returnsZero() {
        assertEquals(0L, JsonUtils.getLong(new JsonObject(), "missing"));
    }
}
