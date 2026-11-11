package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CodexAutoModelProbeTest {

    @Test
    @Timeout(5)
    void autoNeverCountsAsAMismatch() {
        assertFalse(CodexAiProcessManager.isModelMismatch("auto", "gpt-5.5"));
        assertFalse(CodexAiProcessManager.isModelMismatch("Auto", "gpt-5.5"));
        assertFalse(CodexAiProcessManager.isModelMismatch(null, "gpt-5.5"));
    }

    @Test
    @Timeout(5)
    void aRealModelMismatchIsStillReported() {
        assertTrue(CodexAiProcessManager.isModelMismatch("gpt-x", "gpt-5.5"));
        assertFalse(CodexAiProcessManager.isModelMismatch("gpt-x", null));
        assertFalse(CodexAiProcessManager.isModelMismatch("gpt-x", "gpt-x"));
    }

    @Test
    @Timeout(5)
    void theDefaultModelIsTheEntryMarkedIsDefault() {
        var result = JsonParser.parseString(
                "{\"data\":[{\"id\":\"gpt-a\",\"isDefault\":false},{\"id\":\"gpt-b\",\"isDefault\":true}]}").getAsJsonObject();

        assertEquals("gpt-b", CodexAiProcessManager.findDefaultModelObject(result).get("id").getAsString());
    }

    @Test
    @Timeout(5)
    void noEntryMarkedDefaultMeansNoDefault() {
        var result = JsonParser.parseString("{\"data\":[{\"id\":\"gpt-a\"}]}").getAsJsonObject();

        assertNull(CodexAiProcessManager.findDefaultModelObject(result));
    }
}
