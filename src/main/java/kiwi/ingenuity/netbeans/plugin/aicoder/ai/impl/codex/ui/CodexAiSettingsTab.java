package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.util.concurrent.ExecutionException;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CODEX;
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

    public CodexAiSettingsTab() {
        panel = new JPanel(new GridBagLayout());
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

        modelCombo = new JComboBox<>(CodexPluginSettings.KNOWN_MODELS);
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
    }

    private void fireChange() {
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
        executableField.setText(CodexPluginSettings.getExecutable());
        modelCombo.setSelectedItem(CodexPluginSettings.getModel());
        refreshEffortOptions();
        setSelectedOrDefault(effortCombo, CodexPluginSettings.getEffort());
        testResultLabel.setText(" ");
    }

    @Override
    public void store() {
        CodexPluginSettings.setExecutable(executableField.getText().strip());
        Object sel = modelCombo.getSelectedItem();
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
     * Rebuilds the effort combo's entries from {@link CodexReasoningEffortCatalog} for the currently selected model —
     * {@code (model default)} first, then any supported efforts past sessions discovered. No-op-able on a model with no
     * cached capability data (combo keeps {@code (model default)}).
     */
    private void refreshEffortOptions() {
        Object sel = modelCombo.getSelectedItem();
        String model = sel != null ? sel.toString().trim() : CodexPluginSettings.DEFAULT_MODEL;
        java.util.List<String> supported = CodexReasoningEffortCatalog.supportedEffortsFor(model);
        String[] items = new String[1 + supported.size()];
        items[0] = BlankSafeComboRenderer.DEFAULT_OPTION;
        for (int i = 0; i < supported.size(); i++) {
            items[i + 1] = supported.get(i);
        }
        effortCombo.setModel(new javax.swing.DefaultComboBoxModel<>(items));
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
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
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
