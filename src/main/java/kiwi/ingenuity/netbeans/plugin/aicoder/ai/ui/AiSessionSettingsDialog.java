package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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
        setMinimumSize(new Dimension(Math.max(560, getWidth()), Math.min(620, getHeight())));
    }

    private JScrollPane buildForm() {
        ScrollablePanel form = new ScrollablePanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        addRow(form, c, 0, new JLabel("Session name:"), nameField);
        addRow(form, c, 1, new JLabel("Description:"), new JScrollPane(descriptionArea));
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(configPanel, c);
        addRow(form, c, 3, new JLabel("Session instructions:"), new JScrollPane(sessionInstructionsArea));
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
