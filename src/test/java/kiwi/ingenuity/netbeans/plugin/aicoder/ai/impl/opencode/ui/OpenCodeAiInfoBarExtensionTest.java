package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeConfigOptionsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.events.OpenCodeModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui.OpenCodeAiInfoBarExtension.OptionSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui.OpenCodeAiInfoBarExtension.OptionValue;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OpenCodeAiInfoBarExtensionTest {

    // The §13 verbatim shape: model + mode + effort, each type=select, each
    // option carrying a distinct value and a friendly display name.
    private static final String THREE_OPTIONS
            = "["
            + "  {\"id\":\"model\",\"category\":\"model\",\"type\":\"select\","
            + "   \"currentValue\":\"opencode/big-pickle\","
            + "   \"options\":["
            + "     {\"value\":\"opencode/big-pickle\",\"name\":\"OpenCode Zen/Big Pickle\"},"
            + "     {\"value\":\"opencode/deepseek-v4-flash-free\",\"name\":\"DeepSeek V4 Flash (free)\"}]},"
            + "  {\"id\":\"mode\",\"category\":\"mode\",\"type\":\"select\","
            + "   \"currentValue\":\"build\","
            + "   \"options\":["
            + "     {\"value\":\"build\",\"name\":\"build\",\"description\":\"The default agent.\"},"
            + "     {\"value\":\"plan\",\"name\":\"plan\",\"description\":\"Plan mode. Disallows all edit tools.\"}]},"
            + "  {\"id\":\"effort\",\"category\":\"thought_level\",\"type\":\"select\","
            + "   \"currentValue\":\"low\","
            + "   \"options\":[{\"value\":\"low\",\"name\":\"low\"},{\"value\":\"high\",\"name\":\"high\"}]}"
            + "]";

    // Same as above but with the effort entry omitted — models that do not
    // support reasoning effort simply do not return it.
    private static final String TWO_OPTIONS_NO_EFFORT
            = "["
            + "  {\"id\":\"model\",\"category\":\"model\",\"type\":\"select\","
            + "   \"currentValue\":\"opencode/big-pickle\","
            + "   \"options\":[{\"value\":\"opencode/big-pickle\",\"name\":\"OpenCode Zen/Big Pickle\"}]},"
            + "  {\"id\":\"mode\",\"category\":\"mode\",\"type\":\"select\","
            + "   \"currentValue\":\"build\","
            + "   \"options\":[{\"value\":\"build\",\"name\":\"build\"},{\"value\":\"plan\",\"name\":\"plan\"}]}"
            + "]";

    private static JsonArray configArray(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static OptionSpec byId(List<OptionSpec> specs, String id) {
        for (OptionSpec s : specs) {
            if (id.equals(s.id())) {
                return s;
            }
        }
        return null;
    }

    // ---- Helper ----
    private static JsonArray buildConfigOptionsWithEffortOnly() {
        JsonArray arr = new JsonArray();
        JsonObject effortOpt = new JsonObject();
        effortOpt.addProperty("id", "effort");
        effortOpt.addProperty("type", "select");
        effortOpt.addProperty("currentValue", "medium");
        JsonArray opts = new JsonArray();
        for (String e : new String[]{"low", "medium", "high"}) {
            JsonObject o = new JsonObject();
            o.addProperty("value", e);
            o.addProperty("name", e);
            opts.add(o);
        }
        effortOpt.add("options", opts);
        arr.add(effortOpt);
        return arr;
    }

    @AfterEach
    void resetDiscoveredModels() {
        OpenCodePluginSettings.setDiscoveredModels(null);
    }

    // ---- Fix A: fallback combo seeding ----
    @Test
    void fallbackConfigOptionsHasModelAndModeNotEffort() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        JsonArray fallback = OpenCodeAiInfoBarExtension.buildFallbackConfigOptions(settings);
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(fallback);

        assertEquals(2, specs.size(), "fallback must have exactly 2 entries: model and mode (no effort)");
        assertEquals("model", specs.get(0).id(), "first entry must be model");
        assertEquals("mode", specs.get(1).id(), "second entry must be mode");
    }

    @Test
    void fallbackModelComboContainsKnownModelsAndPreselectsSessionModel() {
        OpenCodePluginSettings.setDiscoveredModels(new String[]{"opencode/big-pickle", "opencode/other-model"});
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("opencode/big-pickle");

        JsonArray fallback = OpenCodeAiInfoBarExtension.buildFallbackConfigOptions(settings);
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(fallback);

        OptionSpec modelSpec = specs.stream().filter(s -> "model".equals(s.id())).findFirst().orElseThrow();
        assertEquals("opencode/big-pickle", modelSpec.currentValue(),
                "model currentValue must match session settings");
        assertTrue(modelSpec.displayNames().containsAll(Arrays.asList("opencode/big-pickle", "opencode/other-model")),
                "fallback model combo must include all known models");
    }

    @Test
    void fallbackModeComboPreselectsSessionMode() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("plan");

        JsonArray fallback = OpenCodeAiInfoBarExtension.buildFallbackConfigOptions(settings);
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(fallback);

        OptionSpec modeSpec = specs.stream().filter(s -> "mode".equals(s.id())).findFirst().orElseThrow();
        assertEquals("plan", modeSpec.currentValue(), "mode currentValue must match session settings");
        assertTrue(modeSpec.displayNames().contains("build"), "build must be a mode option");
        assertTrue(modeSpec.displayNames().contains("plan"), "plan must be a mode option");
    }

    // ---- Fix A: combo change with no live session writes to settings ----
    @Test
    void comboChangeWithNoLiveSessionWritesToSettingsAndDoesNotCallSetConfigOption() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");

        List<String[]> setConfigOptionCalls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                setConfigOptionCalls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        List<String> persistedModes = new ArrayList<>();
        AiSessionHost host = new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
            }

            @Override
            public void suppressNextTurn(String s, String c) {
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return null;
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
                if (newSettings instanceof OpenCodeSessionSettings s) {
                    persistedModes.add(s.mode());
                }
            }
        };

        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, settings, host) {
            @Override
            void applyConfigOptions(JsonArray opts) {
                // stub — avoids Swing in tests
            }
        };

        OptionSpec modeSpec = new OptionSpec("mode", "build",
                List.of(new OptionValue("build", "build"), new OptionValue("plan", "plan")));

        ext.handleConfigChange(modeSpec, "plan", null);

        assertTrue(setConfigOptionCalls.isEmpty(),
                "setConfigOption must NOT be called when no live session");
        assertEquals("plan", settings.mode(),
                "settings.setMode must be updated with the new value");
        assertEquals(List.of("plan"), persistedModes,
                "host.updateSessionSettings must be called to persist the change");
    }

    // ---- Fix B: real OpenCodeConfigOptionsEvent replaces seeded values ----
    @Test
    void realConfigOptionsEventReplacesSeedValues() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        });

        List<JsonArray> applied = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, settings, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
                applied.add(opts);
            }
        };

        JsonArray realOptions = buildConfigOptionsWithEffortOnly();
        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(realOptions));

        assertEquals(1, applied.size(), "applyConfigOptions must be called exactly once");
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(applied.get(0));
        assertEquals(1, specs.size(), "real event with only effort must produce 1 spec (not 2 fallback specs)");
        assertEquals("effort", specs.get(0).id(), "authoritative event data must replace fallback");
    }

    // ---- Fix B: model caching ----
    @Test
    void configOptionsEventWithSevenModelsResultsInGetKnownModelsReturningSevenModels() {
        String[] modelValues = {
            "opencode/big-pickle", "opencode/deepseek-v4-flash-free", "opencode/hy3-free",
            "opencode/laguna-s-2.1-free", "opencode/mimo-v2.5-free",
            "opencode/nemotron-3-ultra-free", "opencode/nemotron-3.5-lightning-free"
        };

        JsonArray configOptions = new JsonArray();
        JsonObject modelOpt = new JsonObject();
        modelOpt.addProperty("id", "model");
        modelOpt.addProperty("type", "select");
        modelOpt.addProperty("currentValue", "opencode/big-pickle");
        JsonArray options = new JsonArray();
        for (String m : modelValues) {
            JsonObject o = new JsonObject();
            o.addProperty("value", m);
            o.addProperty("name", m);
            options.add(o);
        }
        modelOpt.add("options", options);
        configOptions.add(modelOpt);

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        });
        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, null, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
                // stub — avoids Swing in tests
            }
        };

        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(configOptions));

        String[] known = OpenCodePluginSettings.getKnownModels();
        assertEquals(7, known.length, "all 7 discovered models must be cached");
        assertTrue(Arrays.asList(known).contains("opencode/big-pickle"), "big-pickle must be present");
        assertTrue(Arrays.asList(known).contains("opencode/deepseek-v4-flash-free"), "deepseek must be present");
    }

    // ---- Parsing tests (headless — no Swing) ----
    @Test
    void parsesModelModeEffortIntoThreeCombosInOrder() {
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(configArray(THREE_OPTIONS));
        assertEquals(3, specs.size(), "model + mode + effort must yield three combos");
        assertEquals("model", specs.get(0).id());
        assertEquals("mode", specs.get(1).id());
        assertEquals("effort", specs.get(2).id());
    }

    @Test
    void arrayWithoutEffortYieldsNoEffortCombo() {
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(configArray(TWO_OPTIONS_NO_EFFORT));
        assertEquals(2, specs.size());
        assertNull(byId(specs, "effort"), "effort combo must be absent when the agent omits it");
        assertNotNull(byId(specs, "model"));
        assertNotNull(byId(specs, "mode"));
    }

    @Test
    void displayNameMapsToUnderlyingValueBothWays() {
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(configArray(THREE_OPTIONS));
        OptionSpec model = byId(specs, "model");
        assertNotNull(model);
        assertTrue(model.displayNames().contains("OpenCode Zen/Big Pickle"),
                "combo must present the friendly display name");
        assertFalse(model.displayNames().contains("opencode/big-pickle"),
                "combo must NOT present the raw underlying value");
        assertEquals("opencode/big-pickle", model.valueForDisplay("OpenCode Zen/Big Pickle"));
        assertEquals("opencode/deepseek-v4-flash-free", model.valueForDisplay("DeepSeek V4 Flash (free)"));
        assertEquals("OpenCode Zen/Big Pickle", model.displayForValue("opencode/big-pickle"));
    }

    @Test
    void unknownDisplayNameFallsBackToItselfForEditableEntries() {
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(configArray(THREE_OPTIONS));
        OptionSpec mode = byId(specs, "mode");
        assertEquals("my-custom-agent", mode.valueForDisplay("my-custom-agent"));
        assertEquals("opencode/unknown", mode.displayForValue("opencode/unknown"));
    }

    @Test
    void optionWithoutNameFallsBackToValueAsDisplay() {
        String json = "[{\"id\":\"model\",\"type\":\"select\",\"currentValue\":\"m1\","
                + "\"options\":[{\"value\":\"m1\"},{\"value\":\"m2\",\"name\":\"Model Two\"}]}]";
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(configArray(json));
        OptionSpec model = byId(specs, "model");
        assertNotNull(model);
        assertTrue(model.displayNames().contains("m1"));
        assertTrue(model.displayNames().contains("Model Two"));
        assertEquals("m1", model.valueForDisplay("m1"));
        assertEquals("m2", model.valueForDisplay("Model Two"));
    }

    @Test
    void nonSelectAndMalformedEntriesAreFilteredOut() {
        String json = "["
                + "  {\"id\":\"model\",\"type\":\"select\",\"currentValue\":\"m1\","
                + "   \"options\":[{\"value\":\"m1\",\"name\":\"M1\"}]},"
                + "  {\"id\":\"note\",\"type\":\"info\",\"currentValue\":\"hi\"},"
                + "  {\"type\":\"select\",\"options\":[]},"
                + "  \"not-an-object\","
                + "  42"
                + "]";
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(configArray(json));
        assertEquals(1, specs.size(), "only the well-formed type=select entry must survive");
        assertEquals("model", specs.get(0).id());
    }

    @Test
    void nullConfigOptionsYieldsEmptyListNotNpe() {
        assertTrue(OpenCodeAiInfoBarExtension.parseConfigOptions(null).isEmpty());
    }

    @Test
    void optionValueAccessorsReturnConstructorValues() {
        OptionValue v = new OptionValue("underlying-val", "Display Name");
        assertEquals("underlying-val", v.value());
        assertEquals("Display Name", v.name());
    }

    // ---- Suppression-guard + setConfigOption tests (real combos, on the EDT) ----
    @Test
    void repopulateDoesNotFireChangeListenerButUserSelectionSendsUnderlyingValue() throws Exception {
        List<String[]> calls = Collections.synchronizedList(new ArrayList<>());
        JsonArray config = configArray(THREE_OPTIONS);

        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public JsonArray configOptions() {
                return config;
            }

            @Override
            public synchronized boolean isSessionLive() {
                // simulate live session so user changes route to setConfigOption
                return true;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(fakeManager, null, null);

        SwingUtilities.invokeAndWait(() -> {
            List<JComponent> comps = ext.createComponents();

            assertTrue(calls.isEmpty(), "initial populate must not fire setConfigOption");

            ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(config));
            assertTrue(calls.isEmpty(), "repopulate must not fire setConfigOption (suppression guard)");

            JPanel comboPanel = (JPanel) comps.get(0);
            @SuppressWarnings("unchecked")
            JComboBox<String> modelCombo = (JComboBox<String>) comboPanel.getComponent(0);
            assertEquals("OpenCode Zen/Big Pickle", modelCombo.getSelectedItem(),
                    "model combo must pre-select the current value's display name");
            modelCombo.setSelectedItem("DeepSeek V4 Flash (free)");
        });

        // Drain the invokeLater(applyConfigOptions) scheduled by the snapshot response.
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals(1, calls.size(), "exactly one user selection must fire setConfigOption");
        assertEquals("model", calls.get(0)[0], "configId must be the option id");
        assertEquals("opencode/deepseek-v4-flash-free", calls.get(0)[1],
                "must send the underlying value, not the display name");
    }

    // ---- Addendum 1: model catalog + property bus + NbPreferences persistence ----
    @Test
    void catalogIsPopulatedFromConfigOptionsEvent() {
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        });
        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, null, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
            }
        };
        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(configArray(TWO_OPTIONS_NO_EFFORT)));
        try {
            List<String> cached = OpenCodeAiImplementation.modelCatalog().getCachedModels();
            assertTrue(cached.contains("opencode/big-pickle"),
                    "modelCatalog must contain the model discovered from configOptions");
        }
        finally {
            OpenCodePluginSettings.setDiscoveredModels(null);
        }
    }

    @Test
    void propertyBusReceivesOpenCodeModelsEventFromConfigOptionsEvent() throws InterruptedException {
        List<AiPropertyEvent> busEvents = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        AiPropertyListener busListener = event -> {
            if (event instanceof OpenCodeModelsEvent) {
                busEvents.add(event);
                latch.countDown();
            }
        };
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.OPENCODE, busListener);
        try {
            OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
            });
            OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, null, null) {
                @Override
                void applyConfigOptions(JsonArray opts) {
                }
            };
            ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(configArray(TWO_OPTIONS_NO_EFFORT)));
            assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "AiTypePropertyBus must receive OpenCodeModelsEvent within 5 s");
            OpenCodeModelsEvent me = (OpenCodeModelsEvent) busEvents.get(0);
            assertEquals(List.of("opencode/big-pickle"), me.models());
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.OPENCODE, busListener);
            OpenCodePluginSettings.setDiscoveredModels(null);
        }
    }

    @Test
    void discoveredModelsSurviveInMemoryReset() {
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        });
        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, null, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
            }
        };
        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(configArray(TWO_OPTIONS_NO_EFFORT)));

        // Simulate what happens on a fresh IDE run: in-memory field is null
        OpenCodePluginSettings.setDiscoveredModels(null);

        // getKnownModels() must return from NbPreferences fallback
        String[] known = OpenCodePluginSettings.getKnownModels();
        assertEquals(1, known.length, "discovered models must survive in-memory reset via NbPreferences");
        assertEquals("opencode/big-pickle", known[0]);
    }

    @Test
    void onPropertyEventWithOpenCodeModelsEventRebuildsFallbackComboWhenNoLiveSession() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }
        };

        List<JsonArray> applied = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, settings, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
                applied.add(opts);
            }
        };

        OpenCodePluginSettings.setDiscoveredModels(new String[]{"alpha", "beta", "gamma"});
        ext.onPropertyEvent(new OpenCodeModelsEvent(List.of("alpha", "beta", "gamma")));
        SwingUtilities.invokeAndWait(() -> {
        });

        assertFalse(applied.isEmpty(), "onPropertyEvent must trigger applyConfigOptions rebuild");
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(applied.get(applied.size() - 1));
        OptionSpec modelSpec = specs.stream().filter(s -> "model".equals(s.id())).findFirst().orElseThrow();
        assertTrue(modelSpec.displayNames().containsAll(List.of("alpha", "beta", "gamma")),
                "rebuilt fallback must include all three newly-discovered models");
    }

    // ---- Regression: OpenCodeModelsEvent for an idle session must not reset the model ----
    @Test
    void onPropertyEventPreservesCurrentlyDisplayedModelInsteadOfResettingToGlobalDefault() throws Exception {
        // Reproduces the diagnosed root cause directly: settings.model() is never
        // populated for a session whose model was only ever known via a live ACP
        // connection (OpenCodeAiProcessManager.applyInitialModeIfNeeded has a
        // counterpart for mode and effort but none for model), so
        // buildFallbackConfigOptions falls back to the global default here.
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }
        };

        List<JsonArray> applied = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, settings, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
                applied.add(opts);
            }
        };

        // The session is currently displaying a real, discovered model that was
        // never written back into settings.model() — the idle-session state the
        // bug report describes.
        JsonObject modelOpt = new JsonObject();
        modelOpt.addProperty("id", "model");
        modelOpt.addProperty("type", "select");
        modelOpt.addProperty("currentValue", "opencode/deepseek-v4-flash-free");
        JsonArray modelOpts = new JsonArray();
        for (String v : new String[]{"opencode/big-pickle", "opencode/deepseek-v4-flash-free"}) {
            JsonObject o = new JsonObject();
            o.addProperty("value", v);
            o.addProperty("name", v);
            modelOpts.add(o);
        }
        modelOpt.add("options", modelOpts);
        JsonArray displayedBeforeEvent = new JsonArray();
        displayedBeforeEvent.add(modelOpt);
        ext.recordAndApply(displayedBeforeEvent);
        applied.clear();

        // An unrelated session's discovery broadcasts a fresh model list — the
        // event is keyed by AiType, not by this session (class javadoc).
        OpenCodePluginSettings.setDiscoveredModels(
                new String[]{"opencode/hy3-free", "opencode/nemotron-3-ultra-free"});
        ext.onPropertyEvent(new OpenCodeModelsEvent(
                List.of("opencode/hy3-free", "opencode/nemotron-3-ultra-free")));
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals(1, applied.size(), "the broadcast must trigger exactly one rebuild");
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(applied.get(0));
        OptionSpec modelSpec = specs.stream().filter(s -> "model".equals(s.id())).findFirst().orElseThrow();
        assertEquals("opencode/deepseek-v4-flash-free", modelSpec.currentValue(),
                "the model actually displayed before the broadcast must survive it — it must "
                + "NOT reset to the global default (opencode/big-pickle)");
        assertTrue(modelSpec.displayNames().containsAll(
                List.of("opencode/hy3-free", "opencode/nemotron-3-ultra-free")),
                "the option list itself must still refresh to the newly-discovered models");
    }

    // ---- Full end-to-end reproduction: does a REAL handshake broadcast from one
    // session reset a different, idle session's model? Goes through the actual
    // AiTypePropertyBus + cacheDiscoveredModels() production path (not a
    // hand-simulated onPropertyEvent call), mirroring exactly how AiTopComponent
    // wires handleAiTypeProperty -> infoBarExtension.onPropertyEvent. ----
    @Test
    void unrelatedSessionsHandshakeBroadcastDoesNotResetOtherIdleSessionsModel() throws Exception {
        // ---- "_2": idle, never started, showing its own distinct model ----
        OpenCodeSessionSettings settings2 = new OpenCodeSessionSettings();
        settings2.setModel("opencode/nemotron-3-ultra-free");
        OpenCodeAiProcessManager manager2 = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }
        };
        List<JsonArray> applied2 = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext2 = new OpenCodeAiInfoBarExtension(manager2, settings2, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
                applied2.add(opts);
            }
        };
        ext2.createComponents();

        OptionSpec initialModelSpec = OpenCodeAiInfoBarExtension.parseConfigOptions(applied2.get(0))
                .stream().filter(s -> "model".equals(s.id())).findFirst().orElseThrow();
        assertEquals("opencode/nemotron-3-ultra-free", initialModelSpec.currentValue(),
                "sanity check: _2 must start out showing its own model, unaffected");

        // ---- "_1": gets messaged; its handshake reports the agent's pre-correction
        // model, exactly like the live bug (the agent always starts on big-pickle) ----
        OpenCodeAiProcessManager manager1 = new OpenCodeAiProcessManager(e -> {
        });
        OpenCodeAiInfoBarExtension ext1 = new OpenCodeAiInfoBarExtension(manager1, null, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
                // stub — this test is about _2, not _1's own combo
            }
        };

        // Wire _2 to the type-wide bus exactly as AiTopComponent.handleAiTypeProperty
        // wires a real info bar extension.
        AiPropertyListener forwardTo2 = ext2::onPropertyEvent;
        CountDownLatch busDelivered = new CountDownLatch(1);
        AiPropertyListener latchListener = event -> busDelivered.countDown();
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.OPENCODE, forwardTo2);
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.OPENCODE, latchListener);
        try {
            JsonObject modelOpt = new JsonObject();
            modelOpt.addProperty("id", "model");
            modelOpt.addProperty("type", "select");
            modelOpt.addProperty("currentValue", "opencode/big-pickle");
            JsonArray options = new JsonArray();
            for (String v : new String[]{"opencode/big-pickle", "opencode/nemotron-3-ultra-free", "opencode/other"}) {
                JsonObject o = new JsonObject();
                o.addProperty("value", v);
                o.addProperty("name", v);
                options.add(o);
            }
            modelOpt.add("options", options);
            JsonArray agentConfigOptions = new JsonArray();
            agentConfigOptions.add(modelOpt);

            // Real production call: fires cacheDiscoveredModels() -> AiTypePropertyBus.fire()
            // for real, exactly like OpenCodeAiProcessManager.spawnAndHandshake() does.
            ext1.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(agentConfigOptions));

            assertTrue(busDelivered.await(5, TimeUnit.SECONDS),
                    "the type-wide OpenCodeModelsEvent broadcast must actually fire");
            SwingUtilities.invokeAndWait(() -> {
            });
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.OPENCODE, forwardTo2);
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.OPENCODE, latchListener);
            OpenCodePluginSettings.setDiscoveredModels(null);
        }

        OptionSpec finalModelSpec = OpenCodeAiInfoBarExtension.parseConfigOptions(applied2.get(applied2.size() - 1))
                .stream().filter(s -> "model".equals(s.id())).findFirst().orElseThrow();
        assertEquals("opencode/nemotron-3-ultra-free", finalModelSpec.currentValue(),
                "_2's own model must survive _1's unrelated handshake broadcast, going through the "
                + "REAL AiTypePropertyBus/cacheDiscoveredModels path, not a simulated event");
    }

    // ---- Namespace mismatch: does the REAL combo (buildCombo/displayForValue,
    // not just the configOptions JSON) still show the user's persisted model
    // when a broadcast's discovered option "value" strings use a different
    // literal form than settings.model()? This is the gap the previous
    // reproduction test missed by stubbing out applyConfigOptions(). ----
    @Test
    void namespaceMismatchBetweenPersistedModelAndDiscoveredOptionsLeavesComboShowingWrongModel() throws Exception {
        // ---- "_2": idle, showing its own persisted model under the namespaced
        // form used when the session was created (e.g. "opencode/<id>"). ----
        OpenCodeSessionSettings settings2 = new OpenCodeSessionSettings();
        settings2.setModel("opencode/nemotron-3-ultra-free");
        OpenCodeAiProcessManager manager2 = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }
        };
        OpenCodeAiInfoBarExtension ext2 = new OpenCodeAiInfoBarExtension(manager2, settings2, null);

        SwingUtilities.invokeAndWait(() -> {
            List<JComponent> comps = ext2.createComponents();
            JPanel comboPanel = (JPanel) comps.get(0);
            @SuppressWarnings("unchecked")
            JComboBox<String> modelCombo = (JComboBox<String>) comboPanel.getComponent(0);
            assertEquals("opencode/nemotron-3-ultra-free", modelCombo.getSelectedItem(),
                    "sanity check: _2 must start out correctly selected");
        });

        // ---- "_1": a real handshake discovers models WITHOUT the "opencode/"
        // prefix — mirroring OpenCode's own log, which reports bare
        // "modelID=big-pickle", never "modelID=opencode/big-pickle". ----
        OpenCodeAiProcessManager manager1 = new OpenCodeAiProcessManager(e -> {
        });
        OpenCodeAiInfoBarExtension ext1 = new OpenCodeAiInfoBarExtension(manager1, null, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
                // stub — this test is about _2, not _1's own combo
            }
        };

        AiPropertyListener forwardTo2 = ext2::onPropertyEvent;
        CountDownLatch busDelivered = new CountDownLatch(1);
        AiPropertyListener latchListener = event -> busDelivered.countDown();
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.OPENCODE, forwardTo2);
        AiTypePropertyBus.getInstance().addListener(AiTypeEnum.OPENCODE, latchListener);
        try {
            JsonObject modelOpt = new JsonObject();
            modelOpt.addProperty("id", "model");
            modelOpt.addProperty("type", "select");
            modelOpt.addProperty("currentValue", "big-pickle");
            JsonArray options = new JsonArray();
            for (String v : new String[]{"big-pickle", "nemotron-3-ultra-free", "other"}) {
                JsonObject o = new JsonObject();
                o.addProperty("value", v);
                o.addProperty("name", v);
                options.add(o);
            }
            modelOpt.add("options", options);
            JsonArray agentConfigOptions = new JsonArray();
            agentConfigOptions.add(modelOpt);

            ext1.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(agentConfigOptions));

            assertTrue(busDelivered.await(5, TimeUnit.SECONDS),
                    "the type-wide OpenCodeModelsEvent broadcast must actually fire");
            SwingUtilities.invokeAndWait(() -> {
            });
        }
        finally {
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.OPENCODE, forwardTo2);
            AiTypePropertyBus.getInstance().removeListener(AiTypeEnum.OPENCODE, latchListener);
            OpenCodePluginSettings.setDiscoveredModels(null);
        }

        SwingUtilities.invokeAndWait(() -> {
            @SuppressWarnings("unchecked")
            JComboBox<String> modelCombo = (JComboBox<String>) ext2.comboPanel.getComponent(0);
            assertEquals("opencode/nemotron-3-ultra-free", modelCombo.getSelectedItem(),
                    "_2's combo must still visibly show the model that will actually run, even "
                    + "though the freshly-discovered option list uses a different value namespace "
                    + "than the persisted settings — it must never silently fall back to whatever "
                    + "the combo defaults to (e.g. the first discovered entry)");
        });
    }

    // ---- Does an idle-session dropdown change survive a later broadcast? Chris
    // confirmed the four sessions all started on big-pickle and were changed
    // to distinct models via the info bar dropdown itself, while idle — not
    // via the create dialog. handleConfigChange()'s idle branch calls
    // applyToSettings() but never recordAndApply(), so lastKnownConfigOptions
    // is never refreshed after the pick. If a later OpenCodeModelsEvent
    // broadcast arrives, preserveSelections() "preserves" that stale
    // lastKnownConfigOptions currentValue over the freshly-derived one from
    // settings — silently reverting the user's pick. This test needs no
    // cross-session broadcast and no object-identity divergence to reproduce:
    // it is self-contained to one idle session picking its own model. ----
    @Test
    void idleSessionDropdownModelChangeSurvivesALaterModelsBroadcast() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setModel("opencode/big-pickle");
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return false;
            }
        };
        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, settings, null);

        SwingUtilities.invokeAndWait(ext::createComponents);

        // ---- User picks a different model from the idle combo. ----
        OptionSpec modelSpec = OpenCodeAiInfoBarExtension.parseConfigOptions(
                OpenCodeAiInfoBarExtension.buildFallbackConfigOptions(settings))
                .stream().filter(s -> "model".equals(s.id())).findFirst().orElseThrow();
        SwingUtilities.invokeAndWait(()
                -> ext.handleConfigChange(modelSpec, "opencode/nemotron-3-ultra-free", null));

        assertEquals("opencode/nemotron-3-ultra-free", settings.model(),
                "sanity check: the idle-session pick must write straight through to settings");

        // ---- Some other session's discovery later broadcasts a fresh model
        // list — must not undo the pick just made on this idle session. ----
        OpenCodePluginSettings.setDiscoveredModels(
                new String[]{"opencode/big-pickle", "opencode/nemotron-3-ultra-free"});
        try {
            SwingUtilities.invokeAndWait(() -> ext.onPropertyEvent(new OpenCodeModelsEvent(
                    List.of("opencode/big-pickle", "opencode/nemotron-3-ultra-free"))));
            SwingUtilities.invokeAndWait(() -> {
            });
        }
        finally {
            OpenCodePluginSettings.setDiscoveredModels(null);
        }

        SwingUtilities.invokeAndWait(() -> {
            @SuppressWarnings("unchecked")
            JComboBox<String> modelCombo = (JComboBox<String>) ext.comboPanel.getComponent(0);
            assertEquals("opencode/nemotron-3-ultra-free", modelCombo.getSelectedItem(),
                    "the model picked on this idle session must still be shown after a later "
                    + "OpenCodeModelsEvent broadcast — it must not revert to whatever was "
                    + "displayed before the pick (e.g. the global default)");
        });
    }

    // ---- preserveSelections() unit tests (pure helper, no Swing) ----
    @Test
    void preserveSelectionsKeepsDisplayedCurrentValueButUsesRefreshedOptionList() {
        JsonArray displayed = configArray(
                "[{\"id\":\"model\",\"type\":\"select\",\"currentValue\":\"old-selected\","
                + "\"options\":[{\"value\":\"old-selected\",\"name\":\"old-selected\"}]}]");
        JsonArray refreshed = configArray(
                "[{\"id\":\"model\",\"type\":\"select\",\"currentValue\":\"global-default\","
                + "\"options\":[{\"value\":\"new-a\",\"name\":\"new-a\"},{\"value\":\"new-b\",\"name\":\"new-b\"}]}]");

        JsonArray merged = OpenCodeAiInfoBarExtension.preserveSelections(displayed, refreshed);

        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(merged);
        OptionSpec model = byId(specs, "model");
        assertEquals("old-selected", model.currentValue(),
                "currentValue must come from what was already displayed, not the refreshed default");
        assertTrue(model.displayNames().containsAll(List.of("new-a", "new-b")),
                "the option list must come from refreshed, not displayed");
    }

    @Test
    void preserveSelectionsLeavesEntryUntouchedWhenIdIsNotInDisplayed() {
        JsonArray displayed = configArray(
                "[{\"id\":\"mode\",\"type\":\"select\",\"currentValue\":\"build\",\"options\":[]}]");
        JsonArray refreshed = configArray(
                "[{\"id\":\"model\",\"type\":\"select\",\"currentValue\":\"global-default\","
                + "\"options\":[{\"value\":\"global-default\",\"name\":\"global-default\"}]}]");

        JsonArray merged = OpenCodeAiInfoBarExtension.preserveSelections(displayed, refreshed);

        OptionSpec model = byId(OpenCodeAiInfoBarExtension.parseConfigOptions(merged), "model");
        assertEquals("global-default", model.currentValue(),
                "with no prior selection for this id, the refreshed value must stand");
    }

    @Test
    void preserveSelectionsHandlesNullDisplayedOrRefreshed() {
        JsonArray refreshed = configArray("[{\"id\":\"model\",\"type\":\"select\",\"options\":[]}]");
        assertSame(refreshed, OpenCodeAiInfoBarExtension.preserveSelections(null, refreshed),
                "null displayed (nothing shown yet) must fall through to refreshed as-is");

        JsonArray displayed = configArray("[{\"id\":\"model\",\"type\":\"select\",\"options\":[]}]");
        assertSame(displayed, OpenCodeAiInfoBarExtension.preserveSelections(displayed, null),
                "null refreshed must fall back to whatever was displayed");
    }

    // ---- Addendum 3: mode persistence from live-session combo changes ----
    @Test
    void handleConfigChangeLivePath_writesAppliedModeToSettings() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");

        JsonObject modeInSnapshot = new JsonObject();
        modeInSnapshot.addProperty("id", "mode");
        modeInSnapshot.addProperty("currentValue", "plan");
        JsonArray snapshot = new JsonArray();
        snapshot.add(modeInSnapshot);

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return true;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                return CompletableFuture.completedFuture(snapshot);
            }
        };

        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, settings, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
            }
        };

        OptionSpec modeSpec = new OptionSpec("mode", "build",
                List.of(new OptionValue("build", "build"), new OptionValue("plan", "plan")));
        ext.handleConfigChange(modeSpec, "plan", null);

        // applyToSettings is called from thenAccept — give the future time to settle
        Thread.sleep(50);

        assertEquals("plan", settings.mode(),
                "settings.mode must be updated with the applied value from the snapshot");
    }

    @Test
    void handleConfigChangeLivePath_doesNotChangeGlobalPluginSettingsMode() throws Exception {
        String globalBefore = OpenCodePluginSettings.getMode();

        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");

        JsonObject modeInSnapshot = new JsonObject();
        modeInSnapshot.addProperty("id", "mode");
        modeInSnapshot.addProperty("currentValue", "plan");
        JsonArray snapshot = new JsonArray();
        snapshot.add(modeInSnapshot);

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return true;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                return CompletableFuture.completedFuture(snapshot);
            }
        };

        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, settings, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
            }
        };

        OptionSpec modeSpec = new OptionSpec("mode", "build",
                List.of(new OptionValue("build", "build"), new OptionValue("plan", "plan")));
        ext.handleConfigChange(modeSpec, "plan", null);
        Thread.sleep(50);

        assertEquals(globalBefore, OpenCodePluginSettings.getMode(),
                "global plugin settings mode must NOT be changed by a session-scoped combo change");
    }

    @Test
    void handleConfigChange_equalityGuard_skipsSetConfigOptionWhenValueMatchesCurrent() {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return true;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, null, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
            }
        };

        // currentValue == value: equality guard must prevent the call
        OptionSpec modeSpec = new OptionSpec("mode", "build",
                List.of(new OptionValue("build", "build"), new OptionValue("plan", "plan")));
        ext.handleConfigChange(modeSpec, "build", null);

        assertTrue(calls.isEmpty(), "setConfigOption must NOT be called when value equals currentValue");
    }

    @Test
    void handleConfigChangeLivePath_writesAppliedValueFromSnapshot_notRequestedValue() throws Exception {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        settings.setMode("build");

        // Simulate agent rejecting "invalid" and keeping "build" as applied value
        JsonObject modeInSnapshot = new JsonObject();
        modeInSnapshot.addProperty("id", "mode");
        modeInSnapshot.addProperty("currentValue", "build");
        JsonArray snapshot = new JsonArray();
        snapshot.add(modeInSnapshot);

        OpenCodeAiProcessManager manager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public synchronized boolean isSessionLive() {
                return true;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                return CompletableFuture.completedFuture(snapshot);
            }
        };

        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(manager, settings, null) {
            @Override
            void applyConfigOptions(JsonArray opts) {
            }
        };

        OptionSpec modeSpec = new OptionSpec("mode", "build",
                List.of(new OptionValue("build", "build"), new OptionValue("invalid", "invalid")));
        ext.handleConfigChange(modeSpec, "invalid", null);
        Thread.sleep(50);

        assertEquals("build", settings.mode(),
                "settings must record the agent-applied value from the snapshot, not the requested value");
    }

    // ---- Addendum 4: effort in info bar ----
    @Test
    void applyToSettings_effortCase_writesToSessionSettings() {
        OpenCodeSessionSettings settings = new OpenCodeSessionSettings();
        List<String> persisted = new ArrayList<>();
        AiSessionHost host = new AiSessionHost() {
            @Override
            public File resolveWorkDir() {
                return null;
            }

            @Override
            public void suppressNextTurn(String s, String c) {
            }

            @Override
            public AiSessionSettings getSessionSettings() {
                return null;
            }

            @Override
            public void updateSessionSettings(AiSessionSettings newSettings) {
                if (newSettings instanceof OpenCodeSessionSettings s) {
                    persisted.add(s.effort());
                }
            }
        };

        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(
                new OpenCodeAiProcessManager(e -> {
                }), settings, host) {
            @Override
            void applyConfigOptions(JsonArray opts) {
            }
        };

        ext.applyToSettings("effort", "high");

        assertEquals("high", settings.effort(),
                "applyToSettings(effort) must update session settings");
        assertEquals(List.of("high"), persisted,
                "applyToSettings(effort) must persist via host.updateSessionSettings");
    }

}
