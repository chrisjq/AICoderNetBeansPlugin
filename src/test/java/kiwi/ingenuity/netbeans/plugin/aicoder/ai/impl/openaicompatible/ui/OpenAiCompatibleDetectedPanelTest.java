package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.GraphicsEnvironment;
import java.time.Instant;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleDetectedPanelTest {

    private static DetectedFacts ollamaFacts() {
        return DetectedFacts.of("gen")
                .withKind(ServerKind.OLLAMA)
                .withApiBase("http://127.0.0.1:11434")
                .withStreaming(Boolean.TRUE)
                .withCapabilities(new DetectedFacts.Capabilities(Boolean.TRUE, Boolean.TRUE))
                .withWindowReadings(List.of(new WindowReading(8192, WindowReading.Source.OLLAMA_PS, true)))
                .withModels(List.of("llama3.2:latest", "qwen2.5-coder:7b"))
                .withTimestamp(Instant.parse("2026-10-23T00:00:00Z"));
    }

    @Test
    @Timeout(10)
    void detectedOllamaShowsKindWindowAndCapabilities() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setServerType(ServerTypeEnum.AUTO);
        settings.setBaseUrl("http://127.0.0.1:11434");
        settings.setToolCallingMode(ToolCallingModeEnum.NATIVE);

        OpenAiCompatibleDetectedPanel panel = new OpenAiCompatibleDetectedPanel();
        panel.updateAndWait(ollamaFacts(), settings);

        assertEquals("Ollama (detected)", panel.kindValue());
        assertEquals("OK", panel.reachabilityValue());
        assertNull(panel.reachabilityTooltip());
        assertEquals("http://127.0.0.1:11434", panel.apiBaseValue());
        assertEquals("yes", panel.streamingValue());
        assertEquals("tools, thinking", panel.capabilitiesValue());
        assertEquals("8192 (Ollama /api/ps)", panel.windowValue());
        assertEquals("auto", panel.overrideValue());
        assertEquals("Native", panel.toolCallingValue());
        assertEquals("2", panel.modelCountValue());
    }

    @Test
    @Timeout(10)
    void explicitTypeIsMarkedChosenAndOverrideWinsTheWindow() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setServerType(ServerTypeEnum.LM_STUDIO);
        settings.setContextWindowOverride(4096);
        settings.setToolCallingMode(ToolCallingModeEnum.SCHEMA);

        OpenAiCompatibleDetectedPanel panel = new OpenAiCompatibleDetectedPanel();
        panel.updateAndWait(ollamaFacts(), settings);

        assertEquals("LM Studio (chosen)", panel.kindValue());
        assertEquals("4096 (override)", panel.windowValue());
        assertEquals("4096", panel.overrideValue());
        assertEquals("Schema", panel.toolCallingValue());
    }

    @Test
    @Timeout(10)
    void missingFactsAreN_AWithUnreachableTooltip() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setServerType(ServerTypeEnum.AUTO);
        settings.setBaseUrl("http://localhost:11434");

        OpenAiCompatibleDetectedPanel panel = new OpenAiCompatibleDetectedPanel();
        panel.updateAndWait(null, settings);

        assertEquals("—", panel.kindValue());
        assertEquals("N/A", panel.reachabilityValue());
        assertEquals("Cannot reach the server at http://localhost:11434", panel.reachabilityTooltip());
        assertEquals("—", panel.apiBaseValue());
        assertEquals("unknown", panel.streamingValue());
        assertEquals("unknown", panel.capabilitiesValue());
        assertEquals("—", panel.windowValue());
        assertEquals("auto", panel.overrideValue());
        assertEquals("Auto", panel.toolCallingValue());
        assertEquals("—", panel.modelCountValue());
    }

    @Test
    @Timeout(10)
    void narrowPanelKeepsTheFirstCaptionAtTheLeftInset() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing component creation needs a display");

        OpenAiCompatibleDetectedPanel detected = new OpenAiCompatibleDetectedPanel();
        javax.swing.JPanel component = (javax.swing.JPanel) detected.component();
        component.setSize(component.getMinimumSize());
        component.doLayout();

        assertEquals(4, component.getComponent(0).getX());
    }

    @Test
    @Timeout(5)
    void kindAndWindowFormattingDoNotNeedSwing() {
        OpenAiCompatibleSessionSettings auto = new OpenAiCompatibleSessionSettings();
        auto.setServerType(ServerTypeEnum.AUTO);
        assertEquals("Ollama (detected)", OpenAiCompatibleDetectedPanel.kindText(ollamaFacts(), auto));

        OpenAiCompatibleSessionSettings chosen = new OpenAiCompatibleSessionSettings();
        chosen.setServerType(ServerTypeEnum.VLLM);
        assertEquals("vLLM (chosen)", OpenAiCompatibleDetectedPanel.kindText(null, chosen));

        assertEquals("unknown", OpenAiCompatibleDetectedPanel.capabilitiesText(null));
        assertEquals("none", OpenAiCompatibleDetectedPanel.capabilitiesText(
                     DetectedFacts.of("g").withCapabilities(new DetectedFacts.Capabilities(false, false))));
        assertEquals("8192 (Ollama /api/ps)", OpenAiCompatibleDetectedPanel.windowText(ollamaFacts(), auto));
    }
}
