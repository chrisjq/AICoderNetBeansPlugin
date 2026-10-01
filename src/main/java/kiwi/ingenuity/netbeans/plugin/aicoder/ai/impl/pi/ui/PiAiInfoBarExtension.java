package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextGaugePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiAvailableThinkingLevelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiContextUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiModelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiSessionModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiThinkingLevelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionCheckedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionVerifiedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.GuardedCombo;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Info bar for pi sessions: a model combo and a thinking-level combo kept live by pi events from the running
 * process, a context gauge fed from {@code get_session_stats.contextUsage}, and a version-warning button.
 * User actions are reported to {@link PiInfoBarListener}; the implementation owns commands and persistence.
 */
public class PiAiInfoBarExtension implements AiInfoBarExtension {

    private volatile PiVersionCheck versionCheck;

    private final JComboBox<String> modelCombo;
    private final JComboBox<String> thinkingLevelCombo;
    private final GuardedCombo<String> modelGuard;
    private final GuardedCombo<String> thinkingGuard;
    private final JButton versionWarningBtn;
    private final JButton compactBtn;
    private final ContextGaugePanel gauge = new ContextGaugePanel();

    private final List<PiInfoBarListener> listeners = new ArrayList<>();
    private volatile VersionDialogPresenter versionDialogPresenter = PiVersionWarningDialog::show;
    /**
     * Set once this session has reported its own model list; from then on the shared catalog no longer
     * overwrites it.
     */
    private volatile boolean sessionModelsReported;
    /**
     * The last KNOWN selection — either notified out (its listeners were told about it) or seeded/pushed in
     * programmatically (a
     * session-restore seed, or pi itself reporting a new current model/level via
     * {@link PiModelChangedEvent}/{@link PiThinkingLevelChangedEvent}) — so a duplicate non-programmatic
     * action event for the SAME value is a no-op. Needed because {@code JComboBox.setSelectedItem} fires an
     * action event even when the value does not change, and {@code addItem} on a still-empty combo
     * auto-selects the first item and fires one too (both plain Swing behaviour, confirmed live) — without
     * this, one real selection could reach the listeners twice. Kept in sync with every
     * programmatic combo mutation (not just the two action listeners): otherwise a value pi itself reported
     * externally (e.g. the user picks A, pi later reports its actual model is B via a
     * {@link PiModelChangedEvent}) would desync this field from what the combo shows, and re-picking A would
     * look like a no-op change and get silently swallowed.
     */
    private String lastNotifiedModel;
    private String lastNotifiedThinkingLevel;

    /**
     * {@code initialVersionCheck}, not {@code versionCheck}: a parameter of the field's own name shadows it
     * for the whole constructor body, including the lambdas declared in it — which is exactly how the
     * version-warning button's click handler ended up capturing the null it was constructed with (see its
     * listener below).
     *
     * <p>
     * {@code cachedCatalogModels} is the catalog snapshot as of construction (the property bus does not
     * replay events, so a bar opened after the catalog was published would otherwise show no model list until
     * the next change); it may be null or empty.
     */
    public PiAiInfoBarExtension(PiSessionSettings settings, PiVersionCheck initialVersionCheck,
                                List<String> cachedCatalogModels) {
        this.versionCheck = initialVersionCheck;

        modelCombo = new JComboBox<>();
        modelGuard = new GuardedCombo<>(modelCombo);
        modelCombo.setEditable(true);
        modelCombo.setToolTipText("pi model — provider/id");
        modelGuard.addActionListener(e -> {
            String selected = selectedModel();
            if (selected == null || selected.isBlank() || selected.equals(lastNotifiedModel)) {
                return;
            }
            lastNotifiedModel = selected;
            listeners.forEach(l -> l.onModelChanged(selected));
        });

        thinkingLevelCombo = new JComboBox<>();
        thinkingGuard = new GuardedCombo<>(thinkingLevelCombo);
        thinkingLevelCombo.setToolTipText("pi thinking level");
        thinkingLevelCombo.setRenderer(new BlankSafeComboRenderer());
        thinkingGuard.addActionListener(e -> {
            Object sel = thinkingLevelCombo.getSelectedItem();
            if (sel == null) {
                return;
            }
            String level = sel.toString();
            if (level.equals(lastNotifiedThinkingLevel)) {
                return;
            }
            lastNotifiedThinkingLevel = level;
            listeners.forEach(l -> l.onThinkingLevelChanged(level));
        });

        versionWarningBtn = new JButton("⚠");
        versionWarningBtn.setForeground(java.awt.Color.RED);
        versionWarningBtn.setFocusable(false);
        versionWarningBtn.addActionListener(e -> {
            // this.versionCheck, always — the field, never a same-named local. The info bar is built synchronously
            // before pi starts, so the check handed to the constructor is null and the real one only arrives later
            // as a PiVersionCheckedEvent. While the constructor's parameter shadowed the field, this lambda captured that
            // null for the lifetime of the button: refreshVersionWarningButtonNow() reads the field, so the button
            // appeared with a correct tooltip, and clicking it returned here and silently did nothing.
            PiVersionCheck check = this.versionCheck;
            if (check == null) {
                return;
            }
            showVersionWarningDialog(check);
            refreshVersionWarningButton();
        });
        // Direct call to the un-dispatched core, not refreshVersionWarningButton(): the constructor's other initial
        // state (combo seeding below) is also set synchronously regardless of thread, and going through the EDT
        // guard here would silently no-op the button's initial visibility when constructed off the EDT (e.g. in
        // tests, which never pump a deferred invokeLater before asserting).
        refreshVersionWarningButtonNow();

        compactBtn = new JButton("⇒ Compact");
        compactBtn.setFont(compactBtn.getFont().deriveFont(11f));
        compactBtn.setToolTipText("Compact conversation to reduce context window usage");
        compactBtn.addActionListener(e -> listeners.forEach(PiInfoBarListener::onCompactRequested));

        // Locking is driven exclusively by the core busy/ready contract via onBusyChanged.
        // Guarded like every other combo mutation in this class: addItem() on a still-empty combo auto-selects the
        // first item and fires an action event on its own (confirmed live Swing behaviour), which would otherwise
        // reach the PiInfoBarListeners for a value nobody chose — it was
        // only ever seeded from the session's own stored settings.
        modelGuard.runProgrammatic(() -> thinkingGuard.runProgrammatic(() -> {
            if (settings != null && settings.model() != null && !settings.model().isBlank()) {
                modelCombo.addItem(settings.model());
                modelCombo.setSelectedItem(settings.model());
                modelCombo.getEditor().setItem(settings.model());
            }
            if (settings != null && settings.thinkingLevel() != null && !settings.thinkingLevel().isBlank()) {
                thinkingLevelCombo.addItem(settings.thinkingLevel());
                thinkingLevelCombo.setSelectedItem(settings.thinkingLevel());
            }
            if (cachedCatalogModels != null && !cachedCatalogModels.isEmpty()) {
                applyAvailableModels(cachedCatalogModels);
            }
        }));
    }

    public void addListener(PiInfoBarListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PiInfoBarListener listener) {
        listeners.remove(listener);
    }

    @Override
    public List<JComponent> createComponents() {
        return List.of(modelCombo, thinkingLevelCombo, gauge.component(), versionWarningBtn, compactBtn);
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        // Type-wide facts arrive here: the discovered model catalog, and PiVersionVerifiedEvent —
        // version-verification state (PiPluginSettings.verifiedVersion, and PiVersionCheck's process-wide
        // not-working-this-session set) is written from whichever tab's dialog the user answered, so every open pi
        // tab's info bar must hear about it rather than only the one instance that triggered the write.
        if (event instanceof AvailableModelsEvent available) {
            // The running session's own get_available_models answer is authoritative for this bar; the catalog is
            // only the stand-in until (or unless) the session reports one.
            if (!sessionModelsReported) {
                setAvailableModels(available.models());
            }
        }
        else if (event instanceof PiVersionVerifiedEvent) {
            refreshVersionWarningButton();
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        // Per-session facts, reported by the session's own process manager.
        if (event instanceof PiSessionModelsEvent models) {
            sessionModelsReported = true;
            setAvailableModels(models.models());
        }
        else if (event instanceof PiAvailableThinkingLevelsEvent levels) {
            setAvailableThinkingLevels(levels.levels());
        }
        else if (event instanceof PiModelChangedEvent changed) {
            if (changed.model() != null && !changed.model().isBlank()) {
                setSelectedModel(changed.model());
            }
        }
        else if (event instanceof PiThinkingLevelChangedEvent changed) {
            if (changed.level() != null && !changed.level().isBlank()) {
                setSelectedThinkingLevel(changed.level());
            }
        }
        else if (event instanceof PiContextUsageEvent usage) {
            setContextUsage(usage.usedTokens(), usage.contextWindowTokens());
        }
        else if (event instanceof PiVersionCheckedEvent checked) {
            this.versionCheck = checked.check();
            refreshVersionWarningButton();
        }
    }

    @Override
    public void onSessionSettingsChanged(AiSessionSettings sessionSettings) {
        if (sessionSettings instanceof AiModelSessionSettings modelSettings
            && modelSettings.model() != null && !modelSettings.model().isBlank()) {
            setSelectedModel(modelSettings.model());
        }
        if (sessionSettings instanceof PiSessionSettings piSettings
            && piSettings.thinkingLevel() != null && !piSettings.thinkingLevel().isBlank()) {
            setSelectedThinkingLevel(piSettings.thinkingLevel());
        }
    }

    @Override
    public void dispose() {
        listeners.clear();
    }

    private String selectedModel() {
        Object item = modelCombo.getEditor() != null ? modelCombo.getEditor().getItem() : modelCombo.getSelectedItem();
        return item != null ? item.toString().trim() : null;
    }

    private void setSelectedModel(String providerSlashId) {
        modelGuard.runProgrammatic(() -> {
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            modelCombo.setSelectedItem(providerSlashId);
            if (modelCombo.getEditor() != null) {
                modelCombo.getEditor().setItem(providerSlashId);
            }
            lastNotifiedModel = providerSlashId;
            if (focused != null) {
                focused.requestFocusInWindow();
            }
        });
    }

    private void setAvailableModels(List<String> models) {
        modelGuard.runProgrammatic(() -> applyAvailableModels(models));
    }

    /**
     * Must run inside {@code modelGuard.runProgrammatic}: replaces the combo's items while keeping whatever
     * model is currently shown.
     */
    private void applyAvailableModels(List<String> models) {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        String current = selectedModel();
        modelCombo.removeAllItems();
        for (String m : models) {
            modelCombo.addItem(m);
        }
        if (current != null && !current.isBlank()) {
            modelCombo.setSelectedItem(current);
            if (modelCombo.getEditor() != null) {
                modelCombo.getEditor().setItem(current);
            }
            lastNotifiedModel = current;
        }
        if (focused != null) {
            focused.requestFocusInWindow();
        }
    }

    private void setSelectedThinkingLevel(String level) {
        thinkingGuard.runProgrammatic(() -> {
            // Unlike modelCombo (editable), thinkingLevelCombo is NOT editable — JComboBox.setSelectedItem silently
            // no-ops on a non-editable combo when the value isn't already one of its items (confirmed live; this is
            // NOT the same as DefaultComboBoxModel.setSelectedItem, which has no such restriction). Without this, an
            // external report of a level not yet in the combo's available-levels list (e.g. get_state's response
            // racing ahead of get_available_thinking_levels') would silently fail to update the UI at all — caught
            // by a test exposing exactly this for a level never added via setAvailableThinkingLevels.
            ensureThinkingLevelItemPresent(level);
            thinkingLevelCombo.setSelectedItem(level);
            lastNotifiedThinkingLevel = level;
        });
    }

    private void ensureThinkingLevelItemPresent(String level) {
        if (level == null) {
            return;
        }
        for (int i = 0; i < thinkingLevelCombo.getItemCount(); i++) {
            if (level.equals(thinkingLevelCombo.getItemAt(i))) {
                return;
            }
        }
        thinkingLevelCombo.addItem(level);
    }

    private void setAvailableThinkingLevels(List<String> levels) {
        thinkingGuard.runProgrammatic(() -> {
            Object current = thinkingLevelCombo.getSelectedItem();
            thinkingLevelCombo.removeAllItems();
            for (String level : levels) {
                thinkingLevelCombo.addItem(level);
            }
            if (current != null) {
                // Deliberately NOT ensureThinkingLevelItemPresent(current) here: current may be a level injected
                // earlier by that same guard (the get_state-before-levels race), and this refresh's list is the
                // authoritative one — forcing it back in would let a bogus level linger in the dropdown forever
                // instead of being dropped by the very refresh meant to correct it. setSelectedItem silently no-ops
                // on this non-editable combo when current isn't one of the just-added items (same hazard as
                // setSelectedThinkingLevel), so read back what's ACTUALLY selected afterwards — levels.get(0) via
                // addItem's own auto-select if current didn't take — rather than assuming current stuck, or the
                // combo and lastNotifiedThinkingLevel disagree.
                thinkingLevelCombo.setSelectedItem(current);
                Object actual = thinkingLevelCombo.getSelectedItem();
                lastNotifiedThinkingLevel = actual != null ? actual.toString() : null;
            }
        });
    }

    private void setContextUsage(int used, int total) {
        gauge.update(used, total);
    }

    @Override
    public void onBusyChanged(boolean busy) {
        modelCombo.setEnabled(!busy);
        thinkingLevelCombo.setEnabled(!busy);
        compactBtn.setEnabled(!busy);
    }

    /**
     * The one line of the click path that opens a modal dialog, isolated so a test can exercise the rest of
     * the path headlessly by overriding it — without a seam, the only way the handler's shadowed-capture bug
     * could have been caught was by clicking the button in a running IDE. Package-private and overridable for
     * that reason alone; production has exactly one implementation.
     */
    void showVersionWarningDialog(PiVersionCheck check) {
        versionDialogPresenter.present(versionWarningBtn, check,
                () -> listeners.forEach(listener -> listener.onVersionVerified(check)),
                () -> listeners.forEach(listener -> listener.onVersionMarkedNotWorking(check)));
    }

    /**
     * Opens the version dialog and reports the user's answer through one of the two callbacks. Replaceable so
     * the answer path can be driven without a modal dialog.
     */
    @FunctionalInterface
    public interface VersionDialogPresenter {

        void present(Component parent, PiVersionCheck check, Runnable onVerified, Runnable onMarkedNotWorking);
    }

    public void setVersionDialogPresenter(VersionDialogPresenter presenter) {
        this.versionDialogPresenter = presenter;
    }

    private void refreshVersionWarningButton() {
        refreshVersionWarningButtonNow();
    }

    private void refreshVersionWarningButtonNow() {
        PiVersionCheck check = versionCheck;
        boolean markedNotWorking = check != null && check.isMarkedNotWorkingThisSession();
        boolean applies = check != null && (check.isWarningApplies() || markedNotWorking);
        versionWarningBtn.setVisible(applies);
        if (!applies) {
            return;
        }
        String version = check.installedVersion();
        versionWarningBtn.setToolTipText(markedNotWorking
                                         ? "pi " + version + " was marked as not working with this plugin. Click to report a bug."
                                         : "pi " + version + " has not been verified with this plugin (tested: " + check.testedVersion()
                                           + ".x). Click to verify.");
    }
}
