package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextGaugePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.OllamaModelDiscovery;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaCapabilityHintEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.events.OllamaTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.GuardedCombo;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

public class OllamaAiInfoBarExtension implements AiInfoBarExtension {

    /**
     * The full static level list offered when the selected model is known to support thinking. Whether it is
     * offered at all is driven by live discovery ({@link OllamaModelDiscovery#modelSupportsThinking}) — see
     * {@link #applyReasoningEffortOptions}.
     */
    private static final List<String> THINKING_LEVELS = List.of("low", "medium", "high");

    private final JComboBox<String> modelCombo = new JComboBox<>(OllamaPluginSettings.getKnownModels());
    private final JComboBox<String> reasoningEffortCombo = new JComboBox<>();
    private final GuardedCombo<String> modelGuard = new GuardedCombo<>(modelCombo);
    private final GuardedCombo<String> effortGuard = new GuardedCombo<>(reasoningEffortCombo);
    private final JLabel hintLabel = new JLabel(" ");
    private final JButton compactBtn;
    private final JButton clearBtn;
    private final ContextGaugePanel gauge = new ContextGaugePanel();
    private final List<OllamaInfoBarListener> listeners = new ArrayList<>();
    /**
     * Set by {@link #onBusyChanged}, the single busy signal now — fed by the core UI's own lock on Send for a
     * turn and by {@code OllamaAiProcessManager.compactContext}'s {@code runWork} for a compaction (see
     * {@code AiTopComponent.enterBusy}/{@code leaveBusy}). Re-read by the button click handlers below to
     * catch the narrow window between {@code setEnabled(false)} and its repaint landing on the EDT.
     */
    private volatile boolean busy = false;
    private volatile int lastUsedTokens = 0;
    private volatile String baseUrl;

    public OllamaAiInfoBarExtension() {
        modelCombo.setEditable(true);
        modelCombo.setSelectedItem(OllamaPluginSettings.getModel());
        modelGuard.addActionListener(e -> applyReasoningEffortOptions(getSelectedModel(), null));
        reasoningEffortCombo.setToolTipText("Thinking effort — applies to the next message");
        reasoningEffortCombo.setRenderer(new BlankSafeComboRenderer());
        applyReasoningEffortOptions(getSelectedModel(), null);
        hintLabel.setVisible(false);

        compactBtn = new JButton("⇒ Compact");
        compactBtn.setFont(compactBtn.getFont().deriveFont(11f));
        compactBtn.setToolTipText("Summarise and trim the oldest context now to free up space");
        compactBtn.setEnabled(false);
        compactBtn.addActionListener(e -> {
            // Re-checked here rather than trusting setEnabled(): a queued click can still dispatch in the
            // narrow window between onBusyChanged(true) and its repaint landing on the EDT.
            if (busy) {
                return;
            }
            // Flipped only once a listener confirms the compaction actually started (posted BUSY) —
            // never optimistically before asking. A refusal (not running, already busy, or runWork
            // declining) never fires onBusyChanged, so an optimistic flip here would spin the gauge
            // forever with nothing to clear it.
            boolean anyStarted = false;
            for (OllamaInfoBarListener l : listeners) {
                anyStarted |= l.onCompactRequested();
            }
            if (anyStarted) {
                gauge.setSummarising(true);
            }
        });

        clearBtn = new JButton("✕ Clear");
        clearBtn.setFont(clearBtn.getFont().deriveFont(11f));
        clearBtn.setToolTipText("Clear the model's conversation memory"
                                + " (the visible chat log above is kept)");
        clearBtn.setEnabled(false);
        clearBtn.addActionListener(e -> {
            if (busy) {
                return;
            }
            listeners.forEach(OllamaInfoBarListener::onClearRequested);
        });
    }

    public void addListener(OllamaInfoBarListener l) {
        listeners.add(l);
    }

    /**
     * The server the thinking combo's live capability lookups ({@link OllamaModelDiscovery}) query — set once
     * by {@code OllamaAiImplementation.createInfoBarExtension}, before the combo is first seeded, since the
     * session's base URL is not otherwise known to this UI-only class. Does not itself trigger a refresh;
     * call {@link #setSelectedReasoningEffort} or change the model afterwards to apply it.
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void addModelChangeListener(java.awt.event.ActionListener listener) {
        modelGuard.addActionListener(listener);
    }

    @Override
    public void dispose() {
        listeners.clear();
    }

    public String getSelectedModel() {
        Object item = modelCombo.getEditor().getItem();
        return item != null ? item.toString().trim() : null;
    }

    public void setSelectedModel(String model) {
        modelGuard.runProgrammatic(() -> modelCombo.setSelectedItem(model));
    }

    public void setAvailableModels(String[] models) {
        if (models == null || models.length == 0) {
            return;
        }
        String current = getSelectedModel();
        modelGuard.runProgrammatic(() -> {
            modelCombo.removeAllItems();
            for (String model : models) {
                modelCombo.addItem(model);
            }
            modelCombo.setSelectedItem(current);
            if (current != null && !current.equals(modelCombo.getSelectedItem())) {
                modelCombo.getEditor().setItem(current);
            }
        });
    }

    public String getSelectedReasoningEffort() {
        Object sel = reasoningEffortCombo.getSelectedItem();
        return (sel == null || BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? null : sel.toString();
    }

    /**
     * Rebuilds {@link #reasoningEffortCombo}'s options for the CURRENTLY selected model before applying {@code
     * effort}, rather than calling {@code reasoningEffortCombo.setSelectedItem} directly — mirrors
     * {@code GrokAiInfoBarExtension}'s identical method, for the identical reason: a non-editable
     * {@code JComboBox} silently no-ops {@code setSelectedItem} for a value that isn't one of its current
     * items, so a stored effort invalid for the current model would otherwise leave the combo's true
     * selection out of sync with what {@link #getSelectedReasoningEffort()} returns even though the display
     * looked fine.
     */
    public void setSelectedReasoningEffort(String effort) {
        applyReasoningEffortOptions(getSelectedModel(), effort);
    }

    /**
     * Repopulates {@link #reasoningEffortCombo} with {@link #THINKING_LEVELS} unless discovery has POSITIVELY
     * confirmed {@code model} cannot think ({@link OllamaModelDiscovery#isModelKnown} true and
     * {@link OllamaModelDiscovery#modelSupportsThinking} false) — "clear only on positive confirmation, never
     * on absence of data". While discovery has not yet reported on {@code model} at all, the combo offers the
     * levels optimistically, matching {@code OllamaAiProcessManager.applyThinkingCapabilityValidation}'s
     * identical send-side rule — the combo must not claim "not set" is the only option while the process
     * manager would in fact still send a pinned/inherited value for that same model. This is a UI courtesy
     * either way; the authoritative "never send an unsupported value" enforcement is that same method's at
     * request-build time, with the 4xx retry as its backstop.
     */
    private void applyReasoningEffortOptions(String model, String preferredEffort) {
        effortGuard.runProgrammatic(() -> {
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
        });
    }

    public void addReasoningEffortChangeListener(java.awt.event.ActionListener listener) {
        effortGuard.addActionListener(listener);
    }

    @Override
    public List<JComponent> createComponents() {
        return List.of(modelCombo, reasoningEffortCombo, gauge.component(), compactBtn, clearBtn, hintLabel);
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof AvailableModelsEvent models) {
            setAvailableModels(models.models().toArray(String[]::new));
            // The model selection itself may be unchanged, so the model combo's own listener (which would
            // otherwise refresh this) never fires — but the capability cache backing applyReasoningEffortOptions
            // was just populated (or updated) by the SAME discovery cycle that produced this event, so a model
            // this combo previously had to show as "not set" only may now have thinking levels to offer.
            applyReasoningEffortOptions(getSelectedModel(), getSelectedReasoningEffort());
        }
        else if (event instanceof OllamaCapabilityHintEvent hint) {
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
        lastUsedTokens = used;
        gauge.update(used, total);
        // Whether this event came from a turn or from compactContext() succeeding, any summarising that was
        // in flight is over by now — the fast path; onBusyChanged(false) below is the backstop for a
        // compaction that FAILED instead, where no usage event ever arrives.
        gauge.setSummarising(false);
        updateButtonState();
    }

    @Override
    public void onBusyChanged(boolean busy) {
        this.busy = busy;
        if (!busy) {
            // Backstop: a failed compaction reports FAILED with no OllamaTokenUsageEvent, so onTokenUsage's
            // clear above never runs. Without this the gauge would spin forever after a failure.
            gauge.setSummarising(false);
        }
        updateButtonState();
    }

    private void updateButtonState() {
        boolean hasContent = lastUsedTokens > 0;
        clearBtn.setEnabled(!busy && hasContent);
        compactBtn.setEnabled(!busy && hasContent);
        // A turn already in flight snapshots the session's settings once at its start (see
        // OllamaAiProcessManager.runTurn), so a change here would not reach that turn anyway — disabled to avoid
        // implying otherwise.
        reasoningEffortCombo.setEnabled(!busy);
        modelCombo.setEnabled(!busy);
    }
}
