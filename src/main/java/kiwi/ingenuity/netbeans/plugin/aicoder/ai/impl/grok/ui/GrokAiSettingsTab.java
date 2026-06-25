package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.GROK;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = SettingsTab.class)
public final class GrokAiSettingsTab implements SettingsTab {

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final JPanel panel;
    private final JPasswordField apiKeyField;
    private final JComboBox<String> modelCombo;

    public GrokAiSettingsTab() {
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("xAI API Key:"), c);

        apiKeyField = new JPasswordField(30);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(apiKeyField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("Model:"), c);

        modelCombo = new JComboBox<>(GrokPluginSettings.KNOWN_MODELS);
        modelCombo.setEditable(true);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(modelCombo, c);

        // Mark the Options dialog dirty (enables Apply/OK) when the user edits.
        apiKeyField.getDocument().addDocumentListener(new DocumentListener() {
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
        apiKeyField.setText(GrokPluginSettings.getApiKey());
        modelCombo.setSelectedItem(GrokPluginSettings.getModel());
    }

    @Override
    public void store() {
        GrokPluginSettings.setApiKey(new String(apiKeyField.getPassword()));
        Object sel = modelCombo.getSelectedItem();
        GrokPluginSettings.setModel(sel != null ? sel.toString() : GrokPluginSettings.DEFAULT_MODEL);
    }

    @Override
    public boolean isValid() {
        return true;
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
}
