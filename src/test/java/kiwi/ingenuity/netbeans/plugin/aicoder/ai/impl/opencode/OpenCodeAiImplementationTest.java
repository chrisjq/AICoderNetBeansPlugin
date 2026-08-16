package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

// Addendum 2 + Correction: setModel must be session-scoped, not global.
class OpenCodeAiImplementationTest {

    private static JsonArray configOptionsWithModel(String modelValue) {
        JsonObject opt = new JsonObject();
        opt.addProperty("id", "model");
        opt.addProperty("type", "select");
        opt.addProperty("currentValue", modelValue);
        JsonArray cfgOpts = new JsonArray();
        cfgOpts.add(opt);
        return cfgOpts;
    }

    @Test
    void setModel_updatesSessionSettings() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-1", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }

            @Override
            public JsonArray configOptions() {
                return null;
            }
        };

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        impl.setModel("opencode/other-model");

        assertEquals("opencode/other-model", settings.model(),
                "setModel must update the session settings");
    }

    @Test
    void setModel_doesNotChangeOpenCodePluginSettingsGlobalDefault() {
        String globalBefore = OpenCodePluginSettings.getModel();

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-2", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }

            @Override
            public JsonArray configOptions() {
                return null;
            }
        };

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        impl.setModel("opencode/new-model");

        assertEquals(globalBefore, OpenCodePluginSettings.getModel(),
                "setModel must NOT write the global plugin default");
    }

    @Test
    void setModel_withLiveSession_differentFromCurrent_callsSetConfigOption() {
        List<String[]> calls = new ArrayList<>();

        JsonArray cfgOpts = configOptionsWithModel("opencode/big-pickle");

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return true;
            }

            @Override
            public JsonArray configOptions() {
                return cfgOpts;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-3", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        impl.setModel("opencode/other-model");

        assertEquals(1, calls.size(), "setConfigOption must be called when new model differs from current");
        assertEquals("model", calls.get(0)[0]);
        assertEquals("opencode/other-model", calls.get(0)[1]);
    }

    @Test
    void setModel_withLiveSession_sameAsCurrent_skipsSetConfigOption() {
        List<String[]> calls = new ArrayList<>();

        JsonArray cfgOpts = configOptionsWithModel("opencode/big-pickle");

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return true;
            }

            @Override
            public JsonArray configOptions() {
                return cfgOpts;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-4", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        impl.setModel("opencode/big-pickle");  // same as current

        assertTrue(calls.isEmpty(), "setConfigOption must NOT be called when model is same as current");
    }

    @Test
    void setModel_withNoLiveSession_updatesSettingsAndDoesNotThrow() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        AiSession session = new AiSession("s-setmodel-5", "Test", null,
                AiTypeEnum.OPENCODE, null, settings, Instant.now(), Instant.now());

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }

            @Override
            public JsonArray configOptions() {
                return null;
            }
        };

        OpenCodeAiImplementation impl = new OpenCodeAiImplementation(e -> {
        }, null) {
            {
                currentSession = session;
            }

            @Override
            protected OpenCodeAiProcessManager delegate() {
                return fakeManager;
            }
        };

        assertDoesNotThrow(() -> impl.setModel("opencode/any-model"),
                "setModel with no live session must not throw");
        assertEquals("opencode/any-model", settings.model(),
                "settings must still be updated even with no live session");
    }
}
