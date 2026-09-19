package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui;

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
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.GROK;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokExecutableLocator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokReasoningEffortSupport;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = SettingsTab.class)
public final class GrokAiSettingsTab implements SettingsTab {

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final JPanel panel;
    private final JTextField executableField;
    private final JButton browseButton;
    private final JButton detectButton;
    private final JButton testButton;
    private final JLabel testResultLabel;
    private final JComboBox<String> modelCombo;
    private final JComboBox<String> reasoningEffortCombo;

    /**
     * True while {@link #load()} or {@link #applyReasoningEffortOptions} is mutating a combo, so the model combo's
     * listener can tell a programmatic restore from a real user pick — mirrors {@code PiAiSettingsTab}'s
     * {@code programmatic} flag.
     */
    private boolean programmatic = false;

    public GrokAiSettingsTab() {
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 5;
        c.weightx = 1;
        panel.add(new JLabel("<html><b>Authentication:</b> Sign in through the Grok CLI in a terminal. Run <tt>grok login</tt> and complete the sign-in flow there.</html>"), c);
        c.gridwidth = 1;

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("Grok executable:"), c);

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
        c.gridy = 2;
        c.gridwidth = 4;
        testResultLabel = new JLabel(" ");
        testResultLabel.setFont(testResultLabel.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(testResultLabel, c);
        c.gridwidth = 1;

        c.gridx = 0;
        c.gridy = 3;
        c.weightx = 0;
        panel.add(new JLabel("Model:"), c);

        modelCombo = new JComboBox<>(GrokPluginSettings.KNOWN_MODELS);
        modelCombo.setEditable(true);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(modelCombo, c);

        c.gridx = 0;
        c.gridy = 4;
        c.weightx = 0;
        panel.add(new JLabel("Default reasoning effort:"), c);
        reasoningEffortCombo = new JComboBox<>();
        c.gridx = 1;
        c.weightx = 1;
        panel.add(reasoningEffortCombo, c);

        c.gridx = 0;
        c.gridy = 5;
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
        modelCombo.addActionListener(e -> {
            if (!programmatic) {
                applyReasoningEffortOptions(selectedModel(), null);
            }
            fireChange();
        });
        reasoningEffortCombo.addActionListener(e -> fireChange());
        browseButton.addActionListener(e -> handleBrowse());
        detectButton.addActionListener(e -> handleDetect());
        testButton.addActionListener(e -> handleTest());
    }

    private String selectedModel() {
        Object item = modelCombo.getEditor() != null ? modelCombo.getEditor().getItem() : modelCombo.getSelectedItem();
        return item != null ? item.toString().trim() : null;
    }

    /**
     * Repopulates {@link #reasoningEffortCombo} with the levels {@code model} supports (plus
     * {@link BlankSafeComboRenderer#DEFAULT_OPTION}), selecting {@code preferredEffort} if given and still valid for
     * {@code model}, else the combo's own current selection if that is still valid, else
     * {@link BlankSafeComboRenderer#DEFAULT_OPTION}. {@code preferredEffort} lets callers like {@link #load()} seed a
     * stored value THROUGH this validation instead of calling {@code reasoningEffortCombo.setSelectedItem} directly
     * afterwards — a non-editable {@code JComboBox} silently no-ops {@code setSelectedItem} for a value that isn't one
     * of its current items (confirmed live; the same trap {@code PiAiInfoBarExtension} hit), which previously left an
     * unsupported stored value as the combo's {@code selectedItemReminder} even though the default option was what got
     * displayed — and {@link #store()} would read that phantom value back out and re-persist it (review finding). This
     * method is the only place that mutates the combo's selection now — a UI courtesy either way; the authoritative
     * "never send an unsupported value" enforcement is {@code GrokAiProcessManager}'s at launch time, and
     * {@link #store()} independently re-validates before persisting as a second line of defence.
     */
    private void applyReasoningEffortOptions(String model, String preferredEffort) {
        boolean wasProgrammatic = programmatic;
        programmatic = true;
        try {
            Object current = reasoningEffortCombo.getSelectedItem();
            reasoningEffortCombo.removeAllItems();
            reasoningEffortCombo.addItem(BlankSafeComboRenderer.DEFAULT_OPTION);
            List<String> supported = GrokReasoningEffortSupport.supportedFor(model);
            for (String level : supported) {
                reasoningEffortCombo.addItem(level);
            }
            String toSelect = (preferredEffort != null && supported.contains(preferredEffort)) ? preferredEffort
                              : (current != null && supported.contains(current.toString()) ? current.toString() : null);
            reasoningEffortCombo.setSelectedItem(toSelect != null ? toSelect : BlankSafeComboRenderer.DEFAULT_OPTION);
        }
        finally {
            programmatic = wasProgrammatic;
        }
    }

    private void fireChange() {
        pcs.firePropertyChange(AiTypeEnum.GROK.key(), null, null);
    }

    @Override
    public String getTabTitle() {
        return AiTypeEnum.GROK.displayName();
    }

    @Override
    public JPanel getComponent() {
        return panel;
    }

    @Override
    public void load() {
        boolean wasProgrammatic = programmatic;
        programmatic = true;
        try {
            executableField.setText(GrokPluginSettings.getExecutable());
            modelCombo.setSelectedItem(GrokPluginSettings.getModel());
            String storedEffort = GrokPluginSettings.getReasoningEffort();
            applyReasoningEffortOptions(selectedModel(), (storedEffort == null || storedEffort.isBlank()) ? null : storedEffort);
            testResultLabel.setText(" ");
        }
        finally {
            programmatic = wasProgrammatic;
        }
    }

    @Override
    public void store() {
        GrokPluginSettings.setExecutable(executableField.getText().strip());
        Object sel = modelCombo.getSelectedItem();
        String model = sel != null ? sel.toString() : GrokPluginSettings.DEFAULT_MODEL;
        GrokPluginSettings.setModel(model);
        Object effort = reasoningEffortCombo.getSelectedItem();
        String effortStr = (effort != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(effort.toString())) ? effort.toString() : null;
        // Re-validated here, not just trusted from the combo's own selection: a non-editable JComboBox can retain an
        // unsupported value as its selectedItemReminder even after a repopulation that displays the default option
        // instead (see applyReasoningEffortOptions's javadoc) — this is the second, independent line of defence the
        // review finding asked for, so an unsupported value can never reach persisted storage even if some future UI
        // path sets the combo's selection without going through applyReasoningEffortOptions.
        if (effortStr != null && !GrokReasoningEffortSupport.supportedFor(model).contains(effortStr)) {
            effortStr = null;
        }
        GrokPluginSettings.setReasoningEffort(effortStr != null ? effortStr : "");
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
        return GROK;
    }

    private void handleBrowse() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select grok executable");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().equals("grok") || f.getName().startsWith("grok.");
            }

            @Override
            public String getDescription() {
                return "grok executable";
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
        String found = GrokExecutableLocator.locate();
        if (found != null) {
            executableField.setText(found);
            testResultLabel.setText("Detected: " + found);
        }
        else {
            testResultLabel.setText("Could not auto-detect grok executable.");
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
                return GrokExecutableLocator.testExecutable(path);
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
