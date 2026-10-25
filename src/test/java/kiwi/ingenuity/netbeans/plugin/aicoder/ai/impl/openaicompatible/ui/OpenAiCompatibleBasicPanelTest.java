package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ConnectionTester;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
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
    void everyFieldSurvivesALoadApplyRoundTrip() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        OpenAiCompatibleSessionSettings source = new OpenAiCompatibleSessionSettings();
        source.setServerType(ServerTypeEnum.LM_STUDIO);
        source.setBaseUrl("http://localhost:1234");
        source.setModel("test-model");
        source.setContextWindowOverride(8192);
        source.setToolCallingMode(ToolCallingModeEnum.NATIVE);
        source.setSaveContextOnClose(true);
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
            typeApiKey(panel, "sk-secret");

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
        javax.swing.JPasswordField field = findPasswordField(panel);
        assertNotNull(field, "the panel must expose a password field for the API key");
        field.setText(key);
        field.postActionEvent();
    }

    private static javax.swing.JPasswordField findPasswordField(OpenAiCompatibleBasicPanel panel) {
        return find(panel.component(), javax.swing.JPasswordField.class);
    }

    private static <T> T find(java.awt.Container container, Class<T> type) {
        for (java.awt.Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof java.awt.Container nested) {
                T found = find(nested, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
