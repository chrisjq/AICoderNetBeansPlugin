package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolAccess;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolCategoryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.McpTemplatePersistenceManager;
import org.openide.util.RequestProcessor;

/**
 * Per-session MCP tool-exposure switches: one checkbox per switchable group, grouped by category, with that group's
 * config settings (§5.6) shown beneath its switch, plus the behaviour settings that gate the collaboration groups and
 * an "Apply MCP template…" action. A group's config settings are hidden — not merely greyed — while the group is off;
 * their stored values are kept, so switching the group on again shows and applies them unchanged.
 */
public final class McpToolSettingsPanel extends JPanel {

    /**
     * Test seam for the destructive template confirmation. The production default displays the prompt on the EDT.
     */
    static volatile BiPredicate<Component, TemplateChoice> templateApplyConfirmation
            = McpToolSettingsPanel::confirmTemplateApplication;

    private static boolean confirmTemplateApplication(Component parent, TemplateChoice choice) {
        return JOptionPane.showConfirmDialog(parent,
                                             "Applying \"" + choice.template().name()
                                             + "\" replaces the tool switches set in this dialog. Continue?",
                                             "Apply MCP template",
                                             JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;
    }

    static void resetTemplateApplyConfirmationForTests() {
        templateApplyConfirmation = McpToolSettingsPanel::confirmTemplateApplication;
    }

    private final McpToolSelectionModel model = new McpToolSelectionModel(McpToolConfig.ALL_ENABLED);
    private final Map<McpToolGroupEnum, JCheckBox> groupBoxes = new EnumMap<>(McpToolGroupEnum.class);
    private final JCheckBox buildQueueBox = new JCheckBox();
    private final JCheckBox allowInterAiBox = new JCheckBox();
    private final JCheckBox autoNotifyBox = new JCheckBox();
    private final JCheckBox allowImportantBox = new JCheckBox();
    private final JCheckBox allowIdleWatcherBox = new JCheckBox();
    private final DatabaseAccessSettingsPanel database;
    private final WebRequestAccessSettingsPanel web;
    private final GitAccessSettingsPanel git;
    private final JCheckBox clipboardBox = new JCheckBox();
    private final Map<McpToolGroupEnum, JPanel> configHolders = new EnumMap<>(McpToolGroupEnum.class);
    private JPanel gitConfigHolder;
    private final List<ActionListener> changeListeners = new ArrayList<>();
    private boolean refreshing;

    public McpToolSettingsPanel() {
        setBorder(BorderFactory.createTitledBorder("MCP Tools"));
        setLayout(new GridBagLayout());

        web = new WebRequestAccessSettingsPanel();
        database = new DatabaseAccessSettingsPanel();
        git = new GitAccessSettingsPanel();
        clipboardBox.setText(AccessControlLabelEnum.ALLOW_CLIPBOARD_ACCESS.displayLabel());

        allowInterAiBox.setText(AccessControlLabelEnum.ALLOW_INTER_AI_COMMS.displayLabel());
        autoNotifyBox.setText(AccessControlLabelEnum.AUTO_NOTIFY_INBOX.displayLabel());
        allowImportantBox.setText(AccessControlLabelEnum.ALLOW_IMPORTANT_MESSAGES.displayLabel());
        allowIdleWatcherBox.setText(AccessControlLabelEnum.ALLOW_IDLE_WATCHER_TIMER.displayLabel());

        int row = 0;
        for (McpToolCategoryEnum category : McpToolCategoryEnum.values()) {
            if (category == McpToolCategoryEnum.ALWAYS_ON) {
                continue;
            }
            JPanel categoryPanel = buildCategoryPanel(category);
            if (categoryPanel != null) {
                addFull(row++, categoryPanel);
            }
        }

        JButton applyTemplate = new JButton("Apply MCP template…");
        applyTemplate.addActionListener(e -> chooseTemplate());
        addFull(row, applyTemplate);

        allowInterAiBox.addActionListener(e -> {
            updateBehaviourEnablement();
            fireChange();
        });
        autoNotifyBox.addActionListener(e -> fireChange());
        allowImportantBox.addActionListener(e -> fireChange());
        allowIdleWatcherBox.addActionListener(e -> fireChange());
        web.addChangeListener(e -> fireChange());
        database.addChangeListener(e -> fireChange());
        database.addRowLimitChangeListener(e -> fireChange());
        git.addChangeListener(e -> fireChange());
        clipboardBox.addActionListener(e -> fireChange());
        updateBehaviourEnablement();
        updateConfigSettingsVisibility();
    }

    public void addChangeListener(ActionListener listener) {
        changeListeners.add(listener);
    }

    public void load(AiSessionSettings settings) {
        model.applyConfig(McpToolAccess.configOf(settings));
        refreshGroupBoxes();
        allowInterAiBox.setSelected(settings.effectiveAllowInterAiComms());
        autoNotifyBox.setSelected(settings.effectiveAutoNotifyInbox());
        allowImportantBox.setSelected(settings.effectiveAllowImportantMessages());
        allowIdleWatcherBox.setSelected(settings.effectiveAllowIdleWatcherTimer());
        updateBehaviourEnablement();
        web.setAllowWebRequestsSelected(settings.effectiveAllowWebRequests());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            web.setOptionSelected(option, settings.effectiveAllowWebRequestAccess(option));
        }
        database.setAllowDatabaseAccessSelected(settings.effectiveAllowDatabaseAccess());
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            database.setOptionSelected(option, settings.effectiveAllowDatabaseAccessOption(option));
        }
        database.setRowLimitValue(settings.effectiveDatabaseRowLimit());
        git.setAllowGitAccessSelected(settings.effectiveAllowGitAccess());
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            git.setOptionSelected(option, settings.effectiveAllowGitAccessOption(option));
        }
        clipboardBox.setSelected(settings.effectiveEnableClipboardAccess());
        updateConfigSettingsVisibility();
    }

    public McpToolConfig selectedConfig() {
        return model.config();
    }

    public boolean isGroupEnabled(McpToolGroupEnum group) {
        return model.isGroupEnabled(group);
    }

    public boolean isAllowInterAiCommsSelected() {
        return allowInterAiBox.isSelected();
    }

    public boolean isAutoNotifySelected() {
        return autoNotifyBox.isSelected();
    }

    public boolean isAllowImportantSelected() {
        return allowImportantBox.isSelected();
    }

    public boolean isAllowIdleWatcherSelected() {
        return allowIdleWatcherBox.isSelected();
    }

    public boolean isAllowWebRequestsSelected() {
        return web.isAllowWebRequestsSelected();
    }

    public boolean isWebOptionSelected(WebRequestAccessOptionEnum option) {
        return web.isOptionSelected(option);
    }

    public boolean isAllowDatabaseAccessSelected() {
        return database.isAllowDatabaseAccessSelected();
    }

    public boolean isDatabaseOptionSelected(DatabaseAccessOptionEnum option) {
        return database.isOptionSelected(option);
    }

    public int getDatabaseRowLimit() {
        return database.getRowLimitValue();
    }

    public boolean isAllowGitAccessSelected() {
        return git.isAllowGitAccessSelected();
    }

    public boolean isGitOptionSelected(GitAccessOptionEnum option) {
        return git.isOptionSelected(option);
    }

    public boolean isClipboardAccessSelected() {
        return clipboardBox.isSelected();
    }

    /**
     * Whether the config-settings holder for {@code group} is currently shown. Groups with no config settings report
     * {@code true}; the two git groups share one holder, shown whenever either is on.
     */
    boolean areConfigSettingsVisibleFor(McpToolGroupEnum group) {
        JPanel holder = configHolders.get(group);
        if (holder != null) {
            return holder.isVisible();
        }
        if (group == McpToolGroupEnum.GIT_READ || group == McpToolGroupEnum.GIT_WRITE) {
            return gitConfigHolder != null && gitConfigHolder.isVisible();
        }
        return true;
    }

    public void applyConfig(McpToolConfig config) {
        model.applyConfig(config);
        refreshGroupBoxes();
        fireChange();
    }

    private JPanel buildCategoryPanel(McpToolCategoryEnum category) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(category.label()));
        int row = 0;
        for (McpToolGroupEnum group : McpToolGroupEnum.values()) {
            if (group.category() != category || group == McpToolGroupEnum.ALWAYS_ON) {
                continue;
            }
            JCheckBox behaviour = null;
            JCheckBox box;
            if (group == McpToolGroupEnum.BUILD_QUEUE) {
                buildQueueBox.setText(group.label());
                buildQueueBox.setEnabled(false);
                box = buildQueueBox;
                groupBoxes.put(group, box);
            }
            else {
                box = new JCheckBox(group.label());
                box.addActionListener(e -> {
                    if (refreshing) {
                        return;
                    }
                    model.setGroupEnabled(group, box.isSelected());
                    refreshDerivedState();
                    fireChange();
                });
                groupBoxes.put(group, box);
            }
            if (group == McpToolGroupEnum.PEER_MESSAGING) {
                behaviour = allowInterAiBox;
            }
            else if (group == McpToolGroupEnum.IDLE_WATCHERS) {
                behaviour = allowIdleWatcherBox;
            }
            addGroupRow(panel, row++, box, behaviour);
            JPanel configHolder = buildConfigHolder(group);
            if (configHolder != null) {
                addFullRow(panel, row++, configHolder);
            }
        }
        if (category == McpToolCategoryEnum.COLLABORATION) {
            addFullRow(panel, row++, autoNotifyBox);
            addFullRow(panel, row++, allowImportantBox);
        }
        if (category == McpToolCategoryEnum.GIT) {
            gitConfigHolder = new JPanel(new BorderLayout());
            gitConfigHolder.add(git, BorderLayout.CENTER);
            addFullRow(panel, row++, gitConfigHolder);
        }
        return row == 0 ? null : panel;
    }

    /**
     * Wraps a group's config settings in the holder that sits directly beneath its MCP switch, so the whole block can
     * be hidden while the group is off. Groups with no config settings return {@code null}.
     */
    private JPanel buildConfigHolder(McpToolGroupEnum group) {
        Component settings;
        if (group == McpToolGroupEnum.DATABASE) {
            settings = database;
        }
        else if (group == McpToolGroupEnum.WEB) {
            settings = web;
        }
        else if (group == McpToolGroupEnum.CLIPBOARD) {
            settings = clipboardBox;
        }
        else {
            return null;
        }
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(settings, BorderLayout.CENTER);
        configHolders.put(group, holder);
        return holder;
    }

    private void updateConfigSettingsVisibility() {
        for (Map.Entry<McpToolGroupEnum, JPanel> entry : configHolders.entrySet()) {
            entry.getValue().setVisible(model.areConfigSettingsVisibleFor(entry.getKey()));
        }
        if (gitConfigHolder != null) {
            gitConfigHolder.setVisible(model.areConfigSettingsVisibleFor(McpToolGroupEnum.GIT_READ)
                    || model.areConfigSettingsVisibleFor(McpToolGroupEnum.GIT_WRITE));
        }
        revalidate();
        repaint();
    }

    private void refreshGroupBoxes() {
        refreshing = true;
        try {
            for (Map.Entry<McpToolGroupEnum, JCheckBox> entry : groupBoxes.entrySet()) {
                if (entry.getKey() != McpToolGroupEnum.BUILD_QUEUE) {
                    entry.getValue().setSelected(model.isGroupEnabled(entry.getKey()));
                }
            }
            refreshDerivedState();
        }
        finally {
            refreshing = false;
        }
    }

    private void refreshDerivedState() {
        buildQueueBox.setSelected(model.isGroupEnabled(McpToolGroupEnum.BUILD_QUEUE));
        updateConfigSettingsVisibility();
    }

    private void updateBehaviourEnablement() {
        boolean comms = allowInterAiBox.isSelected();
        autoNotifyBox.setEnabled(comms);
        allowImportantBox.setEnabled(comms);
    }

    private void chooseTemplate() {
        // The store load takes a cross-process file lock, so it runs off the EDT — on the plugin's shared NetBeans
        // request processor (the same one AiTopComponent uses for its off-EDT work), never the JDK common pool.
        CompletableFuture.supplyAsync(() -> {
            try {
                return new McpTemplatePersistenceManager().loadMcpTemplates();
            }
            catch (IOException ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, RequestProcessor.getDefault()).whenComplete((templates, error) -> SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) {
                return;
            }
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                                  ? error.getCause() : error;
                JOptionPane.showMessageDialog(this, "Could not load MCP templates: " + cause.getMessage(),
                                              "MCP Templates", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (templates.isEmpty()) {
                return;
            }
            TemplateChoice[] choices = templateChoices(templates);
            TemplateChoice choice = (TemplateChoice) JOptionPane.showInputDialog(this, "Apply MCP template",
                                                                                 "MCP Templates",
                                                                                 JOptionPane.PLAIN_MESSAGE, null,
                                                                                 choices, choices[0]);
            if (choice != null && isDisplayable()) {
                applyTemplate(choice);
            }
        }));
    }

    /**
     * Applies a selected MCP template after confirming that it would replace different switches.
     */
    void applyTemplate(TemplateChoice choice) {
        McpToolConfig selected = choice.template().config();
        if (model.config().equals(selected) || templateApplyConfirmation.test(this, choice)) {
            applyConfig(selected);
        }
    }

    static TemplateChoice[] templateChoices(List<McpTemplate> templates) {
        Map<String, Integer> totals = new HashMap<>();
        for (McpTemplate template : templates) {
            totals.merge(template.name(), 1, Integer::sum);
        }
        Map<String, Integer> indexes = new HashMap<>();
        return templates.stream()
                .sorted(Comparator.comparing(McpTemplate::isDefault).reversed()
                        .thenComparing(McpTemplate::name, String.CASE_INSENSITIVE_ORDER))
                .map(template -> {
                    int total = totals.get(template.name());
                    int index = indexes.merge(template.name(), 1, Integer::sum);
                    String label = total == 1 ? template.name() : template.name() + " (" + index + ")";
                    return new TemplateChoice(template, label);
                }).toArray(TemplateChoice[]::new);
    }

    record TemplateChoice(McpTemplate template, String label) {

        @Override
        public String toString() {
            return label;
        }
    }

    private void fireChange() {
        for (ActionListener listener : changeListeners) {
            listener.actionPerformed(null);
        }
    }

    private void addGroupRow(JPanel panel, int row, JCheckBox groupBox, JCheckBox behaviour) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.weightx = behaviour == null ? 1 : 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 4, 2, 4);
        panel.add(groupBox, c);
        if (behaviour != null) {
            c.gridx = 1;
            c.weightx = 1;
            panel.add(behaviour, c);
        }
    }

    private void addFullRow(JPanel panel, int row, Component component) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 4, 2, 4);
        panel.add(component, c);
    }

    private void addFull(int row, Component component) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        add(component, c);
    }

}
