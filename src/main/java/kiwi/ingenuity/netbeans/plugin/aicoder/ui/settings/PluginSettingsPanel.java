package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Collection;
import java.util.List;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.Lookup;

/**
 * Options panel for the AI Coder plugin. Shown in Tools > Options > AI Coder
 * Code.
 *
 * Structured with a General tab (shared across AIs) followed by per-AI tabs.
 * Per-AI tabs are discovered via Lookup ({@link SettingsTab}), so each AI
 * implementation contributes its own settings tab.
 */
public class PluginSettingsPanel extends JPanel {

    static final String PROP_CHANGED = "changed";

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    // General tab controls
    private final JSpinner maxHistorySpinner;
    private final JCheckBox saveHistoryCheck;
    private final JCheckBox debugJsonCheck;
    private final JCheckBox logToolUseCheck;
    private final JSpinner mcpPortSpinner;
    private final JSpinner diffContextSpinner;
    private final JSpinner fontSizeSpinner;
    private final JCheckBox restrictToProjectCheckBox;
    private final JCheckBox allowInterAiCheckBox;
    private final JCheckBox autoNotifyInboxCheckBox;
    private final JCheckBox allowImportantMessagesCheckBox;
    private final JSpinner inboxRetentionSpinner;
    private final JSpinner inboxMaxSizeSpinner;

    // Per-AI tabs discovered via Lookup
    private final List<SettingsTab> aiSettingsTabs;

    public PluginSettingsPanel() {
        setLayout(new BorderLayout());

        JTabbedPane tabs = new JTabbedPane();

        // ===== General tab (shared options) =====
        JPanel general = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        // Chat font size
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        addTo(general, new JLabel("Chat font size (pt):"), c);
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getChatFontSize(), 8, 24, 1));
        c.gridx = 1;
        c.weightx = 0;
        addTo(general, fontSizeSpinner, c);

        // Max history
        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        addTo(general, new JLabel("Max history:"), c);
        maxHistorySpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getMaxHistory(), 0, 10000, 10));
        c.gridx = 1;
        c.weightx = 0;
        addTo(general, maxHistorySpinner, c);

        // Save history
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        saveHistoryCheck = new JCheckBox("Save conversation history between sessions");
        addTo(general, saveHistoryCheck, c);
        c.gridwidth = 1;

        // Debug JSON
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        debugJsonCheck = new JCheckBox("Log raw JSON to NetBeans log (for debugging session % and token counts)");
        addTo(general, debugJsonCheck, c);
        c.gridwidth = 1;

        // Log tool use
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        logToolUseCheck = new JCheckBox("Log tool use to NetBeans log (Tool Used: [tool] arg[value]...)");
        addTo(general, logToolUseCheck, c);
        c.gridwidth = 1;

        // MCP port
        c.gridx = 0;
        c.gridy = 5;
        c.weightx = 0;
        addTo(general, new JLabel("MCP permission port:"), c);
        mcpPortSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getHookServerPort(), 1024, 65535, 1));
        c.gridx = 1;
        c.weightx = 0;
        addTo(general, mcpPortSpinner, c);

        // Diff context
        c.gridx = 0;
        c.gridy = 6;
        c.weightx = 0;
        addTo(general, new JLabel("Diff context lines:"), c);
        diffContextSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getDiffContextLines(), 0, 50, 1));
        c.gridx = 1;
        c.weightx = 0;
        addTo(general, diffContextSpinner, c);

        // Restrict to project files
        c.gridx = 0;
        c.gridy = 7;
        c.gridwidth = 2;
        restrictToProjectCheckBox = new JCheckBox("Restrict AI to project files by default");
        addTo(general, restrictToProjectCheckBox, c);
        c.gridwidth = 1;

        // Allow inter-AI communication
        c.gridx = 0;
        c.gridy = 8;
        c.gridwidth = 2;
        allowInterAiCheckBox = new JCheckBox("Allow inter-AI communication by default");
        addTo(general, allowInterAiCheckBox, c);
        c.gridwidth = 1;

        // Auto-notify inbox
        c.gridx = 0;
        c.gridy = 9;
        c.gridwidth = 2;
        autoNotifyInboxCheckBox = new JCheckBox("Auto-notify AI sessions on inbox messages");
        addTo(general, autoNotifyInboxCheckBox, c);
        c.gridwidth = 1;

        // Allow important messages
        c.gridx = 0;
        c.gridy = 10;
        c.gridwidth = 2;
        allowImportantMessagesCheckBox = new JCheckBox("Allow important messages (interrupt receiving session)");
        addTo(general, allowImportantMessagesCheckBox, c);
        c.gridwidth = 1;

        // Inbox read-message retention (minutes, 0 = keep until deleted)
        c.gridx = 0;
        c.gridy = 11;
        c.gridwidth = 1;
        c.weightx = 0;
        addTo(general, new JLabel("Inbox read retention (min, 0=keep):"), c);
        inboxRetentionSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getInboxRetentionMinutes(), 0, 100000, 5));
        c.gridx = 1;
        addTo(general, inboxRetentionSpinner, c);

        // Inbox max size
        c.gridx = 0;
        c.gridy = 12;
        addTo(general, new JLabel("Inbox max size:"), c);
        inboxMaxSizeSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getInboxMaxSize(), 1, 100000, 50));
        c.gridx = 1;
        addTo(general, inboxMaxSizeSpinner, c);

        // Filler for general tab
        c.gridx = 0;
        c.gridy = 13;
        c.weighty = 1;
        c.gridwidth = 2;
        addTo(general, Box.createVerticalGlue(), c);
        c.gridwidth = 1;
        c.weighty = 0;

        tabs.addTab("General", general);

        // ===== Per-AI tabs (discovered via Lookup) =====
        Collection<? extends SettingsTab> aiTabs
                = Lookup.getDefault().lookupAll(SettingsTab.class);
        for (SettingsTab tab : aiTabs) {
            if (tab.getAiType().isImplemented()) {
                tabs.addTab(tab.getTabTitle(), tab.getComponent());
                tab.addPropertyChangeListener(e -> fireChanged());
            }
        }
        this.aiSettingsTabs = List.copyOf(aiTabs);

        add(tabs, BorderLayout.CENTER);

        // Listeners (attached once)
        maxHistorySpinner.addChangeListener(e -> fireChanged());
        saveHistoryCheck.addActionListener(e -> fireChanged());
        debugJsonCheck.addActionListener(e -> fireChanged());
        logToolUseCheck.addActionListener(e -> fireChanged());
        mcpPortSpinner.addChangeListener(e -> fireChanged());
        diffContextSpinner.addChangeListener(e -> fireChanged());
        fontSizeSpinner.addChangeListener(e -> fireChanged());
        restrictToProjectCheckBox.addActionListener(e -> fireChanged());
        allowInterAiCheckBox.addActionListener(e -> fireChanged());
        autoNotifyInboxCheckBox.addActionListener(e -> fireChanged());
        allowImportantMessagesCheckBox.addActionListener(e -> fireChanged());
        inboxRetentionSpinner.addChangeListener(e -> fireChanged());
        inboxMaxSizeSpinner.addChangeListener(e -> fireChanged());
    }

    // Helper to reduce repetition in layout
    private void addTo(JPanel parent, Component comp, GridBagConstraints c) {
        parent.add(comp, c);
    }

    void load() {
        maxHistorySpinner.setValue(PluginSettings.getMaxHistory());
        saveHistoryCheck.setSelected(PluginSettings.isSaveHistory());
        debugJsonCheck.setSelected(PluginSettings.isDebugJson());
        logToolUseCheck.setSelected(PluginSettings.isLogToolUse());
        mcpPortSpinner.setValue(PluginSettings.getHookServerPort());
        diffContextSpinner.setValue(PluginSettings.getDiffContextLines());
        fontSizeSpinner.setValue(PluginSettings.getChatFontSize());
        restrictToProjectCheckBox.setSelected(PluginSettings.isRestrictToProjectFiles());
        allowInterAiCheckBox.setSelected(PluginSettings.isAllowInterAiComms());
        autoNotifyInboxCheckBox.setSelected(PluginSettings.isAutoNotifyInbox());
        allowImportantMessagesCheckBox.setSelected(PluginSettings.isAllowImportantMessages());
        inboxRetentionSpinner.setValue(PluginSettings.getInboxRetentionMinutes());
        inboxMaxSizeSpinner.setValue(PluginSettings.getInboxMaxSize());
        for (SettingsTab tab : aiSettingsTabs) {
            tab.load();
        }
    }

    void store() {
        PluginSettings.setMaxHistory((Integer) maxHistorySpinner.getValue());
        PluginSettings.setSaveHistory(saveHistoryCheck.isSelected());
        PluginSettings.setDebugJson(debugJsonCheck.isSelected());
        PluginSettings.setLogToolUse(logToolUseCheck.isSelected());
        PluginSettings.setHookServerPort((Integer) mcpPortSpinner.getValue());
        PluginSettings.setDiffContextLines((Integer) diffContextSpinner.getValue());
        PluginSettings.setChatFontSize((Integer) fontSizeSpinner.getValue());
        PluginSettings.setRestrictToProjectFiles(restrictToProjectCheckBox.isSelected());
        PluginSettings.setAllowInterAiComms(allowInterAiCheckBox.isSelected());
        PluginSettings.setAutoNotifyInbox(autoNotifyInboxCheckBox.isSelected());
        PluginSettings.setAllowImportantMessages(allowImportantMessagesCheckBox.isSelected());
        PluginSettings.setInboxRetentionMinutes((Integer) inboxRetentionSpinner.getValue());
        PluginSettings.setInboxMaxSize((Integer) inboxMaxSizeSpinner.getValue());
        for (SettingsTab tab : aiSettingsTabs) {
            tab.store();
        }
    }

    boolean isSettingsValid() {
        return aiSettingsTabs.stream().allMatch(SettingsTab::isValid);
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        super.addPropertyChangeListener(l);
    }

    @Override
    public void addPropertyChangeListener(String propertyName, PropertyChangeListener l) {
        pcs.addPropertyChangeListener(propertyName, l);
        super.addPropertyChangeListener(propertyName, l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        super.removePropertyChangeListener(l);
    }

    @Override
    public void removePropertyChangeListener(String propertyName, PropertyChangeListener l) {
        pcs.removePropertyChangeListener(propertyName, l);
        super.removePropertyChangeListener(propertyName, l);
    }

    private void fireChanged() {
        pcs.firePropertyChange(PROP_CHANGED, null, null);
    }
}
