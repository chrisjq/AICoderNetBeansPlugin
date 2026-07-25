package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui;

import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaCapabilityHintEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

public class OllamaAiInfoBarExtension implements AiInfoBarExtension {

    private final JComboBox<String> modelCombo = new JComboBox<>(OllamaPluginSettings.getKnownModels());
    private final JLabel hintLabel = new JLabel(" ");
    private Runnable disposeAction;

    public OllamaAiInfoBarExtension() {
        modelCombo.setEditable(true);
        modelCombo.setSelectedItem(OllamaPluginSettings.getModel());
        hintLabel.setVisible(false);
    }

    public void addModelChangeListener(java.awt.event.ActionListener listener) {
        modelCombo.addActionListener(listener);
    }

    public void setDisposeAction(Runnable disposeAction) {
        this.disposeAction = disposeAction;
    }

    @Override
    public void dispose() {
        if (disposeAction != null) {
            disposeAction.run();
            disposeAction = null;
        }
    }

    public String getSelectedModel() {
        Object item = modelCombo.getEditor().getItem();
        return item != null ? item.toString().trim() : null;
    }

    public void setSelectedModel(String model) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedModel(model));
            return;
        }
        modelCombo.setSelectedItem(model);
    }

    public void setAvailableModels(String[] models) {
        if (models == null || models.length == 0) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setAvailableModels(models));
            return;
        }
        String current = getSelectedModel();
        modelCombo.removeAllItems();
        for (String model : models) {
            modelCombo.addItem(model);
        }
        modelCombo.setSelectedItem(current);
        if (current != null && !current.equals(modelCombo.getSelectedItem())) {
            modelCombo.getEditor().setItem(current);
        }
    }

    @Override
    public List<JComponent> createComponents() {
        return List.of(modelCombo, hintLabel);
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof OllamaModelsEvent models) {
            setAvailableModels(models.models().toArray(String[]::new));
        }
        else if (event instanceof OllamaCapabilityHintEvent hint) {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> onPropertyEvent(hint));
                return;
            }
            hintLabel.setText(hint.message() != null ? hint.message() : " ");
            hintLabel.setVisible(hint.message() != null && !hint.message().isBlank());
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
    }
}
