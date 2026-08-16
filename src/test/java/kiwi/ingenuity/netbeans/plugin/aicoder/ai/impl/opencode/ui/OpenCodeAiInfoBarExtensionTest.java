package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeConfigOptionsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui.OpenCodeAiInfoBarExtension.OptionSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui.OpenCodeAiInfoBarExtension.OptionValue;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpenCodeAiInfoBarExtension}. The pure {@code configOptions}
 * parsing is exercised headlessly through the static
 * {@link OpenCodeAiInfoBarExtension#parseConfigOptions(JsonArray)} entry point.
 * The suppression guard and display-name → value mapping on selection are
 * exercised through real combo boxes driven on the EDT (headless-safe: no
 * window is realised, only Swing model events).
 */
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
        // Assert the effort combo is ABSENT, not merely hidden.
        assertNull(byId(specs, "effort"), "effort combo must be absent when the agent omits it");
        assertNotNull(byId(specs, "model"));
        assertNotNull(byId(specs, "mode"));
    }

    @Test
    void displayNameMapsToUnderlyingValueBothWays() {
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(configArray(THREE_OPTIONS));
        OptionSpec model = byId(specs, "model");
        assertNotNull(model);
        // The combo shows friendly names ...
        assertTrue(model.displayNames().contains("OpenCode Zen/Big Pickle"),
                "combo must present the friendly display name");
        assertFalse(model.displayNames().contains("opencode/big-pickle"),
                "combo must NOT present the raw underlying value");
        // ... but selecting a name resolves to the underlying value that is sent.
        assertEquals("opencode/big-pickle", model.valueForDisplay("OpenCode Zen/Big Pickle"));
        assertEquals("opencode/deepseek-v4-flash-free", model.valueForDisplay("DeepSeek V4 Flash (free)"));
        // ... and the current value resolves back to its display name for pre-selection.
        assertEquals("OpenCode Zen/Big Pickle", model.displayForValue("opencode/big-pickle"));
    }

    @Test
    void unknownDisplayNameFallsBackToItselfForEditableEntries() {
        // Custom/typed agent names have no matching option; the raw text must be
        // sent through unchanged rather than dropped.
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
        // Missing name → display name equals the value.
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
                + "  {\"id\":\"note\",\"type\":\"info\",\"currentValue\":\"hi\"}," // wrong type
                + "  {\"type\":\"select\",\"options\":[]}," // missing id
                + "  \"not-an-object\"," // not a JSON object
                + "  42" // primitive
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

        // A test double for the process manager: records setConfigOption calls
        // and returns an already-complete snapshot so the round-trip resolves
        // synchronously. No process is spawned.
        OpenCodeAiProcessManager fakeManager = new OpenCodeAiProcessManager(e -> {
        }) {
            @Override
            public JsonArray configOptions() {
                return config;
            }

            @Override
            public CompletableFuture<JsonArray> setConfigOption(String configId, String value) {
                calls.add(new String[]{configId, value});
                return CompletableFuture.completedFuture(new JsonArray());
            }
        };

        OpenCodeAiInfoBarExtension ext = new OpenCodeAiInfoBarExtension(fakeManager);

        SwingUtilities.invokeAndWait(() -> {
            List<JComponent> comps = ext.createComponents();

            // (1) Initial population from configOptions must not be mistaken for a
            //     user change — the suppression guard keeps setConfigOption silent.
            assertTrue(calls.isEmpty(), "initial populate must not fire setConfigOption");

            // (2) Repopulating from a config snapshot event must likewise stay silent.
            ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(config));
            assertTrue(calls.isEmpty(), "repopulate must not fire setConfigOption (suppression guard)");

            // (3) A genuine user selection DOES fire, sending the underlying value
            //     for the chosen display name — not the display name itself.
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
}
