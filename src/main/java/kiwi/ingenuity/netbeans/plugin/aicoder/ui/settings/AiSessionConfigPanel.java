package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Shared controls for global defaults, a session override, and a template
 * snapshot.
 */
public final class AiSessionConfigPanel extends JPanel {

    private static AiSessionSettings globalSnapshot() {
        AiSessionSettings result = new AiSessionSettings();
        result.applyDefaultSettingsFromGlobal();
        return result;
    }

    private static void apply(AiSessionSettings source, AiSessionSettings target) {
        target.setMaxHistory(source.maxHistory());
        target.setSaveHistory(source.saveHistory());
        target.setRestrictToProjectFiles(source.restrictToProjectFiles());
        target.setAutoAccept(source.autoAccept());
        target.setEnableClipboardAccess(source.enableClipboardAccess());
        target.setAllowInterAiComms(source.allowInterAiComms());
        target.setAutoNotifyInbox(source.autoNotifyInbox());
        target.setAllowImportantMessages(source.allowImportantMessages());
        target.setAllowWebRequests(source.allowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            target.setAllowWebRequestAccess(option, source.allowWebRequestAccess(option));
        }
        target.setAllowDatabaseAccess(source.allowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            target.setAllowDatabaseAccessOption(option, source.allowDatabaseAccessOption(option));
        }
        target.setDatabaseRowLimit(source.databaseRowLimit());
    }

    private final AiSessionConfigPanelMode mode;
    private final JSpinner maxHistory = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 10));
    private final JCheckBox saveHistory = new JCheckBox("Save conversation history");
    private final JCheckBox restrict = new JCheckBox();
    private final JCheckBox clipboard = new JCheckBox("Enable Clipboard Access");
    private final JCheckBox autoAccept = new JCheckBox("Automatically accept file changes");
    private final AiMessagingSettingsPanel messaging;
    private final WebRequestAccessSettingsPanel web;
    private final DatabaseAccessSettingsPanel database;

    public AiSessionConfigPanel(AiSessionConfigPanelMode mode) {
        this.mode = mode;
        boolean sessionLabels = mode != AiSessionConfigPanelMode.GLOBAL;
        messaging = new AiMessagingSettingsPanel(sessionLabels);
        web = new WebRequestAccessSettingsPanel(sessionLabels);
        database = new DatabaseAccessSettingsPanel(sessionLabels);
        restrict.setText(sessionLabels ? AccessControlLabelEnum.RESTRICT_TO_PROJECT_FILES.displayLabel()
                : AccessControlLabelEnum.RESTRICT_TO_PROJECT_FILES.globalLabel());
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        addRow(c, 0, new JLabel("Max history:"), maxHistory);
        if (mode != AiSessionConfigPanelMode.GLOBAL) {
            addFull(c, 1, saveHistory);
        }
        addFull(c, mode == AiSessionConfigPanelMode.GLOBAL ? 1 : 2, restrict);
        addFull(c, mode == AiSessionConfigPanelMode.GLOBAL ? 2 : 3, autoAccept);
        addFull(c, mode == AiSessionConfigPanelMode.GLOBAL ? 3 : 4, web);
        addFull(c, mode == AiSessionConfigPanelMode.GLOBAL ? 4 : 5, database);
        addFull(c, mode == AiSessionConfigPanelMode.GLOBAL ? 5 : 6, clipboard);
        addFull(c, mode == AiSessionConfigPanelMode.GLOBAL ? 6 : 7, messaging);
    }

    public AiSessionConfigPanelMode mode() {
        return mode;
    }

    public void addChangeListener(ActionListener listener) {
        maxHistory.addChangeListener(e -> listener.actionPerformed(null));
        saveHistory.addActionListener(listener);
        restrict.addActionListener(listener);
        autoAccept.addActionListener(listener);
        clipboard.addActionListener(listener);
        messaging.addChangeListener(listener);
        web.addChangeListener(listener);
        database.addChangeListener(listener);
        database.addRowLimitChangeListener(e -> listener.actionPerformed(null));
    }

    public void loadGlobal() {
        require(AiSessionConfigPanelMode.GLOBAL);
        loadValues(globalSnapshot());
    }

    public void applyGlobal() {
        require(AiSessionConfigPanelMode.GLOBAL);
        AiSessionSettings values = snapshot();
        PluginSettings.setMaxHistory(values.maxHistory());
        PluginSettings.setRestrictToProjectFiles(values.restrictToProjectFiles());
        PluginSettings.setAllowInterAiComms(values.allowInterAiComms());
        PluginSettings.setAutoNotifyInbox(values.autoNotifyInbox());
        PluginSettings.setAllowImportantMessages(values.allowImportantMessages());
        PluginSettings.setAutoAccept(values.autoAccept());
        PluginSettings.setAllowWebRequests(values.allowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            PluginSettings.setAllowWebRequestAccess(option, values.allowWebRequestAccess(option));
        }
        PluginSettings.setAllowDatabaseAccess(values.allowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            PluginSettings.setAllowDatabaseAccessOption(option, values.allowDatabaseAccessOption(option));
        }
        PluginSettings.setDatabaseRowLimit(values.databaseRowLimit());
        PluginSettings.setEnableClipboardAccess(values.enableClipboardAccess());
    }

    public void loadSession(AiSessionSettings settings) {
        requireNotGlobal();
        loadValues(settings);
    }

    public void applySession(AiSessionSettings settings) {
        requireNotGlobal();
        apply(snapshot(), settings);
    }

    public AiSessionSettings snapshot() {
        AiSessionSettings result = new AiSessionSettings();
        result.setMaxHistory((Integer) maxHistory.getValue());
        result.setSaveHistory(saveHistory.isSelected());
        result.setRestrictToProjectFiles(restrict.isSelected());
        result.setAutoAccept(autoAccept.isSelected());
        result.setEnableClipboardAccess(clipboard.isSelected());
        result.setAllowInterAiComms(messaging.isAllowInterAiSelected());
        result.setAutoNotifyInbox(messaging.isAutoNotifySelected());
        result.setAllowImportantMessages(messaging.isAllowImportantSelected());
        result.setAllowWebRequests(web.isAllowWebRequestsSelected());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            result.setAllowWebRequestAccess(option, web.isOptionSelected(option));
        }
        result.setAllowDatabaseAccess(database.isAllowDatabaseAccessSelected());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            result.setAllowDatabaseAccessOption(option, database.isOptionSelected(option));
        }
        result.setDatabaseRowLimit(database.getRowLimitValue());
        return result;
    }

    private void loadValues(AiSessionSettings settings) {
        maxHistory.setValue(settings.effectiveMaxHistory());
        saveHistory.setSelected(settings.effectiveSaveHistory());
        restrict.setSelected(settings.effectiveRestrictToProjectFiles());
        autoAccept.setSelected(settings.effectiveAutoAccept());
        clipboard.setSelected(settings.effectiveEnableClipboardAccess());
        messaging.setAllowInterAiSelected(settings.effectiveAllowInterAiComms());
        messaging.setAutoNotifySelected(settings.effectiveAutoNotifyInbox());
        messaging.setAllowImportantSelected(settings.effectiveAllowImportantMessages());
        web.setAllowWebRequestsSelected(settings.effectiveAllowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            web.setOptionSelected(option, settings.effectiveAllowWebRequestAccess(option));
        }
        database.setAllowDatabaseAccessSelected(settings.effectiveAllowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            database.setOptionSelected(option, settings.effectiveAllowDatabaseAccessOption(option));
        }
        database.setRowLimitValue(settings.effectiveDatabaseRowLimit());
    }

    private void require(AiSessionConfigPanelMode expected) {
        if (mode != expected) {
            throw new IllegalStateException("Expected " + expected + " mode");
        }
    }

    private void requireNotGlobal() {
        if (mode == AiSessionConfigPanelMode.GLOBAL) {
            throw new IllegalStateException("Global panel does not bind sessions");
        }
    }

    private void addRow(GridBagConstraints c, int row, JLabel label, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.gridwidth = 1;
        add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        add(field, c);
    }

    private void addFull(GridBagConstraints c, int row, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        add(field, c);
        c.gridwidth = 1;
    }
}
