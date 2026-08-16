package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.ScrollablePanel;
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
public class PluginSettingsPanel extends JPanel implements Scrollable {

    static final String PROP_CHANGED = "changed";

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    // General tab controls
    private final AiSessionConfigPanel sessionConfigPanel;
    private final JCheckBox saveHistoryCheck;
    private final JCheckBox saveSessionOnCloseIfTickedCheck;
    private final JCheckBox debugJsonCheck;
    private final JCheckBox debugContextCheck;
    private final JCheckBox logToolUseCheck;
    private final JSpinner mcpPortSpinner;
    private final JSpinner diffContextSpinner;
    private final JSpinner fontSizeSpinner;
    private final JSpinner inboxRetentionSpinner;
    private final JSpinner inboxMaxSizeSpinner;

    // Per-AI tabs discovered via Lookup
    private final List<SettingsTab> aiSettingsTabs;

    // Per-AI enable checkboxes (AiTypeEnum -> JCheckBox)
    private final Map<AiTypeEnum, JCheckBox> aiEnableCheckboxes = new HashMap<>();

    // Per-AI options component (the scrollable CENTER of each tab), whose
    // visibility the enable checkbox toggles.
    private final Map<AiTypeEnum, Component> aiOptionPanels = new HashMap<>();

    public PluginSettingsPanel() {
        setLayout(new BorderLayout());

        JTabbedPane tabs = new JTabbedPane();
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        // ===== General tab (shared options) =====
        JPanel general = new ScrollablePanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        // Plugin Settings group
        JPanel pluginGroup = buildGroupPanel("Plugin Settings");
        GridBagConstraints pg = groupConstraints();
        pg.gridx = 0;
        pg.gridy = 0;
        pg.weightx = 0;
        addTo(pluginGroup, new JLabel("Chat font size (pt):"), pg);
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getChatFontSize(), 8, 24, 1));
        pg.gridx = 1;
        pg.weightx = 1;
        addTo(pluginGroup, fontSizeSpinner, pg);

        pg.gridx = 0;
        pg.gridy = 1;
        pg.gridwidth = 2;
        pg.weightx = 1;
        saveHistoryCheck = new JCheckBox("Save conversation history between sessions");
        addTo(pluginGroup, saveHistoryCheck, pg);

        pg.gridy = 2;
        saveSessionOnCloseIfTickedCheck = new JCheckBox("Save Session by default When Closing Tab (If History is Saved) - Don't ask");
        addTo(pluginGroup, saveSessionOnCloseIfTickedCheck, pg);
        pg.gridwidth = 1;

        pg.gridx = 0;
        pg.gridy = 3;
        pg.weightx = 0;
        addTo(pluginGroup, new JLabel("MCP permission port:"), pg);
        mcpPortSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getHookServerPort(), 1024, 65535, 1));
        pg.gridx = 1;
        pg.weightx = 1;
        addTo(pluginGroup, mcpPortSpinner, pg);

        pg.gridx = 0;
        pg.gridy = 4;
        pg.weightx = 0;
        addTo(pluginGroup, new JLabel("Diff context lines:"), pg);
        diffContextSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getDiffContextLines(), 0, 50, 1));
        pg.gridx = 1;
        pg.weightx = 1;
        addTo(pluginGroup, diffContextSpinner, pg);

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        addTo(general, pluginGroup, c);
        c.gridwidth = 1;

        // Inbox Settings group
        JPanel inboxGroup = buildGroupPanel("Inbox Settings");
        GridBagConstraints ig = groupConstraints();
        ig.gridx = 0;
        ig.gridy = 0;
        ig.weightx = 0;
        addTo(inboxGroup, new JLabel("Inbox read retention (min, 0=keep):"), ig);
        inboxRetentionSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getInboxRetentionMinutes(), 0, 100000, 5));
        ig.gridx = 1;
        ig.weightx = 1;
        addTo(inboxGroup, inboxRetentionSpinner, ig);

        ig.gridx = 0;
        ig.gridy = 1;
        ig.weightx = 0;
        addTo(inboxGroup, new JLabel("Inbox max size:"), ig);
        inboxMaxSizeSpinner = new JSpinner(new SpinnerNumberModel(
                PluginSettings.getInboxMaxSize(), 1, 100000, 50));
        ig.gridx = 1;
        ig.weightx = 1;
        addTo(inboxGroup, inboxMaxSizeSpinner, ig);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        addTo(general, inboxGroup, c);
        c.gridwidth = 1;

        // Debug Logging group
        JPanel debugGroup = buildGroupPanel("Debug Logging");
        GridBagConstraints dg = groupConstraints();
        dg.gridx = 0;
        dg.gridy = 0;
        dg.gridwidth = 2;
        debugJsonCheck = new JCheckBox("Log raw JSON to NetBeans log (for debugging session % and token counts)");
        addTo(debugGroup, debugJsonCheck, dg);

        dg.gridy = 1;
        debugContextCheck = new JCheckBox("Log context history changes to NetBeans log (for debugging AI memory and trimming)");
        addTo(debugGroup, debugContextCheck, dg);

        dg.gridy = 2;
        logToolUseCheck = new JCheckBox("Log tool use to NetBeans log (Tool Used: [tool] arg[value]...)");
        addTo(debugGroup, logToolUseCheck, dg);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.weightx = 1;
        addTo(general, debugGroup, c);
        c.gridwidth = 1;

        // Shared generic defaults use the same component as sessions and templates.
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.weightx = 1;
        sessionConfigPanel = new AiSessionConfigPanel(AiSessionConfigPanelMode.GLOBAL);
        addTo(general, sessionConfigPanel, c);
        c.gridwidth = 1;

        // Filler for general tab
        c.gridx = 0;
        c.gridy = 4;
        c.weightx = 1;
        c.weighty = 1;
        c.gridwidth = 2;
        addTo(general, Box.createVerticalGlue(), c);
        c.gridwidth = 1;
        c.weighty = 0;

        JPanel generalTab = new JPanel(new BorderLayout());
        generalTab.add(makeDefaultsBanner(), BorderLayout.NORTH);
        generalTab.add(wrapTab(general), BorderLayout.CENTER);
        tabs.addTab("General", generalTab);

        // ===== Per-AI tabs (discovered via Lookup) =====
        Collection<? extends SettingsTab> aiTabs
                = Lookup.getDefault().lookupAll(SettingsTab.class);
        for (SettingsTab tab : aiTabs) {
            if (tab.getAiType().isImplemented()) {
                // Build wrapper with enable checkbox at NORTH and scrollable options in CENTER
                JPanel aiWrapper = new JPanel(new BorderLayout());
                JCheckBox enableBox = new JCheckBox("Enable " + tab.getTabTitle());
                aiEnableCheckboxes.put(tab.getAiType(), enableBox);

                Component optionsScroll = wrapTab(tab.getComponent());
                aiOptionPanels.put(tab.getAiType(), optionsScroll);
                aiWrapper.add(enableBox, BorderLayout.NORTH);
                aiWrapper.add(optionsScroll, BorderLayout.CENTER);

                // Initial visibility is set in load(); toggling updates visibility,
                // reflows the tab (revalidate/repaint so the freed space is
                // reclaimed immediately) and marks the panel changed.
                enableBox.addActionListener(e -> {
                    optionsScroll.setVisible(enableBox.isSelected());
                    aiWrapper.revalidate();
                    aiWrapper.repaint();
                    fireChanged();
                });

                tabs.addTab(tab.getTabTitle(), aiWrapper);
                tab.addPropertyChangeListener(e -> fireChanged());
            }
        }
        this.aiSettingsTabs = List.copyOf(aiTabs);

        add(tabs, BorderLayout.CENTER);

        // Listeners (attached once)
        saveHistoryCheck.addActionListener(e -> fireChanged());
        saveSessionOnCloseIfTickedCheck.addActionListener(e -> fireChanged());
        debugJsonCheck.addActionListener(e -> fireChanged());
        debugContextCheck.addActionListener(e -> fireChanged());
        logToolUseCheck.addActionListener(e -> fireChanged());
        mcpPortSpinner.addChangeListener(e -> fireChanged());
        diffContextSpinner.addChangeListener(e -> fireChanged());
        fontSizeSpinner.addChangeListener(e -> fireChanged());
        sessionConfigPanel.addChangeListener(e -> fireChanged());
        inboxRetentionSpinner.addChangeListener(e -> fireChanged());
        inboxMaxSizeSpinner.addChangeListener(e -> fireChanged());
    }

    private Component makeDefaultsBanner() {
        JLabel banner = new JLabel("<html><body style='width:520px'>"
                + "<b>Manage AI sessions via Tools &rarr; AI Manager.</b><br><br>"
                + "<i>The settings below are global defaults applied when a new AI session is "
                + "created &mdash; changing them here affects only newly-created sessions, "
                + "not existing ones.</i>"
                + "</body></html>");
        banner.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 4, 10));
        return banner;
    }

    private Component wrapTab(Component component) {
        ScrollablePanel container = new ScrollablePanel(new BorderLayout());
        container.add(component, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(container);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
    }

    // Helper to reduce repetition in layout
    private void addTo(JPanel parent, Component comp, GridBagConstraints c) {
        parent.add(comp, c);
    }

    private static JPanel buildGroupPanel(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static GridBagConstraints groupConstraints() {
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        return gc;
    }

    void load() {
        sessionConfigPanel.loadGlobal();
        saveHistoryCheck.setSelected(PluginSettings.isSaveHistory());
        saveSessionOnCloseIfTickedCheck.setSelected(PluginSettings.isSaveSessionOnCloseIfTicked());
        debugJsonCheck.setSelected(PluginSettings.isDebugJson());
        debugContextCheck.setSelected(PluginSettings.isDebugContext());
        logToolUseCheck.setSelected(PluginSettings.isLogToolUse());
        mcpPortSpinner.setValue(PluginSettings.getHookServerPort());
        diffContextSpinner.setValue(PluginSettings.getDiffContextLines());
        fontSizeSpinner.setValue(PluginSettings.getChatFontSize());
        inboxRetentionSpinner.setValue(PluginSettings.getInboxRetentionMinutes());
        inboxMaxSizeSpinner.setValue(PluginSettings.getInboxMaxSize());
        for (SettingsTab tab : aiSettingsTabs) {
            tab.load();
        }

        // Initialize per-AI enable checkboxes and apply visibility to their option panes
        for (Map.Entry<AiTypeEnum, JCheckBox> e : aiEnableCheckboxes.entrySet()) {
            AiTypeEnum type = e.getKey();
            boolean enabled = PluginSettings.isAiEnabled(type);
            e.getValue().setSelected(enabled);
            Component options = aiOptionPanels.get(type);
            if (options != null) {
                options.setVisible(enabled);
            }
        }
    }

    void store() {
        sessionConfigPanel.applyGlobal();
        PluginSettings.setSaveHistory(saveHistoryCheck.isSelected());
        PluginSettings.setSaveSessionOnCloseIfTicked(saveSessionOnCloseIfTickedCheck.isSelected());
        PluginSettings.setDebugJson(debugJsonCheck.isSelected());
        PluginSettings.setDebugContext(debugContextCheck.isSelected());
        PluginSettings.setLogToolUse(logToolUseCheck.isSelected());
        PluginSettings.setHookServerPort((Integer) mcpPortSpinner.getValue());
        PluginSettings.setDiffContextLines((Integer) diffContextSpinner.getValue());
        PluginSettings.setChatFontSize((Integer) fontSizeSpinner.getValue());
        PluginSettings.setInboxRetentionMinutes((Integer) inboxRetentionSpinner.getValue());
        PluginSettings.setInboxMaxSize((Integer) inboxMaxSizeSpinner.getValue());
        for (SettingsTab tab : aiSettingsTabs) {
            tab.store();
        }

        // Persist per-AI enabled flags
        for (Map.Entry<AiTypeEnum, JCheckBox> e : aiEnableCheckboxes.entrySet()) {
            PluginSettings.setAiEnabled(e.getKey(), e.getValue().isSelected());
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

    // ===== Scrollable =====
    // The NetBeans Options dialog places this panel inside its own JScrollPane.
    // Tracking the viewport height keeps the panel exactly viewport-sized, so
    // that outer scrollbar never engages and the JTabbedPane header stays fixed;
    // content overflow is handled by each tab's own inner JScrollPane (wrapTab).
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(visibleRect.height - 16, 16);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return true;
    }
}
