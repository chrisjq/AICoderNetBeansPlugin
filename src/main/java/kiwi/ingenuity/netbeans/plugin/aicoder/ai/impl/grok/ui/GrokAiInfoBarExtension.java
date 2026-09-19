package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.GrokReasoningEffortSupport;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.UIConstants;

/**
 * Grok info bar: model selector, reasoning-effort selector + context-window usage progress bar. Mirrors
 * {@code ClaudeAiInfoBarExtension} / {@code GithubCopilotAiInfoBarExtension} but omits the compact button and
 * rate-limit bars — grok's headless CLI has no documented context-compaction command or 5-hour/7-day rate-limit query,
 * unlike Claude. Grok spawns a fresh {@code grok -p} per turn (see {@code GrokAiProcessManager}), so a reasoning-effort
 * change simply applies to the next turn — no restart path is needed and, unlike pi's live-RPC picker, this combo does
 * not need any disabling behaviour beyond whatever the model combo already has.
 */
public class GrokAiInfoBarExtension implements AiInfoBarExtension {

    private final JComboBox<String> modelCombo;
    private final JComboBox<String> reasoningEffortCombo;
    private final JProgressBar contextBar;
    private volatile int maxTokens = 128000;
    private volatile int currentTokens = 0;
    private volatile boolean hasUsageData = false;
    private boolean programmatic = false;
    private Runnable disposeAction;
    private ActionListener reasoningEffortListener = e -> {
    };

    public GrokAiInfoBarExtension() {
        modelCombo = new JComboBox<>(GrokPluginSettings.KNOWN_MODELS);
        modelCombo.setEditable(true);
        modelCombo.setSelectedItem(GrokPluginSettings.getModel());
        modelCombo.setToolTipText("Grok model — pick from list or type any model ID");
        modelCombo.addActionListener(e -> {
            if (!programmatic) {
                applyReasoningEffortOptions(getSelectedModel(), null);
            }
        });

        reasoningEffortCombo = new JComboBox<>();
        reasoningEffortCombo.setToolTipText("Grok reasoning effort");
        reasoningEffortCombo.setRenderer(new BlankSafeComboRenderer());
        applyReasoningEffortOptions(getSelectedModel(), null);
        reasoningEffortCombo.addActionListener(e -> {
            if (!programmatic) {
                reasoningEffortListener.actionPerformed(e);
            }
        });

        contextBar = new JProgressBar(0, 100);
        contextBar.setPreferredSize(new Dimension(UIConstants.INFO_BAR_CONTEXT_PROGRESS_WIDTH, UIConstants.INFO_BAR_PROGRESS_HEIGHT));
        contextBar.setStringPainted(true);
        contextBar.setString("No usage data");
        contextBar.setToolTipText("Context window usage — tokens used / total available");
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

    @Override
    public List<JComponent> createComponents() {
        return List.of(modelCombo, reasoningEffortCombo, contextBar);
    }

    public void addModelChangeListener(ActionListener l) {
        modelCombo.addActionListener(e -> {
            if (!programmatic) {
                l.actionPerformed(e);
            }
        });
    }

    /**
     * Only one listener is ever registered (by {@code GrokAiImplementation.createInfoBarExtension}), unlike
     * {@link #addModelChangeListener} which supports many — kept as a plain field, not a list, since nothing else in
     * this class needs the multi-listener machinery {@code modelCombo} already has.
     */
    public void addReasoningEffortChangeListener(ActionListener l) {
        reasoningEffortListener = l;
    }

    public String getSelectedModel() {
        Object item = modelCombo.getEditor().getItem();
        String typed = item != null ? item.toString().trim() : "";
        return typed.isEmpty() ? GrokPluginSettings.DEFAULT_MODEL : typed;
    }

    /**
     * {@code null} means "pass nothing" (the {@link BlankSafeComboRenderer#DEFAULT_OPTION} entry is selected), matching
     * {@code PiAiInfoBarExtension}'s thinking-level shape.
     */
    public String getSelectedReasoningEffort() {
        Object sel = reasoningEffortCombo.getSelectedItem();
        return (sel != null && !BlankSafeComboRenderer.DEFAULT_OPTION.equals(sel.toString())) ? sel.toString() : null;
    }

    public void setSelectedModel(String model) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedModel(model));
            return;
        }
        programmatic = true;
        try {
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            modelCombo.setSelectedItem(model);
            if (focused != null) {
                focused.requestFocusInWindow();
            }
        }
        finally {
            programmatic = false;
        }
    }

    /**
     * Replaces the dropdown's items with a discovered model list, preserving the current selection. The combo stays
     * editable so any model can still be typed. EDT-safe.
     */
    public void setAvailableModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setAvailableModels(models));
            return;
        }
        programmatic = true;
        try {
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            String current = getSelectedModel();
            modelCombo.removeAllItems();
            for (String m : models) {
                modelCombo.addItem(m);
            }
            modelCombo.setSelectedItem(current);
            if (!current.equals(getSelectedModel())) {
                modelCombo.getEditor().setItem(current);
            }
            if (focused != null) {
                focused.requestFocusInWindow();
            }
        }
        finally {
            programmatic = false;
        }
    }

    /**
     * Rebuilds {@link #reasoningEffortCombo}'s options for the CURRENTLY selected model before applying {@code
     * effort}, rather than calling {@code reasoningEffortCombo.setSelectedItem} directly — two review findings this
     * fixes at once: (1) a non-editable {@code JComboBox} silently no-ops {@code setSelectedItem} for a value that
     * isn't one of its current items (the same trap pi hit), so a stored effort invalid for the current model would
     * previously leave the combo's true selection out of sync with what {@link #getSelectedReasoningEffort()} returns
     * even though the display looked fine; (2) called after {@link #onSessionSettingsChanged} programmatically changes
     * the model (which suppresses the model combo's own listener that would otherwise rebuild these options), this
     * ensures the effort combo reflects the NEW model's supported levels — not stale options left over from whatever
     * model was selected before the session-settings update — instead of only fixing that on the next unrelated
     * model-combo interaction.
     */
    public void setSelectedReasoningEffort(String effort) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedReasoningEffort(effort));
            return;
        }
        applyReasoningEffortOptions(getSelectedModel(), effort);
    }

    /**
     * Repopulates {@link #reasoningEffortCombo} with the levels {@code model} supports (plus
     * {@link BlankSafeComboRenderer#DEFAULT_OPTION}), selecting {@code preferredEffort} if given and still valid for
     * {@code model}, else the combo's own current selection if that is still valid, else
     * {@link BlankSafeComboRenderer#DEFAULT_OPTION} — a UI courtesy; the authoritative "never send an unsupported
     * value" enforcement is {@code GrokAiProcessManager}'s at launch time, not this combo's.
     */
    private void applyReasoningEffortOptions(String model, String preferredEffort) {
        boolean wasProgrammatic = programmatic;
        programmatic = true;
        try {
            Object current = reasoningEffortCombo.getSelectedItem();
            reasoningEffortCombo.removeAllItems();
            reasoningEffortCombo.addItem(BlankSafeComboRenderer.DEFAULT_OPTION);
            List<String> supported = GrokReasoningEffortSupport.supportedFor(model);
            for (String level : supported) {
                reasoningEffortCombo.addItem(level);
            }
            String toSelect = (preferredEffort != null && supported.contains(preferredEffort)) ? preferredEffort
                              : (current != null && supported.contains(current.toString()) ? current.toString() : null);
            reasoningEffortCombo.setSelectedItem(toSelect != null ? toSelect : BlankSafeComboRenderer.DEFAULT_OPTION);
        }
        finally {
            programmatic = wasProgrammatic;
        }
    }

    private void updateContextBar() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateContextBar);
            return;
        }
        hasUsageData = true;
        int pct = maxTokens > 0 ? (int) ((currentTokens * 100.0) / maxTokens) : 0;
        int remaining = Math.max(0, maxTokens - currentTokens);
        contextBar.setValue(Math.min(100, pct));
        contextBar.setString(String.format("%,d / %,d", currentTokens, maxTokens));
        contextBar.setToolTipText(String.format(
                "Token usage: %,d / %,d; %,d remaining (%d%%)",
                currentTokens, maxTokens, remaining, pct));
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof GrokModelsEvent me) {
            setAvailableModels(me.models());
        }
    }

    @Override
    public void onSessionSettingsChanged(AiSessionSettings settings) {
        if (settings instanceof AiModelSessionSettings modelSettings
                && modelSettings.model() != null && !modelSettings.model().isBlank()) {
            setSelectedModel(modelSettings.model());
        }
        if (settings instanceof GrokSessionSettings grokSettings) {
            setSelectedReasoningEffort(grokSettings.reasoningEffort());
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof GrokTokenUsageEvent te) {
            currentTokens = te.currentTokens();
            if (te.maxTokens() > 0) {
                maxTokens = te.maxTokens();
            }
            updateContextBar();
        }
    }

    @Override
    public void onSessionPct(double pct) {
        if (pct >= 0 && hasUsageData) {
            currentTokens = (int) (pct * maxTokens / 100.0);
            updateContextBar();
        }
    }

}
