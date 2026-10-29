package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ConnectionTester;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SavedServerKeys;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SecretStore;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.keys.SessionSecrets;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.SavedServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Basic panel's load/applyTo round trip: every P0b field survives, the base URL is normalised on apply, and a typed
 * key marks the session as having one.
 */
class OpenAiCompatibleBasicPanelTest {

    @Test
    @Timeout(15)
    void aPartlyFailedSessionKeySaveDeletesTheKeyThatSavedAndKeepsTheStagedKey() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        AiSession saved = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);
        AiSession broken = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);
        String brokenKey = "aicoder.openai_compatible." + broken.id();
        Map<String, char[]> entries = new HashMap<>();
        SecretStore store = new SecretStore() {
            @Override
            public char[] read(String key) {
                char[] value = entries.get(key);
                return key.equals(brokenKey) && value != null ? "other".toCharArray() : value;
            }

            @Override
            public void save(String key, char[] value, String description) {
                entries.put(key, value.clone());
            }

            @Override
            public void delete(String key) {
                entries.remove(key);
            }
        };
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        panel.setSessionSecretStoreForTest(store);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            findPasswordField(panel).setText("sk-partial");
            findButton(panel, "Change").doClick();

            assertThrows(CompletionException.class,
                         () -> panel.savePendingSecrets(List.of(saved, broken)).join());

            assertNull(entries.get("aicoder.openai_compatible." + saved.id()),
                       "the key that did save is deleted when another session's save fails");
            assertTrue(panel.pendingApiKey().isPresent(), "the key stays staged so a retry can use it");
        }
        finally {
            panel.dispose();
        }
    }

    /**
     * Waits until the EDT has run everything queued so far (the panel changes its staged-key state there).
     */
    private static void flushEdt() {
        try {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
        catch (InterruptedException | InvocationTargetException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * A keyring whose read-back returns other bytes while {@code working[0]} is false, so a save reports failure.
     */
    private static SecretStore keyring(Map<String, char[]> entries, boolean[] working) {
        return new SecretStore() {
            @Override
            public char[] read(String key) {
                char[] value = entries.get(key);
                return value != null && !working[0] ? "other".toCharArray() : value == null ? null : value.clone();
            }

            @Override
            public void save(String key, char[] value, String description) {
                entries.put(key, value.clone());
            }

            @Override
            public void delete(String key) {
                entries.remove(key);
            }
        };
    }

    @Test
    @Timeout(15)
    void aFailedKeySaveIsRetriedByTheNextApplyAndTheStagedKeyIsKeptUntilItSucceeds() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        boolean[] working = {false};
        SessionSecrets secrets = new SessionSecrets("retry", keyring(entries, working), Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            findPasswordField(panel).setText("sk-retry");

            panel.applyTo(new OpenAiCompatibleSessionSettings());
            assertFalse(panel.keySaveFuture().join(), "the first save fails its read-back");
            assertTrue(panel.pendingApiKey().isPresent(), "the staged key is kept until a save succeeds");

            working[0] = true;
            panel.applyTo(new OpenAiCompatibleSessionSettings());
            assertTrue(panel.keySaveFuture().join(), "the next apply retries the save and it succeeds");
            assertArrayEquals("sk-retry".toCharArray(), entries.get("aicoder.openai_compatible.retry"));
            flushEdt();
            assertTrue(panel.pendingApiKey().isEmpty(), "the staged key is forgotten once it is saved");
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void revertPutsBackTheKeyThatWasThereWhenTheFormLoaded() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        entries.put("aicoder.openai_compatible.revert", "old-key".toCharArray());
        boolean[] working = {true};
        SessionSecrets secrets = new SessionSecrets("revert", keyring(entries, working), Runnable::run, true);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            // Production order: load, then startLoading, then the keyring state is known before a save is staged.
            panel.startLoading();
            panel.keyStateReady().join();
            findPasswordField(panel).setText("new-key");
            panel.applyTo(new OpenAiCompatibleSessionSettings());
            CompletableFuture<Boolean> change = panel.keySaveFuture();
            assertTrue(change.join());

            CompletableFuture<Void> revert = panel.revertKeyChange(change);
            panel.dispose();
            revert.join();

            assertArrayEquals("old-key".toCharArray(), entries.get("aicoder.openai_compatible.revert"),
                              "the revert puts the key from before the form loaded back");
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void revertWithNoPreviousKeyClearsTheKeyTheFormSaved() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        boolean[] working = {true};
        SessionSecrets secrets = new SessionSecrets("revert-none", keyring(entries, working), Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            // Production order: load, then startLoading, then the keyring state is known before a save is staged.
            panel.startLoading();
            panel.keyStateReady().join();
            findPasswordField(panel).setText("new-key");
            panel.applyTo(new OpenAiCompatibleSessionSettings());
            CompletableFuture<Boolean> change = panel.keySaveFuture();
            assertTrue(change.join());

            panel.revertKeyChange(change).join();

            assertNull(entries.get("aicoder.openai_compatible.revert-none"),
                       "with no key before the form loaded, the revert clears the saved key");
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void everyFieldSurvivesALoadApplyRoundTrip() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        OpenAiCompatibleSessionSettings source = new OpenAiCompatibleSessionSettings();
        source.setServerType(ServerTypeEnum.LM_STUDIO);
        source.setBaseUrl("http://localhost:1234");
        source.setModel("test-model");
        source.setContextWindowOverride(8192);
        source.setToolCallingMode(ToolCallingModeEnum.NATIVE);
        // Leave this unset: a newly created/legacy session must show the default checked and persist true on Apply.
        source.setApiKeySet(true);

        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            panel.load(source);

            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);

            assertEquals(ServerTypeEnum.LM_STUDIO, result.serverType());
            assertEquals("http://localhost:1234", result.baseUrl());
            assertEquals("test-model", result.effectiveModel());
            assertEquals(8192, result.contextWindowOverride());
            assertEquals(ToolCallingModeEnum.NATIVE, result.toolCallingMode());
            assertEquals(Boolean.TRUE, result.saveContextOnClose());
            assertEquals(Boolean.TRUE, result.apiKeySet());
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void baseUrlIsNormalisedOnApplyAndASlashV1IsRemoved() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        OpenAiCompatibleSessionSettings source = new OpenAiCompatibleSessionSettings();
        source.setBaseUrl("https://host/v1/chat/completions");

        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            panel.load(source);

            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);

            assertEquals("https://host", result.baseUrl(), "the /chat/completions and /v1 are normalised away");
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void zeroContextOverrideMeansAutoAndSurvivesAsZero() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        OpenAiCompatibleSessionSettings source = new OpenAiCompatibleSessionSettings();
        source.setContextWindowOverride(0);

        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            panel.load(source);

            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);

            assertEquals(0, result.effectiveContextWindowOverride(), "0 (auto) is kept as-is");
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void aTypedKeyMarksTheSessionAsHavingOneAndIsHeldInMemory() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        OpenAiCompatibleSessionSettings source = new OpenAiCompatibleSessionSettings();
        source.setApiKeySet(false);

        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            panel.load(source);
            findPasswordField(panel).setText("sk-secret"); // Create/OK may be pressed without first pressing Change.

            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);

            assertEquals(Boolean.TRUE, result.apiKeySet(), "typing a key means the session has one");
            assertTrue(panel.pendingApiKey().isPresent(), "the typed key is held in memory for the caller to save");
            assertArrayEquals("sk-secret".toCharArray(), panel.pendingApiKey().orElseThrow());
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void applyingAnUnchangedTypedKeySavesItForAnExistingSession() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        Map<String, char[]> keyring = new HashMap<>();
        SecretStore store = new SecretStore() {
            @Override
            public char[] read(String key) {
                return keyring.get(key);
            }

            @Override
            public void save(String key, char[] value, String description) {
                keyring.put(key, value.clone());
            }

            @Override
            public void delete(String key) {
                keyring.remove(key);
            }
        };
        SessionSecrets secrets = new SessionSecrets("session-1", store, Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            findPasswordField(panel).setText("sk-secret"); // Do not press Change: OK/apply must still store it.

            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);

            assertEquals(Boolean.TRUE, result.apiKeySet());
            assertTrue(panel.keySaveFuture().join(), "keySaveFuture reports the successful keyring write");
            assertArrayEquals("sk-secret".toCharArray(),
                              keyring.get("aicoder.openai_compatible.session-1"));
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void rejectedProbeExecutorReEnablesTestButton() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(null,
                                                                          OpenAiCompatibleLimits.defaults(), (name, value) -> CompletableFuture.completedFuture(true),
                                                                          command -> {
                                                                              throw new RejectedExecutionException("closed");
                                                                          });
        try {
            JButton test = findButton(panel, "Test connection");
            test.doClick();
            assertTrue(test.isEnabled());
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void stagedChangeDoesNotWriteUntilApply() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> keyring = new HashMap<>();
        SecretStore store = new SecretStore() {
            public char[] read(String key) {
                return keyring.get(key);
            }

            public void save(String key, char[] value, String description) {
                keyring.put(key, value.clone());
            }

            public void delete(String key) {
                keyring.remove(key);
            }
        };
        SessionSecrets secrets = new SessionSecrets("staged", store, Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            findPasswordField(panel).setText("staged-key");
            findButton(panel, "Change").doClick();
            assertNull(keyring.get("aicoder.openai_compatible.staged"));
            panel.dispose();
            assertNull(keyring.get("aicoder.openai_compatible.staged"));
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void stagedClearDoesNotDeleteUntilApply() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> keyring = new HashMap<>();
        keyring.put("aicoder.openai_compatible.clear-staged", "stored".toCharArray());
        SecretStore store = new SecretStore() {
            public char[] read(String key) {
                return keyring.get(key);
            }

            public void save(String key, char[] value, String description) {
                keyring.put(key, value.clone());
            }

            public void delete(String key) {
                keyring.remove(key);
            }
        };
        SessionSecrets secrets = new SessionSecrets("clear-staged", store, Runnable::run, true);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            findButton(panel, "Clear").doClick();
            assertArrayEquals("stored".toCharArray(), keyring.get("aicoder.openai_compatible.clear-staged"));
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void keySaveFutureReportsFalseAndClearRemovesPendingSave() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        SecretStore forgetful = new SecretStore() {
            @Override
            public char[] read(String key) {
                return null;
            }

            @Override
            public void save(String key, char[] value, String description) {
            }

            @Override
            public void delete(String key) {
            }
        };
        SessionSecrets secrets = new SessionSecrets("session-lossy", forgetful, Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            findPasswordField(panel).setText("sk-not-persisted");
            findButton(panel, "Change").doClick();
            assertNull(panel.keySaveFuture(), "Change is staged; nothing is saved before apply");

            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);

            assertEquals(Boolean.TRUE, result.apiKeySet(), "the candidate remembers that a key was supplied");
            assertNotNull(panel.keySaveFuture(), "apply starts the staged save");
            assertFalse(panel.keySaveFuture().join(), "the save future reports the keyring did not persist it");
            findButton(panel, "Clear").doClick();
            OpenAiCompatibleSessionSettings afterClear = new OpenAiCompatibleSessionSettings();
            panel.applyTo(afterClear);
            assertEquals(Boolean.FALSE, afterClear.apiKeySet(), "clearing the key removes the candidate");
            assertNotNull(panel.keySaveFuture(), "apply starts the staged clear");
            assertTrue(panel.keySaveFuture().join(), "the clear replaces the failed save and reports success");
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void testConnectionUsesAdvertisedReasoningLevelsForTheSelectedModel() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            applyTestResult(panel, connectedResult(Map.of(
                            "splash", List.of("off", "on"),
                            "full", List.of("off", "low", "medium", "xhigh", "on"))));
            assertEquals(List.of("default", "off", "on"), comboItems(reasoningCombo(panel)));

            reasoningCombo(panel).setSelectedItem("on");
            modelCombo(panel).setSelectedItem("full");
            assertEquals(List.of("default", "off", "low", "medium", "xhigh", "on"), comboItems(reasoningCombo(panel)));
            assertEquals("on", reasoningCombo(panel).getSelectedItem(),
                         "an effort available for both models must be retained");

            reasoningCombo(panel).setSelectedItem("xhigh");
            modelCombo(panel).setSelectedItem("splash");
            assertEquals("default", reasoningCombo(panel).getSelectedItem(),
                         "an effort unavailable for the new model must reset to default");
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void testConnectionUsesFallbackLevelsWhenTheServerDoesNotAdvertiseAny() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            applyTestResult(panel, connectedResult(Map.of()));
            assertEquals(List.of("default", "low", "medium", "high"), comboItems(reasoningCombo(panel)));
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void pickingASavedServerWithAKeyStagesUrlTypeAndKey() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        SavedServer server = SavedServer.create("Entry", "http://h:1", ServerTypeEnum.LLAMA_CPP);
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(server));
        SecretStore store = keyring(entries, new boolean[]{true});
        new SavedServerKeys(store, Runnable::run).saveAsync(server.id(), "sk-e".toCharArray()).join();
        SessionSecrets secrets = new SessionSecrets("session", store, Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets, OpenAiCompatibleLimits.defaults(),
                                                                          secrets::saveHeaderAsync, Runnable::run, new SavedServerKeys(store, Runnable::run));
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            baseUrlCombo(panel).setSelectedItem(server);
            flushEdt();
            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);
            assertEquals("http://h:1", result.baseUrl());
            assertEquals(ServerTypeEnum.LLAMA_CPP, result.serverType());
            assertTrue(result.apiKeySet());
            assertTrue(panel.keySaveFuture().join());
            assertArrayEquals("sk-e".toCharArray(), entries.get("aicoder.openai_compatible.session"));
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void pickingASavedServerWithoutAKeyClearsTheSessionKey() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        entries.put("aicoder.openai_compatible.session-clear", "old".toCharArray());
        SavedServer server = SavedServer.create("No key", "http://h:2", ServerTypeEnum.AUTO);
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(server));
        SecretStore store = keyring(entries, new boolean[]{true});
        SessionSecrets secrets = new SessionSecrets("session-clear", store, Runnable::run, true);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets, OpenAiCompatibleLimits.defaults(),
                                                                          secrets::saveHeaderAsync, Runnable::run, new SavedServerKeys(store, Runnable::run));
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            baseUrlCombo(panel).setSelectedItem(server);
            flushEdt();
            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);
            assertFalse(result.apiKeySet());
            assertNull(entries.get("aicoder.openai_compatible.session-clear"));
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void cancelLeavesTheSessionUntouched() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        SavedServer server = SavedServer.create("Entry", "http://h:3", ServerTypeEnum.AUTO);
        SecretStore store = keyring(entries, new boolean[]{true});
        new SavedServerKeys(store, Runnable::run).saveAsync(server.id(), "new".toCharArray()).join();
        SessionSecrets secrets = new SessionSecrets("cancel", store, Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets, OpenAiCompatibleLimits.defaults(),
                                                                          secrets::saveHeaderAsync, Runnable::run, new SavedServerKeys(store, Runnable::run));
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            baseUrlCombo(panel).setSelectedItem(server);
            flushEdt();
            panel.dispose();
            assertNull(entries.get("aicoder.openai_compatible.cancel"));
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void newSessionStartsOnTheDefaultServer() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        SavedServer server = SavedServer.create("Default", "http://d:1", ServerTypeEnum.AUTO);
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(server));
        OpenAiCompatiblePluginSettings.setDefaultServerId(server.id());
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);
            assertEquals("http://d:1", result.baseUrl());
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void typedCustomUrlIsKept() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            baseUrlCombo(panel).getEditor().setItem("http://custom:9");
            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);
            assertEquals("http://custom:9", result.baseUrl());
        }
        finally {
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void reloadingTheSavedServerListStagesNothing() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setBaseUrl("http://original:9");
        settings.setServerType(ServerTypeEnum.LLAMA_CPP);
        settings.setApiKeySet(true);
        SavedServer saved = SavedServer.create("Saved", "http://saved:1", ServerTypeEnum.AUTO);
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(saved));
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            panel.load(settings);
            panel.loadSavedServers("http://original:9");
            assertEquals("http://original:9", baseUrlCombo(panel).getEditor().getItem());
            assertEquals(ServerTypeEnum.LLAMA_CPP, field(OpenAiCompatibleBasicPanel.class, panel, "serverTypeCombo") instanceof JComboBox<?> combo
                                                   ? combo.getSelectedItem() : null);
            assertTrue(panel.pendingApiKey().isEmpty());
            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);
            assertTrue(result.apiKeySet());
            assertEquals(ServerTypeEnum.LLAMA_CPP, result.serverType());
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void aFailedReadOfALaterPickRestoresTheStateBeforeTheFirstPick() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        SecretStore store = new SecretStore() {
            @Override
            public char[] read(String key) {
                if (key.startsWith("aicoder.openai_compatible_server.")) {
                    throw new IllegalStateException("read failed");
                }
                char[] value = entries.get(key);
                return value == null ? null : value.clone();
            }

            @Override
            public void save(String key, char[] value, String description) {
                entries.put(key, value.clone());
            }

            @Override
            public void delete(String key) {
                entries.remove(key);
            }
        };
        SavedServer first = SavedServer.create("First", "http://first:1", ServerTypeEnum.AUTO);
        SavedServer second = SavedServer.create("Second", "http://second:1", ServerTypeEnum.AUTO);
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(first, second));
        List<Runnable> reads = new ArrayList<>();
        SessionSecrets secrets = new SessionSecrets("pick-fail", store, Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets, OpenAiCompatibleLimits.defaults(),
                                                                          secrets::saveHeaderAsync, Runnable::run, new SavedServerKeys(store, reads::add));
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            baseUrlCombo(panel).setSelectedItem(first);
            baseUrlCombo(panel).setSelectedItem(second);
            List.copyOf(reads).forEach(Runnable::run);
            flushEdt();

            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);
            assertEquals(Boolean.FALSE, result.apiKeySet(), "the failed read restores the state from before the first pick");
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void pickingAnEntryShowsItsUrlNotTheRecord() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        SavedServer server = SavedServer.create("Entry", "http://entry:2", ServerTypeEnum.AUTO);
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(server));
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            baseUrlCombo(panel).setSelectedItem(server);
            assertEquals(server.url(), baseUrlCombo(panel).getEditor().getItem());
            assertFalse(baseUrlCombo(panel).getEditor().getItem().toString().contains("SavedServer["));
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void aKeyReadErrorKeepsTheSessionKey() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        SecretStore store = new SecretStore() {
            @Override
            public char[] read(String key) {
                if (key.startsWith("aicoder.openai_compatible_server.")) {
                    throw new IllegalStateException("read failed");
                }
                char[] value = entries.get(key);
                return value == null ? null : value.clone();
            }

            @Override
            public void save(String key, char[] value, String description) {
                entries.put(key, value.clone());
            }

            @Override
            public void delete(String key) {
                entries.remove(key);
            }
        };
        entries.put("aicoder.openai_compatible.error-session", "old".toCharArray());
        SavedServer server = SavedServer.create("Entry", "http://entry:3", ServerTypeEnum.AUTO);
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(server));
        SessionSecrets secrets = new SessionSecrets("error-session", store, Runnable::run, true);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets, OpenAiCompatibleLimits.defaults(),
                                                                          secrets::saveHeaderAsync, Runnable::run, new SavedServerKeys(store, Runnable::run));
        try {
            OpenAiCompatibleSessionSettings loaded = new OpenAiCompatibleSessionSettings();
            loaded.setApiKeySet(true);
            loaded.setBaseUrl("http://own:1");
            panel.load(loaded);
            baseUrlCombo(panel).setSelectedItem(server);
            flushEdt();
            OpenAiCompatibleSessionSettings result = new OpenAiCompatibleSessionSettings();
            panel.applyTo(result);
            assertTrue(result.apiKeySet());
            assertArrayEquals("old".toCharArray(), entries.get("aicoder.openai_compatible.error-session"));
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void aSlowFirstPickCannotOverrideTheSecond() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Deque<Runnable> tasks = new ArrayDeque<>();
        Map<String, char[]> entries = new HashMap<>();
        SecretStore store = keyring(entries, new boolean[]{true});
        SavedServer first = SavedServer.create("A", "http://a:1", ServerTypeEnum.AUTO);
        SavedServer second = SavedServer.create("B", "http://b:2", ServerTypeEnum.AUTO);
        new SavedServerKeys(store, Runnable::run).saveAsync(first.id(), "key-a".toCharArray()).join();
        new SavedServerKeys(store, Runnable::run).saveAsync(second.id(), "key-b".toCharArray()).join();
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(first, second));
        SavedServerKeys savedKeys = new SavedServerKeys(store, tasks::add);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(null, OpenAiCompatibleLimits.defaults(),
                                                                          null, Runnable::run, savedKeys);
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            baseUrlCombo(panel).setSelectedItem(first);
            baseUrlCombo(panel).setSelectedItem(second);
            tasks.removeLast().run();
            tasks.removeFirst().run();
            flushEdt();
            assertArrayEquals("key-b".toCharArray(), panel.pendingApiKey().orElseThrow());
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @Test
    @Timeout(15)
    void okRightAfterAPickSavesThePickedKey() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        Map<String, char[]> entries = new HashMap<>();
        SecretStore store = keyring(entries, new boolean[]{true});
        SavedServer server = SavedServer.create("Entry", "http://entry:4", ServerTypeEnum.AUTO);
        new SavedServerKeys(store, Runnable::run).saveAsync(server.id(), "picked".toCharArray()).join();
        OpenAiCompatiblePluginSettings.useForTest(MemoryPreferences.create());
        OpenAiCompatiblePluginSettings.setSavedServers(List.of(server));
        SessionSecrets secrets = new SessionSecrets("picked-session", store, Runnable::run, false);
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(secrets, OpenAiCompatibleLimits.defaults(),
                                                                          null, Runnable::run, new SavedServerKeys(store, Runnable::run));
        try {
            panel.load(new OpenAiCompatibleSessionSettings());
            panel.startLoading();
            baseUrlCombo(panel).setSelectedItem(server);
            panel.keyStateReady().join();
            flushEdt();
            panel.applyTo(new OpenAiCompatibleSessionSettings());
            assertTrue(panel.keySaveFuture().join());
            assertArrayEquals("picked".toCharArray(), entries.get("aicoder.openai_compatible.picked-session"));
        }
        finally {
            OpenAiCompatiblePluginSettings.useForTest(null);
            panel.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<Object> baseUrlCombo(OpenAiCompatibleBasicPanel panel) throws ReflectiveOperationException {
        return (JComboBox<Object>) field(OpenAiCompatibleBasicPanel.class, panel, "baseUrlCombo");
    }

    private static ConnectionTester.Result connectedResult(Map<String, List<String>> levelsByModel) {
        return new ConnectionTester.Result(ConnectionTester.Kind.CONNECTED, 2, List.of("splash", "full"),
                                           "http://localhost", ServerKind.LMSTUDIO, levelsByModel);
    }

    private static void applyTestResult(OpenAiCompatibleBasicPanel panel, ConnectionTester.Result result)
            throws ReflectiveOperationException {
        Method method = OpenAiCompatibleBasicPanel.class.getDeclaredMethod("applyTestResult",
                                                                           ConnectionTester.Result.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(panel, result, null);
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> modelCombo(OpenAiCompatibleBasicPanel panel) throws ReflectiveOperationException {
        return (JComboBox<String>) field(OpenAiCompatibleBasicPanel.class, panel, "modelCombo");
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> reasoningCombo(OpenAiCompatibleBasicPanel panel) throws ReflectiveOperationException {
        Object advanced = field(OpenAiCompatibleBasicPanel.class, panel, "advanced");
        return (JComboBox<String>) ((OpenAiCompatibleAdvancedPanel) advanced).reasoningEffortComponent();
    }

    private static Object field(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static List<String> comboItems(JComboBox<String> combo) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            items.add(combo.getItemAt(i));
        }
        return items;
    }

    private static void typeApiKey(OpenAiCompatibleBasicPanel panel, String key) {
        JPasswordField field = findPasswordField(panel);
        assertNotNull(field, "the panel must expose a password field for the API key");
        field.setText(key);
        field.postActionEvent();
    }

    private static JButton findButton(OpenAiCompatibleBasicPanel panel, String text) {
        for (Component child : panel.component().getComponents()) {
            if (child instanceof Container container) {
                JButton found = findButton(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("button not found: " + text);
    }

    private static JButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                JButton found = findButton(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JPasswordField findPasswordField(OpenAiCompatibleBasicPanel panel) {
        return find(panel.component(), JPasswordField.class);
    }

    private static <T> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container nested) {
                T found = find(nested, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
