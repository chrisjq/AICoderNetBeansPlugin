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
