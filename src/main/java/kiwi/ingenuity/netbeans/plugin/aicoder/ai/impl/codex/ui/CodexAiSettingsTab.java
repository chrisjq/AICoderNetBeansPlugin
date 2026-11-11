package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileFilter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CODEX;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexExecutableLocator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexReasoningEffortCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = SettingsTab.class)
public final class CodexAiSettingsTab implements SettingsTab {

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final JPanel panel;
    private final JTextField executableField;
    private final JButton browseButton;
    private final JButton detectButton;
    private final JButton testButton;
    private final JLabel testResultLabel;
    private final JComboBox<String> modelCombo;
    private final JComboBox<String> effortCombo;
    private final AiPropertyListener catalogListener = event -> {
        if (event instanceof AvailableModelsEvent available) {
            SwingUtilities.invokeLater(() -> runQuietly(() -> refreshModelOptions(available.models())));
        }
    };
    private boolean catalogListenerRegistered;
    /**
     * True while the tab itself rewrites its combos, so those rewrites are not taken for user edits.
     */
    private boolean updating;

    public CodexAiSettingsTab() {
        panel = new JPanel(new GridBagLayout()) {
            @Override
            public void addNotify() {
                super.addNotify();
                addCatalogListener();
                runQuietly(() -> refreshModelOptions(List.of(CodexPluginSettings.getKnownModels())));
                CodexAiImplementation.triggerModelDiscovery();
            }

            @Override
            public void removeNotify() {
                removeCatalogListener();
                super.removeNotify();
            }
        };
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("Codex executable:"), c);

        executableField = new JTextField(30);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(executableField, c);

        browseButton = new JButton("Browse…");
        c.gridx = 2;
        c.weightx = 0;
        panel.add(browseButton, c);

        detectButton = new JButton("Auto-detect");
        c.gridx = 3;
        panel.add(detectButton, c);

        testButton = new JButton("Test");
        c.gridx = 4;
        panel.add(testButton, c);

        c.gridx = 1;
        c.gridy = 1;
        c.gridwidth = 4;
        testResultLabel = new JLabel(" ");
        testResultLabel.setFont(testResultLabel.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(testResultLabel, c);
        c.gridwidth = 1;

        c.gridx = 0;
        c.gridy = 2;
        c.weightx = 0;
        panel.add(new JLabel("Model:"), c);

        modelCombo = new JComboBox<>(new String[]{CodexPluginSettings.AUTO_MODEL});
        modelCombo.setEditable(true);
        modelCombo.setToolTipText("Default model for new Codex sessions (editable — any model ID accepted)");
        c.gridx = 1;
        c.weightx = 1;
        panel.add(modelCombo, c);

        c.gridx = 0;
        c.gridy = 3;
        c.weightx = 0;
        panel.add(new JLabel("Default effort:"), c);

        effortCombo = new JComboBox<>(new String[]{BlankSafeComboRenderer.DEFAULT_OPTION});
        effortCombo.setEditable(true);
        effortCombo.setToolTipText("Default reasoning effort for new sessions — \"" + BlankSafeComboRenderer.DEFAULT_OPTION
                + "\" omits the field, letting the model apply its own default (options from the model/list probe; editable)");
        c.gridx = 1;
        c.weightx = 1;
        panel.add(effortCombo, c);

        c.gridx = 0;
        c.gridy = 4;
        c.weighty = 1;
        c.gridwidth = 5;
        panel.add(Box.createVerticalGlue(), c);
        c.gridwidth = 1;
        c.weighty = 0;

        executableField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fireChange();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fireChange();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fireChange();
            }
        });
        modelCombo.addActionListener(e -> fireChange());
        effortCombo.addActionListener(e -> fireChange());
        browseButton.addActionListener(e -> handleBrowse());
        detectButton.addActionListener(e -> handleDetect());
        testButton.addActionListener(e -> handleTest());
        refreshModelOptions(List.of(CodexPluginSettings.getKnownModels()));
    }

    /**
     * Runs a change the tab makes to its own combos without it counting as a user edit.
     */
    private void runQuietly(Runnable change) {
        boolean wasUpdating = updating;
        updating = true;
        try {
            change.run();
        }
        finally {
            updating = wasUpdating;
        }
    }

    private void addCatalogListener() {
        if (!catalogListenerRegistered) {
            AiTypePropertyBus.getInstance().addListener(CODEX, catalogListener);
            catalogListenerRegistered = true;
        }
    }

    private void removeCatalogListener() {
        if (catalogListenerRegistered) {
            AiTypePropertyBus.getInstance().removeListener(CODEX, catalogListener);
            catalogListenerRegistered = false;
        }
    }

    private void fireChange() {
        if (updating) {
            return;
        }
        pcs.firePropertyChange(AiTypeEnum.CODEX.key(), null, null);
    }

    @Override
    public String getTabTitle() {
        return AiTypeEnum.CODEX.displayName();
    }

    @Override
    public JPanel getComponent() {
        return panel;
    }

    @Override
    public void load() {
        runQuietly(this::loadNow);
    }

    private void loadNow() {
        executableField.setText(CodexPluginSettings.getExecutable());
        refreshModelOptions(List.of(CodexPluginSettings.getKnownModels()));
        modelCombo.setSelectedItem(CodexPluginSettings.toDisplayModel(CodexPluginSettings.getModel()));
        refreshEffortOptions();
        setSelectedOrDefault(effortCombo, CodexPluginSettings.getEffort());
        testResultLabel.setText(" ");
    }

    @Override
    public void store() {
        CodexPluginSettings.setExecutable(executableField.getText().strip());
        Object sel = modelCombo.isEditable() && modelCombo.getEditor() != null
                     ? modelCombo.getEditor().getItem() : modelCombo.getSelectedItem();
        CodexPluginSettings.setModel(sel != null ? sel.toString() : CodexPluginSettings.DEFAULT_MODEL);
        CodexPluginSettings.setEffort(valueOrDefault(effortCombo));
    }

    @Override
    public boolean isValid() {
        String exe = executableField.getText().strip();
        if (exe.isEmpty()) {
            return true;
        }
        File f = new File(exe);
        return !f.isAbsolute() || f.isFile();
    }

    /**
     * Rebuilds the model combo: {@code auto} first, then the catalogue's models, with the current selection kept even
     * when the list does not contain it, so a stored custom model stays selectable.
     */
    private void refreshModelOptions(List<String> models) {
        Object editorItem = modelCombo.isEditable() && modelCombo.getEditor() != null
                            ? modelCombo.getEditor().getItem() : modelCombo.getSelectedItem();
        String selected = editorItem != null ? editorItem.toString().trim() : CodexPluginSettings.AUTO_MODEL;
        modelCombo.removeAllItems();
        modelCombo.addItem(CodexPluginSettings.AUTO_MODEL);
        if (models != null) {
            models.stream().filter(model -> model != null && !model.isBlank()).forEach(modelCombo::addItem);
        }
        String display = CodexPluginSettings.toDisplayModel(CodexPluginSettings.toStoredModel(selected));
        boolean found = false;
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            if (display.equals(modelCombo.getItemAt(i))) {
                found = true;
                break;
            }
        }
        if (!found) {
            modelCombo.addItem(display);
        }
        modelCombo.setSelectedItem(display);
    }

    private void refreshEffortOptions() {
        Object sel = modelCombo.getSelectedItem();
        String model = sel != null ? sel.toString().trim() : CodexPluginSettings.DEFAULT_MODEL;
        List<String> supported = CodexReasoningEffortCatalog.supportedEffortsFor(model);
        String[] items = new String[1 + supported.size()];
        items[0] = BlankSafeComboRenderer.DEFAULT_OPTION;
        for (int i = 0; i < supported.size(); i++) {
            items[i + 1] = supported.get(i);
        }
        effortCombo.setModel(new DefaultComboBoxModel<>(items));
    }

    private void setSelectedOrDefault(JComboBox<String> combo, String storedValue) {
        combo.setSelectedItem((storedValue == null || storedValue.isBlank()) ? BlankSafeComboRenderer.DEFAULT_OPTION : storedValue);
    }

    private String valueOrDefault(JComboBox<String> combo) {
        Object sel = combo.isEditable() && combo.getEditor() != null ? combo.getEditor().getItem() : combo.getSelectedItem();
        String s = sel != null ? sel.toString().trim() : "";
        return BlankSafeComboRenderer.DEFAULT_OPTION.equals(s) ? "" : s;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    @Override
    public AiTypeEnum getAiType() {
        return CODEX;
    }

    private void handleBrowse() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select codex executable");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().equals("codex") || f.getName().startsWith("codex.");
            }

            @Override
            public String getDescription() {
                return "codex executable";
            }
        });
        fc.setAcceptAllFileFilterUsed(true);
        String current = executableField.getText().strip();
        File startDir;
        if (!current.isEmpty()) {
            File f = new File(current);
            startDir = f.isFile() ? f.getParentFile() : f;
        }
        else {
            startDir = new File("/usr/bin");
            if (!startDir.isDirectory()) {
                startDir = new File(System.getProperty("user.home"));
            }
        }
        fc.setCurrentDirectory(startDir);
        if (fc.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            executableField.setText(fc.getSelectedFile().getAbsolutePath());
            testResultLabel.setText(" ");
        }
    }

    private void handleDetect() {
        String found = CodexExecutableLocator.locate();
        if (found != null) {
            executableField.setText(found);
            testResultLabel.setText("Detected: " + found);
        }
        else {
            testResultLabel.setText("Could not auto-detect codex executable.");
        }
    }

    private void handleTest() {
        String path = executableField.getText().strip();
        if (path.isEmpty()) {
            testResultLabel.setText("Enter a path first.");
            return;
        }
        testButton.setEnabled(false);
        testResultLabel.setText("Testing…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return CodexExecutableLocator.testExecutable(path);
            }

            @Override
            protected void done() {
                testButton.setEnabled(true);
                try {
                    testResultLabel.setText("OK: " + get());
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    testResultLabel.setText("Interrupted.");
                }
                catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    testResultLabel.setText("Error: " + (cause != null ? cause.getMessage() : ex.getMessage()));
                }
            }
        }.execute();
    }
}
