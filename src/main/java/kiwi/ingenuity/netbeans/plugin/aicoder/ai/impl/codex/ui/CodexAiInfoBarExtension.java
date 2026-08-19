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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events.CodexTokenUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.UIConstants;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Info bar for Codex sessions: model selector + context-window usage gauge.
 * Mirrors {@code GrokAiInfoBarExtension} in structure.
 *
 * <p>
 * Two traps this design deliberately avoids:
 * <ul>
 * <li>{@code AiTypePropertyBus} is keyed by AI type, not session — one
 * session's model broadcast would reset every other Codex session's combo. This
 * class receives model updates only through {@link #onSessionSettingsChanged},
 * which is session-scoped.</li>
 * <li>The combo is seeded from the session's own {@code settings.model()},
 * falling back to the global default only when the session has no stored
 * preference — matching what the process manager itself does at start
 * time.</li>
 * </ul>
 *
 * <p>
 * Layout: {@code [Model ▾]  [=== context gauge ===]}
 */
public class CodexAiInfoBarExtension implements AiInfoBarExtension {

    private static final DateTimeFormatter RESET_TIME_FORMAT = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm");

    private final JComboBox<String> modelCombo;
    private final JProgressBar contextBar;
    private final JProgressBar rateLimitBar;
    private volatile long maxTokens = 0;
    private volatile long currentTokens = 0;
    private boolean programmatic = false;
    private Runnable disposeAction;

    /**
     * @param initialModel the session's stored model, or null to fall back to
     * the global default from {@link CodexPluginSettings#getModel()}
     */
    public CodexAiInfoBarExtension(String initialModel) {
        modelCombo = new JComboBox<>(CodexPluginSettings.KNOWN_MODELS);
        modelCombo.setEditable(true);
        String model = (initialModel != null && !initialModel.isBlank())
                ? initialModel : CodexPluginSettings.getModel();
        modelCombo.setSelectedItem(model);
        modelCombo.setToolTipText("Codex model — pick from list or type any model ID");

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
        return List.of(modelCombo, contextBar, rateLimitBar);
    }

    /**
     * Registers a listener for user-initiated model selection changes. The
     * listener is NOT called for programmatic changes (e.g.
     * {@link #setSelectedModel}). The caller (typically
     * {@code CodexAiImplementation.createInfoBarExtension}) is responsible for
     * persisting the selection via
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
     * Returns the currently shown model — reads the editor field so a typed
     * (not-yet-confirmed) value is included.
     */
    public String getSelectedModel() {
        Object item = modelCombo.getEditor().getItem();
        String typed = item != null ? item.toString().trim() : "";
        return typed.isEmpty() ? CodexPluginSettings.DEFAULT_MODEL : typed;
    }

    /**
     * Updates the combo to show {@code model}, without firing the change
     * listener. EDT-safe.
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
    }
}
