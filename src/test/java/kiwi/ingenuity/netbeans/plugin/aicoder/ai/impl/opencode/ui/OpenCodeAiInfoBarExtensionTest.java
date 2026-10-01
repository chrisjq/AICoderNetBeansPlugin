package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeConfigOptionsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui.OpenCodeAiInfoBarExtension.OptionSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui.OpenCodeAiInfoBarExtension.OptionValue;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OpenCodeAiInfoBarExtensionTest {

    private static final String OPTIONS = "["
                                          + "{\"id\":\"model\",\"type\":\"select\",\"currentValue\":\"m1\","
                                          + "\"options\":[{\"value\":\"m1\",\"name\":\"Model One\"},{\"value\":\"m2\",\"name\":\"Model Two\"}]},"
                                          + "{\"id\":\"mode\",\"type\":\"select\",\"currentValue\":\"build\","
                                          + "\"options\":[{\"value\":\"build\",\"name\":\"build\"},{\"value\":\"plan\",\"name\":\"plan\"}]},"
                                          + "{\"id\":\"effort\",\"type\":\"select\",\"currentValue\":\"low\","
                                          + "\"options\":[{\"value\":\"low\",\"name\":\"low\"},{\"value\":\"high\",\"name\":\"high\"}]}]";

    private static JsonArray options() {
        return JsonParser.parseString(OPTIONS).getAsJsonArray();
    }

    private static OptionSpec byId(List<OptionSpec> specs, String id) {
        return specs.stream().filter(s -> id.equals(s.id())).findFirst().orElseThrow();
    }

    private static OpenCodeAiInfoBarExtension bar(JsonArray initial,
                                                  OpenCodeSessionSettings settings, List<String[]> calls, AtomicReference<String> compact) {
        return new OpenCodeAiInfoBarExtension(initial, settings,
                (id, value) -> calls.add(new String[]{id, value}),
                () -> compact.set("compact"));
    }

    private static JComboBox<?> combo(JPanel panel, int index) {
        return (JComboBox<?>) panel.getComponent(index);
    }

    private static JsonArray snapshot(OpenCodeAiInfoBarExtension ext) {
        try {
            var field = OpenCodeAiInfoBarExtension.class.getDeclaredField("lastKnownConfigOptions");
            field.setAccessible(true);
            return (JsonArray) field.get(ext);
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void fallbackConfigOptionsHasModelAndModeNotEffort() {
        List<OptionSpec> specs = OpenCodeAiInfoBarExtension.parseConfigOptions(
                OpenCodeAiInfoBarExtension.buildFallbackConfigOptions(new OpenCodeSessionSettings()));
        assertEquals(List.of("model", "mode"), specs.stream().map(OptionSpec::id).toList());
    }

    @Test
    void fallbackModelComboPreselectsSessionModel() {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        s.setModel("m2");
        assertEquals("m2", byId(OpenCodeAiInfoBarExtension.parseConfigOptions(
                OpenCodeAiInfoBarExtension.buildFallbackConfigOptions(s)), "model").currentValue());
    }

    @Test
    void fallbackModeComboPreselectsSessionMode() {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        s.setMode("plan");
        OptionSpec mode = byId(OpenCodeAiInfoBarExtension.parseConfigOptions(
                OpenCodeAiInfoBarExtension.buildFallbackConfigOptions(s)), "mode");
        assertEquals("plan", mode.currentValue());
        assertTrue(mode.displayNames().containsAll(List.of("build", "plan")));
    }

    @Test
    void parsesModelModeEffortIntoThreeCombosInOrder() {
        assertEquals(List.of("model", "mode", "effort"),
                OpenCodeAiInfoBarExtension.parseConfigOptions(options()).stream().map(OptionSpec::id).toList());
    }

    @Test
    void arrayWithoutEffortYieldsNoEffortCombo() {
        JsonArray a = JsonParser.parseString("[{\"id\":\"model\",\"type\":\"select\",\"options\":[]}]").getAsJsonArray();
        assertNull(OpenCodeAiInfoBarExtension.parseConfigOptions(a).stream()
                .filter(s -> "effort".equals(s.id())).findFirst().orElse(null));
    }

    @Test
    void displayNameMapsToUnderlyingValueBothWays() {
        OptionSpec model = byId(OpenCodeAiInfoBarExtension.parseConfigOptions(options()), "model");
        assertEquals("m2", model.valueForDisplay("Model Two"));
        assertEquals("Model One", model.displayForValue("m1"));
        assertFalse(model.displayNames().contains("m1"));
    }

    @Test
    void unknownDisplayNameFallsBackToItselfForEditableEntries() {
        OptionSpec mode = byId(OpenCodeAiInfoBarExtension.parseConfigOptions(options()), "mode");
        assertEquals("custom", mode.valueForDisplay("custom"));
        assertEquals("unknown", mode.displayForValue("unknown"));
    }

    @Test
    void optionWithoutNameFallsBackToValueAsDisplay() {
        JsonArray a = JsonParser.parseString("[{\"id\":\"model\",\"type\":\"select\","
                                             + "\"options\":[{\"value\":\"m1\"}]}]").getAsJsonArray();
        OptionSpec s = OpenCodeAiInfoBarExtension.parseConfigOptions(a).get(0);
        assertEquals(List.of("m1"), s.displayNames());
        assertEquals("m1", s.valueForDisplay("m1"));
    }

    @Test
    void optionWithBlankNameFallsBackToValueAsDisplay() {
        JsonArray a = JsonParser.parseString("[{\"id\":\"effort\",\"type\":\"select\","
                                             + "\"options\":[{\"value\":\"high\",\"name\":\"\"}]}]").getAsJsonArray();
        assertEquals(List.of("high"), OpenCodeAiInfoBarExtension.parseConfigOptions(a).get(0).displayNames());
    }

    @Test
    void optionWithWhitespaceOnlyNameFallsBackToValueAsDisplay() {
        JsonArray a = JsonParser.parseString("[{\"id\":\"effort\",\"type\":\"select\","
                                             + "\"options\":[{\"value\":\"high\",\"name\":\"  \"}]}]").getAsJsonArray();
        assertEquals(List.of("high"), OpenCodeAiInfoBarExtension.parseConfigOptions(a).get(0).displayNames());
    }

    @Test
    void optionWithBlankNameAndBlankValueStaysBlankForRenderer() {
        JsonArray a = JsonParser.parseString("[{\"id\":\"effort\",\"type\":\"select\","
                                             + "\"options\":[{\"value\":\"\",\"name\":\"\"}]}]").getAsJsonArray();
        assertEquals(List.of(""), OpenCodeAiInfoBarExtension.parseConfigOptions(a).get(0).displayNames());
    }

    @Test
    void nonSelectAndMalformedEntriesAreFilteredOut() {
        JsonArray a = JsonParser.parseString("[{\"id\":\"model\",\"type\":\"info\"},"
                                             + "{\"type\":\"select\"},\"bad\",42]").getAsJsonArray();
        assertTrue(OpenCodeAiInfoBarExtension.parseConfigOptions(a).isEmpty());
    }

    @Test
    void nullConfigOptionsYieldsEmptyListNotNpe() {
        assertTrue(OpenCodeAiInfoBarExtension.parseConfigOptions(null).isEmpty());
    }

    @Test
    void optionValueAccessorsReturnConstructorValues() {
        OptionValue v = new OptionValue("raw", "display");
        assertEquals("raw", v.value());
        assertEquals("display", v.name());
    }

    @Test
    void preserveSelectionsKeepsDisplayedCurrentValueButUsesRefreshedOptionList() {
        JsonArray displayed = JsonParser.parseString("[{\"id\":\"model\",\"type\":\"select\","
                                                     + "\"currentValue\":\"old\",\"options\":[{\"value\":\"old\",\"name\":\"old\"}]}]").getAsJsonArray();
        JsonArray refreshed = JsonParser.parseString("[{\"id\":\"model\",\"type\":\"select\","
                                                     + "\"currentValue\":\"new\",\"options\":[{\"value\":\"new\",\"name\":\"new\"}]}]").getAsJsonArray();
        assertEquals("old", byId(OpenCodeAiInfoBarExtension.parseConfigOptions(
                OpenCodeAiInfoBarExtension.preserveSelections(displayed, refreshed)), "model").currentValue());
    }

    @Test
    void preserveSelectionsLeavesUnknownIdUntouched() {
        JsonArray displayed = JsonParser.parseString("[{\"id\":\"mode\",\"type\":\"select\",\"currentValue\":\"plan\",\"options\":[]}]").getAsJsonArray();
        JsonArray refreshed = JsonParser.parseString("[{\"id\":\"model\",\"type\":\"select\",\"currentValue\":\"m1\",\"options\":[]}]").getAsJsonArray();
        assertEquals("m1", byId(OpenCodeAiInfoBarExtension.parseConfigOptions(
                OpenCodeAiInfoBarExtension.preserveSelections(displayed, refreshed)), "model").currentValue());
    }

    @Test
    void preserveSelectionsHandlesNullDisplayedOrRefreshed() {
        JsonArray a = options();
        assertSame(a, OpenCodeAiInfoBarExtension.preserveSelections(null, a));
        assertSame(a, OpenCodeAiInfoBarExtension.preserveSelections(a, null));
    }

    @Test
    void realConfigOptionsEventReplacesSeedValues() {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        OpenCodeAiInfoBarExtension ext = bar(options(), s, new ArrayList<>(), new AtomicReference<>());
        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(
                JsonParser.parseString("[{\"id\":\"effort\",\"type\":\"select\",\"options\":[]}]").getAsJsonArray()));
        assertEquals(1, snapshot(ext).size());
    }

    @Test
    void repopulateDoesNotFireChangeListenerButUserSelectionSendsUnderlyingValue() throws Exception {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), new OpenCodeSessionSettings(), calls, new AtomicReference<>());
        SwingUtilities.invokeAndWait(() -> {
            List<JComponent> components = ext.createComponents();
            assertTrue(calls.isEmpty());
            JComboBox<?> model = combo((JPanel) components.get(0), 0);
            model.setSelectedItem("Model Two");
        });
        assertEquals(1, calls.size());
        assertArrayEquals(new String[]{"model", "m2"}, calls.get(0));
    }

    @Test
    void onPropertyEventWithOpenCodeModelsEventRebuildsFallbackComboWhenNoLiveSession() throws Exception {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        OpenCodeAiInfoBarExtension ext = bar(null, s, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        SwingUtilities.invokeAndWait(() -> ext.onPropertyEvent(new AvailableModelsEvent(List.of("alpha", "beta"))));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertTrue(snapshot(ext).toString().contains("alpha"));
    }

    @Test
    void onPropertyEventPreservesCurrentlyDisplayedModelInsteadOfResettingToGlobalDefault() throws Exception {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        OpenCodeAiInfoBarExtension ext = bar(options(), s, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        SwingUtilities.invokeAndWait(() -> ext.onPropertyEvent(new AvailableModelsEvent(List.of("x", "y"))));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertEquals("m1", byId(OpenCodeAiInfoBarExtension.parseConfigOptions(snapshot(ext)), "model").currentValue());
    }

    @Test
    void namespaceMismatchBetweenPersistedModelAndDiscoveredOptionsLeavesComboShowingWrongModel() throws Exception {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        s.setModel("opencode/m2");
        OpenCodeAiInfoBarExtension ext = bar(null, s, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        SwingUtilities.invokeAndWait(() -> ext.onPropertyEvent(new AvailableModelsEvent(List.of("m1", "m2"))));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertEquals("opencode/m2", byId(OpenCodeAiInfoBarExtension.parseConfigOptions(snapshot(ext)), "model").currentValue());
        assertEquals("opencode/m2", combo(ext.comboPanel, 0).getSelectedItem(),
                "the real model combo must visibly retain the persisted namespace-qualified model");
    }

    @Test
    void idleSessionDropdownModelChangeUpdatesSettingsWithoutBroadcast() throws Exception {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        s.setModel("m1");
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), s, calls, new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        SwingUtilities.invokeAndWait(() -> combo(ext.comboPanel, 0).setSelectedItem("Model Two"));
        assertEquals("model", calls.get(0)[0]);
        assertEquals("m2", calls.get(0)[1]);
    }

    @Test
    void createComponentsAppendsCompactButtonEnabledByDefault() throws Exception {
        AtomicReference<String> compact = new AtomicReference<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), compact);
        final List<JComponent>[] result = new List[1];
        SwingUtilities.invokeAndWait(() -> result[0] = ext.createComponents());
        assertEquals(3, result[0].size());
        assertEquals("⇒ Compact", ((JButton) result[0].get(2)).getText());
        assertTrue(result[0].get(2).isEnabled());
    }

    @Test
    void compactButtonClickRaisesCallback() throws Exception {
        AtomicReference<String> compact = new AtomicReference<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), compact);
        final JButton[] button = new JButton[1];
        SwingUtilities.invokeAndWait(() -> button[0] = (JButton) ext.createComponents().get(2));
        SwingUtilities.invokeAndWait(button[0]::doClick);
        assertEquals("compact", compact.get());
    }

    @Test
    void onBusyChangedDisablesAndEnablesCombosAndCompactButton() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        SwingUtilities.invokeAndWait(() -> ext.onBusyChanged(true));
        assertFalse(ext.comboPanel.getComponent(0).isEnabled());
        SwingUtilities.invokeAndWait(() -> ext.onBusyChanged(false));
        assertTrue(ext.comboPanel.getComponent(0).isEnabled());
    }

    @Test
    void comboPanelRemainsComponentsIndexOfZero() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        final List<JComponent>[] result = new List[1];
        SwingUtilities.invokeAndWait(() -> result[0] = ext.createComponents());
        assertSame(ext.comboPanel, result[0].get(0));
    }

    @Test
    void createComponentsBuildsOneComboPerSelectOption() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        assertEquals(3, Arrays.stream(ext.comboPanel.getComponents()).filter(c -> c instanceof JComboBox).count());
    }

    @Test
    void configEventRebuildsExistingCombos() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(options()));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertEquals(3, Arrays.stream(ext.comboPanel.getComponents()).filter(c -> c instanceof JComboBox).count());
    }

    @Test
    void programmaticConfigPopulationDoesNotRaiseCallback() throws Exception {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), null, calls, new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        assertTrue(calls.isEmpty());
    }

    @Test
    void userSelectionRaisesExactlyOneCallback() throws Exception {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), null, calls, new AtomicReference<>());
        SwingUtilities.invokeAndWait(() -> {
            ext.createComponents();
            combo(ext.comboPanel, 1).setSelectedItem("plan");
        });
        assertEquals(1, calls.size());
    }

    @Test
    void userSelectionUsesConfigId() throws Exception {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), null, calls, new AtomicReference<>());
        SwingUtilities.invokeAndWait(() -> {
            ext.createComponents();
            combo(ext.comboPanel, 1).setSelectedItem("plan");
        });
        assertEquals("mode", calls.get(0)[0]);
    }

    @Test
    void userSelectionUsesUnderlyingValue() throws Exception {
        List<String[]> calls = new ArrayList<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), null, calls, new AtomicReference<>());
        SwingUtilities.invokeAndWait(() -> {
            ext.createComponents();
            combo(ext.comboPanel, 0).setSelectedItem("Model Two");
        });
        assertEquals("m2", calls.get(0)[1]);
    }

    @Test
    void usageEventDoesNotRebuildCombos() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        int before = ext.comboPanel.getComponentCount();
        ext.onAiProcessImplEvent(new kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeUsageEvent(10, 100));
        assertEquals(before, ext.comboPanel.getComponentCount());
    }

    @Test
    void busyStateSurvivesConfigRepopulation() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        ext.onBusyChanged(true);
        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(options()));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertFalse(ext.comboPanel.getComponent(0).isEnabled());
    }

    @Test
    void readyStateReenablesAfterConfigRepopulation() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        ext.onBusyChanged(false);
        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(options()));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertTrue(ext.comboPanel.getComponent(0).isEnabled());
    }

    @Test
    void refreshedModelsReplaceOptionsButKeepCurrentValue() throws Exception {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        s.setModel("m2");
        OpenCodeAiInfoBarExtension ext = bar(null, s, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        ext.onPropertyEvent(new AvailableModelsEvent(List.of("m3", "m4")));
        SwingUtilities.invokeAndWait(() -> {
        });
        OptionSpec model = byId(OpenCodeAiInfoBarExtension.parseConfigOptions(snapshot(ext)), "model");
        assertEquals("m2", model.currentValue());
        assertTrue(model.displayNames().containsAll(List.of("m3", "m4")));
    }

    @Test
    void emptyModelBroadcastDoesNotThrow() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(null, new OpenCodeSessionSettings(), new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        assertDoesNotThrow(() -> ext.onPropertyEvent(new AvailableModelsEvent(List.of())));
    }

    @Test
    void nullInitialOptionsUsesFallback() throws Exception {
        OpenCodeSessionSettings s = new OpenCodeSessionSettings();
        s.setMode("plan");
        OpenCodeAiInfoBarExtension ext = bar(null, s, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        assertEquals("plan", byId(OpenCodeAiInfoBarExtension.parseConfigOptions(snapshot(ext)), "mode").currentValue());
    }

    @Test
    void optionSpecsRetainOptionOrder() {
        assertEquals(List.of("m1", "m2"), byId(OpenCodeAiInfoBarExtension.parseConfigOptions(options()), "model")
                .options().stream().map(OptionValue::value).toList());
    }

    @Test
    void optionSpecExposesCurrentValue() {
        assertEquals("build", byId(OpenCodeAiInfoBarExtension.parseConfigOptions(options()), "mode").currentValue());
    }

    @Test
    void modelDisplayNamesAreStable() {
        assertEquals(List.of("Model One", "Model Two"),
                byId(OpenCodeAiInfoBarExtension.parseConfigOptions(options()), "model").displayNames());
    }

    @Test
    void changingBusyDoesNotChangeOptionValues() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        String before = snapshot(ext).toString();
        ext.onBusyChanged(true);
        assertEquals(before, snapshot(ext).toString());
    }

    @Test
    void allActionComponentsAreEnabledWhenReady() throws Exception {
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), new AtomicReference<>());
        SwingUtilities.invokeAndWait(ext::createComponents);
        ext.onBusyChanged(false);
        for (Component c : ext.comboPanel.getComponents()) {
            if (c instanceof JComboBox<?> combo) {
                assertTrue(combo.isEnabled());
            }
        }
    }

    @Test
    void compactCallbackIsNotRaisedByCreation() throws Exception {
        AtomicReference<String> compact = new AtomicReference<>();
        OpenCodeAiInfoBarExtension ext = bar(options(), null, new ArrayList<>(), compact);
        SwingUtilities.invokeAndWait(ext::createComponents);
        assertNull(compact.get());
    }

    @Test
    void configEventRetainsIncomingArrayAsSnapshot() {
        JsonArray incoming = options();
        OpenCodeAiInfoBarExtension ext = bar(null, null, new ArrayList<>(), new AtomicReference<>());
        ext.onAiProcessImplEvent(new OpenCodeConfigOptionsEvent(incoming));
        assertSame(incoming, snapshot(ext));
    }

    @Test
    void availableModelsEventIsAcceptedAsPropertyEvent() {
        OpenCodeAiInfoBarExtension ext = bar(null, null, new ArrayList<>(), new AtomicReference<>());
        assertDoesNotThrow(() -> ext.onPropertyEvent(new AvailableModelsEvent(List.of("m"))));
    }
}
