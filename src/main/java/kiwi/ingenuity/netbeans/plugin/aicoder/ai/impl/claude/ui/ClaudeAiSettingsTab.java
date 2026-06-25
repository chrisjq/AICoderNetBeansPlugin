package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui;

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
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeExecutableLocator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = SettingsTab.class)
public class ClaudeAiSettingsTab implements SettingsTab {

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    private final JPanel panel;
    private final JTextField executableField;
    private final JComboBox<String> modelCombo;
    private final JButton browseButton;
    private final JButton detectButton;
    private final JButton testButton;
    private final JLabel testResultLabel;

    public ClaudeAiSettingsTab() {
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("Claude executable:"), c);

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
        modelCombo = new JComboBox<>(ClaudePluginSettings.getKnownModels());
        c.gridx = 1;
        c.weightx = 1;
        panel.add(modelCombo, c);

        c.gridx = 0;
        c.gridy = 3;
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
        browseButton.addActionListener(e -> handleBrowse());
        detectButton.addActionListener(e -> handleDetect());
        testButton.addActionListener(e -> handleTest());
    }

    @Override
    public String getTabTitle() {
        return "Claude";
    }

    @Override
    public JPanel getComponent() {
        return panel;
    }

    @Override
    public void load() {
        executableField.setText(ClaudePluginSettings.getExecutable());
        String saved = ClaudePluginSettings.getModel();
        modelCombo.setSelectedItem(saved);
        testResultLabel.setText(" ");
    }

    @Override
    public void store() {
        ClaudePluginSettings.setExecutable(executableField.getText().strip());
        ClaudePluginSettings.setModel((String) modelCombo.getSelectedItem());
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

    private void handleBrowse() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select claude executable");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().equals("claude") || f.getName().startsWith("claude.");
            }

            @Override
            public String getDescription() {
                return "claude executable";
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
        String found = ClaudeExecutableLocator.locate();
        if (found != null) {
            executableField.setText(found);
            testResultLabel.setText("Detected: " + found);
        }
        else {
            testResultLabel.setText("Could not auto-detect claude executable.");
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
                return ClaudeExecutableLocator.testExecutable(path);
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
        return CLAUDE;
    }
}
