package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.ScrollablePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiMessagingSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.DatabaseAccessSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.WebRequestAccessSettingsPanel;
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
    private final AiMessagingSettingsPanel aiMessagingPanel;
    private final WebRequestAccessSettingsPanel webRequestAccessPanel;
    private final DatabaseAccessSettingsPanel databaseAccessPanel;
    private final JTextArea sessionInstructionsArea = new JTextArea(4, 24);

    private boolean resetAutoNotify = false;
    private final boolean globalAutoNotify;

    private AiSessionSettings result = null;
    private String resultName = null;
    private String resultDescription = null;

    private AiSessionSettingsDialog(AiSession session) {
        super(WindowManager.getDefault().getMainWindow(), "Session Configuration", true);
        AiSessionSettings cfg = session.settings();
        globalAutoNotify = PluginSettings.isAutoNotifyInbox();

        aiMessagingPanel = new AiMessagingSettingsPanel(true);
        webRequestAccessPanel = new WebRequestAccessSettingsPanel(true);
        databaseAccessPanel = new DatabaseAccessSettingsPanel(true);

        historySpinner = new JSpinner(new SpinnerNumberModel(
                cfg.effectiveMaxHistory(), 0, 10000, 10));

        nameField.setText(session.name());
        descriptionArea.setText(session.description() != null ? session.description() : "");
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);

        boolean globalRestrict = PluginSettings.isRestrictToProjectFiles();
        restrictCheckBox.setText(
                AccessControlLabelEnum.RESTRICT_TO_PROJECT_FILES.sessionLabel(globalRestrict));
        restrictCheckBox.setSelected(cfg.effectiveRestrictToProjectFiles());

        webRequestAccessPanel.setAllowWebRequestsSelected(cfg.effectiveAllowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            webRequestAccessPanel.setOptionSelected(option,
                    cfg.effectiveAllowWebRequestAccess(option));
        }

        databaseAccessPanel.setAllowDatabaseAccessSelected(cfg.effectiveAllowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            databaseAccessPanel.setOptionSelected(option,
                    cfg.effectiveAllowDatabaseAccessOption(option));
        }
        databaseAccessPanel.setRowLimitValue(cfg.effectiveDatabaseRowLimit());

        aiMessagingPanel.setAllowInterAiSelected(cfg.effectiveAllowInterAiComms());
        aiMessagingPanel.setAutoNotifySelected(cfg.effectiveAutoNotifyInbox());
        aiMessagingPanel.setAllowImportantSelected(
                cfg.effectiveAllowImportantMessages());
        aiMessagingPanel.addChangeListener(e -> resetAutoNotify = false);

        sessionInstructionsArea.setText(cfg.sessionInstructions() != null ? cfg.sessionInstructions() : "");
        sessionInstructionsArea.setLineWrap(true);
        sessionInstructionsArea.setWrapStyleWord(true);

        aiMessagingPanel.setAutoNotifyAccessory(createAutoNotifyResetButton());

        JButton sessionInstructionsResetBtn = new JButton("Reset to default");
        sessionInstructionsResetBtn.addActionListener(e -> sessionInstructionsArea.setText(""));

        add(buildScrollableForm(sessionInstructionsResetBtn), BorderLayout.CENTER);

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

    private Component buildScrollableForm(JButton sessionInstructionsResetBtn) {
        ScrollablePanel form = buildForm(sessionInstructionsResetBtn);
        JScrollPane scrollPane = new JScrollPane(form);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        Dimension preferred = form.getPreferredSize();
        scrollPane.setPreferredSize(new Dimension(
                Math.max(560, preferred.width + 24),
                Math.min(520, preferred.height + 8)));
        return scrollPane;
    }

    private JButton createAutoNotifyResetButton() {
        JButton autoNotifyResetBtn = new JButton("Reset");
        autoNotifyResetBtn.setToolTipText("Revert to global default");
        autoNotifyResetBtn.addActionListener(e -> {
            aiMessagingPanel.setAutoNotifySelected(globalAutoNotify);
            resetAutoNotify = true;
        });
        return autoNotifyResetBtn;
    }

    private ScrollablePanel buildForm(JButton sessionInstructionsResetBtn) {
        ScrollablePanel p = new ScrollablePanel(new GridBagLayout());
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
        addFull(p, c, row++, webRequestAccessPanel);
        addFull(p, c, row++, databaseAccessPanel);
        addFull(p, c, row++, aiMessagingPanel);
        addRow(p, c, row++, new JLabel("Session instructions:"), new JScrollPane(sessionInstructionsArea));
        addFull(p, c, row++, sessionInstructionsResetBtn);
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = 1;
        p.add(new JPanel(), c);
        return p;
    }

    private void addRow(JPanel p, GridBagConstraints c, int row, JLabel label, Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.weighty = 0;
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
        c.weighty = 0;
        p.add(comp, c);
    }

    private void buildResult(AiSessionSettings original) {
        resultName = nameField.getText().trim();
        resultDescription = descriptionArea.getText().trim();

        int historyValue = (Integer) historySpinner.getValue();
        Integer maxHistory = historyValue == PluginSettings.getMaxHistory() ? null : historyValue;

        boolean restrictSelected = restrictCheckBox.isSelected();
        Boolean restrictToProjectFiles = restrictSelected == PluginSettings.isRestrictToProjectFiles() ? null : restrictSelected;

        boolean webRequestsSelected = webRequestAccessPanel.isAllowWebRequestsSelected();
        Boolean allowWebRequests = webRequestsSelected == PluginSettings.isAllowWebRequests() ? null : webRequestsSelected;

        boolean databaseAccessSelected = databaseAccessPanel.isAllowDatabaseAccessSelected();
        Boolean allowDatabaseAccess = databaseAccessSelected == PluginSettings.isAllowDatabaseAccess() ? null : databaseAccessSelected;

        int rowLimitValue = databaseAccessPanel.getRowLimitValue();
        Integer databaseRowLimit = rowLimitValue == PluginSettings.getDatabaseRowLimit() ? null : rowLimitValue;

        boolean interAiSelected = aiMessagingPanel.isAllowInterAiSelected();
        Boolean allowInterAiComms = interAiSelected == PluginSettings.isAllowInterAiComms() ? null : interAiSelected;

        Boolean autoNotifyInbox;
        if (resetAutoNotify) {
            autoNotifyInbox = null;
        }
        else {
            boolean autoSelected = aiMessagingPanel.isAutoNotifySelected();
            autoNotifyInbox = autoSelected == globalAutoNotify ? null : autoSelected;
        }

        boolean importantSelected = aiMessagingPanel.isAllowImportantSelected();
        Boolean allowImportantMessages = importantSelected == PluginSettings.isAllowImportantMessages() ? null : importantSelected;

        String sessionInstructions = sessionInstructionsArea.getText().trim();
        if (sessionInstructions.isBlank()) {
            sessionInstructions = null;
        }

        original.setMaxHistory(maxHistory);
        original.setRestrictToProjectFiles(restrictToProjectFiles);
        original.setAllowWebRequests(allowWebRequests);
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            boolean selected = webRequestAccessPanel.isOptionSelected(option);
            Boolean override = selected == PluginSettings.isAllowWebRequestAccess(option)
                    ? null : selected;
            original.setAllowWebRequestAccess(option, override);
        }
        original.setAllowDatabaseAccess(allowDatabaseAccess);
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            boolean selected = databaseAccessPanel.isOptionSelected(option);
            Boolean override = selected == PluginSettings.isAllowDatabaseAccessOption(option)
                    ? null : selected;
            original.setAllowDatabaseAccessOption(option, override);
        }
        original.setDatabaseRowLimit(databaseRowLimit);
        original.setAllowInterAiComms(allowInterAiComms);
        original.setAutoNotifyInbox(autoNotifyInbox);
        original.setAllowImportantMessages(allowImportantMessages);
        original.setSessionInstructions(sessionInstructions);

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
