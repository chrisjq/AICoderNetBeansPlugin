package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiAvailableThinkingLevelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiContextUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiModelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiSessionModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiThinkingLevelChangedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionCheckedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events.PiVersionVerifiedEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiAiInfoBarExtensionTest {

    private static final class RecordingListener implements PiInfoBarListener {

        String model;
        String thinkingLevel;
        PiVersionCheck verified;
        PiVersionCheck markedNotWorking;

        @Override
        public void onModelChanged(String providerSlashId) {
            model = providerSlashId;
        }

        @Override
        public void onThinkingLevelChanged(String level) {
            thinkingLevel = level;
        }

        @Override
        public void onVersionVerified(PiVersionCheck check) {
            verified = check;
        }

        @Override
        public void onVersionMarkedNotWorking(PiVersionCheck check) {
            markedNotWorking = check;
        }

        @Override
        public void onCompactRequested() {
        }
    }

    private static PiAiInfoBarExtension bar() {
        return new PiAiInfoBarExtension(null, null, null);
    }

    private static void deliver(PiAiInfoBarExtension ext, AiProcessImplEvent event) throws Exception {
        SwingUtilities.invokeAndWait(() -> ext.onAiProcessImplEvent(event));
    }

    private static void deliver(PiAiInfoBarExtension ext, AiPropertyEvent event) throws Exception {
        SwingUtilities.invokeAndWait(() -> ext.onPropertyEvent(event));
    }

    @SuppressWarnings("unchecked")
    private static List<String> items(JComboBox<?> combo) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            items.add(String.valueOf(((JComboBox<String>) combo).getItemAt(i)));
        }
        return items;
    }

    @Test
    void theInfoBarExposesNoImperativeSetterForTheVersionCheck() {
        for (java.lang.reflect.Method m : PiAiInfoBarExtension.class.getDeclaredMethods()) {
            assertFalse(m.getName().equals("setVersionCheck"),
                    "the version check must arrive as a PiVersionCheckedEvent, not through a setter");
        }
    }

    @Test
    void createComponentsReturnsModelThinkingGaugeVersionButtonAndCompactButton() {
        PiAiInfoBarExtension ext = bar();
        assertEquals(5, ext.createComponents().size());
    }

    @Test
    void versionWarningButtonHiddenWhenNoVersionCheckProvided() {
        PiAiInfoBarExtension ext = bar();
        assertFalse(ext.createComponents().get(3).isVisible());
    }

    @Test
    void versionWarningButtonHiddenForATestedVersion() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(null,
                new PiVersionCheck(PiVersionCheck.TESTED_MAJOR_MINOR + ".1"), null);
        assertFalse(ext.createComponents().get(3).isVisible());
    }

    @Test
    void versionWarningButtonVisibleWhenWarningApplies() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(null, new PiVersionCheck("0.99.0"), null);
        assertTrue(ext.createComponents().get(3).isVisible());
    }

    @Test
    void versionCheckedEventMakesTheWarningButtonAppear() throws Exception {
        PiAiInfoBarExtension ext = bar();
        JComponent warningBtn = ext.createComponents().get(3);
        assertFalse(warningBtn.isVisible());

        deliver(ext, new PiVersionCheckedEvent(new PiVersionCheck("0.99.0")));

        assertTrue(warningBtn.isVisible(), "a PiVersionCheckedEvent for an untested version must show the warning button");
    }

    @Test
    void versionCheckedEventWithNoCheckClearsAStaleWarning() throws Exception {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(null, new PiVersionCheck("0.99.0"), null);
        JComponent warningBtn = ext.createComponents().get(3);
        assertTrue(warningBtn.isVisible());

        deliver(ext, new PiVersionCheckedEvent(null));

        assertFalse(warningBtn.isVisible(), "a failed probe (null check) must clear the previous session's warning");
    }

    @Test
    void clickingTheWarningButtonUsesTheCheckSuppliedAfterConstruction() throws Exception {
        // AiTopComponent builds the info bar synchronously, BEFORE PiAiProcessManager.start() has discovered the
        // installed version, so the constructor may get null here and the real check arrives afterwards as a
        // PiVersionCheckedEvent. The click handler must therefore read the field at click
        // time. It once captured the constructor's same-named parameter instead — the button still appeared and
        // still showed the right tooltip (both read the field), but every click hit the null guard and returned,
        // which is unobservable without driving the click itself.
        PiVersionCheck[] opened = new PiVersionCheck[1];
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(null, null, null) {
            @Override
            void showVersionWarningDialog(PiVersionCheck check) {
                opened[0] = check;
            }
        };
        JButton warningBtn = (JButton) ext.createComponents().get(3);

        PiVersionCheck arrivedLater = new PiVersionCheck("0.99.0");
        deliver(ext, new PiVersionCheckedEvent(arrivedLater));
        assertTrue(warningBtn.isVisible(), "the later check should make the button appear");

        SwingUtilities.invokeAndWait(warningBtn::doClick);
        assertSame(arrivedLater, opened[0], "the click must open the dialog with the check the event supplied");
    }

    @Test
    void theDialogsAnswersReachTheListenersWithTheCheckTheDialogWasOpenedFor() throws Exception {
        PiAiInfoBarExtension ext = bar();
        RecordingListener listener = new RecordingListener();
        ext.addListener(listener);
        PiVersionCheck check = new PiVersionCheck("0.99.0");
        deliver(ext, new PiVersionCheckedEvent(check));
        JButton warningBtn = (JButton) ext.createComponents().get(3);

        ext.setVersionDialogPresenter((parent, shown, onVerified, onMarkedNotWorking) -> {
            assertSame(check, shown);
            onVerified.run();
        });
        SwingUtilities.invokeAndWait(warningBtn::doClick);
        assertSame(check, listener.verified, "the dialog's Verify answer must reach the listeners");
        assertNull(listener.markedNotWorking);

        ext.setVersionDialogPresenter((parent, shown, onVerified, onMarkedNotWorking) -> onMarkedNotWorking.run());
        SwingUtilities.invokeAndWait(warningBtn::doClick);
        assertSame(check, listener.markedNotWorking, "the dialog's Not-working answer must reach the listeners");
    }

    @Test
    void busyContractDisablesEveryActionControlAndReadyReEnablesThem() throws Exception {
        PiAiInfoBarExtension ext = bar();
        List<JComponent> components = ext.createComponents();

        SwingUtilities.invokeAndWait(() -> ext.onBusyChanged(true));
        assertFalse(components.get(0).isEnabled(), "busy must disable the model combo");
        assertFalse(components.get(1).isEnabled(), "busy must disable the thinking-level combo");
        assertFalse(components.get(4).isEnabled(), "busy must disable Compact");

        SwingUtilities.invokeAndWait(() -> ext.onBusyChanged(false));
        assertTrue(components.get(0).isEnabled(), "ready must re-enable the model combo");
        assertTrue(components.get(1).isEnabled(), "ready must re-enable the thinking-level combo");
        assertTrue(components.get(4).isEnabled(), "ready must re-enable Compact");
    }

    @Test
    void secondInfoBarInstanceRefreshesWhenVersionVerifiedEventFires() throws Exception {
        // Verifying a version via PiVersionWarningDialog in one tab writes type-global state
        // (PiPluginSettings.verifiedVersion / PiVersionCheck's process-wide not-working set) — every OTHER open pi
        // tab's info bar must also refresh, not just the one the dialog was opened from. This instance never touches
        // PiPluginSettings itself; onPropertyEvent is its only way to hear about it.
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(null, new PiVersionCheck("0.99.0"), null);
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
    void selectingAModelNotifiesTheListenerWithTheFullProviderSlashId() {
        PiAiInfoBarExtension ext = bar();
        RecordingListener listener = new RecordingListener();
        ext.addListener(listener);
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);

        modelCombo.addItem("github-copilot/gpt-5-mini");
        modelCombo.setSelectedItem("github-copilot/gpt-5-mini");

        assertEquals("github-copilot/gpt-5-mini", listener.model);
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectingAThinkingLevelNotifiesTheListener() {
        PiAiInfoBarExtension ext = bar();
        RecordingListener listener = new RecordingListener();
        ext.addListener(listener);
        JComboBox<String> levelCombo = (JComboBox<String>) ext.createComponents().get(1);

        levelCombo.addItem("high");
        levelCombo.setSelectedItem("high");

        assertEquals("high", listener.thinkingLevel);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reselectingAModelAfterAnExternalChangeStillNotifies() throws Exception {
        PiAiInfoBarExtension ext = bar();
        RecordingListener listener = new RecordingListener();
        ext.addListener(listener);
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);

        // User picks A.
        modelCombo.addItem("github-copilot/gpt-5-mini");
        modelCombo.setSelectedItem("github-copilot/gpt-5-mini");
        assertEquals("github-copilot/gpt-5-mini", listener.model);

        // pi itself reports the actual current model is something else (e.g. it started a different model at
        // launch) — a programmatic change, not a user pick.
        deliver(ext, new PiModelChangedEvent("anthropic/claude-sonnet-4-6"));
        assertEquals("anthropic/claude-sonnet-4-6", modelCombo.getSelectedItem());

        listener.model = null;

        // User picks A again — must NOT be swallowed as a stale "no-op" against the old lastNotifiedModel.
        modelCombo.setSelectedItem("github-copilot/gpt-5-mini");

        assertEquals("github-copilot/gpt-5-mini", listener.model,
                "reselecting the original value after an external change must notify again");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reselectingAThinkingLevelAfterAnExternalChangeStillNotifies() throws Exception {
        PiAiInfoBarExtension ext = bar();
        RecordingListener listener = new RecordingListener();
        ext.addListener(listener);
        JComboBox<String> levelCombo = (JComboBox<String>) ext.createComponents().get(1);

        levelCombo.addItem("high");
        levelCombo.setSelectedItem("high");
        assertEquals("high", listener.thinkingLevel);

        deliver(ext, new PiThinkingLevelChangedEvent("low"));
        assertEquals("low", levelCombo.getSelectedItem());

        listener.thinkingLevel = null;

        levelCombo.setSelectedItem("high");

        assertEquals("high", listener.thinkingLevel,
                "reselecting the original level after an external change must notify again");
    }

    @Test
    @SuppressWarnings("unchecked")
    void setAvailableThinkingLevelsRefresh_dropsAnInjectedLevelAndSyncsLastNotified() throws Exception {
        PiAiInfoBarExtension ext = bar();
        RecordingListener listener = new RecordingListener();
        ext.addListener(listener);
        JComboBox<String> levelCombo = (JComboBox<String>) ext.createComponents().get(1);

        // pi reports a current level not yet in any available-levels list (the get_state-before-levels race) —
        // ensureThinkingLevelItemPresent injects it so the selection is at least visible.
        deliver(ext, new PiThinkingLevelChangedEvent("extreme"));
        assertEquals("extreme", levelCombo.getSelectedItem());

        // The authoritative levels list then arrives and does NOT contain "extreme" — it must be dropped, not
        // linger in the dropdown forever.
        deliver(ext, new PiAvailableThinkingLevelsEvent(List.of("low", "medium", "high")));

        Object shown = levelCombo.getSelectedItem();
        assertEquals("low", shown, "the injected level must not survive a refresh that doesn't contain it");

        // If lastNotifiedThinkingLevel still disagreed with what's shown (stuck on "extreme"), reselecting the
        // value ALREADY shown would incorrectly look like a real change and notify again.
        listener.thinkingLevel = null;
        levelCombo.setSelectedItem(shown);
        assertNull(listener.thinkingLevel,
                "reselecting the value already shown must be a no-op — lastNotifiedThinkingLevel must agree "
                + "with the combo after the refresh dropped the injected level");
    }

    @Test
    @SuppressWarnings("unchecked")
    void constructorSeedsCombosFromSessionSettings() {
        PiSessionSettings settings = new PiSessionSettings();
        settings.setModel("anthropic/claude-sonnet-4-6");
        settings.setThinkingLevel("medium");

        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(settings, null, null);

        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);
        JComboBox<String> levelCombo = (JComboBox<String>) ext.createComponents().get(1);
        assertEquals("anthropic/claude-sonnet-4-6", modelCombo.getSelectedItem());
        assertEquals("medium", levelCombo.getSelectedItem());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionModelsEventPopulatesTheModelCombo() throws Exception {
        PiAiInfoBarExtension ext = bar();
        List<JComponent> components = ext.createComponents();

        deliver(ext, new PiSessionModelsEvent(List.of("github-copilot/gpt-5-mini", "anthropic/claude-sonnet-4-6")));

        JComboBox<String> modelCombo = (JComboBox<String>) components.get(0);
        assertEquals(List.of("github-copilot/gpt-5-mini", "anthropic/claude-sonnet-4-6"), items(modelCombo));
    }

    @Test
    @SuppressWarnings("unchecked")
    void availableThinkingLevelsEventPopulatesTheLevelCombo() throws Exception {
        PiAiInfoBarExtension ext = bar();
        List<JComponent> components = ext.createComponents();

        deliver(ext, new PiAvailableThinkingLevelsEvent(List.of("off", "minimal", "low", "medium", "high")));

        JComboBox<String> levelCombo = (JComboBox<String>) components.get(1);
        assertEquals(5, levelCombo.getItemCount());
    }

    @Test
    void contextUsageEventUpdatesTheGauge() throws Exception {
        PiAiInfoBarExtension ext = bar();
        JProgressBar gauge = (JProgressBar) ext.createComponents().get(2);

        deliver(ext, new PiContextUsageEvent(50, 200));

        assertEquals(25, gauge.getValue());
    }

    @Test
    void modelChangedEventDoesNotNotifyTheListeners() throws Exception {
        PiAiInfoBarExtension ext = bar();
        RecordingListener listener = new RecordingListener();
        ext.addListener(listener);

        deliver(ext, new PiModelChangedEvent("anthropic/claude-sonnet-4-6"));
        deliver(ext, new PiThinkingLevelChangedEvent("high"));

        assertNull(listener.model, "a model pi reported must not be echoed back as a user pick");
        assertNull(listener.thinkingLevel, "a level pi reported must not be echoed back as a user pick");
    }

    // ---- the type-wide model catalog arrives on the property bus ----
    @Test
    @SuppressWarnings("unchecked")
    void availableModelsEventFromTheCatalogPopulatesTheModelCombo() throws Exception {
        PiAiInfoBarExtension ext = bar();
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);

        deliver(ext, new AvailableModelsEvent(List.of("p1/m1", "p2/m2")));

        assertEquals(List.of("p1/m1", "p2/m2"), items(modelCombo));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBarOpenedAfterTheCatalogWasPublishedIsSeededFromTheCachedSnapshot() {
        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(null, null, List.of("p1/m1", "p2/m2"));
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);

        assertEquals(List.of("p1/m1", "p2/m2"), items(modelCombo),
                "the bus does not replay, so the constructor's cached snapshot is the only way a late bar sees the list");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachedSnapshotSeedingKeepsTheSessionsOwnModelSelectedAndNotifiesNobody() throws Exception {
        PiSessionSettings settings = new PiSessionSettings();
        settings.setModel("p2/m2");

        PiAiInfoBarExtension ext = new PiAiInfoBarExtension(settings, null, List.of("p1/m1", "p2/m2"));
        RecordingListener listener = new RecordingListener();
        ext.addListener(listener);
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);
        assertEquals("p2/m2", modelCombo.getSelectedItem(), "seeding the list must keep the session's own model selected");

        deliver(ext, new AvailableModelsEvent(List.of("p3/m3", "p2/m2", "p1/m1")));

        assertEquals("p2/m2", modelCombo.getSelectedItem(), "a refreshed list must keep the shown model selected");
        assertNull(listener.model, "replacing the list must not look like a user pick");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSessionsOwnModelListBeatsALaterCatalogEvent() throws Exception {
        PiAiInfoBarExtension ext = bar();
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);

        deliver(ext, new PiSessionModelsEvent(List.of("session/only")));
        deliver(ext, new AvailableModelsEvent(List.of("catalog/a", "catalog/b")));

        assertEquals(List.of("session/only"), items(modelCombo),
                "once the running pi has reported its models, the type-wide catalog must not overwrite them");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCatalogEventBeforeTheSessionReportsIsReplacedBySessionModels() throws Exception {
        PiAiInfoBarExtension ext = bar();
        JComboBox<String> modelCombo = (JComboBox<String>) ext.createComponents().get(0);

        deliver(ext, new AvailableModelsEvent(List.of("catalog/a", "catalog/b")));
        deliver(ext, new PiSessionModelsEvent(List.of("session/only")));

        assertEquals(List.of("session/only"), items(modelCombo));
    }

    @Test
    void compactButtonIsPresentAndEnabledByDefault() {
        PiAiInfoBarExtension ext = bar();
        JComponent compactBtn = ext.createComponents().get(4);
        assertTrue(compactBtn.isEnabled());
        assertTrue(compactBtn.isVisible());
    }

    @Test
    void clickingCompactNotifiesListener() {
        PiAiInfoBarExtension ext = bar();
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
        PiAiInfoBarExtension ext = bar();
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
