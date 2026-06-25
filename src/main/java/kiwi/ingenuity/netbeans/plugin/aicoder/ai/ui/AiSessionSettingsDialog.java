package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;
import org.openide.windows.WindowManager;

public class AiSessionSettingsDialog extends JDialog {

    public static AiSessionSettingsDialog show(AiSession session) {
        AiSessionSettingsDialog dlg = new AiSessionSettingsDialog(session);
        dlg.setLocationRelativeTo(WindowManager.getDefault().getMainWindow());
        dlg.setVisible(true);
        return dlg;
    }

    private final JTextField nameField = new JTextField(24);
    private final JTextArea descriptionArea = new JTextArea(3, 24);
    private final JSpinner historySpinner;
    private final JCheckBox restrictCheckBox = new JCheckBox();
    private final JCheckBox allowInterAiCheckBox = new JCheckBox();
    private final JCheckBox autoNotifyCheckBox = new JCheckBox();
    private final JCheckBox allowImportantCheckBox = new JCheckBox();
    private final JTextArea sessionInstructionsArea = new JTextArea(4, 24);

    private boolean resetAutoNotify = false;
    private final boolean globalAutoNotify;

    private AbstractAiSessionSettings result = null;
    private String resultName = null;
    private String resultDescription = null;

    private AiSessionSettingsDialog(AiSession session) {
        super(WindowManager.getDefault().getMainWindow(), "Session Configuration", true);
        AbstractAiSessionSettings cfg = session.settings() != null ? session.settings() : AbstractAiSessionSettings.defaults();
        globalAutoNotify = PluginSettings.isAutoNotifyInbox();

        historySpinner = new JSpinner(new SpinnerNumberModel(
                cfg.effectiveMaxHistory(), 0, 10000, 10));

        nameField.setText(session.name());
        descriptionArea.setText(session.description() != null ? session.description() : "");
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);

        boolean globalRestrict = PluginSettings.isRestrictToProjectFiles();
        restrictCheckBox.setText("Restrict to project files (global: " + (globalRestrict ? "on" : "off") + ")");
        restrictCheckBox.setSelected(cfg.effectiveRestrictToProjectFiles());

        boolean globalInterAi = PluginSettings.isAllowInterAiComms();
        allowInterAiCheckBox.setText("Allow inter-AI communication (global: " + (globalInterAi ? "on" : "off") + ")");
        allowInterAiCheckBox.setSelected(cfg.effectiveAllowInterAiComms());

        autoNotifyCheckBox.setText("Auto-notify on incoming messages (global: " + (globalAutoNotify ? "on" : "off") + ")");
        autoNotifyCheckBox.setSelected(cfg.effectiveAutoNotifyInbox());
        autoNotifyCheckBox.setVisible(cfg.effectiveAllowInterAiComms());

        boolean globalImportant = PluginSettings.isAllowImportantMessages();
        allowImportantCheckBox.setText("Allow important messages, interrupt this session (global: " + (globalImportant ? "on" : "off") + ")");
        allowImportantCheckBox.setSelected(cfg.effectiveAllowImportantMessages());
        allowImportantCheckBox.setVisible(cfg.effectiveAllowInterAiComms());

        sessionInstructionsArea.setText(cfg.sessionInstructions() != null ? cfg.sessionInstructions() : "");
        sessionInstructionsArea.setLineWrap(true);
        sessionInstructionsArea.setWrapStyleWord(true);

        allowInterAiCheckBox.addActionListener(e -> {
            autoNotifyCheckBox.setVisible(allowInterAiCheckBox.isSelected());
            allowImportantCheckBox.setVisible(allowInterAiCheckBox.isSelected());
            pack();
        });

        autoNotifyCheckBox.addActionListener(e -> resetAutoNotify = false);

        JButton autoNotifyResetBtn = new JButton("Reset");
        autoNotifyResetBtn.setToolTipText("Revert to global default");
        autoNotifyResetBtn.addActionListener(e -> {
            autoNotifyCheckBox.setSelected(globalAutoNotify);
            resetAutoNotify = true;
        });

        JButton sessionInstructionsResetBtn = new JButton("Reset to default");
        sessionInstructionsResetBtn.addActionListener(e -> sessionInstructionsArea.setText(""));

        JPanel form = buildForm(autoNotifyResetBtn, sessionInstructionsResetBtn);
        add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        JButton okBtn = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        getRootPane().setDefaultButton(okBtn);
        okBtn.addActionListener(e -> {
            buildResult(cfg);
            dispose();
        });
        cancelBtn.addActionListener(e -> dispose());
        buttons.add(okBtn);
        buttons.add(cancelBtn);
        add(buttons, BorderLayout.SOUTH);

        pack();
        setMinimumSize(getPreferredSize());
    }

    private JPanel buildForm(JButton autoNotifyResetBtn, JButton sessionInstructionsResetBtn) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addRow(p, c, row++, new JLabel("Session name:"), nameField);
        addRow(p, c, row++, new JLabel("Description:"), new JScrollPane(descriptionArea));
        addRow(p, c, row++, new JLabel("History size:"), historySpinner);
        addFull(p, c, row++, restrictCheckBox);
        addFull(p, c, row++, allowInterAiCheckBox);

        JPanel autoNotifyRow = new JPanel(new BorderLayout(4, 0));
        autoNotifyRow.add(autoNotifyCheckBox, BorderLayout.CENTER);
        autoNotifyRow.add(autoNotifyResetBtn, BorderLayout.EAST);
        c.gridy = row++;
        c.gridwidth = 2;
        c.gridx = 0;
        c.weightx = 1;
        p.add(autoNotifyRow, c);

        addFull(p, c, row++, allowImportantCheckBox);

        addRow(p, c, row++, new JLabel("Session instructions:"), new JScrollPane(sessionInstructionsArea));
        addFull(p, c, row, sessionInstructionsResetBtn);
        return p;
    }

    private void addRow(JPanel p, GridBagConstraints c, int row, JLabel label, Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        p.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(field, c);
    }

    private void addFull(JPanel p, GridBagConstraints c, int row, Component comp) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        p.add(comp, c);
    }

    private void buildResult(AbstractAiSessionSettings original) {
        resultName = nameField.getText().trim();
        resultDescription = descriptionArea.getText().trim();

        int historyValue = (Integer) historySpinner.getValue();
        Integer maxHistory = historyValue == PluginSettings.getMaxHistory() ? null : historyValue;

        boolean restrictSelected = restrictCheckBox.isSelected();
        Boolean restrictToProjectFiles = restrictSelected == PluginSettings.isRestrictToProjectFiles() ? null : restrictSelected;

        boolean interAiSelected = allowInterAiCheckBox.isSelected();
        Boolean allowInterAiComms = interAiSelected == PluginSettings.isAllowInterAiComms() ? null : interAiSelected;

        Boolean autoNotifyInbox;
        if (resetAutoNotify) {
            autoNotifyInbox = null;
        }
        else {
            boolean autoSelected = autoNotifyCheckBox.isSelected();
            autoNotifyInbox = autoSelected == globalAutoNotify ? null : autoSelected;
        }

        boolean importantSelected = allowImportantCheckBox.isSelected();
        Boolean allowImportantMessages = importantSelected == PluginSettings.isAllowImportantMessages() ? null : importantSelected;

        String sessionInstructions = sessionInstructionsArea.getText().trim();
        if (sessionInstructions.isBlank()) {
            sessionInstructions = null;
        }

        if (original instanceof AbstractAiModelSessionSettings modelCfg) {
            result = new AbstractAiModelSessionSettings(
                    maxHistory, restrictToProjectFiles, allowInterAiComms, autoNotifyInbox,
                    allowImportantMessages, sessionInstructions,
                    modelCfg.model(),
                    original.autoAccept());
        }
        else {
            result = new AbstractAiSessionSettings(maxHistory, restrictToProjectFiles, allowInterAiComms,
                    autoNotifyInbox, allowImportantMessages, sessionInstructions, original.autoAccept());
        }
    }

    public AbstractAiSessionSettings getResultConfig() {
        return result;
    }

    public String getResultName() {
        return resultName;
    }

    public String getResultDescription() {
        return resultDescription;
    }
}
