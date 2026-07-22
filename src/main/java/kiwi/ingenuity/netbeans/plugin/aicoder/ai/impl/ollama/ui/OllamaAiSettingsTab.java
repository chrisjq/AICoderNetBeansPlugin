package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaModelDiscovery;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = SettingsTab.class)
public final class OllamaAiSettingsTab implements SettingsTab {

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final JPanel panel;
    private final JTextField baseUrlField;
    private final JComboBox<String> modelCombo;
    private final JButton testButton;
    private final JLabel testResultLabel;

    public OllamaAiSettingsTab() {
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        panel.add(new JLabel("<html><b>Ollama (Local)</b> — HTTP backend, no API key required.</html>"), c);
        c.gridwidth = 1;

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("Base URL:"), c);

        baseUrlField = new JTextField(30);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(baseUrlField, c);

        testButton = new JButton("Test");
        c.gridx = 2;
        c.weightx = 0;
        panel.add(testButton, c);

        c.gridx = 1;
        c.gridy = 2;
        c.gridwidth = 2;
        testResultLabel = new JLabel(" ");
        testResultLabel.setFont(testResultLabel.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(testResultLabel, c);
        c.gridwidth = 1;

        c.gridx = 0;
        c.gridy = 3;
        c.weightx = 0;
        panel.add(new JLabel("Model:"), c);

        modelCombo = new JComboBox<>(OllamaPluginSettings.getKnownModels());
        modelCombo.setEditable(true);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(modelCombo, c);
        c.gridwidth = 1;

        c.gridx = 0;
        c.gridy = 4;
        c.weighty = 1;
        c.gridwidth = 3;
        panel.add(Box.createVerticalGlue(), c);
        c.gridwidth = 1;
        c.weighty = 0;

        baseUrlField.getDocument().addDocumentListener(new DocumentListener() {
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
        testButton.addActionListener(e -> handleTest());
    }

    @Override
    public String getTabTitle() {
        return AiTypeEnum.OLLAMA_LOCAL.displayName();
    }

    @Override
    public JPanel getComponent() {
        return panel;
    }

    @Override
    public void load() {
        baseUrlField.setText(OllamaPluginSettings.getBaseUrl());
        modelCombo.setSelectedItem(OllamaPluginSettings.getModel());
        testResultLabel.setText(" ");
    }

    @Override
    public void store() {
        OllamaPluginSettings.setBaseUrl(baseUrlField.getText().strip());
        Object sel = modelCombo.getSelectedItem();
        OllamaPluginSettings.setModel(
                sel != null ? sel.toString() : OllamaPluginSettings.DEFAULT_MODEL);
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
        return AiTypeEnum.OLLAMA_LOCAL;
    }

    private void fireChanged() {
        pcs.firePropertyChange(PROP_CHANGED, null, null);
    }

    private void handleTest() {
        String url = baseUrlField.getText().strip();
        if (url.isEmpty()) {
            testResultLabel.setText("Enter a Base URL first.");
            return;
        }
        testButton.setEnabled(false);
        testResultLabel.setText("Testing…");
        OllamaModelDiscovery.discoverAsync(url,
                models -> SwingUtilities.invokeLater(() -> {
                    testButton.setEnabled(true);
                    if (models != null && models.length > 0) {
                        testResultLabel.setText("Connected — " + models.length + " model(s) found");
                        OllamaPluginSettings.setDiscoveredModels(models);
                        String current = modelCombo.getEditor().getItem() != null
                                ? modelCombo.getEditor().getItem().toString() : null;
                        modelCombo.removeAllItems();
                        for (String m : models) {
                            modelCombo.addItem(m);
                        }
                        if (current != null) {
                            modelCombo.setSelectedItem(current);
                        }
                    }
                    else {
                        testResultLabel.setText("Connected — no models returned");
                    }
                }),
                hint -> {
                    if (hint != null) {
                        SwingUtilities.invokeLater(() -> testResultLabel.setText("Error: " + hint));
                    }
                });
    }
}
