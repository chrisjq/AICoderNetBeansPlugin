package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettingsKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OllamaImportTest {

    private Preferences preferences;

    private Preferences preferences() {
        if (preferences == null) {
            preferences = Preferences.userRoot().node("openai-compatible-import-" + UUID.randomUUID());
            OpenAiCompatiblePluginSettings.useForTest(preferences);
        }
        return preferences;
    }

    @AfterEach
    void cleanup() throws Exception {
        OpenAiCompatiblePluginSettings.useForTest(null);
        if (preferences != null) {
            preferences.removeNode();
        }
    }

    @Test
    @Timeout(5)
    void nothingStoredMarksImportWithoutChangingDefaults() {
        preferences();
        assertFalse(OpenAiCompatiblePluginSettings.importFromOllamaOnce());
        assertEquals(ServerTypeEnum.AUTO, OpenAiCompatiblePluginSettings.getServerType());
        assertTrue(preferences().getBoolean(OpenAiCompatiblePluginSettingsKeyEnum.IMPORTED_FROM_OLLAMA.key(), false));
    }

    @Test
    @Timeout(5)
    void urlImportNormalisesAndLeavesTypeAuto() {
        preferences().put(OllamaPluginSettingsKeyEnum.BASE_URL.key(), "http://localhost:11434/v1/");
        assertTrue(OpenAiCompatiblePluginSettings.importFromOllamaOnce());
        assertEquals("http://localhost:11434", OpenAiCompatiblePluginSettings.getBaseUrl());
        assertEquals(ServerTypeEnum.AUTO, OpenAiCompatiblePluginSettings.getServerType());
    }

    @Test
    @Timeout(5)
    void modelOnlyLeavesTypeAuto() {
        preferences().put(OllamaPluginSettingsKeyEnum.MODEL.key(), "qwen");
        assertTrue(OpenAiCompatiblePluginSettings.importFromOllamaOnce());
        assertEquals("qwen", OpenAiCompatiblePluginSettings.getModel());
        assertEquals(ServerTypeEnum.AUTO, OpenAiCompatiblePluginSettings.getServerType());
    }

    @Test
    @Timeout(5)
    void secondCallDoesNothing() {
        preferences().put(OllamaPluginSettingsKeyEnum.MODEL.key(), "first");
        assertTrue(OpenAiCompatiblePluginSettings.importFromOllamaOnce());
        preferences().put(OllamaPluginSettingsKeyEnum.MODEL.key(), "second");
        assertFalse(OpenAiCompatiblePluginSettings.importFromOllamaOnce());
        assertEquals("first", OpenAiCompatiblePluginSettings.getModel());
    }

    @Test
    @Timeout(5)
    void concurrentCallsImportOnlyOnce() throws Exception {
        preferences().put(OllamaPluginSettingsKeyEnum.MODEL.key(), "qwen");
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> results = new ArrayList<>();
        Thread first = new Thread(() -> runImport(start, results));
        Thread second = new Thread(() -> runImport(start, results));
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    }

    private static void runImport(CountDownLatch start, List<Boolean> results) {
        try {
            start.await();
            synchronized (results) {
                results.add(OpenAiCompatiblePluginSettings.importFromOllamaOnce());
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
