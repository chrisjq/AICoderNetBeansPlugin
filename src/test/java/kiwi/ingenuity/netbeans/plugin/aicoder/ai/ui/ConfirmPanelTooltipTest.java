package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

/**
 * The confirm dialog's Yes/No buttons can carry per-backend tooltips supplied by {@code AiTypeEnum}. A backend that
 * supplies nothing must leave both buttons bare — an invented tooltip that says nothing useful trains the user to
 * ignore them all — and the multi-file batch gate must stay bare too, because the OpenCode hint is advice about a
 * single rejected tool call and a whole change set is not one tool call.
 */
class ConfirmPanelTooltipTest {

    /**
     * The product voice, verbatim. Pinned here so the wiring cannot drift from the user's wording.
     */
    private static final String OPENCODE_ACCEPT = "Accept";
    private static final String OPENCODE_REJECT
            = "Reject, you may need to remind it to use MCP tool manually.";

    private static <T> T onEdt(java.util.concurrent.Callable<T> fn) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(fn.call());
            }
            catch (Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
        return result.get();
    }

    /**
     * The panel's buttons in add order: accept (yes) first, then reject (no).
     */
    private static List<JButton> buttons(Component root) {
        List<JButton> out = new ArrayList<>();
        if (root instanceof JButton b) {
            out.add(b);
        }
        else if (root instanceof Container c) {
            for (Component child : c.getComponents()) {
                out.addAll(buttons(child));
            }
        }
        return out;
    }

    private static ConfirmEvent event() {
        return new ConfirmEvent("Delete", "Delete /tmp/a.txt?", "/tmp/a.txt", null, new CompletableFuture<>());
    }

    @Test
    void openCodeSuppliesTheExactProductStrings() {
        assertEquals(OPENCODE_ACCEPT, AiTypeEnum.OPENCODE.confirmAcceptTooltip(),
                "OpenCode's accept tooltip must be the user's wording, verbatim");
        assertEquals(OPENCODE_REJECT, AiTypeEnum.OPENCODE.confirmRejectTooltip(),
                "OpenCode's reject tooltip must be the user's wording, verbatim");
    }

    @Test
    void openCodeIsTheOnlyBackendWithTooltips() {
        for (AiTypeEnum type : AiTypeEnum.values()) {
            if (type == AiTypeEnum.OPENCODE) {
                continue;
            }
            assertNull(type.confirmAcceptTooltip(), type + " must not invent an accept tooltip");
            assertNull(type.confirmRejectTooltip(), type + " must not invent a reject tooltip");
        }
    }

    @Test
    void openCodeTooltipsReachBothButtons() throws Exception {
        onEdt(() -> {
            ConfirmPanel panel = new ConfirmPanel(event(),
                    AiTypeEnum.OPENCODE.confirmAcceptTooltip(), AiTypeEnum.OPENCODE.confirmRejectTooltip());
            List<JButton> btns = buttons(panel);
            assertEquals(2, btns.size(), "the confirm panel must render exactly two buttons");
            assertEquals(OPENCODE_ACCEPT, btns.get(0).getToolTipText(),
                    "the accept button must carry the backend tooltip");
            assertEquals(OPENCODE_REJECT, btns.get(1).getToolTipText(),
                    "the reject button must carry the backend tooltip");
            return null;
        });
    }

    @Test
    void backendSupplyingNothingLeavesBothButtonsBare() throws Exception {
        onEdt(() -> {
            ConfirmPanel panel = new ConfirmPanel(event(), null, null);
            List<JButton> btns = buttons(panel);
            assertEquals(2, btns.size(), "the confirm panel must render exactly two buttons");
            assertNull(btns.get(0).getToolTipText(), "a backend with no tooltip must leave the accept button bare");
            assertNull(btns.get(1).getToolTipText(), "a backend with no tooltip must leave the reject button bare");
            return null;
        });
    }

    @Test
    void plainConfirmEventConstructorAlsoLeavesButtonsBare() throws Exception {
        onEdt(() -> {
            ConfirmPanel panel = new ConfirmPanel(event());
            List<JButton> btns = buttons(panel);
            assertEquals(2, btns.size(), "the confirm panel must render exactly two buttons");
            assertNull(btns.get(0).getToolTipText(),
                    "the no-tooltip ConfirmEvent constructor must not invent an accept tooltip");
            assertNull(btns.get(1).getToolTipText(),
                    "the no-tooltip ConfirmEvent constructor must not invent a reject tooltip");
            return null;
        });
    }

    @Test
    void batchGateConstructorKeepsTooltipsBare() throws Exception {
        onEdt(() -> {
            // The exact shape MultiReviewDriver uses for its main-panel affordance: the tooltip-free constructor.
            ConfirmPanel panel = new ConfirmPanel(
                    "Allow MultiEdit: 2 files\n  proj/A.java\n  proj/B.java",
                    "Accept Diffs", "Reject", new CompletableFuture<>());
            List<JButton> btns = buttons(panel);
            assertEquals(2, btns.size(), "the confirm panel must render exactly two buttons");
            assertEquals("Accept Diffs", btns.get(0).getText(), "the batch accept button label must be unchanged");
            assertEquals("Reject", btns.get(1).getText(), "the batch reject button label must be unchanged");
            assertNull(btns.get(0).getToolTipText(),
                    "the multi-file batch gate must stay bare even though OpenCode has tooltips set");
            assertNull(btns.get(1).getToolTipText(),
                    "the multi-file batch gate must stay bare even though OpenCode has tooltips set");
            return null;
        });
    }
}
