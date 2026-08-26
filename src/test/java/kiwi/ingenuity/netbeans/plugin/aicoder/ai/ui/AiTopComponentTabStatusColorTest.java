package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiTopComponent.TabStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the pure static colour-mapping helper {@code resolvedTabStatusColor()} extracted from
 * {@code AiTopComponent.tabStatusColor()}. Each {@code TabStatus} must resolve to the hex string derived from its
 * {@code STATUS_COLOR_*} constant; {@code THINKING} additionally has a flash variant.
 */
class AiTopComponentTabStatusColorTest {

    // No "unknown status" test: the switch takes TabStatus and has no default branch, so an
    // unmapped status is a compile error. There is no runtime value left to assert on.
    /**
     * Matches the convention in the colour constants: {@code toHex(Color)} uses uppercase hex.
     */
    private static String toHex(int r, int g, int b) {
        return String.format("#%02X%02X%02X", r, g, b);
    }

    @Test
    void readyResolvesToGreen() {
        String hex = AiTopComponent.resolvedTabStatusColor(TabStatus.READY, false);
        assertEquals(toHex(0x4C, 0xAF, 0x50), hex,
                "READY must map to the green status dot");
    }

    @Test
    void thinkingWithoutFlashResolvesToOrange() {
        String hex = AiTopComponent.resolvedTabStatusColor(TabStatus.THINKING, false);
        assertEquals(toHex(0xFF, 0x98, 0x00), hex,
                "THINKING without flash must map to orange");
    }

    @Test
    void thinkingWithFlashResolvesToMagenta() {
        String hex = AiTopComponent.resolvedTabStatusColor(TabStatus.THINKING, true);
        assertEquals(toHex(0xFF, 0x00, 0xFF), hex,
                "THINKING with flash must map to the magenta flash colour");
    }

    @Test
    void fatalResolvesToRed() {
        String hex = AiTopComponent.resolvedTabStatusColor(TabStatus.FATAL, false);
        assertEquals(toHex(0xF4, 0x43, 0x36), hex,
                "FATAL must map to the red status dot");
    }

    @Test
    void awaitingUserResolvesToWhite() {
        String hex = AiTopComponent.resolvedTabStatusColor(TabStatus.AWAITING_USER, false);
        assertEquals(toHex(0xFF, 0xFF, 0xFF), hex,
                "AWAITING_USER must map to white");
    }

    @Test
    void awaitingUserWithFlashStillResolvesToWhite() {
        // Flash is irrelevant for AWAITING_USER — flashThinking() early-returns
        // when tabStatus != THINKING, so a stale flash flag must not change the colour.
        String hex = AiTopComponent.resolvedTabStatusColor(TabStatus.AWAITING_USER, true);
        assertEquals(toHex(0xFF, 0xFF, 0xFF), hex,
                "AWAITING_USER must stay white even if flashActive is stale");
    }

}
