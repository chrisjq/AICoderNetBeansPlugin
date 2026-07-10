package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Locks in {@link GrokModelDiscovery#parseModelIds} against the real
 * {@code grok models} output, captured from an actual installed grok CLI
 * (v0.2.93) rather than guessed.
 */
class GrokModelDiscoveryTest {

    @Test
    void parseModelIds_realCliOutput_extractsBareIds() {
        List<String> lines = List.of(
                "You are logged in with grok.com.",
                "",
                "Default model: grok-4.5",
                "",
                "Available models:",
                "  * grok-4.5 (default)",
                "  - grok-composer-2.5-fast"
        );
        List<String> ids = GrokModelDiscovery.parseModelIds(lines);
        assertEquals(List.of("grok-4.5", "grok-composer-2.5-fast"), ids);
    }

    @Test
    void parseModelIds_emptyOrBannerOnly_returnsEmpty() {
        List<String> lines = List.of("You are not logged in.", "", "Run `grok login` first.");
        assertTrue(GrokModelDiscovery.parseModelIds(lines).isEmpty());
    }

    @Test
    void parseModelIds_deduplicatesRepeatedIds() {
        List<String> lines = List.of("  * grok-4.5 (default)", "  - grok-4.5");
        assertEquals(List.of("grok-4.5"), GrokModelDiscovery.parseModelIds(lines));
    }
}
