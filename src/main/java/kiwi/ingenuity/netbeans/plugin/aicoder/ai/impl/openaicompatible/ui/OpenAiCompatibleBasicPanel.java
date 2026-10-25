package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ConnectionTester;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.DefaultKeyRules;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.FormLayoutUtil;

/**
 * The shared Basic settings panel for an OpenAI-compatible endpoint (spec §8.1, P0b set): server type, base URL, API
 * key, model, context-window override, tool-calling mode, save-context-on-close and a Test connection button.
 *
 * <p>
 * Built with no session yet (session creation) or with a session's {@link SessionSecrets} (editing). In the new-session
 * case a typed key is held in memory and returned by {@link #pendingApiKey()} so the caller can save it once the
 * session exists; the Keyring is never touched on the EDT.
 */
public final class OpenAiCompatibleBasicPanel implements AiSessionCreateSettingsPanel<OpenAiCompatibleSessionSettings> {

    private static final long AUTO_CONTEXT = 0L;
    private static final String ADVANCED_COLLAPSED = "Advanced ▸";
    private static final String ADVANCED_EXPANDED = "Advanced ▾";

    private final JPanel panel = new JPanel(new GridBagLayout());
    private final JComboBox<ServerTypeEnum> serverTypeCombo = new JComboBox<>(ServerTypeEnum.values());
    private final JTextField baseUrlField = new JTextField(24);
    private final JLabel baseUrlNote = new JLabel(" ");
    private final JLabel baseUrlDefaultLabel = new JLabel(" ");
    private final JPasswordField apiKeyField = new JPasswordField(24);
    private final JLabel keyStateLabel = new JLabel();
    private final JLabel keyWarningLabel = new JLabel(" ");
    private final JButton changeKeyButton = new JButton("Change");
    private final JButton clearKeyButton = new JButton("Clear");
    private final JComboBox<String> modelCombo = new JComboBox<>();
    private final JLabel modelDefaultLabel = new JLabel(" ");
    private final JComboBox<String> summaryModelCombo = new JComboBox<>();
    private final JSpinner contextWindowSpinner = new JSpinner(
            new SpinnerNumberModel((int) AUTO_CONTEXT, 0, Integer.MAX_VALUE, 1024));
    private final JLabel contextDefaultLabel = new JLabel("(default: auto)");
    private final JComboBox<ToolCallingModeEnum> toolCallingCombo = new JComboBox<>(ToolCallingModeEnum.values());
    private final JCheckBox saveContextCheck = new JCheckBox("Save context on close");
    private final JCheckBox showReasoningCheck = new JCheckBox("Show the model's reasoning in the chat");
    private final JComboBox<CompactionStrategyEnum> compactionCombo = new JComboBox<>(
            new CompactionStrategyEnum[]{CompactionStrategyEnum.AUTO, CompactionStrategyEnum.SUMMARISE,
                CompactionStrategyEnum.DROP, CompactionStrategyEnum.NONE});
    private final JCheckBox autoCompactCheck = new JCheckBox("Compact automatically when the context is full");
    private final JCheckBox pruneCheck = new JCheckBox("Shorten old tool output in the model's context");
    private final JButton testButton = new JButton("Test connection");
    private final JLabel statusLabel = new JLabel(" ");
    private final OpenAiCompatibleAdvancedPanel advanced = new OpenAiCompatibleAdvancedPanel();
    private final JButton advancedToggle = new JButton(ADVANCED_COLLAPSED);

    private final SessionSecrets secrets;
    private final HeaderSecretSaver headerSaver;
    private final OpenAiCompatibleLimits limits;
    private char[] pendingApiKey;
    private boolean apiKeySet;
    private Map<String, List<String>> reasoningLevelsByModel = Map.of();
    private boolean updating;
    private boolean ollamaImportChecked;
    private ActionListener changeListener;
    private Consumer<ConnectionTester.Result> testResultListener;

    public OpenAiCompatibleBasicPanel() {
        this(null, OpenAiCompatibleLimits.defaults());
    }

    public OpenAiCompatibleBasicPanel(SessionSecrets secrets) {
        this(secrets, OpenAiCompatibleLimits.defaults());
    }

    public OpenAiCompatibleBasicPanel(SessionSecrets secrets, OpenAiCompatibleLimits limits) {
        this(secrets, limits, secrets == null ? null : secrets::saveHeaderAsync);
    }

    /**
     * Stores one secret header value in the Keyring in the background; replaced by a fake in tests.
     */
    interface HeaderSecretSaver {

        /**
         * @return true when the value was stored and read back
         */
        CompletableFuture<Boolean> save(String name, char[] value);
    }

    OpenAiCompatibleBasicPanel(SessionSecrets secrets, OpenAiCompatibleLimits limits, HeaderSecretSaver headerSaver) {
        this.secrets = secrets;
        this.headerSaver = headerSaver;
        this.limits = limits == null ? OpenAiCompatibleLimits.defaults() : limits;
        buildLayout();
        wireBehaviour();
        refreshKeyState();
    }

    private void buildLayout() {
        baseUrlField.setToolTipText("e.g. http://localhost:11434 or https://host/v1 — both work");
        modelCombo.setEditable(true);
        summaryModelCombo.setEditable(true);
        summaryModelCombo.setToolTipText("The model that writes the summaries when the context is compacted. "
                + "Leave empty to use the chat model.");
        serverTypeCombo.setRenderer((list, value, index, selected, focus)
                -> new JLabel(value == null ? "" : value.displayName()));
        compactionCombo.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.displayName());
            label.setToolTipText(value == null ? null : strategyTooltip(value));
            return label;
        });
        compactionCombo.setToolTipText(strategyTooltip(CompactionStrategyEnum.AUTO));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        int row = 0;
        row = addRow(c, row, "Server type:", serverTypeCombo);
        JPanel baseUrlRow = new JPanel(new BorderLayout(6, 0));
        baseUrlRow.add(baseUrlField, BorderLayout.CENTER);
        JPanel baseUrlNotes = new JPanel(new BorderLayout());
        baseUrlNotes.add(baseUrlNote, BorderLayout.NORTH);
        baseUrlNotes.add(baseUrlDefaultLabel, BorderLayout.SOUTH);
        baseUrlRow.add(baseUrlNotes, BorderLayout.SOUTH);
        row = addRow(c, row, "Base URL:", baseUrlRow);
        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.add(apiKeyField, BorderLayout.CENTER);
        JPanel keyButtons = new JPanel();
        keyButtons.add(keyStateLabel);
        keyButtons.add(changeKeyButton);
        keyButtons.add(clearKeyButton);
        keyRow.add(keyButtons, BorderLayout.EAST);
        row = addRow(c, row, "API key:", keyRow);
        row = addRow(c, row, "", keyWarningLabel);
        JPanel testRow = new JPanel(new BorderLayout(6, 0));
        testRow.add(testButton, BorderLayout.WEST);
        testRow.add(statusLabel, BorderLayout.CENTER);
        row = addRow(c, row, "", testRow);
        JPanel modelRow = new JPanel(new BorderLayout(6, 0));
        modelRow.add(modelCombo, BorderLayout.CENTER);
        modelRow.add(modelDefaultLabel, BorderLayout.SOUTH);
        row = addRow(c, row, "Model:", modelRow);
        row = addRow(c, row, "Reasoning effort:", advanced.reasoningEffortComponent());
        row = addRow(c, row, "", showReasoningCheck);
        row = addRow(c, row, "Summary model:", summaryModelCombo);
        JPanel contextRow = new JPanel(new BorderLayout(6, 0));
        contextRow.add(contextWindowSpinner, BorderLayout.CENTER);
        contextRow.add(contextDefaultLabel, BorderLayout.EAST);
        row = addRow(c, row, "Context window:", contextRow);
        row = addRow(c, row, "Tool calling:", toolCallingCombo);
        row = addRow(c, row, "Compaction:", compactionCombo);
        row = addRow(c, row, "", autoCompactCheck);
        row = addRow(c, row, "", pruneCheck);
        row = addRow(c, row, "", saveContextCheck);
        row = addRow(c, row, "", advancedToggle);
        advanced.component().setVisible(false);
        addRow(c, row, "", advanced.component());
    }

    /**
     * The tooltip of a compaction strategy. Auto drops the oldest messages until summaries exist.
     */
    static String strategyTooltip(CompactionStrategyEnum strategy) {
        return switch (strategy) {
            case AUTO ->
                "Summarises when the window is at least the summarise threshold (16,384 by default), "
                + "otherwise drops the oldest messages.";
            case DROP ->
                "Drops the oldest messages when the context is full";
            case NONE ->
                "Never compacts; the server decides what happens when the context is full";
            case SUMMARISE ->
                "Summarises the oldest messages when the context is full";
        };
    }

    private void toggleAdvanced() {
        boolean show = !advanced.component().isVisible();
        advanced.component().setVisible(show);
        advancedToggle.setText(show ? ADVANCED_EXPANDED : ADVANCED_COLLAPSED);
        panel.revalidate();
        panel.repaint();
    }

    private int addRow(GridBagConstraints c, int row, String label, java.awt.Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(FormLayoutUtil.label(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(field, c);
        return row + 1;
    }

    private void wireBehaviour() {
        Runnable markChange = this::fireChange;
        serverTypeCombo.addActionListener(e -> {
            applyServerTypePreset();
            markChange.run();
        });
        baseUrlField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            refreshKeyState();
            markChange.run();
        }));
        compactionCombo.addActionListener(e -> {
            Object selected = compactionCombo.getSelectedItem();
            if (selected instanceof CompactionStrategyEnum strategy) {
                compactionCombo.setToolTipText(strategyTooltip(strategy));
            }
            markChange.run();
        });
        autoCompactCheck.addActionListener(e -> markChange.run());
        pruneCheck.addActionListener(e -> markChange.run());
        changeKeyButton.addActionListener(e -> onChangeKey());
        clearKeyButton.addActionListener(e -> onClearKey());
        apiKeyField.addActionListener(e -> {
            pendingApiKey = apiKeyField.getPassword();
            markChange.run();
        });
        apiKeyField.getDocument().addDocumentListener(new SimpleDocumentListener(this::refreshKeyWarning));
        modelCombo.addActionListener(e -> {
            refreshReasoningEffortLevels(levelsForSelectedModel());
            markChange.run();
        });
        summaryModelCombo.addActionListener(e -> markChange.run());
        contextWindowSpinner.addChangeListener(e -> markChange.run());
        toolCallingCombo.addActionListener(e -> markChange.run());
        saveContextCheck.addActionListener(e -> markChange.run());
        showReasoningCheck.addActionListener(e -> markChange.run());
        testButton.addActionListener(e -> runTestConnection());
        advancedToggle.addActionListener(e -> toggleAdvanced());
    }

    @Override
    public JComponent component() {
        return panel;
    }

    @Override
    public void load(OpenAiCompatibleSessionSettings settings) {
        updating = true;
        try {
            serverTypeCombo.setSelectedItem(settings.effectiveServerType());
            baseUrlField.setText(settings.effectiveBaseUrl());
            apiKeySet = settings.effectiveApiKeySet();
            modelCombo.setSelectedItem(settings.effectiveModel());
            summaryModelCombo.setSelectedItem(settings.summaryModel() == null ? "" : settings.summaryModel());
            contextWindowSpinner.setValue(settings.effectiveContextWindowOverride());
            toolCallingCombo.setSelectedItem(settings.effectiveToolCallingMode());
            saveContextCheck.setSelected(settings.effectiveSaveContextOnClose());
            showReasoningCheck.setSelected(settings.effectiveShowReasoning());
            CompactionStrategyEnum strategy = settings.effectiveCompactionStrategy();
            compactionCombo.setSelectedItem(strategy);
            autoCompactCheck.setSelected(settings.effectiveAutoCompact());
            pruneCheck.setSelected(settings.effectivePrune());
            baseUrlNote.setText(" ");
            baseUrlDefaultLabel.setText("(default: " + defaultOrNone(OpenAiCompatiblePluginSettings.getBaseUrl()) + ")");
            modelDefaultLabel.setText("(default: " + defaultOrNone(OpenAiCompatiblePluginSettings.getModel()) + ")");
        }
        finally {
            updating = false;
        }
        advanced.load(settings);
        refreshKeyState();
    }

    private static String defaultOrNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    @Override
    public void applyTo(OpenAiCompatibleSessionSettings settings) {
        settings.setServerType((ServerTypeEnum) serverTypeCombo.getSelectedItem());

        String raw = baseUrlField.getText();
        boolean removedV1 = BaseUrls.removedV1(raw);
        settings.setBaseUrl(BaseUrls.normalise(raw));
        if (removedV1) {
            baseUrlNote.setText("/v1 removed — the plugin adds it");
        }

        Object model = modelCombo.isEditable() && modelCombo.getEditor() != null
                       ? modelCombo.getEditor().getItem() : modelCombo.getSelectedItem();
        settings.setModel(model == null ? null : model.toString());

        Object summaryModel = summaryModelCombo.getEditor() != null
                              ? summaryModelCombo.getEditor().getItem() : summaryModelCombo.getSelectedItem();
        String summaryText = summaryModel == null ? "" : summaryModel.toString().trim();
        settings.setSummaryModel(summaryText.isEmpty() ? null : summaryText);

        settings.setContextWindowOverride(((Number) contextWindowSpinner.getValue()).intValue());
        settings.setToolCallingMode((ToolCallingModeEnum) toolCallingCombo.getSelectedItem());
        settings.setSaveContextOnClose(saveContextCheck.isSelected());
        settings.setShowReasoning(showReasoningCheck.isSelected());
        settings.setCompactionStrategy((CompactionStrategyEnum) compactionCombo.getSelectedItem());
        settings.setAutoCompact(autoCompactCheck.isSelected());
        settings.setPrune(pruneCheck.isSelected());
        if (pendingApiKey != null && pendingApiKey.length > 0) {
            apiKeySet = true;
        }
        settings.setApiKeySet(apiKeySet);
        advanced.applyTo(settings);
        saveSecretHeaders();
    }

    /**
     * Hands the typed secret header values to the Keyring in the background (they never go into the settings). With no
     * saver (new-session panel) they stay in the table and are returned by {@link #pendingSecretHeaders()}.
     */
    private void saveSecretHeaders() {
        if (headerSaver == null) {
            return;
        }
        Map<String, char[]> pending = advanced.pendingSecretHeaders();
        if (pending.isEmpty()) {
            return;
        }
        List<CompletableFuture<Boolean>> saves = new ArrayList<>();
        for (Map.Entry<String, char[]> entry : pending.entrySet()) {
            saves.add(headerSaver.save(entry.getKey(), entry.getValue()));
        }
        advanced.clearPendingSecretHeaders();
        CompletableFuture.allOf(saves.toArray(new CompletableFuture<?>[0]))
                .whenComplete((ignored, error) -> pending.values().forEach(value -> Arrays.fill(value, (char) 0)));
    }

    /**
     * The secret header values typed in the new-session panel, to be saved once the session exists. Empty when this
     * panel saves them itself.
     */
    public Map<String, char[]> pendingSecretHeaders() {
        return headerSaver == null ? advanced.pendingSecretHeaders() : Map.of();
    }

    /**
     * @return the problems in the extra headers (refused, restricted or duplicate names); empty when fine
     */
    public List<String> headerErrors() {
        return advanced.headerErrors();
    }

    @Override
    public void startLoading() {
        loadOllamaImportOnce();
        runTestConnection();
    }

    @Override
    public void addChangeListener(ActionListener listener) {
        changeListener = listener;
        advanced.addChangeListener(listener);
    }

    public void setTestResultListener(Consumer<ConnectionTester.Result> listener) {
        testResultListener = listener;
    }

    @Override
    public void dispose() {
        char[] key = pendingApiKey;
        pendingApiKey = null;
        if (key != null) {
            java.util.Arrays.fill(key, (char) 0);
        }
        if (secrets != null) {
            secrets.close();
        }
    }

    /**
     * The key typed in the new-session panel, to be saved once the session exists. Empty when none was typed.
     */
    public Optional<char[]> pendingApiKey() {
        return pendingApiKey == null || pendingApiKey.length == 0 ? Optional.empty() : Optional.of(pendingApiKey);
    }

    private void fireChange() {
        if (!updating && changeListener != null) {
            changeListener.actionPerformed(null);
        }
    }

    private void applyServerTypePreset() {
        if (updating) {
            return;
        }
        Object selected = serverTypeCombo.getSelectedItem();
        if (!(selected instanceof ServerTypeEnum type)) {
            return;
        }
        String preset = type.presetUrl();
        if (preset.isBlank()) {
            return;
        }
        String current = baseUrlField.getText().trim();
        if (current.isEmpty() || isAnotherPreset(current)) {
            baseUrlField.setText(preset);
            baseUrlNote.setText("Base URL changed to " + preset);
        }
    }

    private static boolean isAnotherPreset(String url) {
        for (ServerTypeEnum type : ServerTypeEnum.values()) {
            if (!type.presetUrl().isBlank() && type.presetUrl().equals(url)) {
                return true;
            }
        }
        return false;
    }

    private void onChangeKey() {
        char[] typed = apiKeyField.getPassword();
        if (typed.length == 0) {
            return;
        }
        pendingApiKey = typed;
        apiKeySet = true;
        if (secrets != null) {
            secrets.saveKeyAsync(typed).thenAccept(ok -> SwingUtilities.invokeLater(() -> {
                if (Boolean.TRUE.equals(ok)) {
                    statusLabel.setText("Key saved");
                }
                else {
                    statusLabel.setText("Key kept for this run only");
                }
                refreshKeyState();
            }));
        }
        apiKeyField.setText("");
        refreshKeyState();
    }

    private void onClearKey() {
        pendingApiKey = null;
        apiKeySet = false;
        if (secrets != null) {
            secrets.clearKeyAsync().thenRun(() -> SwingUtilities.invokeLater(this::refreshKeyState));
        }
        refreshKeyState();
    }

    private void refreshKeyState() {
        String usingDefault = DefaultKeyRules.usingDefaultLabel(baseUrlField.getText(),
                                                                OpenAiCompatiblePluginSettings.getBaseUrl(), OpenAiCompatiblePluginSettings.isDefaultKeySet(),
                                                                apiKeySet);
        keyStateLabel.setText(usingDefault != null ? usingDefault : apiKeySet ? "Key: set" : "Key: not set");
        advanced.setApiKeySet(apiKeySet || usingDefault != null);
        refreshKeyWarning();
    }

    /**
     * The unencrypted-key warning (spec §8.6): an {@code http://} URL to a non-local host while a key is active (set,
     * typed, or the default's for the same server).
     */
    private void refreshKeyWarning() {
        String url = baseUrlField.getText().trim().toLowerCase(Locale.ROOT);
        boolean keyActive = apiKeySet || apiKeyField.getPassword().length > 0
                || DefaultKeyRules.usingDefaultLabel(baseUrlField.getText(),
                                                     OpenAiCompatiblePluginSettings.getBaseUrl(), OpenAiCompatiblePluginSettings.isDefaultKeySet(),
                                                     apiKeySet) != null;
        keyWarningLabel.setText(url.startsWith("http://") && !BaseUrls.isLocal(url) && keyActive
                                ? "The API key would be sent unencrypted (http://) to a non-local server." : " ");
    }

    private void runTestConnection() {
        testButton.setEnabled(false);
        statusLabel.setText("Testing…");
        char[] key = pendingApiKey != null && pendingApiKey.length > 0 ? pendingApiKey : null;
        if (key == null && secrets != null) {
            key = secrets.apiKey(Duration.ZERO).orElse(null);
        }
        String url = baseUrlField.getText();
        ConnectionTester tester = new ConnectionTester();
        tester.test(url, key, List.of(), limits).whenComplete((result, error)
                -> SwingUtilities.invokeLater(() -> {
                    testButton.setEnabled(true);
                    applyTestResult(result, error);
                }));
    }

    private void applyTestResult(ConnectionTester.Result result, Throwable error) {
        if (error != null || result == null) {
            statusLabel.setText("N/A");
            statusLabel.setToolTipText("Cannot reach the server at " + baseUrlField.getText());
            return;
        }
        statusLabel.setText(result.text());
        statusLabel.setToolTipText(result.kind() == ConnectionTester.Kind.NOT_AVAILABLE
                                   ? "Cannot reach the server at " + baseUrlField.getText() : null);
        if (result.kind() != ConnectionTester.Kind.CONNECTED) {
            reasoningLevelsByModel = Map.of();
            return;
        }
        reasoningLevelsByModel = result.reasoningLevelsByModel();
        if (!result.models().isEmpty()) {
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            for (String name : result.models()) {
                model.addElement(name);
            }
            String current = selectedModel();
            modelCombo.setModel(model);
            if (current != null) {
                modelCombo.setSelectedItem(current);
            }
            fillSummaryModels(result.models());
        }
        refreshReasoningEffortLevels(levelsForSelectedModel());
        if (testResultListener != null) {
            testResultListener.accept(result);
        }
    }

    private String selectedModel() {
        Object selected = modelCombo.getSelectedItem();
        if (selected != null && !selected.toString().isBlank()) {
            return selected.toString().trim();
        }
        Object typed = modelCombo.getEditor() == null ? null : modelCombo.getEditor().getItem();
        return typed == null || typed.toString().isBlank() ? null : typed.toString().trim();
    }

    private List<String> levelsForSelectedModel() {
        String model = selectedModel();
        return model == null ? null : reasoningLevelsByModel.get(model);
    }

    private void refreshReasoningEffortLevels(List<String> advertised) {
        @SuppressWarnings("unchecked")
        JComboBox<String> combo = (JComboBox<String>) advanced.reasoningEffortComponent();
        Object current = combo.getSelectedItem();
        combo.removeAllItems();
        combo.addItem(BlankSafeComboRenderer.DEFAULT_OPTION);
        List<String> levels = advertised == null || advertised.isEmpty()
                              ? List.of("low", "medium", "high") : advertised;
        levels.stream().filter(level -> level != null && !level.isBlank()).distinct().forEach(combo::addItem);
        if (current != null && combo.getItemCount() > 1) {
            for (int i = 1; i < combo.getItemCount(); i++) {
                if (current.equals(combo.getItemAt(i))) {
                    combo.setSelectedItem(current);
                    return;
                }
            }
        }
        combo.setSelectedItem(BlankSafeComboRenderer.DEFAULT_OPTION);
    }

    /**
     * Fills the summary model list from the same model list as the chat model, keeping what is typed (empty means "use
     * the chat model").
     */
    private void fillSummaryModels(List<String> names) {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (String name : names) {
            model.addElement(name);
        }
        Object current = summaryModelCombo.getEditor() != null ? summaryModelCombo.getEditor().getItem()
                         : summaryModelCombo.getSelectedItem();
        boolean wasUpdating = updating;
        updating = true;
        try {
            summaryModelCombo.setModel(model);
            summaryModelCombo.setSelectedItem(current == null ? "" : current);
        }
        finally {
            updating = wasUpdating;
        }
    }

    private void loadOllamaImportOnce() {
        if (ollamaImportChecked) {
            return;
        }
        ollamaImportChecked = true;
        BackendExecutors.probePool().execute(() -> {
            boolean imported = OpenAiCompatiblePluginSettings.importFromOllamaOnce();
            if (imported) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Imported from Ollama (local)"));
            }
        });
    }

    /**
     * A document listener that treats every edit as a user change.
     */
    private static final class SimpleDocumentListener implements javax.swing.event.DocumentListener {

        private final Runnable onChange;

        private SimpleDocumentListener(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            onChange.run();
        }
    }
}
