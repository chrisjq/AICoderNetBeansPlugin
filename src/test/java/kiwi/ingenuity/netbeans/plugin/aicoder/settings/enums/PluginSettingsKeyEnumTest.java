package kiwi.ingenuity.netbeans.plugin.aicoder.settings.enums;

import java.util.Arrays;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettingsKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PluginSettingsKeyEnumTest {

    @Test
    void allValuesHaveNonBlankKey() {
        for (PluginSettingsKeyEnum v : PluginSettingsKeyEnum.values()) {
            assertFalse(v.key().isBlank(), "Blank key for " + v.name());
        }
    }

    @Test
    void noDuplicateKeys() {
        long distinct = Arrays.stream(PluginSettingsKeyEnum.values())
                .map(PluginSettingsKeyEnum::key)
                .distinct()
                .count();
        assertEquals(PluginSettingsKeyEnum.values().length, distinct);
    }

    @Test
    void inboxRetentionKeyString() {
        assertEquals("ai.inbox.retentionMinutes",
                PluginSettingsKeyEnum.INBOX_RETENTION_MINUTES.key());
    }

    @Test
    void inboxMaxSizeKeyString() {
        assertEquals("ai.inbox.maxSize",
                PluginSettingsKeyEnum.INBOX_MAX_SIZE.key());
    }

    @Test
    void saveSessionOnCloseIfTickedKeyString() {
        assertEquals("ai.saveSessionOnCloseIfTicked",
                PluginSettingsKeyEnum.SAVE_SESSION_ON_CLOSE_IF_TICKED.key());
    }

    @Test
    void typedDefaultsAreAvailable() {
        assertTrue(PluginSettingsKeyEnum.SAVE_HISTORY.defaultBoolean());
        assertEquals(3, PluginSettingsKeyEnum.DIFF_CONTEXT_LINES.defaultInt());
        assertEquals(PluginSettingsKeyEnum.MCP_SERVER_PORT.defaultInt(),
                PluginSettingsKeyEnum.MCP_SERVER_PORT.defaultInt());
        assertNull(PluginSettingsKeyEnum.LAST_SESSION_AI_TYPE.defaultString());
    }

    @Test
    void prefixKeysDoNotExposeTypedDefaults() {
        assertFalse(PluginSettingsKeyEnum.AI_ENABLED_PREFIX.hasDefaultValue());
        assertThrows(IllegalStateException.class,
                PluginSettingsKeyEnum.AI_ENABLED_PREFIX::defaultBoolean);
    }
}
