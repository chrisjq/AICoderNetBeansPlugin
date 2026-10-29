package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.BorderLayout;
import java.awt.Component;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ConnectionTester;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.CompactionStrategyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SavedServerKeys;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.SavedServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
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
    private final JComboBox<Object> baseUrlCombo = new JComboBox<>();
    private final JLabel baseUrlNote = new JLabel(" ");
    private final JButton manageServersButton = new JButton("…");
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
    private final SavedServerKeys savedServerKeys;
    private final HeaderSecretSaver headerSaver;
    private final OpenAiCompatibleLimits limits;
    private final Executor probeExecutor;
    private char[] pendingApiKey;
    private CompletableFuture<Boolean> keySaveFuture;
    /**
     * A copy of the key the session had when the form loaded (null when it had none), for reverting an uncommitted
     * save.
     */
    private char[] keyBeforeChange;
    private boolean keyBeforeChangeKnown;
    private CompletableFuture<Void> pendingRevert;
    /**
     * Completes once startLoading has read the keyring state (completed from the start, so a panel that never loads
     * does not hold an OK back).
     */
    private CompletableFuture<Void> keyStateReady = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> savedKeyRead = CompletableFuture.completedFuture(null);
    private int savedServerSelectionToken;
    private String selectedSavedServerId;
    private boolean savedPickApiKeySet;
    private boolean savedPickClearKeyPending;
    private char[] savedPickPendingApiKey;
    private boolean disposed;
    /**
     * The keyring for new-session key saves; null means the default keyring.
     */
    private SecretStore sessionSecretStore;
    private boolean apiKeySet;
    private boolean clearKeyPending;
    private Map<String, List<String>> reasoningLevelsByModel = Map.of();
    private boolean updating;
    private boolean ollamaImportChecked;
    private ActionListener changeListener;
    private Consumer<ConnectionTester.Result> testResultListener;
    private Runnable testConnectionHook;

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
        this(secrets, limits, headerSaver, BackendExecutors.probePool(), new SavedServerKeys());
    }

    OpenAiCompatibleBasicPanel(SessionSecrets secrets, OpenAiCompatibleLimits limits, HeaderSecretSaver headerSaver,
                               Executor probeExecutor) {
        this(secrets, limits, headerSaver, probeExecutor, new SavedServerKeys());
    }

    OpenAiCompatibleBasicPanel(SessionSecrets secrets, OpenAiCompatibleLimits limits, HeaderSecretSaver headerSaver,
                               Executor probeExecutor, SavedServerKeys savedServerKeys) {
        this.secrets = secrets;
        this.savedServerKeys = savedServerKeys == null ? new SavedServerKeys() : savedServerKeys;
        this.headerSaver = headerSaver;
        this.limits = limits == null ? OpenAiCompatibleLimits.defaults() : limits;
        this.probeExecutor = probeExecutor == null ? BackendExecutors.probePool() : probeExecutor;
        buildLayout();
        wireBehaviour();
        refreshKeyState();
    }

    private void buildLayout() {
        baseUrlCombo.setEditable(true);
        baseUrlCombo.setEditor(new BasicComboBoxEditor() {
            @Override
            public void setItem(Object item) {
                super.setItem(item instanceof SavedServer server ? server.url() : item);
            }

            @Override
            public Object getItem() {
                return ((JTextField) getEditorComponent()).getText();
            }
        });
        baseUrlCombo.setToolTipText("e.g. http://localhost:11434 or https://host/v1 — both work");
        baseUrlCombo.setRenderer((list, value, index, selected, focus)
                -> new JLabel(value instanceof SavedServer server ? server.label() : value == null ? "" : value.toString()));
        manageServersButton.setToolTipText("Manage servers");
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
        baseUrlRow.add(baseUrlCombo, BorderLayout.CENTER);
        baseUrlRow.add(manageServersButton, BorderLayout.EAST);
        baseUrlRow.add(baseUrlNote, BorderLayout.SOUTH);
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

    private int addRow(GridBagConstraints c, int row, String label, Component field) {
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
        ((JTextField) baseUrlCombo.getEditor().getEditorComponent()).getDocument()
                .addDocumentListener(new SimpleDocumentListener(() -> {
                    if (!updating && selectedSavedServerId != null
                            && !savedServerUrl(selectedSavedServerId).equals(baseUrlText())) {
                        selectedSavedServerId = null;
                    }
                    refreshKeyState();
                    markChange.run();
                }));
        baseUrlCombo.addActionListener(e -> {
            if (!updating && baseUrlCombo.getSelectedItem() instanceof SavedServer server
                    && !server.id().equals(selectedSavedServerId)) {
                selectSavedServer(server);
            }
        });
        manageServersButton.addActionListener(e -> {
            String current = baseUrlText();
            SavedServersEditor.showManageDialog(panel, savedServerKeys);
            loadSavedServers(current);
        });
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
            String loadedBaseUrl = settings.effectiveBaseUrl();
            loadSavedServers(loadedBaseUrl);
            if (secrets == null) {
                selectInitialSavedServer(loadedBaseUrl);
            }
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
            modelDefaultLabel.setText("(default: " + defaultOrNone(OpenAiCompatiblePluginSettings.getModel()) + ")");
        }
        finally {
            updating = false;
        }
        advanced.load(settings);
        refreshKeyState();
    }

    /**
     * Records the key the session had when the form loaded, so a dialog closed without committing can put it back. When
     * the keyring state is still unknown nothing is recorded, and no revert is attempted.
     */
    private void recordKeyBeforeChange() {
        if (keyBeforeChange != null) {
            Arrays.fill(keyBeforeChange, (char) 0);
        }
        keyBeforeChange = null;
        keyBeforeChangeKnown = false;
        if (secrets == null || !keyStateKnown(secrets.keyState())) {
            return;
        }
        keyBeforeChangeKnown = true;
        keyBeforeChange = secrets.apiKey(Duration.ZERO).map(char[]::clone).orElse(null);
    }

    /**
     * The error to report for a failed save: the cause of a wrapper, so the message is not wrapped twice.
     */
    private static Throwable unwrapped(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    /**
     * Whether a keyring state read from the store is a known one: a missing value and a value that is there are both
     * known, while a keyring that could not be read is not.
     */
    private static boolean keyStateKnown(SessionSecrets.KeyState state) {
        return state == SessionSecrets.KeyState.PRESENT || state == SessionSecrets.KeyState.NOT_SET
                || state == SessionSecrets.KeyState.MISSING_ON_THIS_COMPUTER;
    }

    /**
     * Records the key before the change only when nothing has been staged yet: once a Change or Clear is staged, the
     * key the form loaded with is no longer the state a revert should restore.
     */
    private void recordKeyBeforeChangeUnlessStaged() {
        if (pendingApiKey == null && !clearKeyPending) {
            recordKeyBeforeChange();
        }
    }

    /**
     * Undoes this panel's keyring change once {@code change} has finished: the previous key is saved back, or the key
     * is cleared when there was none. Nothing is undone when the change failed, or when the state at load is unknown.
     * The previous key is copied now, so the revert still works after {@link #dispose()}.
     */
    public CompletableFuture<Void> revertKeyChange(CompletableFuture<Boolean> change) {
        char[] previous = keyBeforeChange == null ? null : keyBeforeChange.clone();
        if (change == null || secrets == null || !keyBeforeChangeKnown) {
            Arrays.fill(previous == null ? new char[0] : previous, (char) 0);
            return CompletableFuture.completedFuture(null);
        }
        SessionSecrets target = secrets;
        CompletableFuture<Void> revert = change
                .handle((changed, error) -> error == null && Boolean.TRUE.equals(changed))
                .thenCompose(succeeded -> {
                    if (!succeeded) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    if (previous == null) {
                        return target.clearKeyAsync();
                    }
                    return target.saveKeyAsync(previous).thenApply(ignored -> (Void) null);
                })
                .whenComplete((ignored, error) -> {
                    if (previous != null) {
                        Arrays.fill(previous, (char) 0);
                    }
                });
        pendingRevert = revert;
        return revert;
    }

    private String baseUrlText() {
        Object item = baseUrlCombo.getEditor().getItem();
        return item instanceof SavedServer server ? server.url() : item == null ? "" : item.toString();
    }

    private void setBaseUrlText(String value) {
        baseUrlCombo.getEditor().setItem(value == null ? "" : value);
    }

    void loadSavedServers(String currentUrl) {
        boolean wasUpdating = updating;
        updating = true;
        try {
            baseUrlCombo.removeAllItems();
            for (SavedServer server : OpenAiCompatiblePluginSettings.getSavedServers()) {
                baseUrlCombo.addItem(server);
            }
            baseUrlCombo.setSelectedItem(null);
            selectedSavedServerId = null;
            setBaseUrlText(currentUrl);
        }
        finally {
            updating = wasUpdating;
        }
    }

    private void selectInitialSavedServer(String loadedUrl) {
        Optional<SavedServer> defaultServer = OpenAiCompatiblePluginSettings.getDefaultServer();
        if (loadedUrl == null || loadedUrl.isBlank()) {
            defaultServer.ifPresent(this::selectSavedServer);
            return;
        }
        String normalised = BaseUrls.normalise(loadedUrl);
        SavedServer match = defaultServer.filter(server -> normalised.equals(BaseUrls.normalise(server.url())))
                .orElseGet(() -> OpenAiCompatiblePluginSettings.getSavedServers().stream()
                        .filter(server -> normalised.equals(BaseUrls.normalise(server.url())))
                        .findFirst().orElse(null));
        if (match != null) {
            selectSavedServer(match);
        }
    }

    private String savedServerUrl(String id) {
        return OpenAiCompatiblePluginSettings.getSavedServers().stream()
                .filter(server -> server.id().equals(id)).map(SavedServer::url).findFirst().orElse("");
    }

    private void selectSavedServer(SavedServer server) {
        selectedSavedServerId = server.id();
        // Only a pick made with no saved-key read pending takes the snapshot: a later pick must keep the state from
        // before the first one, not the temporary "key present" state that pick set.
        if (savedKeyRead.isDone()) {
            savedPickApiKeySet = apiKeySet;
            savedPickClearKeyPending = clearKeyPending;
            zeroSavedPickPendingApiKey();
            savedPickPendingApiKey = pendingApiKey == null ? null : pendingApiKey.clone();
        }
        // Until the asynchronous read completes, retain a conservative "key present" state. A read failure must never
        // make Apply/OK clear the session's existing key; a successful empty read below will explicitly clear it.
        if (secrets != null) {
            apiKeySet = true;
            refreshKeyState();
        }
        int token = ++savedServerSelectionToken;
        serverTypeCombo.setSelectedItem(server.serverType());
        setBaseUrlText(server.url());
        CompletableFuture<Void> ready = new CompletableFuture<>();
        savedKeyRead = ready;
        savedServerKeys.readAsync(server.id()).whenComplete((key, error)
                -> SwingUtilities.invokeLater(() -> {
                    try {
                        applySavedServerKey(token, key, error);
                    }
                    finally {
                        ready.complete(null);
                    }
                }));
    }

    private void applySavedServerKey(int token, Optional<char[]> key, Throwable error) {
        char[] value = key != null && key.isPresent() ? key.get() : null;
        try {
            if (disposed || token != savedServerSelectionToken) {
                return;
            }
            if (error != null) {
                apiKeySet = savedPickApiKeySet;
                clearKeyPending = savedPickClearKeyPending;
                char[] old = pendingApiKey;
                pendingApiKey = savedPickPendingApiKey == null ? null : savedPickPendingApiKey.clone();
                if (old != null) {
                    Arrays.fill(old, (char) 0);
                }
                refreshKeyState();
                statusLabel.setText("Could not read the saved key — the session key is unchanged");
                return;
            }
            if (value == null || value.length == 0) {
                onClearKey();
                return;
            }
            char[] old = pendingApiKey;
            pendingApiKey = value.clone();
            if (old != null) {
                Arrays.fill(old, (char) 0);
            }
            apiKeySet = true;
            clearKeyPending = false;
            apiKeyField.setText("");
            refreshKeyState();
        }
        finally {
            if (value != null) {
                Arrays.fill(value, (char) 0);
            }
            // Only the latest pick's snapshot is still needed; a stale read must not clear the one a newer read uses.
            if (token == savedServerSelectionToken) {
                zeroSavedPickPendingApiKey();
            }
            if (!disposed && token == savedServerSelectionToken && testButton.isEnabled()) {
                runTestConnection();
            }
        }
    }

    private void zeroSavedPickPendingApiKey() {
        if (savedPickPendingApiKey != null) {
            Arrays.fill(savedPickPendingApiKey, (char) 0);
            savedPickPendingApiKey = null;
        }
    }

    private static String defaultOrNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    @Override
    public void applyTo(OpenAiCompatibleSessionSettings settings) {
        if (keySaveFuture != null && keySaveFuture.isDone()) {
            // A finished save is reported once; a failed one is retried below while its key is still staged.
            keySaveFuture = null;
        }
        captureTypedApiKey();
        settings.setServerType((ServerTypeEnum) serverTypeCombo.getSelectedItem());

        String raw = baseUrlText();
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
            clearKeyPending = false;
            savePendingApiKey();
        }
        else if (clearKeyPending && secrets != null) {
            keySaveFuture = secrets.clearKeyAsync().thenApply(ignored -> true);
            clearKeyPending = false;
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
        if (secrets == null) {
            if (savedKeyRead.isDone()) {
                runTestConnection();
            }
            return;
        }
        CompletableFuture<Void> ready = new CompletableFuture<>();
        keyStateReady = ready;
        secrets.loadAsync(List.of()).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            // The keyring state is known only now; a Change or Clear made before this point is not recorded as the "before".
            recordKeyBeforeChangeUnlessStaged();
            ready.complete(null);
            runTestConnection();
        }));
    }

    /**
     * Completes once the keyring state this panel needs to revert a save is known. Completed at once when the panel has
     * no keyring or startLoading has not been called.
     */
    @Override
    public CompletableFuture<Void> keyStateReady() {
        return CompletableFuture.allOf(keyStateReady, savedKeyRead);
    }

    @Override
    public CompletableFuture<Boolean> keySaveFuture() {
        return keySaveFuture;
    }

    @Override
    public CompletableFuture<Void> savePendingSecrets(List<AiSession> sessions) {
        Optional<char[]> pending = pendingApiKey();
        if (pending.isEmpty() || sessions == null || sessions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        // The staged key stays staged until the picker discards it after the sessions are created, so a failed creation
        // can be retried with the same key. The saves use a copy, zeroed once they have all finished.
        char[] key = pending.get().clone();
        List<CompletableFuture<Boolean>> saves = new ArrayList<>();
        for (AiSession session : sessions) {
            SessionSecrets sessionSecrets = sessionSecretsFor(session.id());
            saves.add(sessionSecrets.saveKeyAsync(key)
                    .whenComplete((ok, error) -> sessionSecrets.close()));
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(saves.toArray(new CompletableFuture<?>[0]));
        return all.<CompletableFuture<Void>>handle((ignored, error) -> {
            boolean allSaved = error == null
                    && saves.stream().allMatch(save -> Boolean.TRUE.equals(save.getNow(false)));
            if (allSaved) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            List<CompletableFuture<Void>> cleanup = sessions.stream()
                    .map(session -> deleteSessionSecretsFor(session.id()))
                    .toList();
            return CompletableFuture.allOf(cleanup.toArray(new CompletableFuture<?>[0]))
                    .thenCompose(cleanupIgnored -> CompletableFuture.failedFuture(
                            error == null ? new IllegalStateException(
                                            "The API key could not be stored in the system keyring.")
                            : unwrapped(error)));
        })
                .thenCompose(future -> future)
                .whenComplete((ignored, error) -> Arrays.fill(key, (char) 0));
    }

    /**
     * Forgets the staged key once the sessions it was saved for have been created. Until then the key stays staged, so
     * a failed creation can be retried.
     */
    public void discardStagedKey() {
        char[] key = pendingApiKey;
        pendingApiKey = null;
        if (key != null) {
            Arrays.fill(key, (char) 0);
        }
    }

    /**
     * The keyring handle for one session's key: the default keyring, or the store a test injected.
     */
    private SessionSecrets sessionSecretsFor(String sessionId) {
        return sessionSecretStore == null ? new SessionSecrets(sessionId, true)
               : new SessionSecrets(sessionId, sessionSecretStore, BackendExecutors.probePool(), true);
    }

    private CompletableFuture<Void> deleteSessionSecretsFor(String sessionId) {
        return sessionSecretStore == null ? SessionSecrets.deleteAllAsync(sessionId)
               : SessionSecrets.deleteAllAsync(sessionId, sessionSecretStore, BackendExecutors.probePool());
    }

    /**
     * Replaces the keyring the session keys are saved to (tests use an in-memory store).
     */
    void setSessionSecretStoreForTest(SecretStore store) {
        sessionSecretStore = store;
    }

    @Override
    public void addChangeListener(ActionListener listener) {
        changeListener = listener;
        advanced.addChangeListener(listener);
    }

    public void setTestResultListener(Consumer<ConnectionTester.Result> listener) {
        testResultListener = listener;
    }

    void setTestConnectionHookForTest(Runnable hook) {
        testConnectionHook = hook;
    }

    @Override
    public void dispose() {
        disposed = true;
        savedServerSelectionToken++;
        zeroSavedPickPendingApiKey();
        char[] key = pendingApiKey;
        pendingApiKey = null;
        if (key != null) {
            Arrays.fill(key, (char) 0);
        }
        if (keyBeforeChange != null) {
            Arrays.fill(keyBeforeChange, (char) 0);
            keyBeforeChange = null;
        }
        if (secrets != null) {
            // A revert still running needs the secrets open; close them when it finishes.
            CompletableFuture<Void> revert = pendingRevert;
            SessionSecrets toClose = secrets;
            if (revert != null && !revert.isDone()) {
                revert.whenComplete((ignored, error) -> toClose.close());
            }
            else {
                toClose.close();
            }
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
        String current = baseUrlText().trim();
        if (current.isEmpty() || isAnotherPreset(current)) {
            setBaseUrlText(preset);
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
        captureTypedApiKey();
        if (pendingApiKey == null || pendingApiKey.length == 0) {
            return;
        }
        apiKeySet = true;
        clearKeyPending = false;
        apiKeyField.setText("");
        refreshKeyState();
    }

    /**
     * Keeps a key entered without pressing Change until the caller applies the form. New-session panels have no session
     * keyring yet; the picker consumes this value after it creates a session id.
     */
    private void captureTypedApiKey() {
        char[] typed = apiKeyField.getPassword();
        if (typed.length == 0) {
            return;
        }
        char[] previous = pendingApiKey;
        pendingApiKey = typed;
        if (previous != null && previous != typed) {
            Arrays.fill(previous, (char) 0);
        }
    }

    /**
     * Starts the keyring save of the staged key. The staged key stays in place until the save succeeds, so a failed
     * save can be retried by the next OK. The save works on its own copy, which is zeroed only after the save has
     * finished.
     */
    private void savePendingApiKey() {
        if (secrets == null || pendingApiKey == null || pendingApiKey.length == 0) {
            return;
        }
        char[] staged = pendingApiKey;
        char[] key = staged.clone();
        CompletableFuture<Boolean> save = secrets.saveKeyAsync(key);
        keySaveFuture = save;
        // The staged key is touched only on the EDT, where the form reads and writes it too.
        save.whenComplete((ok, error) -> SwingUtilities.invokeLater(() -> {
            Arrays.fill(key, (char) 0);
            if (error == null && Boolean.TRUE.equals(ok) && pendingApiKey == staged) {
                pendingApiKey = null;
                Arrays.fill(staged, (char) 0);
            }
            statusLabel.setText(Boolean.TRUE.equals(ok) ? "Key saved"
                                : "API key not saved (keyring unavailable) — press OK to retry");
            refreshKeyState();
        }));
    }

    private void onClearKey() {
        keySaveFuture = null;
        clearKeyPending = true;
        char[] pending = pendingApiKey;
        pendingApiKey = null;
        Arrays.fill(pending == null ? new char[0] : pending, (char) 0);
        apiKeyField.setText("");
        apiKeySet = false;
        refreshKeyState();
    }

    private void refreshKeyState() {
        keyStateLabel.setText(apiKeySet ? "Key: set" : "Key: not set");
        advanced.setApiKeySet(apiKeySet);
        refreshKeyWarning();
    }

    /**
     * The unencrypted-key warning (spec §8.6): an {@code http://} URL to a non-local host while a key is active (set,
     * typed, or the default's for the same server).
     */
    private void refreshKeyWarning() {
        String url = baseUrlText().trim().toLowerCase(Locale.ROOT);
        boolean keyActive = apiKeySet || apiKeyField.getPassword().length > 0;
        keyWarningLabel.setText(url.startsWith("http://") && !BaseUrls.isLocal(url) && keyActive
                                ? "The API key would be sent unencrypted (http://) to a non-local server." : " ");
    }

    private void runTestConnection() {
        if (testConnectionHook != null) {
            testConnectionHook.run();
            return;
        }
        testButton.setEnabled(false);
        statusLabel.setText("Testing…");
        String url = baseUrlText();
        ServerTypeEnum serverType = (ServerTypeEnum) serverTypeCombo.getSelectedItem();
        Object selectedModel = modelCombo.getSelectedItem();
        String model = selectedModel instanceof String value ? value : "";
        char[] key = pendingApiKey != null && pendingApiKey.length > 0 ? pendingApiKey.clone() : null;
        try {
            probeExecutor.execute(() -> {
                char[] resolved = key;
                if (resolved == null && secrets != null) {
                    resolved = secrets.apiKey(limits.probeTimeout()).orElse(null);
                }
                ConnectionTester tester = new ConnectionTester();
                char[] requestKey = resolved;
                try {
                    tester.test(url, serverType, model, requestKey, List.of(), limits).whenComplete((result, error) -> {
                        Arrays.fill(requestKey == null ? new char[0] : requestKey, (char) 0);
                        SwingUtilities.invokeLater(() -> {
                            testButton.setEnabled(true);
                            applyTestResult(result, error);
                        });
                    });
                }
                catch (RuntimeException ex) {
                    Arrays.fill(requestKey == null ? new char[0] : requestKey, (char) 0);
                    SwingUtilities.invokeLater(() -> {
                        testButton.setEnabled(true);
                        applyTestResult(null, ex);
                    });
                }
            });
        }
        catch (RejectedExecutionException ex) {
            Arrays.fill(key == null ? new char[0] : key, (char) 0);
            testButton.setEnabled(true);
            statusLabel.setText("N/A");
        }
    }

    private void applyTestResult(ConnectionTester.Result result, Throwable error) {
        if (error != null || result == null) {
            statusLabel.setText("N/A");
            statusLabel.setToolTipText("Cannot reach the server at " + baseUrlText());
            return;
        }
        statusLabel.setText(result.text());
        statusLabel.setToolTipText(result.kind() == ConnectionTester.Kind.NOT_AVAILABLE
                                   ? "Cannot reach the server at " + baseUrlText() : null);
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
    private static final class SimpleDocumentListener implements DocumentListener {

        private final Runnable onChange;

        private SimpleDocumentListener(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            onChange.run();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            onChange.run();
        }
    }
}
