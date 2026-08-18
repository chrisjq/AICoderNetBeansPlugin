package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;

class ConfirmPanel extends JPanel {

    private static final Color BORDER_COLOR = new Color(0xff, 0xb8, 0x6c);

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private boolean responded = false;
    private final JButton yesBtn;
    private final JButton noBtn;

    ConfirmPanel(ConfirmEvent event) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 8, 6, 4)));

        JLabel label = new JLabel("<html><b>" + esc(event.displayText()) + "</b></html>");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        add(label);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        yesBtn = new JButton("Yes");
        noBtn = new JButton("No");
        yesBtn.addActionListener(e -> respond(event, true));
        noBtn.addActionListener(e -> respond(event, false));
        btnRow.add(yesBtn);
        btnRow.add(noBtn);
        add(btnRow);

        // Disable buttons when the future is resolved externally (timeout or Stop)
        event.response().whenComplete((d, ex) -> SwingUtilities.invokeLater(() -> {
            if (!responded) {
                responded = true;
                yesBtn.setEnabled(false);
                noBtn.setEnabled(false);
            }
        }));
    }

    private void respond(ConfirmEvent event, boolean allow) {
        if (responded) {
            return;
        }
        responded = true;
        yesBtn.setEnabled(false);
        noBtn.setEnabled(false);
        event.response().complete(allow ? PermissionDecision.allowed() : PermissionDecision.denied(null));
    }
}
