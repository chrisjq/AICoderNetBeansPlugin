package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.TitledBorder;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.OpenAiClientSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.OpenAiContextSettingsPanel;

/**
 * Shared controls for global defaults, a session override, and a template snapshot.
 */
public final class AiSessionConfigPanel extends JPanel {

    private static void apply(AiSessionSettings source, AiSessionSettings target) {
        target.setMaxHistory(source.maxHistory());
        target.setSaveHistory(source.saveHistory());
        target.setRestrictToProjectFiles(source.restrictToProjectFiles());
        target.setAutoAccept(source.autoAccept());
        target.setEnableClipboardAccess(source.enableClipboardAccess());
        target.setAllowInterAiComms(source.allowInterAiComms());
        target.setAutoNotifyInbox(source.autoNotifyInbox());
        target.setAllowImportantMessages(source.allowImportantMessages());
        target.setAllowIdleWatcherTimer(source.allowIdleWatcherTimer());
        target.setMcpSteering(source.mcpSteering());

        target.setAllowWebRequests(source.allowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            target.setAllowWebRequestAccess(option, source.allowWebRequestAccess(option));
        }
        target.setAllowDatabaseAccess(source.allowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            target.setAllowDatabaseAccessOption(option, source.allowDatabaseAccessOption(option));
        }
        target.setAllowGitAccess(source.allowGitAccess());
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            target.setAllowGitAccessOption(option, source.allowGitAccessOption(option));
        }
        target.setDatabaseRowLimit(source.databaseRowLimit());
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

    private static void addRowTo(JPanel panel, GridBagConstraints c, int row, JLabel label, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.gridwidth = 1;
        panel.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    private static void addFullTo(JPanel panel, GridBagConstraints c, int row, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(field, c);
        c.gridwidth = 1;
    }

    private final AiSessionConfigPanelMode mode;
    private final JSpinner maxHistory = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 10));
    private final JCheckBox saveHistory = new JCheckBox("Save conversation history");
    private final JCheckBox restrict = new JCheckBox();
    private final JCheckBox clipboard = new JCheckBox(AccessControlLabelEnum.ALLOW_CLIPBOARD_ACCESS.displayLabel());
    private final JCheckBox autoAccept = new JCheckBox("Automatically accept file changes");
    // TEMPLATE mode only: a template edits every config setting directly. A live SESSION edits them through
    // mcpTools, beneath each group's switch, and GLOBAL edits none, so these are null there rather than built unseen.
    private final AiMessagingSettingsPanel messaging;
    private final WebRequestAccessSettingsPanel web;
    private final DatabaseAccessSettingsPanel database;
    private final GitAccessSettingsPanel git;
    // TEMPLATE and SESSION modes; null in GLOBAL.
    private final McpSteeringSettingsPanel mcpSteering;
    private final OpenAiContextSettingsPanel contextPanel;
    private final JPanel typePanelHolder = new JPanel(new BorderLayout());
    @SuppressWarnings("rawtypes")
    private AiSessionCreateSettingsPanel currentTypePanel;
    private Class<? extends AiSessionSettings> currentTypePanelSettingsClass;
    /**
     * The per-group MCP switches with each group's config settings beneath them. Non-null only in
     * {@link AiSessionConfigPanelMode#SESSION} mode.
     */
    private McpToolSettingsPanel mcpTools;
    private ActionListener changeListener;

    // Non-null only in non-GLOBAL modes — group wrappers for show/hide control
    private JPanel sessionContextGroup;
    private JPanel sessionTypeGroup;
    private TitledBorder sessionTypeBorder;

    public AiSessionConfigPanel(AiSessionConfigPanelMode mode) {
        this.mode = mode;
        boolean sessionLabels = mode != AiSessionConfigPanelMode.GLOBAL;
        boolean template = mode == AiSessionConfigPanelMode.TEMPLATE;
        messaging = template ? new AiMessagingSettingsPanel() : null;
        web = template ? new WebRequestAccessSettingsPanel() : null;
        database = template ? new DatabaseAccessSettingsPanel() : null;
        git = template ? new GitAccessSettingsPanel() : null;
        mcpSteering = sessionLabels ? new McpSteeringSettingsPanel() : null;
        // Templates are type-agnostic and must expose all editable settings; a SESSION shows the switch only for a type
        // that supports MCP steering (loadSession(settings, aiType) decides).
        if (mcpSteering != null) {
            mcpSteering.setVisible(template);
        }
        restrict.setText(AccessControlLabelEnum.RESTRICT_TO_PROJECT_FILES.displayLabel());
        setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        contextPanel = new OpenAiContextSettingsPanel();

        if (mode == AiSessionConfigPanelMode.GLOBAL) {
            // Only the OpenAI context defaults are global now. The note that session defaults live in the Default config
            // template and tool groups in MCP templates is shown once, by the Options panel's banner.
            JPanel openAiGroup = buildGroupPanel("OpenAI Compatible Settings");
            GridBagConstraints oc = groupConstraints();
            addFullTo(openAiGroup, oc, 0, contextPanel);
            addFull(c, 0, openAiGroup);
        }
        else if (mode == AiSessionConfigPanelMode.TEMPLATE) {
            // General Settings group — visible always
            JPanel generalGroup = buildGroupPanel("General Settings");
            GridBagConstraints gc = groupConstraints();
            addRowTo(generalGroup, gc, 0, new JLabel("Max history:"), maxHistory);
            addFullTo(generalGroup, gc, 1, saveHistory);
            addFullTo(generalGroup, gc, 2, restrict);
            addFullTo(generalGroup, gc, 3, autoAccept);
            addFullTo(generalGroup, gc, 4, web);
            addFullTo(generalGroup, gc, 5, database);
            addFullTo(generalGroup, gc, 6, git);
            addFullTo(generalGroup, gc, 7, clipboard);
            addFullTo(generalGroup, gc, 8, messaging);
            addFullTo(generalGroup, gc, 9, mcpSteering);
            addFull(c, 0, generalGroup);

            // OpenAI Compatible Settings group — hidden until an OpenAI-compatible session is loaded
            sessionContextGroup = buildGroupPanel("OpenAI Compatible Settings");
            GridBagConstraints oc = groupConstraints();
            addFullTo(sessionContextGroup, oc, 0, contextPanel);
            sessionContextGroup.setVisible(false);
            addFull(c, 1, sessionContextGroup);

            // AI-type-specific group — hidden until a typed session is loaded; title set dynamically
            sessionTypeBorder = BorderFactory.createTitledBorder("");
            sessionTypeGroup = new JPanel(new GridBagLayout());
            sessionTypeGroup.setBorder(sessionTypeBorder);
            GridBagConstraints tc = groupConstraints();
            addFullTo(sessionTypeGroup, tc, 0, typePanelHolder);
            sessionTypeGroup.setVisible(false);
            addFull(c, 2, sessionTypeGroup);
        }
        else {
            // Session Settings group — the generic per-session settings plus the MCP tools panel, which owns the
            // per-group switches together with the web, database, git and clipboard settings of a live session.
            mcpTools = new McpToolSettingsPanel();
            JPanel generalGroup = buildGroupPanel("Session Settings");
            GridBagConstraints gc = groupConstraints();
            addRowTo(generalGroup, gc, 0, new JLabel("Max history:"), maxHistory);
            addFullTo(generalGroup, gc, 1, saveHistory);
            addFullTo(generalGroup, gc, 2, restrict);
            addFullTo(generalGroup, gc, 3, autoAccept);
            addFullTo(generalGroup, gc, 4, mcpSteering);
            addFullTo(generalGroup, gc, 5, mcpTools);
            addFull(c, 0, generalGroup);

            // OpenAI Compatible Settings group — hidden until an OpenAI-compatible session is loaded
            sessionContextGroup = buildGroupPanel("OpenAI Compatible Settings");
            GridBagConstraints oc = groupConstraints();
            addFullTo(sessionContextGroup, oc, 0, contextPanel);
            sessionContextGroup.setVisible(false);
            addFull(c, 1, sessionContextGroup);

            // AI-type-specific group — hidden until a typed session is loaded; title set dynamically
            sessionTypeBorder = BorderFactory.createTitledBorder("");
            sessionTypeGroup = new JPanel(new GridBagLayout());
            sessionTypeGroup.setBorder(sessionTypeBorder);
            GridBagConstraints tc = groupConstraints();
            addFullTo(sessionTypeGroup, tc, 0, typePanelHolder);
            sessionTypeGroup.setVisible(false);
            addFull(c, 2, sessionTypeGroup);
        }
    }

    public AiSessionConfigPanelMode mode() {
        return mode;
    }

    public void addChangeListener(ActionListener listener) {
        this.changeListener = listener;
        maxHistory.addChangeListener(e -> listener.actionPerformed(null));
        saveHistory.addActionListener(listener);
        restrict.addActionListener(listener);
        autoAccept.addActionListener(listener);
        if (mode == AiSessionConfigPanelMode.TEMPLATE) {
            clipboard.addActionListener(listener);
            messaging.addChangeListener(listener);
            web.addChangeListener(listener);
            database.addChangeListener(listener);
            git.addChangeListener(listener);
            database.addRowLimitChangeListener(e -> listener.actionPerformed(null));
        }
        if (mcpSteering != null) {
            mcpSteering.addChangeListener(listener);
        }
        contextPanel.addChangeListener(listener);
        if (currentTypePanel != null) {
            currentTypePanel.addChangeListener(listener);
        }
        if (mode == AiSessionConfigPanelMode.SESSION) {
            mcpTools.addChangeListener(listener);
        }
    }

    public void loadGlobal() {
        require(AiSessionConfigPanelMode.GLOBAL);
        contextPanel.load(new OpenAiClientSessionSettings());
    }

    public void applyGlobal() {
        require(AiSessionConfigPanelMode.GLOBAL);
        OpenAiClientSessionSettings ctx = new OpenAiClientSessionSettings();
        contextPanel.applyTo(ctx);
        PluginSettings.setContextTrimTrigger(ctx.contextTrimTrigger().name());
        PluginSettings.setContextTrimStrategy(ctx.contextTrimStrategy().name());
        PluginSettings.setContextTokenThreshold(ctx.contextTokenThreshold());
        PluginSettings.setContextTrimTargetPercent(ctx.contextTrimTargetPercent());
        PluginSettings.setContextMaxMessages(ctx.contextMaxMessages());
        PluginSettings.setContextPersistOnClose(ctx.contextPersistOnClose());
    }

    public void loadSession(AiSessionSettings settings) {
        requireNotGlobal();
        loadValues(settings);
        // Templates are type-agnostic and must keep exposing this control; per-type gating
        // happens at runtime via session.getType().mcpSteeringSupport().supported().
        // Without an AI type there is nothing to gate on: only a template shows the switch.
        boolean templateMode = mode == AiSessionConfigPanelMode.TEMPLATE;
        mcpSteering.setVisible(templateMode);
        mcpSteering.setEnabled(templateMode);
        boolean isOpenAi = settings instanceof OpenAiClientSessionSettings;
        contextPanel.setVisible(isOpenAi);
        if (sessionContextGroup != null) {
            sessionContextGroup.setVisible(isOpenAi);
        }
        if (isOpenAi) {
            contextPanel.load((OpenAiClientSessionSettings) settings);
        }
        replaceTypePanel(null, settings);
    }

    public void loadSession(AiSessionSettings settings, AiTypeEnum aiType) {
        requireNotGlobal();
        loadValues(settings);
        boolean mcpSteeringSupported = aiType != null && aiType.mcpSteeringSupport().supported();
        mcpSteering.setVisible(mcpSteeringSupported);
        mcpSteering.setEnabled(mcpSteeringSupported);
        boolean isOpenAi = aiType != null && aiType.isOpenAiCompatible();
        contextPanel.setVisible(isOpenAi);
        if (sessionContextGroup != null) {
            sessionContextGroup.setVisible(isOpenAi);
        }
        if (isOpenAi && settings instanceof OpenAiClientSessionSettings o) {
            contextPanel.load(o);
        }
        replaceTypePanel(aiType, settings);
    }

    public void applySession(AiSessionSettings settings) {
        requireNotGlobal();
        AiSessionSettings values = snapshot();
        apply(values, settings);
        if (mode == AiSessionConfigPanelMode.SESSION) {
            settings.setMcpToolConfig(values.mcpToolConfig());
        }
        if (contextPanel != null && contextPanel.isVisible()
                && settings instanceof OpenAiClientSessionSettings) {
            contextPanel.applyTo((OpenAiClientSessionSettings) settings);
        }
        if (currentTypePanel != null && currentTypePanelSettingsClass != null
                && currentTypePanelSettingsClass.isInstance(settings)) {
            currentTypePanel.applyTo(settings);
        }
    }

    public void dispose() {
        if (currentTypePanel != null) {
            currentTypePanel.dispose();
            currentTypePanel = null;
            currentTypePanelSettingsClass = null;
        }
    }

    public AiSessionSettings snapshot() {
        AiSessionSettings result = new AiSessionSettings();
        result.setMaxHistory((Integer) maxHistory.getValue());
        result.setSaveHistory(saveHistory.isSelected());
        result.setRestrictToProjectFiles(restrict.isSelected());
        result.setAutoAccept(autoAccept.isSelected());
        if (mcpSteering != null) {
            result.setMcpSteering(mcpSteering.isMcpSteeringSelected());
        }

        if (mode == AiSessionConfigPanelMode.SESSION) {
            result.setAllowInterAiComms(mcpTools.isAllowInterAiCommsSelected());
            result.setAutoNotifyInbox(mcpTools.isAutoNotifySelected());
            result.setAllowImportantMessages(mcpTools.isAllowImportantSelected());
            result.setAllowIdleWatcherTimer(mcpTools.isAllowIdleWatcherSelected());
            result.setMcpToolConfig(mcpTools.selectedConfig());
            result.setAllowWebRequests(mcpTools.isAllowWebRequestsSelected());
            for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
                result.setAllowWebRequestAccess(option, mcpTools.isWebOptionSelected(option));
            }
            result.setAllowDatabaseAccess(mcpTools.isAllowDatabaseAccessSelected());
            for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
                result.setAllowDatabaseAccessOption(option, mcpTools.isDatabaseOptionSelected(option));
            }
            result.setDatabaseRowLimit(mcpTools.getDatabaseRowLimit());
            result.setAllowGitAccess(mcpTools.isAllowGitAccessSelected());
            for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
                result.setAllowGitAccessOption(option, mcpTools.isGitOptionSelected(option));
            }
            result.setEnableClipboardAccess(mcpTools.isClipboardAccessSelected());
        }
        else if (mode == AiSessionConfigPanelMode.TEMPLATE) {
            result.setAllowInterAiComms(messaging.isAllowInterAiSelected());
            result.setAutoNotifyInbox(messaging.isAutoNotifySelected());
            result.setAllowImportantMessages(messaging.isAllowImportantSelected());
            result.setAllowIdleWatcherTimer(messaging.isAllowIdleWatcherSelected());
            result.setAllowWebRequests(web.isAllowWebRequestsSelected());
            for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
                result.setAllowWebRequestAccess(option, web.isOptionSelected(option));
            }
            result.setAllowDatabaseAccess(database.isAllowDatabaseAccessSelected());
            for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
                result.setAllowDatabaseAccessOption(option, database.isOptionSelected(option));
            }
            result.setAllowGitAccess(git.isAllowGitAccessSelected());
            for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
                result.setAllowGitAccessOption(option, git.isOptionSelected(option));
            }
            result.setDatabaseRowLimit(database.getRowLimitValue());
            result.setEnableClipboardAccess(clipboard.isSelected());
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void replaceTypePanel(AiTypeEnum aiType, AiSessionSettings settings) {
        if (currentTypePanel != null) {
            currentTypePanel.dispose();
            typePanelHolder.removeAll();
            currentTypePanel = null;
            currentTypePanelSettingsClass = null;
        }
        if (aiType == null) {
            typePanelHolder.setVisible(false);
            typePanelHolder.revalidate();
            typePanelHolder.repaint();
            if (sessionTypeGroup != null) {
                sessionTypeGroup.setVisible(false);
                sessionTypeGroup.revalidate();
                sessionTypeGroup.repaint();
            }
            return;
        }
        currentTypePanel = aiType.getSettingsCreator().createSettingsPanel();
        currentTypePanelSettingsClass = settings != null ? settings.getClass() : null;
        currentTypePanel.load(settings);
        if (changeListener != null) {
            currentTypePanel.addChangeListener(changeListener);
        }
        typePanelHolder.add(currentTypePanel.component(), BorderLayout.CENTER);
        typePanelHolder.setVisible(true);
        currentTypePanel.startLoading();
        typePanelHolder.revalidate();
        typePanelHolder.repaint();
        if (sessionTypeGroup != null) {
            sessionTypeBorder.setTitle(aiType.displayName() + " Settings");
            sessionTypeGroup.setVisible(true);
            sessionTypeGroup.revalidate();
            sessionTypeGroup.repaint();
        }
    }

    private void loadValues(AiSessionSettings settings) {
        maxHistory.setValue(settings.effectiveMaxHistory());
        saveHistory.setSelected(settings.effectiveSaveHistory());
        restrict.setSelected(settings.effectiveRestrictToProjectFiles());
        autoAccept.setSelected(settings.effectiveAutoAccept());
        if (mcpSteering != null) {
            mcpSteering.setMcpSteeringSelected(settings.effectiveMcpSteering());
        }
        if (mode == AiSessionConfigPanelMode.SESSION) {
            mcpTools.load(settings);
            return;
        }
        if (mode != AiSessionConfigPanelMode.TEMPLATE) {
            return;
        }

        messaging.setAllowInterAiSelected(settings.effectiveAllowInterAiComms());
        messaging.setAutoNotifySelected(settings.effectiveAutoNotifyInbox());
        messaging.setAllowImportantSelected(settings.effectiveAllowImportantMessages());
        messaging.setAllowIdleWatcherSelected(settings.effectiveAllowIdleWatcherTimer());
        clipboard.setSelected(settings.effectiveEnableClipboardAccess());
        web.setAllowWebRequestsSelected(settings.effectiveAllowWebRequests());
        database.setAllowDatabaseAccessSelected(settings.effectiveAllowDatabaseAccess());
        git.setAllowGitAccessSelected(settings.effectiveAllowGitAccess());
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            git.setOptionSelected(option, settings.effectiveAllowGitAccessOption(option));
        }
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            web.setOptionSelected(option, settings.effectiveAllowWebRequestAccess(option));
        }
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
