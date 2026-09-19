package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionListener;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexRateLimitEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexReasoningEffortEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.UIConstants;

/**
 * Info bar for Codex sessions: model selector + reasoning-effort selector + context-window usage gauge. Mirrors
 * {@code GrokAiInfoBarExtension} in structure.
 *
 * <p>
 * The effort combo is fed per-session by {@link CodexReasoningEffortEvent} (the {@code model/list} probe done during
 * thread establishment, spec §4): the selectable entries are the model's {@code supportedReasoningEfforts} and the
 * first entry is always {@code (model default)}, which maps to a {@code null} stored effort meaning "omit the
 * {@code turn/start} effort field". The combo's current selection is seeded from the session's stored effort, or from
 * the {@code reasoningEffort} threaded back by {@code thread/start}. Because Codex applies an {@code effort} override
 * per turn (no restart), the combo stays enabled while a turn is running — the spec's "disabled while a turn runs" rule
 * applies only to backends that cannot accept the change mid-turn (spec §1.6).
 *
 * <p>
 * Two traps this design deliberately avoids:
 * <ul>
 * <li>{@code AiTypePropertyBus} is keyed by AI type, not session — one session's model broadcast would reset every
 * other Codex session's combo. This class receives model and effort updates only through
 * {@link #onSessionSettingsChanged} and {@link #onAiProcessImplEvent}, both of which are session-scoped.</li>
 * <li>The combo is seeded from the session's own {@code settings.model()}, falling back to the global default only when
 * the session has no stored preference — matching what the process manager itself does at start time.</li>
 * </ul>
 *
 * <p>
 * Layout: {@code [Model ▾] [Effort ▾]  [=== context gauge ===]}
 */
public class CodexAiInfoBarExtension implements AiInfoBarExtension {

    private static final DateTimeFormatter RESET_TIME_FORMAT = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm");

    private final JComboBox<String> modelCombo;
    private final JComboBox<String> effortCombo;
    private final JProgressBar contextBar;
    private final JProgressBar rateLimitBar;
    private volatile long maxTokens = 0;
    private volatile long currentTokens = 0;
    private volatile String lastStoredEffort;
    private boolean programmatic = false;
    private Runnable disposeAction;

    /**
     * @param initialModel the session's stored model, or null to fall back to the global default from
     * {@link CodexPluginSettings#getModel()}
     */
    public CodexAiInfoBarExtension(String initialModel) {
        modelCombo = new JComboBox<>(CodexPluginSettings.KNOWN_MODELS);
        modelCombo.setEditable(true);
        String model = (initialModel != null && !initialModel.isBlank())
                       ? initialModel : CodexPluginSettings.getModel();
        modelCombo.setSelectedItem(model);
        modelCombo.setToolTipText("Codex model — pick from list or type any model ID");

        effortCombo = new JComboBox<>(new String[]{BlankSafeComboRenderer.DEFAULT_OPTION});
        effortCombo.setToolTipText("Reasoning effort for this session's turns (applies from the next turn) — "
                + "options come from the model/list probe at session start");
        effortCombo.setRenderer(new BlankSafeComboRenderer());

        contextBar = new JProgressBar(0, 100);
        contextBar.setPreferredSize(new Dimension(UIConstants.INFO_BAR_CONTEXT_PROGRESS_WIDTH, UIConstants.INFO_BAR_PROGRESS_HEIGHT));
        contextBar.setStringPainted(true);
        contextBar.setString("No usage data");
        contextBar.setToolTipText("Context window usage — tokens used / context window size");

        rateLimitBar = new JProgressBar(0, 100);
        rateLimitBar.setPreferredSize(new Dimension(UIConstants.INFO_BAR_SESSION_PROGRESS_WIDTH, UIConstants.INFO_BAR_PROGRESS_HEIGHT));
        rateLimitBar.setStringPainted(true);
        rateLimitBar.setString("Rate");
        rateLimitBar.setToolTipText("Codex account rate-limit usage");
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
        return List.of(modelCombo, effortCombo, contextBar, rateLimitBar);
    }

    /**
     * Registers a listener for user-initiated model selection changes. The listener is NOT called for programmatic
     * changes (e.g. {@link #setSelectedModel}). The caller (typically
     * {@code CodexAiImplementation.createInfoBarExtension}) is responsible for persisting the selection via
     * {@code delegate.setModel()} / {@code host.updateSessionSettings()}.
     */
    public void addModelChangeListener(ActionListener l) {
        modelCombo.addActionListener(e -> {
            if (!programmatic) {
                l.actionPerformed(e);
            }
        });
    }

    /**
     * Registers a listener for user-initiated reasoning-effort changes. Not called for programmatic changes
     * ({@link #setSelectedEffort} or the {@link CodexReasoningEffortEvent} refresh). The caller persists the selection
     * via {@code host.updateSessionSettings()} — sending happens per turn from the session settings, so no backend call
     * is needed.
     */
    public void addEffortChangeListener(ActionListener l) {
        effortCombo.addActionListener(e -> {
            if (!programmatic) {
                l.actionPerformed(e);
            }
        });
    }

    /**
     * Returns the currently shown model — reads the editor field so a typed (not-yet-confirmed) value is included.
     */
    public String getSelectedModel() {
        Object item = modelCombo.getEditor().getItem();
        String typed = item != null ? item.toString().trim() : "";
        return typed.isEmpty() ? CodexPluginSettings.DEFAULT_MODEL : typed;
    }

    /**
     * Returns the currently selected effort, or {@code null} when {@code (model default)} is selected (meaning "omit
     * the effort field").
     */
    public String getSelectedEffort() {
        Object sel = effortCombo.getSelectedItem();
        String s = sel != null ? sel.toString().trim() : "";
        return s.isEmpty() || BlankSafeComboRenderer.DEFAULT_OPTION.equals(s) ? null : s;
    }

    /**
     * Updates the combo to show {@code model}, without firing the change listener. EDT-safe.
     */
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
     * Shows {@code effort} in the combo without firing the change listener; {@code null} selects
     * {@code (model default)}. EDT-safe.
     */
    public void setSelectedEffort(String effort) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedEffort(effort));
            return;
        }
        programmatic = true;
        try {
            String target = (effort == null || effort.isBlank()) ? BlankSafeComboRenderer.DEFAULT_OPTION : effort;
            effortCombo.setSelectedItem(containsItem(target) ? target : BlankSafeComboRenderer.DEFAULT_OPTION);
        }
        finally {
            programmatic = false;
        }
    }

    private boolean containsItem(String value) {
        for (int i = 0; i < effortCombo.getItemCount(); i++) {
            if (value.equals(effortCombo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds the effort combo from the capability event: {@code (model
     * default)} first, then each supported effort. Seeding picks the session's stored effort when it is supported, else
     * the live {@code reasoningEffort} read back from {@code thread/start}. Doesn't fire the change listener. EDT-safe.
     */
    private void refreshEffortOptions(CodexReasoningEffortEvent ev) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> refreshEffortOptions(ev));
            return;
        }
        List<String> supported = ev.supportedEfforts();
        String[] items = new String[1 + supported.size()];
        items[0] = BlankSafeComboRenderer.DEFAULT_OPTION;
        for (int i = 0; i < supported.size(); i++) {
            items[i + 1] = supported.get(i);
        }
        programmatic = true;
        try {
            effortCombo.setModel(new javax.swing.DefaultComboBoxModel<>(items));
            effortCombo.setSelectedItem(BlankSafeComboRenderer.DEFAULT_OPTION);
            String stored = lastStoredEffort;
            if (stored != null && !stored.isBlank() && containsItem(stored)) {
                effortCombo.setSelectedItem(stored);
            }
            else {
                String cur = ev.currentEffort();
                if (cur != null && !cur.isBlank() && containsItem(cur)) {
                    effortCombo.setSelectedItem(cur);
                }
            }
        }
        finally {
            programmatic = false;
        }
    }

    private void updateContextBar() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateContextBar);
            return;
        }
        if (maxTokens <= 0) {
            contextBar.setString(String.format("%,d tokens", currentTokens));
            contextBar.setValue(0);
            contextBar.setToolTipText("Context window size unknown — token count only");
            return;
        }
        long pctLong = (currentTokens * 100L) / maxTokens;
        int pct = (int) Math.min(100L, pctLong);
        long remaining = Math.max(0L, maxTokens - currentTokens);
        contextBar.setValue(pct);
        contextBar.setString(String.format("%,d / %,d", currentTokens, maxTokens));
        contextBar.setToolTipText(String.format(
                "Token usage: %,d / %,d; %,d remaining (%d%%)",
                currentTokens, maxTokens, remaining, pct));
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof CodexRateLimitEvent rateLimit) {
            updateRateLimitBar(rateLimit);
        }
        // Codex has no dynamic model-discovery broadcast that is safe to act
        // on here — do not use AiTypePropertyBus for model changes because it
        // is keyed by type, not session, and would reset every Codex session's
        // combo on any event.
    }

    private void updateRateLimitBar(CodexRateLimitEvent rateLimit) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateRateLimitBar(rateLimit));
            return;
        }
        int usedPercent = (int) Math.round(Math.max(0.0, Math.min(100.0, rateLimit.usedPercent())));
        rateLimitBar.setValue(usedPercent);
        rateLimitBar.setString(usedPercent + "%");
        String reset = rateLimit.resetsAtEpochSeconds() > 0
                       ? Instant.ofEpochSecond(rateLimit.resetsAtEpochSeconds())
                        .atZone(ZoneId.systemDefault()).format(RESET_TIME_FORMAT)
                       : "unknown";
        rateLimitBar.setToolTipText(String.format(
                "Codex rate-limit usage: %.1f%% used; resets %s",
                rateLimit.usedPercent(), reset));
    }

    @Override
    public void onSessionSettingsChanged(AiSessionSettings settings) {
        if (settings instanceof AiModelSessionSettings modelSettings
                && modelSettings.model() != null && !modelSettings.model().isBlank()) {
            setSelectedModel(modelSettings.model());
        }
        if (settings instanceof CodexSessionSettings codexSettings) {
            lastStoredEffort = codexSettings.effort();
            setSelectedEffort(lastStoredEffort);
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof CodexTokenUsageEvent te) {
            currentTokens = te.usedTokens();
            if (te.contextWindow() > 0) {
                maxTokens = te.contextWindow();
            }
            updateContextBar();
        }
        else if (event instanceof CodexReasoningEffortEvent effortEvent) {
            refreshEffortOptions(effortEvent);
        }
    }
}
