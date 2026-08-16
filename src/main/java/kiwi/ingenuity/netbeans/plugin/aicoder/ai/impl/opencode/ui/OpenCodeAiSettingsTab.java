package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui;

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
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.OPENCODE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeExecutableLocator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = SettingsTab.class)
public final class OpenCodeAiSettingsTab implements SettingsTab {

    private static final String[] MODE_OPTIONS = {"build", "plan"};

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final JPanel panel;
    private final JTextField executableField;
    private final JButton browseButton;
    private final JButton detectButton;
    private final JButton testButton;
    private final JLabel testResultLabel;
    private final JComboBox<String> modelCombo;
    private final JComboBox<String> modeCombo;

    public OpenCodeAiSettingsTab() {
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("OpenCode executable:"), c);

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

        modelCombo = new JComboBox<>(OpenCodePluginSettings.KNOWN_MODELS);
        modelCombo.setEditable(true);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(modelCombo, c);

        c.gridx = 0;
        c.gridy = 3;
        c.weightx = 0;
        panel.add(new JLabel("Default mode:"), c);

        modeCombo = new JComboBox<>(MODE_OPTIONS);
        modeCombo.setEditable(true);
        modeCombo.setToolTipText("Default agent mode for new sessions (build = full agent, plan = read-only planning)");
        c.gridx = 1;
        c.weightx = 1;
        panel.add(modeCombo, c);

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
        modeCombo.addActionListener(e -> fireChange());
        browseButton.addActionListener(e -> handleBrowse());
        detectButton.addActionListener(e -> handleDetect());
        testButton.addActionListener(e -> handleTest());
    }

    private void fireChange() {
        pcs.firePropertyChange(AiTypeEnum.OPENCODE.key(), null, null);
    }

    @Override
    public String getTabTitle() {
        return AiTypeEnum.OPENCODE.displayName();
    }

    @Override
    public JPanel getComponent() {
        return panel;
    }

    @Override
    public void load() {
        executableField.setText(OpenCodePluginSettings.getExecutable());
        modelCombo.setSelectedItem(OpenCodePluginSettings.getModel());
        modeCombo.setSelectedItem(OpenCodePluginSettings.getMode());
        testResultLabel.setText(" ");
    }

    @Override
    public void store() {
        OpenCodePluginSettings.setExecutable(executableField.getText().strip());
        Object sel = modelCombo.getSelectedItem();
        OpenCodePluginSettings.setModel(sel != null ? sel.toString() : OpenCodePluginSettings.DEFAULT_MODEL);
        Object modeSel = modeCombo.getSelectedItem();
        OpenCodePluginSettings.setMode(modeSel != null ? modeSel.toString() : OpenCodePluginSettings.DEFAULT_MODE);
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
        return OPENCODE;
    }

    private void handleBrowse() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select opencode executable");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().equals("opencode") || f.getName().startsWith("opencode.");
            }

            @Override
            public String getDescription() {
                return "opencode executable";
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
        String found = OpenCodeExecutableLocator.locate();
        if (found != null) {
            executableField.setText(found);
            testResultLabel.setText("Detected: " + found);
        }
        else {
            testResultLabel.setText("Could not auto-detect opencode executable.");
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
                return OpenCodeExecutableLocator.testExecutable(path);
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
