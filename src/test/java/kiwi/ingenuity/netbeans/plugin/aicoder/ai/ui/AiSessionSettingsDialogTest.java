package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.ScrollablePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import org.junit.jupiter.api.Test;

class AiSessionSettingsDialogTest {

    @Test
    void formPlacesConfigBeforeDescriptionAndInstructions() {
        AiTypeEnum type = AiTypeEnum.values()[0];
        AiSession session = AiSession.create("/tmp", type);
        AiSessionSettingsDialog dialog = new AiSessionSettingsDialog(session);
        try {
            JScrollPane outer = dialog.buildForm();
            ScrollablePanel form = assertInstanceOf(ScrollablePanel.class, outer.getViewport().getView());
            Component[] children = form.getComponents();

            assertEquals("Session name:", ((JLabel) children[0]).getText());
            assertInstanceOf(JTextField.class, children[1]);
            assertInstanceOf(AiSessionConfigPanel.class, children[2]);
            assertEquals("Description:", ((JLabel) children[3]).getText());
            assertInstanceOf(JScrollPane.class, children[4]);
            assertEquals("Session Instructions:", ((JLabel) children[5]).getText());
            assertInstanceOf(JScrollPane.class, children[6]);
        } finally {
            dialog.dispose();
        }
    }
}
