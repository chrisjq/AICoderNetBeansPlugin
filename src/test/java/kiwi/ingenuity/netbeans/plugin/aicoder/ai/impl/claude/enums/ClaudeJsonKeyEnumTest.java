package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.enums;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeJsonKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class ClaudeJsonKeyEnumTest {

    @Test
    void allValuesHaveNonBlankKey() {
        for (ClaudeJsonKeyEnum v : ClaudeJsonKeyEnum.values()) {
            assertFalse(v.key().isBlank(), "Blank key for " + v.name());
        }
    }

    @Test
    void typeKey_hasExpectedValue() {
        assertEquals("type", ClaudeJsonKeyEnum.TYPE.key());
    }
}
