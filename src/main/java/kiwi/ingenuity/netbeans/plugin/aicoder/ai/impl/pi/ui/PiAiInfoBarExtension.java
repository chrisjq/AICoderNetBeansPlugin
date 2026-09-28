package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextGaugePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionVerifiedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Info bar for pi sessions: a model combo and a thinking-level combo live from the running process (via
 * {@link PiSessionControl}), a context gauge fed from {@code get_session_stats.contextUsage}, and a
 * version-warning button.
 */
public class PiAiInfoBarExtension implements AiInfoBarExtension {

    private final PiSessionControl control;
    private volatile PiVersionCheck versionCheck;

    private final JComboBox<String> modelCombo;
    private final JComboBox<String> thinkingLevelCombo;
    private final JButton versionWarningBtn;
    private final JButton compactBtn;
    private final ContextGaugePanel gauge = new ContextGaugePanel();

    private final List<PiInfoBarListener> listeners = new ArrayList<>();
    private final PiSessionControl.Listener controlListener = new ControlListener();
    private boolean programmatic = false;
    // The two inputs to refreshCompactEnabled(); EDT-confined like every other component mutation in this class.
    private boolean combosEnabled = true;
    private boolean compacting = false;
    /**
     * The last KNOWN selection — either notified out (this class called {@code control.setModel}/
     * {@code setThinkingLevel} and its listeners for it) or seeded/pushed in programmatically (a
     * session-restore seed, or pi itself reporting a new current model/level via
     * {@link PiSessionControl.Listener}) — so a duplicate non-programmatic action event for the SAME value is
     * a no-op. Needed because {@code JComboBox.setSelectedItem} fires an action event even when the value
     * does not change, and {@code addItem} on a still-empty combo auto-selects the first item and fires one
     * too (both plain Swing behaviour, confirmed live) — without this, one real selection could reach
     * {@link PiSessionControl} twice. Kept in sync with every programmatic combo mutation (not just the two
     * action listeners): otherwise a value pi itself reported externally (e.g. the user picks A, pi later
     * reports its actual model is B via {@code onCurrentSelectionChanged}) would desync this field from what
     * the combo shows, and re-picking A would look like a no-op change and get silently swallowed.
     */
    private String lastNotifiedModel;
    private String lastNotifiedThinkingLevel;

    /**
     * {@code initialVersionCheck}, not {@code versionCheck}: a parameter of the field's own name shadows it
     * for the whole constructor body, including the lambdas declared in it — which is exactly how the
     * version-warning button's click handler ended up capturing the null it was constructed with (see its
     * listener below).
     */
    public PiAiInfoBarExtension(PiSessionControl control, PiSessionSettings settings, PiVersionCheck initialVersionCheck) {
        this.control = control;
        this.versionCheck = initialVersionCheck;

        modelCombo = new JComboBox<>();
        modelCombo.setEditable(true);
        modelCombo.setToolTipText("pi model — provider/id");
        modelCombo.addActionListener(e -> {
            if (programmatic) {
                return;
            }
            String selected = selectedModel();
            if (selected == null || selected.isBlank() || selected.equals(lastNotifiedModel)) {
                return;
            }
            lastNotifiedModel = selected;
            int slash = selected.indexOf('/');
            String provider = slash > 0 ? selected.substring(0, slash) : "";
            String modelId = slash > 0 ? selected.substring(slash + 1) : selected;
            if (control != null) {
                control.setModel(provider, modelId);
            }
            listeners.forEach(l -> l.onModelChanged(selected));
        });

        thinkingLevelCombo = new JComboBox<>();
        thinkingLevelCombo.setToolTipText("pi thinking level");
        thinkingLevelCombo.setRenderer(new BlankSafeComboRenderer());
        thinkingLevelCombo.addActionListener(e -> {
            if (programmatic) {
                return;
            }
            Object sel = thinkingLevelCombo.getSelectedItem();
            if (sel == null) {
                return;
            }
            String level = sel.toString();
            if (level.equals(lastNotifiedThinkingLevel)) {
                return;
            }
            lastNotifiedThinkingLevel = level;
            if (control != null) {
                control.setThinkingLevel(level);
            }
            listeners.forEach(l -> l.onThinkingLevelChanged(level));
        });

        versionWarningBtn = new JButton("⚠");
        versionWarningBtn.setForeground(java.awt.Color.RED);
        versionWarningBtn.setFocusable(false);
        versionWarningBtn.addActionListener(e -> {
            // this.versionCheck, always — the field, never a same-named local. The info bar is built synchronously
            // before pi starts, so the check handed to the constructor is null and the real one only arrives later
            // via setVersionCheck(). While the constructor's parameter shadowed the field, this lambda captured that
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

        if (control != null) {
            control.setListener(controlListener);
            // A tab can be created (e.g. session restore) while a turn from before the IDE restart/tab reopen is
            // already running — without this, a freshly built info bar starts with its combos/Compact enabled
            // regardless, and stays that way until the NEXT onTurnRunningChanged fires, letting the user fire
            // setModel/setThinkingLevel/compact mid-turn. Direct call to the un-dispatched core (see
            // refreshVersionWarningButtonNow's comment above for why): this constructor's other initial state is
            // also set synchronously regardless of thread.
            setCombosEnabledNow(!control.isTurnRunning());
        }
        // Guarded like every other combo mutation in this class: addItem() on a still-empty combo auto-selects the
        // first item and fires an action event on its own (confirmed live Swing behaviour), which would otherwise
        // reach PiSessionControl.setModel/setThinkingLevel and PiInfoBarListener for a value nobody chose — it was
        // only ever seeded from the session's own stored settings.
        programmatic = true;
        try {
            if (settings != null && settings.model() != null && !settings.model().isBlank()) {
                modelCombo.addItem(settings.model());
                modelCombo.setSelectedItem(settings.model());
            }
            if (settings != null && settings.thinkingLevel() != null && !settings.thinkingLevel().isBlank()) {
                thinkingLevelCombo.addItem(settings.thinkingLevel());
                thinkingLevelCombo.setSelectedItem(settings.thinkingLevel());
            }
        } finally {
            programmatic = false;
        }
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
        // Pi's model/usage/session-state updates arrive through PiSessionControl.Listener instead, EXCEPT
        // PiVersionVerifiedEvent: version-verification state (PiPluginSettings.verifiedVersion, and
        // PiVersionCheck's process-wide not-working-this-session set) is type-global, written from whichever tab's
        // dialog the user answered, so every open pi tab's info bar must hear about it here rather than only the
        // one instance that triggered the write.
        if (event instanceof PiVersionVerifiedEvent) {
            refreshVersionWarningButton();
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        // See onPropertyEvent(AiPropertyEvent) above.
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
        if (control != null) {
            control.setListener(null);
        }
        listeners.clear();
    }

    private String selectedModel() {
        Object item = modelCombo.getEditor() != null ? modelCombo.getEditor().getItem() : modelCombo.getSelectedItem();
        return item != null ? item.toString().trim() : null;
    }

    private void setSelectedModel(String providerSlashId) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedModel(providerSlashId));
            return;
        }
        programmatic = true;
        try {
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            modelCombo.setSelectedItem(providerSlashId);
            if (modelCombo.getEditor() != null) {
                modelCombo.getEditor().setItem(providerSlashId);
            }
            lastNotifiedModel = providerSlashId;
            if (focused != null) {
                focused.requestFocusInWindow();
            }
        } finally {
            programmatic = false;
        }
    }

    private void setAvailableModels(List<String> models) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setAvailableModels(models));
            return;
        }
        programmatic = true;
        try {
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
        } finally {
            programmatic = false;
        }
    }

    private void setSelectedThinkingLevel(String level) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setSelectedThinkingLevel(level));
            return;
        }
        programmatic = true;
        try {
            // Unlike modelCombo (editable), thinkingLevelCombo is NOT editable — JComboBox.setSelectedItem silently
            // no-ops on a non-editable combo when the value isn't already one of its items (confirmed live; this is
            // NOT the same as DefaultComboBoxModel.setSelectedItem, which has no such restriction). Without this, an
            // external report of a level not yet in the combo's available-levels list (e.g. get_state's response
            // racing ahead of get_available_thinking_levels') would silently fail to update the UI at all — caught
            // by a test exposing exactly this for a level never added via setAvailableThinkingLevels.
            ensureThinkingLevelItemPresent(level);
            thinkingLevelCombo.setSelectedItem(level);
            lastNotifiedThinkingLevel = level;
        } finally {
            programmatic = false;
        }
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
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setAvailableThinkingLevels(levels));
            return;
        }
        programmatic = true;
        try {
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
        } finally {
            programmatic = false;
        }
    }

    private void setContextUsage(int used, int total) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setContextUsage(used, total));
            return;
        }
        gauge.update(used, total);
    }

    private void setCombosEnabled(boolean enabled) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setCombosEnabled(enabled));
            return;
        }
        setCombosEnabledNow(enabled);
    }

    private void setCombosEnabledNow(boolean enabled) {
        combosEnabled = enabled;
        modelCombo.setEnabled(enabled);
        thinkingLevelCombo.setEnabled(enabled);
        refreshCompactEnabled();
    }

    @Override
    public void onCompactingChanged(boolean compacting) {
        this.compacting = compacting;
        refreshCompactEnabled();
    }

    /**
     * Compact has two independent reasons to be disabled — a turn running, and a compaction in flight — and
     * must honour both. Setting it from either alone let one undo the other: a turn ending mid-compaction
     * re-enabled it.
     */
    private void refreshCompactEnabled() {
        compactBtn.setEnabled(combosEnabled && !compacting);
    }

    /**
     * Updates {@code versionCheck} in place (e.g. after a later {@code get_state} reports a different
     * installed version than was known at construction time) and refreshes the button.
     */
    public void setVersionCheck(PiVersionCheck versionCheck) {
        this.versionCheck = versionCheck;
        refreshVersionWarningButton();
    }

    /**
     * The one line of the click path that opens a modal dialog, isolated so a test can exercise the rest of
     * the path headlessly by overriding it — without a seam, the only way the handler's shadowed-capture bug
     * could have been caught was by clicking the button in a running IDE. Package-private and overridable for
     * that reason alone; production has exactly one implementation.
     */
    void showVersionWarningDialog(PiVersionCheck check) {
        PiVersionWarningDialog.show(versionWarningBtn, check);
    }

    private void refreshVersionWarningButton() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshVersionWarningButton);
            return;
        }
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

    private final class ControlListener implements PiSessionControl.Listener {

        @Override
        public void onAvailableModelsChanged(List<String> providerSlashId) {
            setAvailableModels(providerSlashId);
        }

        @Override
        public void onAvailableThinkingLevelsChanged(List<String> levels) {
            setAvailableThinkingLevels(levels);
        }

        @Override
        public void onCurrentSelectionChanged(String providerSlashId, String thinkingLevel) {
            if (providerSlashId != null && !providerSlashId.isBlank()) {
                setSelectedModel(providerSlashId);
            }
            if (thinkingLevel != null && !thinkingLevel.isBlank()) {
                setSelectedThinkingLevel(thinkingLevel);
            }
        }

        @Override
        public void onContextUsageChanged(int usedTokens, int contextWindowTokens) {
            setContextUsage(usedTokens, contextWindowTokens);
        }

        @Override
        public void onTurnRunningChanged(boolean running) {
            setCombosEnabled(!running);
        }
    }
}
