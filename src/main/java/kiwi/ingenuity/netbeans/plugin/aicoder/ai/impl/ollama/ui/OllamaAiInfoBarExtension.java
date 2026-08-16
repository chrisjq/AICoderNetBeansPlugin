package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextGaugePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaCapabilityHintEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

public class OllamaAiInfoBarExtension implements AiInfoBarExtension {

    private final JComboBox<String> modelCombo = new JComboBox<>(OllamaPluginSettings.getKnownModels());
    private final JLabel hintLabel = new JLabel(" ");
    private final JButton compactBtn;
    private final JButton clearBtn;
    private final ContextGaugePanel gauge = new ContextGaugePanel();
    private final List<OllamaInfoBarListener> listeners = new ArrayList<>();
    private volatile BooleanSupplier isProcessing = () -> false;
    private volatile BooleanSupplier isSummarising = () -> false;
    private volatile int lastUsedTokens = 0;
    private boolean programmaticModelSelection = false;
    private Runnable disposeAction;

    public OllamaAiInfoBarExtension() {
        modelCombo.setEditable(true);
        modelCombo.setSelectedItem(OllamaPluginSettings.getModel());
        hintLabel.setVisible(false);

        compactBtn = new JButton("⇒ Compact");
        compactBtn.setFont(compactBtn.getFont().deriveFont(11f));
        compactBtn.setToolTipText("Summarise and trim the oldest context now to free up space");
        compactBtn.setEnabled(false);
        compactBtn.addActionListener(e -> {
            // Re-checked here rather than trusting setEnabled(): sendPrompt()
            // sets processing=true and returns, but the repaint that greys the
            // button out lands on a later EDT cycle, leaving a narrow window
            // where a queued click still dispatches.
            if (isProcessing.getAsBoolean() || isSummarising.getAsBoolean()) {
                return;
            }
            // Optimistic: the summarising supplier only turns true once the
            // background thread reaches the broker, so the gauge would lag a
            // request/response round trip without this immediate flip.
            gauge.setSummarising(true);
            updateButtonState();
            listeners.forEach(OllamaInfoBarListener::onCompactRequested);
        });

        clearBtn = new JButton("✕ Clear");
        clearBtn.setFont(clearBtn.getFont().deriveFont(11f));
        clearBtn.setToolTipText("Clear the model's conversation memory"
                + " (the visible chat log above is kept)");
        clearBtn.setEnabled(false);
        clearBtn.addActionListener(e -> {
            if (isProcessing.getAsBoolean()) {
                return;
            }
            listeners.forEach(OllamaInfoBarListener::onClearRequested);
        });
    }

    public void addListener(OllamaInfoBarListener l) {
        listeners.add(l);
    }

    /**
     * Supplies live processing state for the action-listener re-check. Not
     * wired through the constructor because the process manager the info bar
     * needs to poll is created after this extension, in
     * OllamaAiImplementation.createInfoBarExtension.
     */
    public void setProcessingSupplier(BooleanSupplier isProcessing) {
        this.isProcessing = isProcessing != null ? isProcessing : () -> false;
    }

    /**
     * Same pattern as {@link #setProcessingSupplier}, polled for the same
     * reason.
     */
    public void setSummarisingSupplier(BooleanSupplier isSummarising) {
        this.isSummarising = isSummarising != null ? isSummarising : () -> false;
    }

    public void addModelChangeListener(java.awt.event.ActionListener listener) {
        modelCombo.addActionListener(e -> {
            if (!programmaticModelSelection) {
                listener.actionPerformed(e);
            }
        });
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
        listeners.clear();
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
        programmaticModelSelection = true;
        try {
            modelCombo.setSelectedItem(model);
        }
        finally {
            programmaticModelSelection = false;
        }
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
        return List.of(modelCombo, compactBtn, clearBtn, gauge.component(), hintLabel);
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
    public void onSessionSettingsChanged(AiSessionSettings settings) {
        if (settings instanceof AiModelSessionSettings modelSettings
                && modelSettings.model() != null && !modelSettings.model().isBlank()) {
            setSelectedModel(modelSettings.model());
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof OllamaTokenUsageEvent usage) {
            onTokenUsage(usage.used(), usage.total());
        }
    }

    private void onTokenUsage(int used, int total) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onTokenUsage(used, total));
            return;
        }
        lastUsedTokens = used;
        gauge.update(used, total);
        // Whether this event came from a turn or from compactContext()
        // finishing, any summarising that was in flight is over by now.
        gauge.setSummarising(false);
        updateButtonState();
    }

    @Override
    public void onProcessingChanged(boolean processing) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onProcessingChanged(processing));
            return;
        }
        updateButtonState();
    }

    private void updateButtonState() {
        boolean busy = isProcessing.getAsBoolean();
        boolean summarising = isSummarising.getAsBoolean();
        boolean hasContent = lastUsedTokens > 0;
        clearBtn.setEnabled(!busy && hasContent);
        compactBtn.setEnabled(!busy && !summarising && hasContent);
    }
}
