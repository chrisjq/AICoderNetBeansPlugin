package kiwi.ingenuity.netbeans.plugin.aicoder.settings.enums;

import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettingsKeyEnum;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
