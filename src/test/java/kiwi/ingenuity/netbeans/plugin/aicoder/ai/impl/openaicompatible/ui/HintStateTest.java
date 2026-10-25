package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Color;
import java.util.List;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.HintState.HintKind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class HintStateTest {

    @Test
    @Timeout(5)
    void priorityFollowsTheSpecOrderOneToEight() {
        List<HintKind> expected = List.of(HintKind.UNREACHABLE, HintKind.KEY, HintKind.TOOLS_DO_NOT_FIT,
                                          HintKind.SERVER_CUTTING_PROMPT, HintKind.SMALL_WINDOW,
                                          HintKind.WINDOW_UNKNOWN, HintKind.TYPE_MISMATCH, HintKind.TOOLS_SHARE);
        assertEquals(expected, List.of(HintKind.values()));
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(i + 1, expected.get(i).priority());
        }
    }

    @Test
    @Timeout(5)
    void theHighestPriorityHintIsShownWhateverTheSettingOrder() {
        HintState state = new HintState();
        state.set(HintKind.TOOLS_SHARE, "Tools use 12 %", "share");
        state.set(HintKind.SMALL_WINDOW, "Small context window (8192)", "raise it");
        assertEquals("Small context window (8192)", state.text());

        state.set(HintKind.KEY, "API key rejected", "");
        assertEquals("API key rejected", state.text());

        state.setUnreachable("http://localhost:1234");
        assertEquals("N/A", state.text());
        assertEquals(HintKind.UNREACHABLE, state.top().orElseThrow().kind());
    }

    @Test
    @Timeout(5)
    void theTooltipListsEveryActiveHintInPriorityOrder() {
        HintState state = new HintState();
        state.set(HintKind.WINDOW_UNKNOWN, "Window unknown", "set the context window");
        state.set(HintKind.KEY, "API key needed", "");
        state.setUnreachable("http://h:1");

        assertEquals("N/A — Cannot reach the server at http://h:1\n"
                + "API key needed\n"
                + "Window unknown — set the context window", state.tooltip());
    }

    @Test
    @Timeout(5)
    void theUnreachableTooltipIsAlwaysTheFixedText() {
        HintState state = new HintState();
        state.setUnreachable("http://localhost:11434");

        assertEquals("Cannot reach the server at http://localhost:11434", state.top().orElseThrow().tooltip());
    }

    @Test
    @Timeout(5)
    void clearingRemovesOnlyThatHintAndRevealsTheNextOne() {
        HintState state = new HintState();
        state.setUnreachable("http://h");
        state.set(HintKind.SMALL_WINDOW, "Small context window (4096)", "");

        state.clear(HintKind.UNREACHABLE);
        assertFalse(state.isActive(HintKind.UNREACHABLE));
        assertEquals("Small context window (4096)", state.text());

        state.clear(HintKind.SMALL_WINDOW);
        assertEquals("", state.text());
        assertEquals("", state.tooltip());
        assertTrue(state.top().isEmpty());
    }

    @Test
    @Timeout(5)
    void settingASlotReplacesItAndABlankTextClearsIt() {
        HintState state = new HintState();
        state.set(HintKind.KEY, "API key rejected", "");
        state.set(HintKind.KEY, "API key needed", "enter it");

        assertEquals(1, state.all().size());
        assertEquals("API key needed", state.text());

        state.set(HintKind.KEY, " ", "ignored");
        assertFalse(state.isActive(HintKind.KEY));
    }

    @Test
    @Timeout(10)
    void theExtensionWarningButtonShowsTooltipAndClears() throws Exception {
        OpenAiCompatibleInfoBarExtension extension = new OpenAiCompatibleInfoBarExtension();
        assertFalse(extension.warningVisible());

        Thread worker = new Thread(() -> {
            extension.set(HintKind.SMALL_WINDOW, "Small context window (4096)", "raise it");
            extension.setUnreachable("http://h:1");
        });
        worker.start();
        worker.join();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals("N/A", extension.hintText());
        assertTrue(extension.warningVisible());
        assertTrue(extension.warningToolTip().startsWith("<html>"));
        assertTrue(extension.warningToolTip().contains("Cannot reach the server at http://h:1"));
        assertTrue(extension.warningToolTip().contains("Small context window (4096)"));
        assertEquals(Color.RED, extension.warningForeground());

        extension.clear(HintKind.UNREACHABLE);
        SwingUtilities.invokeAndWait(() -> {
        });
        assertEquals("Small context window (4096)", extension.hintText());
        assertEquals("Small context window (4096) — raise it", extension.warningToolTip());
        assertTrue(extension.warningVisible());

        extension.clearAll();
        SwingUtilities.invokeAndWait(() -> {
        });
        assertEquals("", extension.hintText());
        assertNull(extension.warningToolTip());
        assertFalse(extension.warningVisible());
    }

    @Test
    @Timeout(5)
    void clickingWarningUsesInjectedPresenter() throws Exception {
        OpenAiCompatibleInfoBarExtension extension = new OpenAiCompatibleInfoBarExtension();
        java.util.concurrent.atomic.AtomicReference<String> shown = new java.util.concurrent.atomic.AtomicReference<>();
        extension.setWarningDialogPresenter(shown::set);
        extension.set(HintKind.SMALL_WINDOW, "Small context window (4096)", "raise it");
        SwingUtilities.invokeAndWait(() -> {
            extension.createComponents().stream().filter(c -> c instanceof javax.swing.JButton)
                    .map(javax.swing.JButton.class::cast).filter(b -> "⚠".equals(b.getText())).findFirst()
                    .orElseThrow().doClick();
        });
        assertEquals(extension.hintTooltip(), shown.get());
    }

    // ---- canonical hint texts ----
    @Test
    @Timeout(5)
    void rankThreeSaysHowMuchTheToolsUseAndToApplyMinimal() {
        assertEquals("Tools use ~9000 of 8192 — apply Minimal", HintState.toolsTooBigText(9000, 8192));
        assertEquals("Window 4096 too small for tools", HintState.windowTooSmallForToolsText(4096));
    }

    @Test
    @Timeout(5)
    void rankEightAppliesFromAQuarterOfTheWindow() {
        assertFalse(HintState.toolsShareApplies(2047, 8192));
        assertTrue(HintState.toolsShareApplies(2048, 8192));
        assertFalse(HintState.toolsShareApplies(0, 8192));
        assertFalse(HintState.toolsShareApplies(100, 0));
        assertEquals("Tools use ~3000 of 8192", HintState.toolsShareText(3000, 8192));
        assertTrue(HintState.toolsShareTooltip(3000, 8192).startsWith("Tool definitions take about 37 % of the "
                + "8192-token context window"), HintState.toolsShareTooltip(3000, 8192));
        assertTrue(HintState.toolsShareTooltip(3000, 8192).contains("'Minimal' MCP template"));
    }

    @Test
    @Timeout(5)
    void rankFiveAndSevenTooltipsCarryTheKindsRemedy() {
        assertEquals("Small context window (4096) — older history will be trimmed rather than summarised. Consider "
                + "raising it on the server.", HintState.smallWindowText(4096));
        assertTrue(HintState.smallWindowTooltip(4096, "OLLAMA").contains("OLLAMA_CONTEXT_LENGTH or the model's num_ctx"));
        assertTrue(HintState.smallWindowTooltip(4096, "LM_STUDIO").contains("set Context Length when loading the model"));
        assertTrue(HintState.smallWindowTooltip(4096, null).contains("raise it on the server"));

        assertEquals("Server doesn't look like llama.cpp — try Auto-detect", HintState.typeMismatchText("llama.cpp"));
        String tooltip = HintState.typeMismatchTooltip("llama.cpp", "OLLAMA");
        assertTrue(tooltip.contains("answers like OLLAMA"), tooltip);
        assertTrue(tooltip.contains("OLLAMA_CONTEXT_LENGTH"), tooltip);
    }

    @Test
    @Timeout(10)
    void theTypedSettersFeedThePriorityStateAndTheTooltipListsEveryHint() throws Exception {
        OpenAiCompatibleInfoBarExtension extension = new OpenAiCompatibleInfoBarExtension();

        extension.updateToolsShare(3000, 8192);
        extension.setTypeMismatch("vLLM", "LLAMACPP");
        extension.setSmallWindow(8192, "LLAMACPP");
        extension.setToolsTooBig(9000, 8192, "Tool definitions alone need ~9000 tokens of your 8192-token window.");
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals("Tools use ~9000 of 8192 — apply Minimal", extension.hintText(), "rank 3 is the highest");
        String tooltip = extension.hintTooltip();
        String[] lines = tooltip.split("\n");
        assertEquals(4, lines.length, tooltip);
        assertTrue(lines[0].startsWith("Tools use ~9000 of 8192 — apply Minimal — Tool definitions alone"), lines[0]);
        assertTrue(lines[1].startsWith("Small context window (8192)") && lines[1].contains("start it with -c"),
                   lines[1]);
        assertTrue(lines[2].startsWith("Server doesn't look like vLLM — try Auto-detect — "), lines[2]);
        assertTrue(lines[3].startsWith("Tools use ~3000 of 8192 — Tool definitions take about 37 %"), lines[3]);

        extension.updateToolsShare(100, 8192);
        assertFalse(extension.hintTooltip().contains("Tools use ~3000"), "a small share clears rank 8");

        extension.setWindowTooSmallForTools(4096, "Context window 4096 is too small for tools.");
        assertTrue(extension.hintText().startsWith("Window 4096 too small for tools"), "replaces the rank-3 slot");
    }

    @Test
    @Timeout(5)
    void nothingSetsTheLabelOutsideThePriorityState() {
        for (java.lang.reflect.Method method : OpenAiCompatibleInfoBarExtension.class.getMethods()) {
            assertFalse(method.getName().equals("setHint"), "setHint bypassed the hint priority and is gone");
        }
    }
}
