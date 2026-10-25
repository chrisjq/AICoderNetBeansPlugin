package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.GraphicsEnvironment;
import javax.swing.JFrame;
import javax.swing.JPanel;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class DialogPlacementTest {

    @Test
    void anchorFallsBackForNonShowingComponent() {
        JPanel panel = new JPanel();
        assertNotNull(DialogPlacement.anchor(panel));
    }

    @Test
    void anchorUsesWindowAncestorWhenShowing() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        JFrame frame = new JFrame();
        try {
            JPanel panel = new JPanel();
            frame.add(panel);
            frame.pack();
            frame.setVisible(true);
            assertSame(frame, DialogPlacement.anchor(panel));
        }
        finally {
            frame.dispose();
        }
    }
}
