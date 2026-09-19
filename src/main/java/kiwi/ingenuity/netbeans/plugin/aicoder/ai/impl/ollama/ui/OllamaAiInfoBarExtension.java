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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaModelDiscovery;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaCapabilityHintEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

public class OllamaAiInfoBarExtension implements AiInfoBarExtension {

    /**
     * The full static level list offered when the selected model is known to support thinking. Whether it is offered at
     * all is driven by live discovery ({@link OllamaModelDiscovery#modelSupportsThinking}) — see
     * {@link #applyReasoningEffortOptions}.
     */
    private static final List<String> THINKING_LEVELS = List.of("low", "medium", "high");

    private final JComboBox<String> modelCombo = new JComboBox<>(OllamaPluginSettings.getKnownModels());
    private final JComboBox<String> reasoningEffortCombo = new JComboBox<>();
    private final JLabel hintLabel = new JLabel(" ");
    private final JButton compactBtn;
    private final JButton clearBtn;
    private final ContextGaugePanel gauge = new ContextGaugePanel();
    private final List<OllamaInfoBarListener> listeners = new ArrayList<>();
    private volatile BooleanSupplier isProcessing = () -> false;
    private volatile BooleanSupplier isSummarising = () -> false;
    private volatile int lastUsedTokens = 0;
    private volatile String baseUrl;
    private boolean programmaticModelSelection = false;
    private boolean programmaticReasoningEffortSelection = false;
    private Runnable disposeAction;

    public OllamaAiInfoBarExtension() {
        modelCombo.setEditable(true);
        modelCombo.setSelectedItem(OllamaPluginSettings.getModel());
        modelCombo.addActionListener(e -> {
            if (!programmaticModelSelection) {
                applyReasoningEffortOptions(getSelectedModel(), null);
            }
        });
        reasoningEffortCombo.setToolTipText("Thinking effort — applies to the next message");
        reasoningEffortCombo.setRenderer(new BlankSafeComboRenderer());
        applyReasoningEffortOptions(getSelectedModel(), null);
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
     * Supplies live processing state for the action-listener re-check. Not wired through the constructor because the
     * process manager the info bar needs to poll is created after this extension, in
     * OllamaAiImplementation.createInfoBarExtension.
     */
    public void setProcessingSupplier(BooleanSupplier isProcessing) {
        this.isProcessing = isProcessing != null ? isProcessing : () -> false;
    }

    /**
     * Same pattern as {@link #setProcessingSupplier}, polled for the same reason.
     */
    public void setSummarisingSupplier(BooleanSupplier isSummarising) {
        this.isSummarising = isSummarising != null ? isSummarising : () -> false;
    }

    /**
     * The server the thinking combo's live capability lookups ({@link OllamaModelDiscovery}) query — set once by
     * {@code OllamaAiImplementation.createInfoBarExtension}, before the combo is first seeded, since the session's base
     * URL is not otherwise known to this UI-only class. Does not itself trigger a refresh; call
     * {@link #setSelectedReasoningEffort} or change the model afterwards to apply it.
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
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

    public String getSelectedReasoningEffort() {
        Object sel = reasoningEffortCombo.getSelectedItem();
        return (sel == null || BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? null : sel.toString();
    }

    /**
     * Rebuilds {@link #reasoningEffortCombo}'s options for the CURRENTLY selected model before applying {@code
     * effort}, rather than calling {@code reasoningEffortCombo.setSelectedItem} directly — mirrors
     * {@code GrokAiInfoBarExtension}'s identical method, for the identical reason: a non-editable {@code JComboBox}
     * silently no-ops {@code setSelectedItem} for a value that isn't one of its current items, so a stored effort
     * invalid for the current model would otherwise leave the combo's true selection out of sync with what
     * {@link #getSelectedReasoningEffort()} returns even though the display looked fine.
     */
    public void setSelectedReasoningEffort(String effort) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedReasoningEffort(effort));
            return;
        }
        applyReasoningEffortOptions(getSelectedModel(), effort);
    }

    /**
     * Repopulates {@link #reasoningEffortCombo} with {@link #THINKING_LEVELS} unless discovery has POSITIVELY confirmed
     * {@code model} cannot think ({@link OllamaModelDiscovery#isModelKnown} true and
     * {@link OllamaModelDiscovery#modelSupportsThinking} false) — spec §1 rule 3b: "clear only on positive
     * confirmation, never on absence of data". While discovery has not yet reported on {@code model} at all, the combo
     * offers the levels optimistically, matching {@code OllamaAiProcessManager.applyThinkingCapabilityValidation}'s
     * identical send-side rule — the combo must not claim "not set" is the only option while the process manager would
     * in fact still send a pinned/inherited value for that same model. This is a UI courtesy either way; the
     * authoritative "never send an unsupported value" enforcement is that same method's at request-build time, with the
     * 4xx retry as its backstop.
     */
    private void applyReasoningEffortOptions(String model, String preferredEffort) {
        boolean wasProgrammatic = programmaticReasoningEffortSelection;
        programmaticReasoningEffortSelection = true;
        try {
            Object current = reasoningEffortCombo.getSelectedItem();
            reasoningEffortCombo.removeAllItems();
            reasoningEffortCombo.addItem(BlankSafeComboRenderer.DEFAULT_OPTION);
            List<String> supported = (!OllamaModelDiscovery.isModelKnown(baseUrl, model)
                    || OllamaModelDiscovery.modelSupportsThinking(baseUrl, model))
                                     ? THINKING_LEVELS : List.of();
            for (String level : supported) {
                reasoningEffortCombo.addItem(level);
            }
            String toSelect = (preferredEffort != null && supported.contains(preferredEffort)) ? preferredEffort
                              : (current != null && supported.contains(current.toString()) ? current.toString() : null);
            reasoningEffortCombo.setSelectedItem(toSelect != null ? toSelect : BlankSafeComboRenderer.DEFAULT_OPTION);
        }
        finally {
            programmaticReasoningEffortSelection = wasProgrammatic;
        }
    }

    public void addReasoningEffortChangeListener(java.awt.event.ActionListener listener) {
        reasoningEffortCombo.addActionListener(e -> {
            if (!programmaticReasoningEffortSelection) {
                listener.actionPerformed(e);
            }
        });
    }

    @Override
    public List<JComponent> createComponents() {
        return List.of(modelCombo, reasoningEffortCombo, compactBtn, clearBtn, gauge.component(), hintLabel);
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof OllamaModelsEvent models) {
            setAvailableModels(models.models().toArray(String[]::new));
            // The model selection itself may be unchanged, so the model combo's own listener (which would
            // otherwise refresh this) never fires — but the capability cache backing applyReasoningEffortOptions
            // was just populated (or updated) by the SAME discovery cycle that produced this event, so a model
            // this combo previously had to show as "not set" only may now have thinking levels to offer.
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> applyReasoningEffortOptions(getSelectedModel(), getSelectedReasoningEffort()));
            }
            else {
                applyReasoningEffortOptions(getSelectedModel(), getSelectedReasoningEffort());
            }
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
        if (settings instanceof OllamaSessionSettings ollama) {
            // Display only, mirroring OllamaAiImplementation.createInfoBarExtension's initial seed: falls back to
            // the global default so the combo shows what will actually be used, without writing that fallback back
            // into the session's own (still-unset) settings.
            String effort = ollama.reasoningEffort();
            setSelectedReasoningEffort(effort != null && !effort.isBlank() ? effort : OllamaPluginSettings.getReasoningEffort());
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
        // A turn already in flight snapshots the session's settings once at its start (see
        // OllamaAiProcessManager.runTurn), so a change here would not reach that turn anyway — disabled to avoid
        // implying otherwise, per the reasoning-effort design spec's info-bar rule.
        reasoningEffortCombo.setEnabled(!busy);
    }
}
