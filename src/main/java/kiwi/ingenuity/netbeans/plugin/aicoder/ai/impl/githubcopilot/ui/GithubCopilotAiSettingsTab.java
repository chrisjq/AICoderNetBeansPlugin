package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.ui;

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
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.GitHubCoPilot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotExecutableLocator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = SettingsTab.class)
public final class GithubCopilotAiSettingsTab implements SettingsTab {

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final JPanel panel;
    private final JTextField executableField;
    private final JComboBox<String> modelCombo;
    private final JComboBox<String> reasoningEffortCombo;
    private final JButton browseButton;
    private final JButton detectButton;
    private final JButton testButton;
    private final JLabel testResultLabel;

    public GithubCopilotAiSettingsTab() {
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 5;
        c.weightx = 1;
        panel.add(new JLabel("<html><b>Authentication:</b> Sign in through the GitHub Copilot CLI in a terminal. Run <tt>copilot login</tt> and complete the sign-in flow there.</html>"), c);
        c.gridwidth = 1;

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("Copilot executable:"), c);

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

        modelCombo = new JComboBox<>(GithubCopilotPluginSettings.getKnownModels());
        modelCombo.setEditable(true);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(modelCombo, c);

        c.gridx = 0;
        c.gridy = 4;
        c.weightx = 0;
        panel.add(new JLabel("Default reasoning effort:"), c);

        reasoningEffortCombo = new JComboBox<>();
        reasoningEffortCombo.setToolTipText("Default reasoning effort for new sessions — options depend on the selected model");
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
                fireChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fireChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fireChanged();
            }
        });
        modelCombo.addActionListener(e -> {
            fireChanged();
            refreshReasoningEffortOptions(currentModelText(), null);
        });
        reasoningEffortCombo.addActionListener(e -> fireChanged());
        browseButton.addActionListener(e -> handleBrowse());
        detectButton.addActionListener(e -> handleDetect());
        testButton.addActionListener(e -> handleTest());
    }

    @Override
    public String getTabTitle() {
        return AiTypeEnum.GitHubCoPilot.displayName();
    }

    @Override
    public JPanel getComponent() {
        return panel;
    }

    @Override
    public void load() {
        executableField.setText(GithubCopilotPluginSettings.getExecutable());
        modelCombo.setSelectedItem(GithubCopilotPluginSettings.getModel());
        String storedEffort = GithubCopilotPluginSettings.getReasoningEffort();
        refreshReasoningEffortOptions(currentModelText(), (storedEffort == null || storedEffort.isBlank()) ? null : storedEffort);
        testResultLabel.setText(" ");
    }

    @Override
    public void store() {
        GithubCopilotPluginSettings.setExecutable(executableField.getText().strip());
        Object sel = modelCombo.getSelectedItem();
        String model = sel != null ? sel.toString() : GithubCopilotPluginSettings.DEFAULT_MODEL;
        GithubCopilotPluginSettings.setModel(model);
        Object effortSel = reasoningEffortCombo.getSelectedItem();
        String effort = (effortSel == null || BlankSafeComboRenderer.DEFAULT_OPTION.equals(effortSel)) ? null : effortSel.toString();
        // Defensive re-validation against the live per-model list, not just trust in the combo's own selected item:
        // reasoningEffortCombo is non-editable, and JComboBox.setSelectedItem on a non-editable combo can leave
        // getSelectedItem() returning a value that is no longer one of the combo's own items (e.g. a stale
        // selection retained across a refresh that dropped it) — silently persisting an unsupported value the user
        // has no way to clear from the UI. Hit exactly this on pi; never persist a value the current model doesn't
        // support.
        if (effort != null && !GithubCopilotPluginSettings.getSupportedReasoningEfforts(model).contains(effort)) {
            effort = null;
        }
        GithubCopilotPluginSettings.setReasoningEffort(effort != null ? effort : "");
    }

    @Override
    public boolean isValid() {
        String exe = executableField.getText().strip();
        if (exe.isEmpty()) {
            return true;
        }
        java.io.File f = new java.io.File(exe);
        return !f.isAbsolute() || f.isFile();
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(PROP_CHANGED, l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(PROP_CHANGED, l);
    }

    private void fireChanged() {
        pcs.firePropertyChange(PROP_CHANGED, null, null);
    }

    private String currentModelText() {
        Object sel = modelCombo.getSelectedItem();
        if (sel != null && !sel.toString().isBlank()) {
            return sel.toString().trim();
        }
        if (modelCombo.isEditable() && modelCombo.getEditor() != null) {
            Object item = modelCombo.getEditor().getItem();
            if (item != null && !item.toString().isBlank()) {
                return item.toString().trim();
            }
        }
        return null;
    }

    /**
     * Rebuilds {@link #reasoningEffortCombo}'s options from {@code model}'s live-discovered supported list — empty/
     * absent means "no support": only {@link BlankSafeComboRenderer#DEFAULT_OPTION} is offered. Nothing about effort
     * levels is hardcoded here.
     */
    private void refreshReasoningEffortOptions(String model, String preferredEffort) {
        java.util.List<String> supported = GithubCopilotPluginSettings.getSupportedReasoningEfforts(model);
        Object currentSel = reasoningEffortCombo.getSelectedItem();
        String current = (currentSel == null || BlankSafeComboRenderer.DEFAULT_OPTION.equals(currentSel)) ? null : currentSel.toString();
        String toSelect = (preferredEffort != null && supported.contains(preferredEffort)) ? preferredEffort
                          : (current != null && supported.contains(current) ? current : null);
        reasoningEffortCombo.removeAllItems();
        reasoningEffortCombo.addItem(BlankSafeComboRenderer.DEFAULT_OPTION);
        for (String effort : supported) {
            reasoningEffortCombo.addItem(effort);
        }
        reasoningEffortCombo.setSelectedItem(toSelect != null ? toSelect : BlankSafeComboRenderer.DEFAULT_OPTION);
    }

    private void handleBrowse() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select copilot executable");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().equals("copilot") || f.getName().startsWith("copilot.");
            }

            @Override
            public String getDescription() {
                return "copilot executable";
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
        String found = GithubCopilotExecutableLocator.locate();
        if (found != null) {
            executableField.setText(found);
            testResultLabel.setText("Detected: " + found);
        }
        else {
            testResultLabel.setText("Could not auto-detect copilot executable.");
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
                return GithubCopilotExecutableLocator.testExecutable(path);
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

    @Override
    public AiTypeEnum getAiType() {
        return GitHubCoPilot;
    }
}
