package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleRequestPanelTest {

    private Preferences prefs;

    @BeforeEach
    void isolatePreferences() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        prefs = MemoryPreferences.create();
        OpenAiCompatiblePluginSettings.useForTest(prefs);
    }

    @AfterEach
    void restorePreferences() throws Exception {
        OpenAiCompatiblePluginSettings.useForTest(null);
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    private static <T extends Component> List<T> find(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Component> void collect(Component component, Class<T> type, List<T> found) {
        if (type.isInstance(component)) {
            found.add(type.cast(component));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, type, found);
            }
        }
    }

    private static List<JTextField> plainFields(Component root) {
        List<JTextField> fields = new ArrayList<>();
        for (JTextField field : find(root, JTextField.class)) {
            // spinner editors are formatted text fields; only the plain fields (temperature, top P, max tokens,
            // header names) are wanted
            if (!(field instanceof JPasswordField) && !(field instanceof JFormattedTextField)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static void addHeaderRow(Component root, String name, String value) {
        find(root, JButton.class).stream().filter(b -> "Add header".equals(b.getText()))
                .findFirst().orElseThrow().doClick();
        List<JTextField> names = plainFields(root);
        names.get(names.size() - 1).setText(name);
        List<JPasswordField> values = find(root, JPasswordField.class);
        values.get(values.size() - 1).setText(value);
    }

    private static OpenAiCompatibleSessionSettings explicit() {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setServerType(ServerTypeEnum.OTHER);
        settings.setBaseUrl("http://h:1");
        settings.setModel("m");
        settings.setApiKeySet(false);
        return settings;
    }

    @Test
    @Timeout(10)
    void theRequestFieldsRoundTripAndEmptyMeansNotSent() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        OpenAiCompatibleSessionSettings in = new OpenAiCompatibleSessionSettings();
        in.setTemperature(0.5);
        in.setTopP(0.8);
        in.setMaxOutputTokens(512);
        panel.load(in);

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);
        assertEquals(0.5, out.temperature());
        assertEquals(0.8, out.topP());
        assertEquals(512, out.maxOutputTokens());

        panel.load(new OpenAiCompatibleSessionSettings());
        OpenAiCompatibleSessionSettings none = new OpenAiCompatibleSessionSettings();
        panel.applyTo(none);
        assertNull(none.temperature());
        assertNull(none.topP());
        assertNull(none.maxOutputTokens());
    }

    @Test
    @Timeout(10)
    void outOfRangeRequestValuesAreClampedOrNotStored() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        panel.load(new OpenAiCompatibleSessionSettings());
        List<JTextField> fields = plainFields(panel.component());
        assertEquals(3, fields.size());
        JTextField temperature = fields.get(0);
        JTextField topP = fields.get(1);
        JTextField maxTokens = fields.get(2);

        // temperature is clamped into [0, 2] and the field shows what is stored
        temperature.setText("2.5");
        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);
        assertEquals(2.0, out.temperature());
        assertEquals("2.0", temperature.getText());

        temperature.setText("-1");
        panel.applyTo(out);
        assertEquals(0.0, out.temperature());
        assertEquals("0.0", temperature.getText());

        // top P is clamped down to 1; zero or less has no valid value, so it is not stored
        topP.setText("1.5");
        panel.applyTo(out);
        assertEquals(1.0, out.topP());
        assertEquals("1.0", topP.getText());

        topP.setText("0");
        panel.applyTo(out);
        assertNull(out.topP());
        assertEquals("0", topP.getText());

        topP.setText("-0.3");
        panel.applyTo(out);
        assertNull(out.topP());

        // max output tokens below 1, and text that is not a number, are not stored
        maxTokens.setText("0");
        panel.applyTo(out);
        assertNull(out.maxOutputTokens());
        maxTokens.setText("many");
        panel.applyTo(out);
        assertNull(out.maxOutputTokens());

        // values inside the range are kept as typed
        temperature.setText("2");
        topP.setText("1");
        maxTokens.setText("1");
        panel.applyTo(out);
        assertEquals(2.0, out.temperature());
        assertEquals(1.0, out.topP());
        assertEquals(1, out.maxOutputTokens());
        assertEquals("2", temperature.getText());
    }

    @Test
    @Timeout(10)
    void headerNamesAndPlainValuesGoToTheSettingsAndSecretValuesNeverDo() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        panel.load(new OpenAiCompatibleSessionSettings());
        addHeaderRow(panel.component(), "X-Team", "blue");
        addHeaderRow(panel.component(), "X-Api-Key", "s3cret-value");

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals(List.of("X-Team", "X-Api-Key"), out.extraHeaderNames());
        assertEquals(Map.of("X-Team", "blue"), out.extraHeaderPlainValues());
        JsonObject json = new JsonObject();
        out.populateJsonObject(json);
        assertFalse(json.toString().contains("s3cret-value"));
        assertTrue(json.toString().contains("X-Api-Key"));
        assertEquals(1, panel.pendingSecretHeaders().size());
    }

    @Test
    @Timeout(10)
    void refusedHeaderRowsAreLeftOutOfTheSettings() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        panel.load(new OpenAiCompatibleSessionSettings());
        addHeaderRow(panel.component(), "Host", "evil");
        addHeaderRow(panel.component(), "X-Team", "blue");

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals(List.of("X-Team"), out.extraHeaderNames());
        assertEquals(List.of("Host is set by the client and cannot be overridden."), panel.headerErrors());
    }

    @Test
    @Timeout(10)
    void noHeadersLeavesTheSettingsUnset() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        panel.load(new OpenAiCompatibleSessionSettings());
        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals(List.of(), out.extraHeaderNames());
        JsonObject json = new JsonObject();
        out.populateJsonObject(json);
        assertFalse(json.has("extraHeaderNames"));
        assertFalse(json.has("extraHeaderPlainValues"));
    }

    @Test
    @Timeout(10)
    void theBasicPanelSendsSecretHeaderValuesToTheKeyringSaverAndNotToTheSettings() {
        List<String> saved = new CopyOnWriteArrayList<>();
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel(null, OpenAiCompatibleLimits.defaults(),
                                                                          (name, value) -> {
                                                                              saved.add(name + "=" + new String(value));
                                                                              return CompletableFuture.completedFuture(true);
                                                                          });
        panel.load(explicit());
        addHeaderRow(panel.component(), "X-Api-Key", "s3cret-value");

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals(List.of("X-Api-Key=s3cret-value"), saved);
        JsonObject json = new JsonObject();
        out.populateJsonObject(json);
        assertFalse(json.toString().contains("s3cret-value"));
        assertEquals(List.of("X-Api-Key"), out.extraHeaderNames());
        assertTrue(panel.pendingSecretHeaders().isEmpty());

        OpenAiCompatibleSessionSettings again = new OpenAiCompatibleSessionSettings();
        panel.applyTo(again);
        assertEquals(List.of("X-Api-Key=s3cret-value"), saved);
    }

    @Test
    @Timeout(10)
    void withoutASaverTheNewSessionPanelHoldsTheSecretsForTheCaller() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        panel.load(explicit());
        addHeaderRow(panel.component(), "X-Token", "t0ken");

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals(List.of("X-Token"), out.extraHeaderNames());
        assertEquals("t0ken", new String(panel.pendingSecretHeaders().get("X-Token")));
    }

    @Test
    @Timeout(10)
    void anApiKeyOnTheBasicPanelMakesAuthorizationARefusedHeader() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        OpenAiCompatibleSessionSettings in = explicit();
        in.setApiKeySet(true);
        panel.load(in);
        addHeaderRow(panel.component(), "Authorization", "Bearer x");

        assertEquals(List.of("Use the API key field instead."), panel.headerErrors());
    }
}
