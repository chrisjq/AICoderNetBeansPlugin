package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Rectangle;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class DialogBoundsTest {

    @Test
    void rememberedBoundsAreClampedToMinimumSize() {
        Rectangle restored = DialogBounds.validated(
                new Rectangle(100, 120, 10, 20), 560, 400,
                List.of(new Rectangle(0, 0, 1200, 800)));

        assertNotNull(restored);
        assertEquals(new Rectangle(100, 120, 560, 400), restored);
    }

    @Test
    void staleBoundsAreRejectedWhenTheyDoNotIntersectAnyScreen() {
        Rectangle restored = DialogBounds.validated(
                new Rectangle(2400, 1600, 560, 400), 560, 400,
                List.of(new Rectangle(0, 0, 1200, 800)));

        assertNull(restored);
    }

    @Test
    void barelyIntersectingBoundsAreRejectedAsUnusable() {
        Rectangle restored = DialogBounds.validated(
                new Rectangle(1165, 780, 560, 400), 560, 400,
                List.of(new Rectangle(0, 0, 1200, 800)));

        assertNull(restored);
    }

    @Test
    void usableBoundsAreRestoredUnchanged() {
        Rectangle original = new Rectangle(200, 100, 850, 600);

        Rectangle restored = DialogBounds.validated(
                original, 820, 560,
                List.of(new Rectangle(0, 0, 1920, 1080)));

        assertEquals(original, restored);
    }
}
