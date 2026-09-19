package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.Box;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.PI;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiExecutableLocator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiModelDiscovery;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = SettingsTab.class)
public class PiAiSettingsTab implements SettingsTab {

    /**
     * "Not set" entry for the MODEL combo only — a separate concept from the thinking-level combo's shared
     * {@link BlankSafeComboRenderer#DEFAULT_OPTION}; the model picker's own wording is out of scope for that
     * unification.
     */
    private static final String MODEL_DEFAULT_LABEL = "(pi default)";
    private static final String[] THINKING_LEVEL_OPTIONS = {
        BlankSafeComboRenderer.DEFAULT_OPTION, "off", "minimal", "low", "medium", "high", "xhigh", "max"
    };

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    private final JPanel panel;
    private final JTextField executableField;
    private final JButton browseButton;
    private final JButton detectButton;
    private final JButton testButton;
    private final JLabel testResultLabel;
    private final JLabel versionStatusLabel;
    private final JButton verifyButton;
    private final JComboBox<String> modelCombo;
    private final JButton refreshModelsButton;
    private final JComboBox<String> thinkingLevelCombo;

    private volatile PiVersionCheck currentVersionCheck;

    /**
     * True while {@link #load()} or {@link #applyDiscoveredModels} is mutating the executable field or a combo, so
     * {@link #fireChanged()} can tell a programmatic restore from a real user edit — mirrors
     * {@code PiAiInfoBarExtension}'s {@code programmatic} flag. Saved and restored (not just set/cleared) around each
     * guarded block because {@link #load()} itself calls {@link #applyDiscoveredModels}, and an unconditional clear on
     * the inner call's exit would prematurely stop guarding the outer one.
     */
    private boolean programmatic = false;

    public PiAiSettingsTab() {
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("pi executable:"), c);

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

        c.gridy = 2;
        versionStatusLabel = new JLabel(" ");
        versionStatusLabel.setFont(versionStatusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(versionStatusLabel, c);
        c.gridwidth = 1;

        verifyButton = new JButton("Verify…");
        verifyButton.setVisible(false);
        c.gridx = 4;
        c.gridy = 2;
        panel.add(verifyButton, c);

        c.gridx = 0;
        c.gridy = 3;
        c.weightx = 0;
        panel.add(new JLabel("Default model:"), c);
        modelCombo = new JComboBox<>();
        modelCombo.setEditable(true);
        c.gridx = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        panel.add(modelCombo, c);
        c.gridwidth = 1;
        refreshModelsButton = new JButton("Refresh");
        c.gridx = 4;
        c.weightx = 0;
        panel.add(refreshModelsButton, c);

        c.gridx = 0;
        c.gridy = 4;
        panel.add(new JLabel("Default thinking level:"), c);
        thinkingLevelCombo = new JComboBox<>(THINKING_LEVEL_OPTIONS);
        c.gridx = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        panel.add(thinkingLevelCombo, c);
        c.gridwidth = 1;

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
        modelCombo.addActionListener(e -> fireChanged());
        thinkingLevelCombo.addActionListener(e -> fireChanged());
        browseButton.addActionListener(e -> handleBrowse());
        detectButton.addActionListener(e -> handleDetect());
        testButton.addActionListener(e -> handleTest());
        refreshModelsButton.addActionListener(e -> handleRefreshModels());
        verifyButton.addActionListener(e -> handleVerify());
    }

    @Override
    public String getTabTitle() {
        return "Pi";
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
            executableField.setText(PiPluginSettings.getExecutable());
            applyDiscoveredModels(Arrays.asList(PiPluginSettings.getKnownModels()));
            setSelectedOrDefault(modelCombo, PiPluginSettings.getModel(), MODEL_DEFAULT_LABEL);
            setSelectedOrDefault(thinkingLevelCombo, PiPluginSettings.getThinkingLevel(), BlankSafeComboRenderer.DEFAULT_OPTION);
            testResultLabel.setText(" ");
            currentVersionCheck = null;
            versionStatusLabel.setText(" ");
            verifyButton.setVisible(false);
        }
        finally {
            programmatic = wasProgrammatic;
        }
        autoProbeVersion();
    }

    /**
     * Runs the version probe once in the background when the tab opens, so the version status/Verify button show
     * without waiting for an explicit Test click. Uses the field's current path, or an auto-detected one if the field
     * is empty; leaves {@link #testResultLabel} and {@link #testButton} untouched either way — a failed probe here just
     * leaves the version status blank, same as before this existed.
     */
    private void autoProbeVersion() {
        String path = executableField.getText().strip();
        if (path.isEmpty()) {
            path = PiExecutableLocator.locate();
        }
        if (path == null || path.isEmpty()) {
            return;
        }
        String probePath = path;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return PiExecutableLocator.testExecutable(probePath);
            }

            @Override
            protected void done() {
                try {
                    updateVersionStatus(get());
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                catch (ExecutionException ex) {
                    // Silent: this is a background convenience probe, not a user-initiated Test click.
                }
            }
        }.execute();
    }

    @Override
    public void store() {
        PiPluginSettings.setExecutable(executableField.getText().strip());
        PiPluginSettings.setModel(valueOrDefault(modelCombo, MODEL_DEFAULT_LABEL));
        PiPluginSettings.setThinkingLevel(valueOrDefault(thinkingLevelCombo, BlankSafeComboRenderer.DEFAULT_OPTION));
    }

    @Override
    public boolean isValid() {
        return PiPluginSettings.isValidExecutablePath(executableField.getText().strip());
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(PI.key(), l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(PI.key(), l);
    }

    @Override
    public AiTypeEnum getAiType() {
        return PI;
    }

    private void fireChanged() {
        if (programmatic) {
            return;
        }
        pcs.firePropertyChange(PI.key(), null, null);
    }

    private void setSelectedOrDefault(JComboBox<String> combo, String storedValue, String defaultLabel) {
        combo.setSelectedItem((storedValue == null || storedValue.isBlank()) ? defaultLabel : storedValue);
    }

    private String valueOrDefault(JComboBox<String> combo, String defaultLabel) {
        Object sel = combo.isEditable() && combo.getEditor() != null ? combo.getEditor().getItem() : combo.getSelectedItem();
        String s = sel != null ? sel.toString().trim() : "";
        return defaultLabel.equals(s) ? "" : s;
    }

    private void handleBrowse() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select pi executable");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().equals("pi") || f.getName().startsWith("pi.");
            }

            @Override
            public String getDescription() {
                return "pi executable";
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
        String found = PiExecutableLocator.locate();
        if (found != null) {
            executableField.setText(found);
            testResultLabel.setText("Detected: " + found);
        }
        else {
            testResultLabel.setText("Could not auto-detect pi executable.");
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
                return PiExecutableLocator.testExecutable(path);
            }

            @Override
            protected void done() {
                testButton.setEnabled(true);
                try {
                    String output = get();
                    testResultLabel.setText("OK: pi " + output);
                    updateVersionStatus(output);
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

    private void handleRefreshModels() {
        refreshModelsButton.setEnabled(false);
        String path = executableField.getText().strip();
        // discoverAsync guarantees exactly one terminal onResult call for every outcome (success, busy-skip,
        // exhausted retries), but the re-enable is still put in a finally here rather than just ordered first —
        // that way this button coming back is guaranteed by this method's own structure, not only by trusting
        // discoverAsync's contract to never regress.
        PiModelDiscovery.discoverAsync(path.isEmpty() ? null : path, models -> SwingUtilities.invokeLater(() -> {
                                   try {
                                       applyDiscoveredModels(models);
                                   }
                                   finally {
                                       refreshModelsButton.setEnabled(true);
                                   }
                               }));
    }

    private void applyDiscoveredModels(List<String> models) {
        boolean wasProgrammatic = programmatic;
        programmatic = true;
        try {
            Object selected = modelCombo.getSelectedItem();
            modelCombo.removeAllItems();
            modelCombo.addItem(MODEL_DEFAULT_LABEL);
            for (String m : models) {
                modelCombo.addItem(m);
            }
            modelCombo.setSelectedItem(selected != null ? selected : MODEL_DEFAULT_LABEL);
        }
        finally {
            programmatic = wasProgrammatic;
        }
    }

    private void handleVerify() {
        PiVersionCheck check = currentVersionCheck;
        if (check == null) {
            return;
        }
        PiVersionWarningDialog.show(panel, check);
        updateVersionStatus(check.installedVersion());
    }

    private void updateVersionStatus(String installedVersion) {
        PiVersionCheck check = new PiVersionCheck(installedVersion);
        currentVersionCheck = check;
        versionStatusLabel.setText(versionStatusText(check));
        verifyButton.setVisible(warningApplies(check) || check.isMarkedNotWorkingThisSession());
    }

    /**
     * Package-private so it is directly unit testable without building the whole tab.
     */
    static String versionStatusText(PiVersionCheck check) {
        if (check.installedVersion() == null || check.installedVersion().isBlank()) {
            return " ";
        }
        if (check.isTestedVersion()) {
            return "Tested with this plugin";
        }
        if (check.installedVersion().equals(PiPluginSettings.getVerifiedVersion())) {
            return "Verified by you";
        }
        return "Not verified (tested: " + check.testedVersion() + ".x)";
    }

    static boolean warningApplies(PiVersionCheck check) {
        return check.isWarningApplies();
    }

    /**
     * Package-private test accessors — {@link #autoProbeVersion()} runs its {@code SwingWorker} asynchronously, so
     * tests need to observe its result without a public getter cluttering the real API.
     */
    String versionStatusLabelTextForTests() {
        return versionStatusLabel.getText();
    }

    boolean verifyButtonVisibleForTests() {
        return verifyButton.isVisible();
    }

    /**
     * Invokes the same handler the Refresh button's own {@code ActionListener} calls, without needing a live Swing
     * click — lets tests drive {@link #handleRefreshModels()} directly, e.g. to fire it twice in a row.
     */
    void triggerRefreshForTests() {
        handleRefreshModels();
    }

    boolean refreshModelsButtonEnabledForTests() {
        return refreshModelsButton.isEnabled();
    }
}
