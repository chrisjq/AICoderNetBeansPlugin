package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionVerifiedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiAiInfoBarExtensionTest {

    private static final class FakeControl implements PiSessionControl {

        Listener listener;
        boolean turnRunning;
        String lastSetModelProvider;
        String lastSetModelId;
        String lastSetThinkingLevel;

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public CompletableFuture<Void> setModel(String provider, String modelId) {
            lastSetModelProvider = provider;
            lastSetModelId = modelId;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> setThinkingLevel(String level) {
            lastSetThinkingLevel = level;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean isTurnRunning() {
            return turnRunning;
        }
    }

    @Test
    void createComponentsReturnsModelThinkingGaugeVersionButtonAndCompactButton() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(new FakeControl(), null, null);
        assertEquals(5, ext.createComponents().size());
    }

    @Test
    void registersItselfAsControlListenerOnConstruction() {
        FakeControl control = new FakeControl();
        new PiAiInfoBarExtension(control, null, null);
        assertNotNull(control.listener);
    }

    @Test
    void disposeUnregistersTheControlListener() {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        ext.dispose();
        assertNull(control.listener);
    }

    @Test
    void versionWarningButtonHiddenWhenNoVersionCheckProvided() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(new FakeControl(), null, null);
        assertFalse(ext.createComponents().get(3).isVisible());
    }

    @Test
    void versionWarningButtonHiddenForATestedVersion() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(new FakeControl(), null, new PiVersionCheck("0.85.1"));
        assertFalse(ext.createComponents().get(3).isVisible());
    }

    @Test
    void versionWarningButtonVisibleWhenWarningApplies() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(new FakeControl(), null, new PiVersionCheck("0.99.0"));
        assertTrue(ext.createComponents().get(3).isVisible());
    }

    @Test
    void secondInfoBarInstanceRefreshesWhenVersionVerifiedEventFires() throws Exception {
        // Verifying a version via PiVersionWarningDialog in one tab writes type-global state
        // (PiPluginSettings.verifiedVersion / PiVersionCheck's process-wide not-working set) — every OTHER open pi
        // tab's info bar must also refresh, not just the one the dialog was opened from. This instance never calls
        // setVersionCheck or touches PiPluginSettings itself; onPropertyEvent is its only way to hear about it.
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(new FakeControl(), null, new PiVersionCheck("0.99.0"));
        JComponent warningBtn = ext.createComponents().get(3);
        assertTrue(warningBtn.isVisible(), "warning should show for an unverified version");

        try {
            PiPluginSettings.setVerifiedVersion("0.99.0");
            SwingUtilities.invokeAndWait(() -> ext.onPropertyEvent(new PiVersionVerifiedEvent()));
            assertFalse(warningBtn.isVisible(), "onPropertyEvent(PiVersionVerifiedEvent) must refresh the button");
        }
        finally {
            PiPluginSettings.setVerifiedVersion("");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectingAModelSendsSetModelSplitOnTheFirstSlash() {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);

        modelCombo.addItem("github-copilot/gpt-5-mini");
        modelCombo.setSelectedItem("github-copilot/gpt-5-mini");

        assertEquals("github-copilot", control.lastSetModelProvider);
        assertEquals("gpt-5-mini", control.lastSetModelId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectingAThinkingLevelSendsSetThinkingLevel() {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        JComboBox<String> levelCombo = (JComboBox<String>) ext.createComponents().get(1);

        levelCombo.addItem("high");
        levelCombo.setSelectedItem("high");

        assertEquals("high", control.lastSetThinkingLevel);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reselectingAModelAfterAnExternalChangeStillNotifies() throws Exception {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);

        // User picks A.
        modelCombo.addItem("github-copilot/gpt-5-mini");
        modelCombo.setSelectedItem("github-copilot/gpt-5-mini");
        assertEquals("github-copilot", control.lastSetModelProvider);
        assertEquals("gpt-5-mini", control.lastSetModelId);

        // pi itself reports the actual current model is something else (e.g. it started a different model at
        // launch) — a programmatic change, not a user pick.
        SwingUtilities.invokeAndWait(
                () -> control.listener.onCurrentSelectionChanged("anthropic/claude-sonnet-4-6", null));
        assertEquals("anthropic/claude-sonnet-4-6", modelCombo.getSelectedItem());

        control.lastSetModelProvider = null;
        control.lastSetModelId = null;

        // User picks A again — must NOT be swallowed as a stale "no-op" against the old lastNotifiedModel.
        modelCombo.setSelectedItem("github-copilot/gpt-5-mini");

        assertEquals("github-copilot", control.lastSetModelProvider,
                     "reselecting the original value after an external change must notify again");
        assertEquals("gpt-5-mini", control.lastSetModelId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reselectingAThinkingLevelAfterAnExternalChangeStillNotifies() throws Exception {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        JComboBox<String> levelCombo = (JComboBox<String>) ext.createComponents().get(1);

        levelCombo.addItem("high");
        levelCombo.setSelectedItem("high");
        assertEquals("high", control.lastSetThinkingLevel);

        SwingUtilities.invokeAndWait(() -> control.listener.onCurrentSelectionChanged(null, "low"));
        assertEquals("low", levelCombo.getSelectedItem());

        control.lastSetThinkingLevel = null;

        levelCombo.setSelectedItem("high");

        assertEquals("high", control.lastSetThinkingLevel,
                     "reselecting the original level after an external change must notify again");
    }

    @Test
    @SuppressWarnings("unchecked")
    void setAvailableThinkingLevelsRefresh_dropsAnInjectedLevelAndSyncsLastNotified() throws Exception {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        JComboBox<String> levelCombo = (JComboBox<String>) ext.createComponents().get(1);

        // pi reports a current level not yet in any available-levels list (the get_state-before-levels race) —
        // ensureThinkingLevelItemPresent injects it so the selection is at least visible.
        SwingUtilities.invokeAndWait(() -> control.listener.onCurrentSelectionChanged(null, "extreme"));
        assertEquals("extreme", levelCombo.getSelectedItem());

        // The authoritative levels list then arrives and does NOT contain "extreme" — it must be dropped, not
        // linger in the dropdown forever.
        SwingUtilities.invokeAndWait(
                () -> control.listener.onAvailableThinkingLevelsChanged(List.of("low", "medium", "high")));

        Object shown = levelCombo.getSelectedItem();
        assertEquals("low", shown, "the injected level must not survive a refresh that doesn't contain it");

        // If lastNotifiedThinkingLevel still disagreed with what's shown (stuck on "extreme"), reselecting the
        // value ALREADY shown would incorrectly look like a real change and notify again.
        control.lastSetThinkingLevel = null;
        levelCombo.setSelectedItem(shown);
        assertNull(control.lastSetThinkingLevel,
                   "reselecting the value already shown must be a no-op — lastNotifiedThinkingLevel must agree "
                   + "with the combo after the refresh dropped the injected level");
    }

    @Test
    @SuppressWarnings("unchecked")
    void constructorSeedsCombosFromSessionSettingsWithoutCallingControl() {
        PiSessionSettings settings = new PiSessionSettings();
        settings.setModel("anthropic/claude-sonnet-4-6");
        settings.setThinkingLevel("medium");
        FakeControl control = new FakeControl();

        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, settings, null);

        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);
        JComboBox<String> levelCombo = (JComboBox<String>) ext.createComponents().get(1);
        assertEquals("anthropic/claude-sonnet-4-6", modelCombo.getSelectedItem());
        assertEquals("medium", levelCombo.getSelectedItem());
        assertNull(control.lastSetModelProvider, "seeding from settings must not itself call set_model");
        assertNull(control.lastSetThinkingLevel, "seeding from settings must not itself call set_thinking_level");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onAvailableModelsChangedPopulatesTheModelCombo() throws Exception {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        List<JComponent> components = ext.createComponents();

        SwingUtilities.invokeAndWait(() -> control.listener.onAvailableModelsChanged(
                List.of("github-copilot/gpt-5-mini", "anthropic/claude-sonnet-4-6")));

        JComboBox<String> modelCombo = (JComboBox<String>) components.get(0);
        assertEquals(2, modelCombo.getItemCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onAvailableThinkingLevelsChangedPopulatesTheLevelCombo() throws Exception {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        List<JComponent> components = ext.createComponents();

        SwingUtilities.invokeAndWait(() -> control.listener.onAvailableThinkingLevelsChanged(
                List.of("off", "minimal", "low", "medium", "high")));

        JComboBox<String> levelCombo = (JComboBox<String>) components.get(1);
        assertEquals(5, levelCombo.getItemCount());
    }

    @Test
    void onContextUsageChangedUpdatesTheGauge() throws Exception {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        JProgressBar gauge = (JProgressBar) ext.createComponents().get(2);

        SwingUtilities.invokeAndWait(() -> control.listener.onContextUsageChanged(50, 200));

        assertEquals(25, gauge.getValue());
    }

    @Test
    void onTurnRunningChangedDisablesThenReEnablesCombosAndCompactButton() throws Exception {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        List<JComponent> components = ext.createComponents();

        SwingUtilities.invokeAndWait(() -> control.listener.onTurnRunningChanged(true));
        assertFalse(components.get(0).isEnabled());
        assertFalse(components.get(1).isEnabled());
        assertFalse(components.get(4).isEnabled(), "the Compact button must disable while a turn runs");

        SwingUtilities.invokeAndWait(() -> control.listener.onTurnRunningChanged(false));
        assertTrue(components.get(0).isEnabled());
        assertTrue(components.get(1).isEnabled());
        assertTrue(components.get(4).isEnabled());
    }

    @Test
    void constructorAppliesAnAlreadyRunningTurnFromControl() {
        FakeControl control = new FakeControl();
        control.turnRunning = true;

        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);

        List<JComponent> components = ext.createComponents();
        assertFalse(components.get(0).isEnabled(), "model combo must start disabled when a turn is already running");
        assertFalse(components.get(1).isEnabled(), "thinking-level combo must start disabled when a turn is already running");
        assertFalse(components.get(4).isEnabled(), "Compact must start disabled when a turn is already running");
    }

    @Test
    void compactButtonIsPresentAndEnabledByDefault() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(new FakeControl(), null, null);
        JComponent compactBtn = ext.createComponents().get(4);
        assertTrue(compactBtn.isEnabled());
        assertTrue(compactBtn.isVisible());
    }

    @Test
    void clickingCompactNotifiesListener() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(new FakeControl(), null, null);
        java.util.concurrent.atomic.AtomicInteger compactCount = new java.util.concurrent.atomic.AtomicInteger();
        ext.addListener(new PiInfoBarListener() {
            @Override
            public void onModelChanged(String providerSlashId) {
            }

            @Override
            public void onThinkingLevelChanged(String level) {
            }

            @Override
            public void onCompactRequested() {
                compactCount.incrementAndGet();
            }
        });

        javax.swing.JButton compactBtn = (javax.swing.JButton) ext.createComponents().get(4);
        for (java.awt.event.ActionListener l : compactBtn.getActionListeners()) {
            l.actionPerformed(new java.awt.event.ActionEvent(compactBtn, java.awt.event.ActionEvent.ACTION_PERFORMED, "compact"));
        }

        assertEquals(1, compactCount.get());
    }

    @Test
    void addListenerReceivesModelAndThinkingLevelChanges() {
        FakeControl control = new FakeControl();
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(control, null, null);
        List<String> modelChanges = new java.util.ArrayList<>();
        List<String> levelChanges = new java.util.ArrayList<>();
        ext.addListener(new PiInfoBarListener() {
            @Override
            public void onModelChanged(String providerSlashId) {
                modelChanges.add(providerSlashId);
            }

            @Override
            public void onThinkingLevelChanged(String level) {
                levelChanges.add(level);
            }

            @Override
            public void onCompactRequested() {
            }
        });

        @SuppressWarnings("unchecked")
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);
        modelCombo.addItem("github-copilot/gpt-5-mini");
        modelCombo.setSelectedItem("github-copilot/gpt-5-mini");

        assertEquals(List.of("github-copilot/gpt-5-mini"), modelChanges);
        assertTrue(levelChanges.isEmpty());
    }
}
