package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.ScrollablePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanelMode;
import org.openide.windows.WindowManager;

/**
 * Session identity and instructions plus the common generic configuration UI.
 */
public class AiSessionSettingsDialog extends JDialog {

    public static AiSessionSettingsDialog show(AiSession session) {
        AiSessionSettingsDialog dialog = new AiSessionSettingsDialog(session);
        dialog.setLocationRelativeTo(WindowManager.getDefault().getMainWindow());
        dialog.setVisible(true);
        return dialog;
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row, JLabel label, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.weighty = 0;
        panel.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }
    private final JTextField nameField = new JTextField(24);
    private final JTextArea descriptionArea = new JTextArea(3, 24);
    private final JTextArea sessionInstructionsArea = new JTextArea(4, 24);
    private final AiSessionConfigPanel configPanel = new AiSessionConfigPanel(AiSessionConfigPanelMode.SESSION);
    private AiSessionSettings result;
    private String resultName;
    private String resultDescription;

    private AiSessionSettingsDialog(AiSession session) {
        super(WindowManager.getDefault().getMainWindow(), "Session Configuration", true);
        AiSessionSettings settings = session.settings();
        nameField.setText(session.name());
        descriptionArea.setText(session.description() == null ? "" : session.description());
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        sessionInstructionsArea.setText(settings.sessionInstructions() == null ? "" : settings.sessionInstructions());
        sessionInstructionsArea.setLineWrap(true);
        sessionInstructionsArea.setWrapStyleWord(true);
        configPanel.loadSession(settings);
        add(buildForm(), BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        getRootPane().setDefaultButton(ok);
        ok.addActionListener(e -> {
            buildResult(settings);
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        buttons.add(ok);
        buttons.add(cancel);
        add(buttons, BorderLayout.SOUTH);
        pack();

        // Calculate usable screen bounds from parent window's GraphicsConfiguration
        GraphicsConfiguration gc = WindowManager.getDefault().getMainWindow().getGraphicsConfiguration();
        Rectangle screenBounds;
        Insets screenInsets = new Insets(0, 0, 0, 0);
        if (gc != null) {
            screenBounds = gc.getBounds();
            screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        }
        else {
            screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        }

        int usableHeight = screenBounds.height - screenInsets.top - screenInsets.bottom;
        int initialHeight = Math.max(600, usableHeight / 2);
        initialHeight = Math.min(initialHeight, usableHeight);
        int initialWidth = Math.max(560, getWidth());

        setSize(new Dimension(initialWidth, initialHeight));
        setMinimumSize(new Dimension(560, 400));
    }

    private JScrollPane buildForm() {
        ScrollablePanel form = new ScrollablePanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        addRow(form, c, 0, new JLabel("Session name:"), nameField);

        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setPreferredSize(new Dimension(300, 200));
        descriptionScroll.setMinimumSize(new Dimension(200, 200));
        addRow(form, c, 1, new JLabel("Description:"), descriptionScroll);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(configPanel, c);

        JScrollPane instructionsScroll = new JScrollPane(sessionInstructionsArea);
        instructionsScroll.setPreferredSize(new Dimension(300, 200));
        instructionsScroll.setMinimumSize(new Dimension(200, 200));
        addRow(form, c, 3, new JLabel("Session instructions:"), instructionsScroll);

        c.gridy = 4;
        c.weighty = 1;
        form.add(new JPanel(), c);
        JScrollPane scroll = new JScrollPane(form);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void buildResult(AiSessionSettings original) {
        resultName = nameField.getText().trim();
        resultDescription = descriptionArea.getText().trim();
        configPanel.applySession(original);
        String instructions = sessionInstructionsArea.getText().trim();
        original.setSessionInstructions(instructions.isBlank() ? null : instructions);
        result = original;
    }

    public AiSessionSettings getResultConfig() {
        return result;
    }

    public String getResultName() {
        return resultName;
    }

    public String getResultDescription() {
        return resultDescription;
    }
}
