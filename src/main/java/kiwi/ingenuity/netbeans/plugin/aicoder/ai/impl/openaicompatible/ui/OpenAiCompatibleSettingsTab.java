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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ConnectionTester;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SavedServerKeys;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.SavedServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.FormLayoutUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogPlacement;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

/**
 * OpenAI Compatible options: reusable saved servers, model/reasoning settings, advanced values, and a Detected panel.
 */
@ServiceProvider(service = SettingsTab.class)
public final class OpenAiCompatibleSettingsTab implements SettingsTab {

    private static final String ADVANCED_COLLAPSED = "Advanced ▸";
    private static final String ADVANCED_EXPANDED = "Advanced ▾";

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final JPanel panel = new JPanel(new GridBagLayout());
    private final SavedServerKeys serverKeys;
    private final SavedServersEditor serversEditor;
    private final JTextField modelField = new JTextField(28);
    private final JComboBox<String> reasoningEffortCombo = new JComboBox<>(
            new String[]{BlankSafeComboRenderer.DEFAULT_OPTION, "off", "low", "medium", "high", "xhigh", "on"});
    private final JLabel noteLabel = new JLabel(" ");
    private final JButton advancedToggle = new JButton(ADVANCED_COLLAPSED);
    private final OpenAiCompatiblePluginAdvancedPanel pluginAdvanced = new OpenAiCompatiblePluginAdvancedPanel();
    private final JButton testButton = new JButton("Test connection");
    private final JLabel testStatus = new JLabel(" ");
    private final OpenAiCompatibleDetectedPanel detected = new OpenAiCompatibleDetectedPanel();
    private final ConnectionTester connectionTester = new ConnectionTester();

    private volatile Consumer<String> noticeSink;
    private boolean updating;
    private volatile CompletableFuture<Boolean> importFuture;
    private volatile CompletableFuture<Void> storeFuture = CompletableFuture.completedFuture(null);
    private DetectedFacts lastFacts;

    public OpenAiCompatibleSettingsTab() {
        this(new SavedServerKeys());
    }

    OpenAiCompatibleSettingsTab(SavedServerKeys serverKeys) {
        this.serverKeys = serverKeys;
        this.serversEditor = new SavedServersEditor(serverKeys);
        buildLayout();
        wireBehaviour();
    }

    private void buildLayout() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        int row = 0;
        row = addRow(c, row, "", new JLabel("Saved OpenAI Compatible servers."));
        row = addRow(c, row, "Servers:", serversEditor);
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
        serversEditor.addChangeListener(this::fireChanged);
        serversEditor.addSelectionListener(selected -> {
            testButton.setEnabled(selected.isPresent());
            refreshDetected();
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
        modelField.getDocument().addDocumentListener(onEdit);
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
            refreshDetected();
            fireChanged();
        }
    }

    private void fireChanged() {
        if (!updating) {
            pcs.firePropertyChange(PROP_CHANGED, null, Boolean.TRUE);
        }
    }

    private void fillFromSettings() {
        updating = true;
        try {
            modelField.setText(OpenAiCompatiblePluginSettings.getModel());
            String effort = OpenAiCompatiblePluginSettings.getReasoningEffort();
            reasoningEffortCombo.setSelectedItem(effort == null ? BlankSafeComboRenderer.DEFAULT_OPTION : effort);
            pluginAdvanced.load();
            serversEditor.load();
        }
        finally {
            updating = false;
        }
        lastFacts = null;
        testStatus.setText(" ");
        testStatus.setToolTipText(null);
        testButton.setEnabled(serversEditor.selected().isPresent());
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
     * Persists the saved server list and plugin-wide options.
     */
    @Override
    public void store() {
        OpenAiCompatiblePluginSettings.setModel(modelField.getText().trim());
        Object effort = reasoningEffortCombo.getSelectedItem();
        OpenAiCompatiblePluginSettings.setReasoningEffort(
                effort == null || BlankSafeComboRenderer.DEFAULT_OPTION.equals(effort) ? null : effort.toString());
        pluginAdvanced.store();
        boolean saved = false;
        try {
            saved = serversEditor.save().get(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException | TimeoutException ex) {
            // Report the failure below without allowing Options OK to appear successful.
        }
        if (!saved) {
            notice("Could not store server settings.");
        }
        storeFuture = CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isValid() {
        return true;
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

    SavedServersEditor serversEditor() {
        return serversEditor;
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
        Optional<SavedServer> selected = serversEditor.selected();
        if (selected.isEmpty()) {
            testButton.setEnabled(false);
            return;
        }
        testButton.setEnabled(false);
        testStatus.setText("Testing\u2026");
        testStatus.setToolTipText(null);
        SavedServer server = selected.get();
        serversEditor.keyForConnectionTest(server).whenComplete((keyResult, keyError) -> {
            char[] key = keyResult != null && keyResult.isPresent() ? keyResult.get() : null;
            connectionTester
                    .test(server.url(), server.serverType(), modelField.getText().trim(), key, List.of(),
                          OpenAiCompatiblePluginSettings.fromPluginSettings())
                    .whenComplete((result, error) -> {
                        if (key != null) {
                            Arrays.fill(key, (char) 0);
                        }
                        SwingUtilities.invokeLater(() -> {
                            testButton.setEnabled(serversEditor.selected().isPresent());
                            applyTestResult(result, error);
                        });
                    });
        });
    }

    private void applyTestResult(ConnectionTester.Result result, Throwable error) {
        String url = serversEditor.selected().map(SavedServer::url).orElse("");
        if (error != null || result == null) {
            testStatus.setText("N/A");
            testStatus.setToolTipText("Cannot reach the server at " + url);
            lastFacts = null;
            refreshDetected();
            return;
        }
        testStatus.setText(result.text());
        testStatus.setToolTipText(result.kind() == ConnectionTester.Kind.NOT_AVAILABLE
                                  ? "Cannot reach the server at " + url : null);
        lastFacts = factsFrom(result);
        refreshDetected();
    }

    private static DetectedFacts factsFrom(ConnectionTester.Result result) {
        if (result.kind() != ConnectionTester.Kind.CONNECTED) {
            return null;
        }
        return result.facts().withGeneration("plugin-defaults");
    }

    private void refreshDetected() {
        detected.update(lastFacts, snapshotSettings());
    }

    private OpenAiCompatibleSessionSettings snapshotSettings() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        serversEditor.selected().ifPresent(server -> {
            settings.setServerType(server.serverType());
            settings.setBaseUrl(server.url());
        });
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
