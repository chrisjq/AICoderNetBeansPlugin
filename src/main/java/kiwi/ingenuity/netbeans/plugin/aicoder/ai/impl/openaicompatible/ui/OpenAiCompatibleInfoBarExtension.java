package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextGaugePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events.OpenAiCompatibleContextUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.HintState.HintKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.GuardedCombo;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogPlacement;

/**
 * The hint label of an OpenAI Compatible session (spec §7.4): the highest-priority active hint is shown and every
 * active hint is listed in the tooltip. Hints are set and cleared from any thread; the label is updated on the EDT.
 */
public final class OpenAiCompatibleInfoBarExtension implements AiInfoBarExtension {

    private final JButton warningButton = new JButton("⚠");
    private volatile Consumer<String> warningDialogPresenter = this::showWarningDialog;
    private final JComboBox<String> modelCombo;
    private final JComboBox<String> effortCombo;
    private final GuardedCombo<String> modelGuard;
    private final GuardedCombo<String> effortGuard;
    private final ContextGaugePanel gauge = new ContextGaugePanel();
    private volatile Consumer<String> modelChangeListener;
    private volatile Consumer<String> effortChangeListener;
    private final HintState state = new HintState();
    private static final String SUMMARY_TIP = "Show the summary of the earlier conversation";
    private static final String NO_SUMMARY_TIP = "No summary yet";
    private static final String TRIMMED_TIP = "Context was trimmed, not summarised";

    private final JButton compactButton = new JButton("⇒ Compact");
    private final JButton summaryButton = new JButton("≡ Summary");
    private final JButton resetButton = new JButton("↺ Reset");
    private volatile Runnable compactListener;
    private volatile Runnable resetListener;
    private volatile Consumer<String> confirmPresenter = this::confirmResetDialog;
    private volatile String summary;

    public OpenAiCompatibleInfoBarExtension() {
        modelCombo = new JComboBox<>();
        modelCombo.setEditable(true);
        modelGuard = new GuardedCombo<>(modelCombo);
        modelCombo.setToolTipText("Model — pick from the server list or type a model ID");
        modelGuard.addActionListener(e -> {
            Consumer<String> listener = modelChangeListener;
            if (listener != null) {
                listener.accept(getSelectedModel());
            }
        });
        effortCombo = new JComboBox<>(new String[]{
            BlankSafeComboRenderer.DEFAULT_OPTION, "off", "low", "medium", "high", "xhigh", "on"
        });
        effortCombo.setRenderer(new BlankSafeComboRenderer());
        effortGuard = new GuardedCombo<>(effortCombo);
        effortCombo.setToolTipText("Reasoning effort — Default omits the parameter");
        effortGuard.addActionListener(e -> {
            Consumer<String> listener = effortChangeListener;
            if (listener != null) {
                listener.accept(getSelectedEffort());
            }
        });
        warningButton.setFocusable(false);
        warningButton.setFont(warningButton.getFont().deriveFont(11f));
        warningButton.setVisible(false);
        warningButton.addActionListener(e -> warningDialogPresenter.accept(state.tooltip()));
        compactButton.setFont(compactButton.getFont().deriveFont(11f));
        summaryButton.setFont(summaryButton.getFont().deriveFont(11f));
        resetButton.setFont(resetButton.getFont().deriveFont(11f));
        summaryButton.setEnabled(false);
        summaryButton.setToolTipText(NO_SUMMARY_TIP);
        summaryButton.addActionListener(e -> showSummary());
        compactButton.setToolTipText("Shorten the model's context now (waits until the current reply finishes)");
        compactButton.addActionListener(e -> {
            Runnable listener = compactListener;
            if (listener != null) {
                listener.run();
            }
        });
        resetButton.setToolTipText("Reset the model's memory to the start of the session (the visible chat log is kept)");
        resetButton.addActionListener(e -> {
            Consumer<String> presenter = confirmPresenter;
            if (presenter != null) {
                presenter.accept("Reset the model's memory to the start of this session? The chat log stays visible but the model forgets the conversation.");
            }
            else {
                Runnable listener = resetListener;
                if (listener != null) {
                    listener.run();
                }
            }
        });
    }

    @Override
    public List<JComponent> createComponents() {
        return List.of(modelCombo, effortCombo, warningButton, gauge.component(), compactButton, summaryButton, resetButton);
    }

    /**
     * Registers what the Compact button does; the process manager hooks its compaction up here. Null removes it.
     */
    public void setCompactListener(Runnable listener) {
        compactListener = listener;
    }

    /**
     * Registers what the Reset button does.
     */
    public void setResetListener(Runnable listener) {
        resetListener = listener;
    }

    /**
     * Replaces the confirm presenter for tests.
     */
    public void setConfirmPresenter(Consumer<String> presenter) {
        confirmPresenter = presenter == null ? this::confirmResetDialog : presenter;
    }

    private void confirmResetDialog(String message) {
        if (message == null) {
            message = "Reset the model's memory to the start of this session? The chat log stays visible but the model forgets the conversation.";
        }
        int result = DialogPlacement.showConfirmDialog(resetButton, message, "Reset context",
                                                       javax.swing.JOptionPane.YES_NO_OPTION,
                                                       javax.swing.JOptionPane.WARNING_MESSAGE);
        if (result == javax.swing.JOptionPane.YES_OPTION) {
            Runnable listener = resetListener;
            if (listener != null) {
                listener.run();
            }
        }
    }

    /**
     * Sets the summary the Summary button shows. A null or blank summary disables the button; the tooltip then says
     * whether the context was trimmed instead of summarised. Safe from any thread.
     */
    public void setSummary(String summaryOrNull, boolean wasTrimmedNotSummarised) {
        boolean available = summaryOrNull != null && !summaryOrNull.isBlank();
        summary = available ? summaryOrNull : null;
        String tip = available ? SUMMARY_TIP : wasTrimmedNotSummarised ? TRIMMED_TIP : NO_SUMMARY_TIP;
        onEdt(() -> {
            summaryButton.setEnabled(available);
            summaryButton.setToolTipText(tip);
        });
    }

    boolean isSummaryEnabled() {
        return summaryButton.isEnabled();
    }

    String summaryToolTip() {
        return summaryButton.getToolTipText();
    }

    /**
     * Opens the summary in a read-only, selectable dialog with a Copy button. Runs on the EDT, from the button.
     */
    private void showSummary() {
        String text = summary;
        if (text == null) {
            return;
        }
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copy);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(summaryButton), "Summary",
                                     Dialog.ModalityType.MODELESS);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.add(new JScrollPane(area), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(560, 420);
        DialogPlacement.centre(dialog, summaryButton);
        dialog.setVisible(true);
    }

    /**
     * Enables or disables the Compact button (it is disabled while a turn or a compaction is running).
     */
    public void setCompactEnabled(boolean enabled) {
        onEdt(() -> compactButton.setEnabled(enabled));
    }

    /**
     * Enables or disables the Reset button and the model/effort selectors as one reset-state group (same rule as
     * Compact).
     */
    public void setResetEnabled(boolean enabled) {
        onEdt(() -> {
            resetButton.setEnabled(enabled);
            modelCombo.setEnabled(enabled);
            effortCombo.setEnabled(enabled);
        });
    }

    /**
     * Shows whether the manager is currently compacting the model-facing context.
     */
    public void setCompacting(boolean compacting) {
        onEdt(() -> gauge.setSummarising(compacting));
    }

    /**
     * Activates or replaces the hint of a slot; the label shows the highest-priority active hint.
     */
    public void set(HintKind kind, String text, String tooltip) {
        state.set(kind, text, tooltip);
        refresh();
    }

    /**
     * Activates the unreachable-server hint: "N/A", tooltip "Cannot reach the server at &lt;URL&gt;".
     */
    public void setUnreachable(String url) {
        state.setUnreachable(url);
        refresh();
    }

    public void clear(HintKind kind) {
        state.clear(kind);
        refresh();
    }

    public void clearAll() {
        state.clearAll();
        refresh();
    }

    /**
     * Rank 3: the tool definitions do not fit the window. {@code message} (the chat text) becomes the tooltip.
     */
    public void setToolsTooBig(int toolTokens, int window, String message) {
        set(HintKind.TOOLS_DO_NOT_FIT, HintState.toolsTooBigText(toolTokens, window), message);
    }

    /**
     * Rank 3: a confirmed window below the minimum window for tools. {@code message} becomes the tooltip.
     */
    public void setWindowTooSmallForTools(int window, String message) {
        set(HintKind.TOOLS_DO_NOT_FIT, HintState.windowTooSmallForToolsText(window), message);
    }

    /**
     * Rank 8: sets the tools-share hint when the tools take at least {@link HintState#TOOLS_SHARE_THRESHOLD} of the
     * window, and clears it otherwise.
     */
    public void updateToolsShare(int toolTokens, int window) {
        if (HintState.toolsShareApplies(toolTokens, window)) {
            set(HintKind.TOOLS_SHARE, HintState.toolsShareText(toolTokens, window),
                HintState.toolsShareTooltip(toolTokens, window));
        }
        else {
            clear(HintKind.TOOLS_SHARE);
        }
    }

    /**
     * Rank 5: a real detected window below the small-window threshold; the tooltip names the kind's remedy.
     */
    public void setSmallWindow(int window, String kindName) {
        set(HintKind.SMALL_WINDOW, HintState.smallWindowText(window), HintState.smallWindowTooltip(window, kindName));
    }

    /**
     * Rank 7: the chosen server type does not match the server; the tooltip names the detected kind's remedy.
     */
    public void setTypeMismatch(String chosenTypeName, String detectedKindName) {
        set(HintKind.TYPE_MISMATCH, HintState.typeMismatchText(chosenTypeName),
            HintState.typeMismatchTooltip(chosenTypeName, detectedKindName));
    }

    /**
     * The hint text now shown (for tests).
     */
    String hintText() {
        return state.text();
    }

    /**
     * The tooltip text now held: every active hint in priority order (for tests).
     */
    String hintTooltip() {
        return state.tooltip();
    }

    boolean warningVisible() {
        return warningButton.isVisible();
    }

    String warningToolTip() {
        return warningButton.getToolTipText();
    }

    Color warningForeground() {
        return warningButton.getForeground();
    }

    void setWarningDialogPresenter(Consumer<String> presenter) {
        warningDialogPresenter = presenter == null ? this::showWarningDialog : presenter;
    }

    private void refresh() {
        onEdt(() -> {
            String lines = state.tooltip();
            warningButton.setVisible(!lines.isEmpty());
            warningButton.setToolTipText(toolTipFor(lines));
            boolean blocking = state.isActive(HintKind.UNREACHABLE)
                    || state.isActive(HintKind.TOOLS_DO_NOT_FIT);
            warningButton.setForeground(warningColor(blocking));
        });
    }

    private static Color warningColor(boolean blocking) {
        if (blocking) {
            return Color.RED;
        }
        Color configured = UIManager.getColor("nb.warningForeground");
        return configured != null ? configured : Color.ORANGE.darker();
    }

    private void showWarningDialog(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copy);
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(warningButton), "Hints",
                                     Dialog.ModalityType.MODELESS);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.add(new JScrollPane(area), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(560, 420);
        DialogPlacement.centre(dialog, warningButton);
        dialog.setVisible(true);
    }

    private static String toolTipFor(String lines) {
        if (lines.isEmpty()) {
            return null;
        }
        if (lines.indexOf('\n') < 0) {
            return lines;
        }
        String escaped = lines.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html>" + escaped.replace("\n", "<br>") + "</html>";
    }

    private static void onEdt(Runnable update) {
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        }
        else {
            SwingUtilities.invokeLater(update);
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof OpenAiCompatibleContextUsageEvent usage) {
            gauge.update(usage.used(), usage.window());
            gauge.bar().setToolTipText(String.format("%,d of %,d tokens · window from %s · %s",
                                                     usage.used(), usage.window(), usage.windowSource(),
                                                     usage.serverKind()));
            gauge.setSummarising(false);
        }
    }

    @Override
    public void onBusyChanged(boolean busy) {
        modelCombo.setEnabled(!busy);
        effortCombo.setEnabled(!busy);
        compactButton.setEnabled(!busy);
        resetButton.setEnabled(!busy);
        if (!busy) {
            gauge.setSummarising(false);
        }
    }

    public void setModelChangeListener(Consumer<String> listener) {
        modelChangeListener = listener;
    }

    public void setEffortChangeListener(Consumer<String> listener) {
        effortChangeListener = listener;
    }

    public String getSelectedModel() {
        Object value = modelCombo.getEditor().getItem();
        return value == null ? "" : value.toString().trim();
    }

    public String getSelectedEffort() {
        Object value = effortCombo.getSelectedItem();
        String text = value == null ? "" : value.toString().trim();
        return BlankSafeComboRenderer.DEFAULT_OPTION.equals(text) ? "" : text;
    }

    public void setSelectedModel(String model) {
        modelGuard.runProgrammatic(() -> {
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            modelCombo.setSelectedItem(model == null ? "" : model);
            if (focused != null) {
                focused.requestFocusInWindow();
            }
        });
    }

    public void setSelectedEffort(String effort) {
        effortGuard.runProgrammatic(() -> effortCombo.setSelectedItem(
                effort == null || effort.isBlank() ? BlankSafeComboRenderer.DEFAULT_OPTION : effort));
    }

    public void setAvailableEfforts(List<String> levels, String current) {
        onEdt(() -> effortGuard.runProgrammatic(() -> {
            String selected = current == null ? "" : current.trim();
            effortCombo.removeAllItems();
            effortCombo.addItem(BlankSafeComboRenderer.DEFAULT_OPTION);
            if (levels == null || levels.isEmpty()) {
                List.of("off", "low", "medium", "high", "xhigh", "on")
                        .forEach(effortCombo::addItem);
            }
            else {
                levels.stream().filter(level -> level != null && !level.isBlank())
                        .distinct().forEach(effortCombo::addItem);
            }
            if (!selected.isBlank() && !containsItem(effortCombo, selected)) {
                effortCombo.addItem(selected);
            }
            effortCombo.setSelectedItem(selected.isBlank() ? BlankSafeComboRenderer.DEFAULT_OPTION : selected);
        }));
    }

    private static boolean containsItem(JComboBox<String> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (value.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    public void setAvailableModels(List<String> models) {
        onEdt(() -> modelGuard.runProgrammatic(() -> {
            String current = getSelectedModel();
            modelCombo.removeAllItems();
            if (models != null) {
                models.stream().filter(model -> model != null && !model.isBlank())
                        .distinct().forEach(modelCombo::addItem);
            }
            modelCombo.setSelectedItem(current);
            modelCombo.getEditor().setItem(current);
        }));
    }

    String contextGaugeToolTip() {
        return gauge.bar().getToolTipText();
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof AvailableModelsEvent models) {
            setAvailableModels(models.models());
        }
    }

    @Override
    public void onSessionSettingsChanged(AiSessionSettings settings) {
        if (settings instanceof AiModelSessionSettings modelSettings) {
            setSelectedModel(modelSettings.model());
        }
        if (settings instanceof OpenAiCompatibleSessionSettings compatible) {
            setSelectedEffort(compatible.reasoningEffort());
        }
    }
}
