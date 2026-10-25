package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ServerFieldEnumTest {

    @Test
    @Timeout(5)
    void keysMatchTheWireSpellings() {
        assertEquals("models", ServerFieldEnum.MODELS.key());
        assertEquals("details", ServerFieldEnum.DETAILS.key());
        assertEquals("name", ServerFieldEnum.NAME.key());
        assertEquals("model", ServerFieldEnum.MODEL.key());
        assertEquals("capabilities", ServerFieldEnum.CAPABILITIES.key());
        assertEquals("model_info", ServerFieldEnum.MODEL_INFO.key());
        assertEquals("context_length", ServerFieldEnum.CONTEXT_LENGTH.key());
        assertEquals("parameters", ServerFieldEnum.PARAMETERS.key());
        assertEquals("data", ServerFieldEnum.DATA.key());
        assertEquals("key", ServerFieldEnum.KEY.key());
        assertEquals("id", ServerFieldEnum.ID.key());
        assertEquals("state", ServerFieldEnum.STATE.key());
        assertEquals("max_context_length", ServerFieldEnum.MAX_CONTEXT_LENGTH.key());
        assertEquals("loaded_instances", ServerFieldEnum.LOADED_INSTANCES.key());
        assertEquals("config", ServerFieldEnum.CONFIG.key());
        assertEquals("trained_for_tool_use", ServerFieldEnum.TRAINED_FOR_TOOL_USE.key());
        assertEquals("loaded_context_length", ServerFieldEnum.LOADED_CONTEXT_LENGTH.key());
        assertEquals("default_generation_settings", ServerFieldEnum.DEFAULT_GENERATION_SETTINGS.key());
        assertEquals("n_ctx", ServerFieldEnum.N_CTX.key());
        assertEquals("context_window", ServerFieldEnum.CONTEXT_WINDOW.key());
        assertEquals("max_model_len", ServerFieldEnum.MAX_MODEL_LEN.key());
        assertEquals("meta", ServerFieldEnum.META.key());
        assertEquals("n_ctx_train", ServerFieldEnum.N_CTX_TRAIN.key());
        assertEquals("tools", ServerFieldEnum.TOOLS.key());
        assertEquals("thinking", ServerFieldEnum.THINKING.key());
    }
}
