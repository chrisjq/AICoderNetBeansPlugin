package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JPanel;
import javax.swing.JTextField;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PiAiSettingsTabTest {

    @AfterEach
    void resetGlobalSettings() {
        PiPluginSettings.setExecutable("");
        PiPluginSettings.setModel("");
        PiPluginSettings.setThinkingLevel("");
        PiPluginSettings.setVerifiedVersion("");
        PiPluginSettings.setDiscoveredModels(new String[0]);
    }

    @Test
    void getTabTitleAndAiType() {
        PiAiSettingsTab tab = new PiAiSettingsTab();
        assertEquals("Pi", tab.getTabTitle());
        assertEquals(AiTypeEnum.PI, tab.getAiType());
    }

    @Test
    void loadThenStoreRoundTripsTheExecutablePath() {
        PiPluginSettings.setExecutable("/usr/local/bin/pi");
        PiAiSettingsTab tab = new PiAiSettingsTab();
        tab.load();

        JTextField executableField = firstFieldOfType(tab.getComponent(), JTextField.class);
        assertEquals("/usr/local/bin/pi", executableField.getText());

        executableField.setText("/opt/pi/bin/pi");
        tab.store();

        assertEquals("/opt/pi/bin/pi", PiPluginSettings.getExecutable());
    }

    @Test
    void load_doesNotMarkTheTabDirty() {
        PiPluginSettings.setExecutable("/usr/local/bin/pi");
        PiPluginSettings.setModel("github-copilot/gpt-5-mini");
        PiPluginSettings.setThinkingLevel("high");
        PiAiSettingsTab tab = new PiAiSettingsTab();
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        tab.addPropertyChangeListener(evt -> fired.incrementAndGet());

        tab.load();

        assertEquals(0, fired.get(), "merely opening the tab must not mark it dirty");
    }

    @Test
    void aRealUserEditToTheExecutableFieldStillFiresPropertyChange() {
        PiAiSettingsTab tab = new PiAiSettingsTab();
        tab.load();
        JTextField executableField = firstFieldOfType(tab.getComponent(), JTextField.class);
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        tab.addPropertyChangeListener(evt -> fired.incrementAndGet());

        executableField.setText("/opt/pi/bin/pi");

        assertTrue(fired.get() > 0, "a real user edit after load() must still mark the tab dirty");
    }

    @Test
    void isValid_emptyExecutableIsValid() {
        PiAiSettingsTab tab = new PiAiSettingsTab();
        tab.load();
        assertTrue(tab.isValid());
    }

    @Test
    void isValid_absolutePathMustExist() {
        PiAiSettingsTab tab = new PiAiSettingsTab();
        tab.load();
        JTextField executableField = firstFieldOfType(tab.getComponent(), JTextField.class);
        executableField.setText("/definitely/does/not/exist/pi");
        assertFalse(tab.isValid());
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultLabelStoresAsEmptyForModelAndThinkingLevel() {
        PiAiSettingsTab tab = new PiAiSettingsTab();
        tab.load();
        tab.store();

        assertEquals("", PiPluginSettings.getModel());
        assertEquals("", PiPluginSettings.getThinkingLevel());
    }

    @Test
    void versionStatusText_testedVersion() {
        assertEquals("Tested with this plugin", PiAiSettingsTab.versionStatusText(new PiVersionCheck("0.85.1")));
    }

    @Test
    void versionStatusText_verifiedByUser() {
        PiPluginSettings.setVerifiedVersion("0.90.0");
        assertEquals("Verified by you", PiAiSettingsTab.versionStatusText(new PiVersionCheck("0.90.0")));
    }

    @Test
    void versionStatusText_notVerified() {
        assertEquals("Not verified (tested: 0.85.x)", PiAiSettingsTab.versionStatusText(new PiVersionCheck("0.90.0")));
    }

    @Test
    void warningApplies_matchesPiVersionCheck() {
        PiVersionCheck tested = new PiVersionCheck("0.85.1");
        PiVersionCheck untested = new PiVersionCheck("0.90.0");
        assertFalse(PiAiSettingsTab.warningApplies(tested));
        assertTrue(PiAiSettingsTab.warningApplies(untested));
    }

    @Test
    void load_autoProbesVersionForATestedExecutableWithoutClickingTest(@TempDir Path dir) throws Exception {
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh\necho 0.85.1\n");
        exe.toFile().setExecutable(true);
        PiPluginSettings.setExecutable(exe.toString());

        PiAiSettingsTab tab = new PiAiSettingsTab();
        tab.load();

        waitUntil(() -> "Tested with this plugin".equals(tab.versionStatusLabelTextForTests()));
        assertFalse(tab.verifyButtonVisibleForTests(), "a tested version must not show the Verify button");
    }

    @Test
    void load_autoProbeShowsVerifyButtonForAnUntestedExecutable(@TempDir Path dir) throws Exception {
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh\necho 0.99.0\n");
        exe.toFile().setExecutable(true);
        PiPluginSettings.setExecutable(exe.toString());

        PiAiSettingsTab tab = new PiAiSettingsTab();
        tab.load();

        waitUntil(tab::verifyButtonVisibleForTests);
        assertEquals("Not verified (tested: 0.85.x)", tab.versionStatusLabelTextForTests());
    }

    /**
     * {@code PiModelDiscovery.discoverAsync} guarantees exactly one terminal {@code onResult} call for every outcome,
     * including the busy-skip case (a discovery already in progress). Occupies that shared in-progress latch with a
     * slow discovery of our own first, so the tab's own Refresh call is very likely to land on the busy-skip path — but
     * the assertion holds either way (busy-skip, or a real attempt of its own against a bad path that fails fast and
     * exhausts its retries), since both converge on the button coming back.
     */
    @Test
    void handleRefreshModels_secondCallWhileOneIsInProgress_stillReEnablesTheButton(@TempDir Path dir) throws Exception {
        Path slowExe = dir.resolve("pi-slow");
        Files.writeString(slowExe, "#!/bin/sh\nsleep 1\nprintf 'provider model\\nprov1 modelA\\n'\n");
        slowExe.toFile().setExecutable(true);

        java.util.concurrent.CountDownLatch slowDiscoveryDone = new java.util.concurrent.CountDownLatch(1);
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiModelDiscovery.discoverAsync(slowExe.toString(),
                                                                                         models -> slowDiscoveryDone.countDown());

        PiAiSettingsTab tab = new PiAiSettingsTab();
        tab.load();

        tab.triggerRefreshForTests();

        assertFalse(tab.refreshModelsButtonEnabledForTests(), "the button starts disabled the moment Refresh is clicked");
        waitUntil(tab::refreshModelsButtonEnabledForTests);

        assertTrue(slowDiscoveryDone.await(5, java.util.concurrent.TimeUnit.SECONDS),
                   "let the background slow discovery finish before the next test runs");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Condition did not become true within 5s");
    }

    @SuppressWarnings("unchecked")
    private static <T> T firstFieldOfType(JPanel panel, Class<T> type) {
        for (java.awt.Component c : panel.getComponents()) {
            if (type.isInstance(c)) {
                return (T) c;
            }
        }
        throw new AssertionError("No component of type " + type + " found");
    }
}
