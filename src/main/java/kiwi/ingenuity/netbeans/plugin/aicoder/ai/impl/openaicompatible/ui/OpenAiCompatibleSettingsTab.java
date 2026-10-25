package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ConnectionTester;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.DefaultKeyRules;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.FormLayoutUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogPlacement;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

/**
 * Plugin defaults for OpenAI Compatible (spec §8.1, §8.4, §8.6): Basic (server type, base URL, API key, model),
 * Advanced plugin-wide values (collapsed), and a Detected panel for the default URL. New sessions start from the Basic
 * values. The key lives in the Keyring under {@code aicoder.openai_compatible.default}; it is cleared on Save when the
 * default URL moves to a different root. Ollama (local)'s URL and model are imported once the first time the form
 * opens.
 */
@ServiceProvider(service = SettingsTab.class)
public final class OpenAiCompatibleSettingsTab implements SettingsTab {

    /**
     * Session id under which the default key is stored: the Keyring entry becomes
     * {@code aicoder.openai_compatible.default}.
     */
    static final String DEFAULT_KEY_ID = "default";

    private static final String ADVANCED_COLLAPSED = "Advanced ▸";
    private static final String ADVANCED_EXPANDED = "Advanced ▾";

    /**
     * The Keyring operations on the default key; replaced by a fake in tests.
     */
    interface DefaultKeyStore {

        /**
         * @return true when the key was stored and read back
         */
        CompletableFuture<Boolean> save(char[] key);

        CompletableFuture<Void> clear();
    }

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final JPanel panel = new JPanel(new GridBagLayout());
    private final JComboBox<ServerTypeEnum> serverTypeCombo = new JComboBox<>(ServerTypeEnum.values());
    private final JTextField baseUrlField = new JTextField(28);
    private final JLabel baseUrlNote = new JLabel(" ");
    private final JPasswordField apiKeyField = new JPasswordField(28);
    private final JLabel keyStateLabel = new JLabel();
    private final JLabel keyWarningLabel = new JLabel(" ");
    private final JButton clearKeyButton = new JButton("Clear");
    private final JTextField modelField = new JTextField(28);
    private final JComboBox<String> reasoningEffortCombo = new JComboBox<>(
            new String[]{BlankSafeComboRenderer.DEFAULT_OPTION, "off", "low", "medium", "high", "xhigh", "on"});
    private final JLabel noteLabel = new JLabel(" ");
    private final JButton advancedToggle = new JButton(ADVANCED_COLLAPSED);
    private final OpenAiCompatiblePluginAdvancedPanel pluginAdvanced = new OpenAiCompatiblePluginAdvancedPanel();
    private final JButton testButton = new JButton("Test connection");
    private final JLabel testStatus = new JLabel(" ");
    private final OpenAiCompatibleDetectedPanel detected = new OpenAiCompatibleDetectedPanel();

    private final DefaultKeyStore keyStore;
    private volatile Consumer<String> noticeSink;
    private boolean updating;
    private boolean clearKeyRequested;
    private volatile CompletableFuture<Boolean> importFuture;
    private volatile CompletableFuture<Void> storeFuture = CompletableFuture.completedFuture(null);
    private DetectedFacts lastFacts;

    public OpenAiCompatibleSettingsTab() {
        this(new DefaultKeyStore() {
            @Override
            public CompletableFuture<Boolean> save(char[] key) {
                SessionSecrets secrets = new SessionSecrets(DEFAULT_KEY_ID,
                                                            OpenAiCompatiblePluginSettings.isDefaultKeySet());
                return secrets.saveKeyAsync(key).whenComplete((ok, error) -> secrets.close());
            }

            @Override
            public CompletableFuture<Void> clear() {
                SessionSecrets secrets = new SessionSecrets(DEFAULT_KEY_ID,
                                                            OpenAiCompatiblePluginSettings.isDefaultKeySet());
                return secrets.clearKeyAsync().whenComplete((ok, error) -> secrets.close());
            }
        });
    }

    OpenAiCompatibleSettingsTab(DefaultKeyStore keyStore) {
        this.keyStore = keyStore;
        buildLayout();
        wireBehaviour();
    }

    private void buildLayout() {
        serverTypeCombo.setRenderer((list, value, index, selected, focus)
                -> new JLabel(value == null ? "" : value.displayName()));
        baseUrlField.setToolTipText("e.g. http://localhost:11434 or https://host/v1 — both work");
        apiKeyField.setToolTipText("Stored in the system keyring. Used by sessions that talk to the same server as "
                + "the default URL and have no key of their own.");

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        int row = 0;
        row = addRow(c, row, "", new JLabel("Defaults for new OpenAI Compatible sessions."));
        row = addRow(c, row, "Server type:", serverTypeCombo);
        row = addRow(c, row, "Base URL:", baseUrlField);
        row = addRow(c, row, "", baseUrlNote);
        row = addRow(c, row, "API key:", apiKeyField);
        JPanel keyRow = new JPanel();
        keyRow.add(keyStateLabel);
        keyRow.add(clearKeyButton);
        row = addRow(c, row, "", keyRow);
        row = addRow(c, row, "", keyWarningLabel);
        JPanel testRow = new JPanel(new BorderLayout(6, 0));
        testRow.add(testButton, BorderLayout.WEST);
        testRow.add(testStatus, BorderLayout.CENTER);
        row = addRow(c, row, "", testRow);
        reasoningEffortCombo.setRenderer(new BlankSafeComboRenderer());
        row = addRow(c, row, "Model:", modelField);
        row = addRow(c, row, "Reasoning effort:", reasoningEffortCombo);
        row = addRow(c, row, "", noteLabel);
        row = addRow(c, row, "", advancedToggle);
        pluginAdvanced.component().setVisible(false);
        row = addRow(c, row, "", pluginAdvanced.component());
        addRow(c, row, "", detected.component());
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
        serverTypeCombo.addActionListener(e -> {
            applyServerTypePreset();
            refreshDetected();
            fireChanged();
        });
        DocumentListener onEdit = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                edited();
            }
        };
        baseUrlField.getDocument().addDocumentListener(onEdit);
        apiKeyField.getDocument().addDocumentListener(onEdit);
        modelField.getDocument().addDocumentListener(onEdit);
        clearKeyButton.addActionListener(e -> {
            clearKeyRequested = true;
            apiKeyField.setText("");
            refreshKeyLabel();
            fireChanged();
        });
        advancedToggle.addActionListener(e -> toggleAdvanced());
        pluginAdvanced.addChangeListener(this::fireChanged);
        testButton.addActionListener(e -> runTestConnection());
    }

    private void toggleAdvanced() {
        boolean show = !pluginAdvanced.component().isVisible();
        pluginAdvanced.component().setVisible(show);
        advancedToggle.setText(show ? ADVANCED_EXPANDED : ADVANCED_COLLAPSED);
        panel.revalidate();
        panel.repaint();
    }

    private void edited() {
        if (!updating) {
            refreshKeyLabel();
            refreshDetected();
            fireChanged();
        }
    }

    private void fireChanged() {
        if (!updating) {
            pcs.firePropertyChange(PROP_CHANGED, null, Boolean.TRUE);
        }
    }

    private void applyServerTypePreset() {
        if (updating || !(serverTypeCombo.getSelectedItem() instanceof ServerTypeEnum type)) {
            return;
        }
        String preset = type.presetUrl();
        if (preset.isBlank()) {
            return;
        }
        String current = baseUrlField.getText().trim();
        if (current.isEmpty() || isAnotherPreset(current)) {
            baseUrlField.setText(preset);
            noteLabel.setText("Base URL changed to " + preset);
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

    private void refreshKeyLabel() {
        if (apiKeyField.getPassword().length > 0) {
            keyStateLabel.setText("Key: will be saved");
        }
        else if (OpenAiCompatiblePluginSettings.isDefaultKeySet() && !clearKeyRequested) {
            keyStateLabel.setText("Key: set");
        }
        else {
            keyStateLabel.setText("Key: not set");
        }
        refreshKeyWarning();
    }

    /**
     * The unencrypted-key warning (spec §8.6): an {@code http://} URL to a non-local host while a key is active (typed,
     * or the stored default key that has not been cleared).
     */
    private void refreshKeyWarning() {
        String url = baseUrlField.getText().trim().toLowerCase(Locale.ROOT);
        boolean keyActive = apiKeyField.getPassword().length > 0
                || OpenAiCompatiblePluginSettings.isDefaultKeySet() && !clearKeyRequested;
        keyWarningLabel.setText(url.startsWith("http://") && !BaseUrls.isLocal(url) && keyActive
                                ? "The API key would be sent unencrypted (http://) to a non-local server." : " ");
    }

    private void fillFromSettings() {
        updating = true;
        try {
            serverTypeCombo.setSelectedItem(OpenAiCompatiblePluginSettings.getServerType());
            baseUrlField.setText(OpenAiCompatiblePluginSettings.getBaseUrl());
            modelField.setText(OpenAiCompatiblePluginSettings.getModel());
            String effort = OpenAiCompatiblePluginSettings.getReasoningEffort();
            reasoningEffortCombo.setSelectedItem(effort == null ? BlankSafeComboRenderer.DEFAULT_OPTION : effort);
            apiKeyField.setText("");
            clearKeyRequested = false;
            baseUrlNote.setText(" ");
            pluginAdvanced.load();
        }
        finally {
            updating = false;
        }
        refreshKeyLabel();
        lastFacts = null;
        testStatus.setText(" ");
        testStatus.setToolTipText(null);
        refreshDetected();
    }

    @Override
    public String getTabTitle() {
        return "OpenAI Compatible";
    }

    @Override
    public JPanel getComponent() {
        return panel;
    }

    /**
     * Fills the form from the stored defaults, then runs the one-time Ollama (local) import in the background; when it
     * imported something the form shows the imported values and "Imported from Ollama (local)".
     */
    @Override
    public void load() {
        fillFromSettings();
        noteLabel.setText(" ");
        try {
            importFuture = CompletableFuture.supplyAsync(
                    OpenAiCompatiblePluginSettings::importFromOllamaOnce, BackendExecutors.probePool())
                    .thenApply(imported -> {
                        if (Boolean.TRUE.equals(imported)) {
                            SwingUtilities.invokeLater(() -> {
                                fillFromSettings();
                                noteLabel.setText("Imported from Ollama (local)");
                            });
                        }
                        return imported;
                    });
        }
        catch (RejectedExecutionException ex) {
            importFuture = CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Saves the defaults. A typed key goes to the Keyring (the "key set" flag turns on only once it was read back); the
     * stored default key is cleared when the URL moved to a different root, or when Clear was pressed.
     */
    @Override
    public void store() {
        String previousUrl = OpenAiCompatiblePluginSettings.getBaseUrl();
        String enteredUrl = baseUrlField.getText();
        String newUrl = BaseUrls.normalise(enteredUrl);
        boolean keyWasSet = OpenAiCompatiblePluginSettings.isDefaultKeySet();
        baseUrlNote.setText(BaseUrls.removedV1(enteredUrl) ? "/v1 removed — the plugin adds it" : " ");

        OpenAiCompatiblePluginSettings.setServerType((ServerTypeEnum) serverTypeCombo.getSelectedItem());
        OpenAiCompatiblePluginSettings.setBaseUrl(newUrl);
        OpenAiCompatiblePluginSettings.setModel(modelField.getText().trim());
        Object effort = reasoningEffortCombo.getSelectedItem();
        OpenAiCompatiblePluginSettings.setReasoningEffort(
                effort == null || BlankSafeComboRenderer.DEFAULT_OPTION.equals(effort) ? null : effort.toString());
        pluginAdvanced.store();

        char[] typed = apiKeyField.getPassword();
        CompletableFuture<Void> work = CompletableFuture.completedFuture(null);
        if (typed.length > 0) {
            work = keyStore.save(typed).thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    OpenAiCompatiblePluginSettings.setDefaultKeySet(true);
                }
                else {
                    notice("The default API key could not be stored in the system keyring.");
                }
            });
        }
        else if (keyWasSet && clearKeyRequested) {
            OpenAiCompatiblePluginSettings.setDefaultKeySet(false);
            work = keyStore.clear();
        }
        else if (keyWasSet && DefaultKeyRules.shouldClearDefaultKey(previousUrl, newUrl)) {
            OpenAiCompatiblePluginSettings.setDefaultKeySet(false);
            work = keyStore.clear();
            notice(DefaultKeyRules.CLEARED_NOTICE);
        }
        storeFuture = work.whenComplete((value, error) -> Arrays.fill(typed, (char) 0));
        apiKeyField.setText("");
        clearKeyRequested = false;
        refreshKeyLabel();
    }

    @Override
    public boolean isValid() {
        String url = baseUrlField.getText().trim().toLowerCase(Locale.ROOT);
        return url.isEmpty() || url.startsWith("http://") || url.startsWith("https://");
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(PROP_CHANGED, l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(PROP_CHANGED, l);
    }

    @Override
    public AiTypeEnum getAiType() {
        return AiTypeEnum.OPENAI_COMPATIBLE;
    }

    /**
     * Where notices go instead of a dialog (tests).
     */
    void setNoticeSink(Consumer<String> sink) {
        noticeSink = sink;
    }

    CompletableFuture<Boolean> importFinished() {
        CompletableFuture<Boolean> future = importFuture;
        return future == null ? CompletableFuture.completedFuture(false) : future;
    }

    CompletableFuture<Void> storeFinished() {
        return storeFuture;
    }

    OpenAiCompatiblePluginAdvancedPanel pluginAdvanced() {
        return pluginAdvanced;
    }

    JButton advancedToggle() {
        return advancedToggle;
    }

    JButton testConnectionButton() {
        return testButton;
    }

    String testConnectionStatus() {
        return testStatus.getText();
    }

    OpenAiCompatibleDetectedPanel detectedPanel() {
        return detected;
    }

    private void runTestConnection() {
        testButton.setEnabled(false);
        testStatus.setText("Testing\u2026");
        testStatus.setToolTipText(null);
        char[] typed = apiKeyField.getPassword();
        char[] key = typed.length == 0 ? null : typed;
        String url = baseUrlField.getText();
        new ConnectionTester()
                .test(url, key, List.of(), OpenAiCompatiblePluginSettings.fromPluginSettings())
                .whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    testButton.setEnabled(true);
                    applyTestResult(result, error);
                }));
    }

    private void applyTestResult(ConnectionTester.Result result, Throwable error) {
        if (error != null || result == null) {
            testStatus.setText("N/A");
            testStatus.setToolTipText("Cannot reach the server at " + baseUrlField.getText());
            lastFacts = null;
            refreshDetected();
            return;
        }
        testStatus.setText(result.text());
        testStatus.setToolTipText(result.kind() == ConnectionTester.Kind.NOT_AVAILABLE
                                  ? "Cannot reach the server at " + baseUrlField.getText() : null);
        lastFacts = factsFrom(result);
        refreshDetected();
    }

    private static DetectedFacts factsFrom(ConnectionTester.Result result) {
        if (result.kind() != ConnectionTester.Kind.CONNECTED) {
            return null;
        }
        DetectedFacts facts = DetectedFacts.of("plugin-defaults")
                .withApiBase(result.apiBase())
                .withModels(result.models());
        if (result.serverKind() != null) {
            facts = facts.withKind(result.serverKind());
        }
        return facts;
    }

    private void refreshDetected() {
        detected.update(lastFacts, snapshotSettings());
    }

    private OpenAiCompatibleSessionSettings snapshotSettings() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        ServerTypeEnum type = (ServerTypeEnum) serverTypeCombo.getSelectedItem();
        if (type != null) {
            settings.setServerType(type);
        }
        settings.setBaseUrl(BaseUrls.normalise(baseUrlField.getText()));
        return settings;
    }

    private void notice(String text) {
        Consumer<String> sink = noticeSink;
        if (sink != null) {
            sink.accept(text);
        }
        else if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> DialogPlacement.showMessageDialog(panel, text, "OpenAI Compatible",
                                                                               JOptionPane.INFORMATION_MESSAGE));
        }
    }
}
