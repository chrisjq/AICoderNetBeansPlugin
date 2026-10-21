package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * The transcript is capped by the supplier the session tab wires in (its own effective Max history), not by the legacy
 * global.
 */
class ConversationPanelHistoryCapTest {

    private static void assistantSays(ConversationPanel panel, String text) {
        panel.beginAssistantMessage();
        panel.appendDelta(text);
        panel.finaliseAssistantMessage();
    }

    @Test
    void theTranscriptIsCappedAtTheSuppliedValue() {
        ConversationPanel panel = new ConversationPanel();
        panel.setHistoryCap(() -> 2);

        for (int i = 1; i <= 4; i++) {
            assistantSays(panel, "m" + i);
        }

        assertEquals(2, panel.getHistory().size());
        assertEquals("m4", panel.getHistory().get(1).markdownText());
        assertEquals("m3", panel.getHistory().get(0).markdownText(), "the oldest messages are the ones dropped");
    }

    @Test
    void aChangedCapAppliesToTheNextMessageWithoutRewiringThePanel() {
        ConversationPanel panel = new ConversationPanel();
        AtomicInteger cap = new AtomicInteger(2);
        panel.setHistoryCap(cap::get);
        assistantSays(panel, "a");
        assistantSays(panel, "b");
        assistantSays(panel, "c");
        assertEquals(2, panel.getHistory().size());

        cap.set(4);
        assistantSays(panel, "d");
        assistantSays(panel, "e");

        assertEquals(4, panel.getHistory().size(), "a raised cap keeps more from now on");
    }

    @Test
    void aCapOfZeroMeansNoCap() {
        ConversationPanel panel = new ConversationPanel();
        panel.setHistoryCap(() -> 0);

        for (int i = 0; i < 5; i++) {
            assistantSays(panel, "m" + i);
        }

        assertEquals(5, panel.getHistory().size());
    }
}
