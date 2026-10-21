package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiSessionSettingsRoutingIdTest {

    @Test
    void acceptsAUuid() {
        assertTrue(AiSessionSettings.isValidMcpRoutingId(UUID.randomUUID().toString()));
    }

    @Test
    void rejectsPathSeparatorsEncodedSlashesShortIdsAndNull() {
        assertFalse(AiSessionSettings.isValidMcpRoutingId("abcd/efgh-ijkl"));
        assertFalse(AiSessionSettings.isValidMcpRoutingId("%2F"));
        assertFalse(AiSessionSettings.isValidMcpRoutingId("a/b"));
        assertFalse(AiSessionSettings.isValidMcpRoutingId("short"));
        assertFalse(AiSessionSettings.isValidMcpRoutingId(null));
    }
}
