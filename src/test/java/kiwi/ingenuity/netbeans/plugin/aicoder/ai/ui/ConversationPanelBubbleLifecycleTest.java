package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ConversationPanelBubbleLifecycleTest {

    @Test
    void finaliseEmptyAssistantRemovesTheBubble() throws Exception {
        onEdt(() -> {
            for (String text : List.of("", " \t\r\n")) {
                ConversationPanel panel = panel();
                AtomicInteger closed = new AtomicInteger();
                panel.setAssistantMessageClosedListener(closed::incrementAndGet);
                panel.beginAssistantMessage();
                panel.appendDelta(text);
                panel.finaliseAssistantMessage();
                panel.finaliseAssistantMessage();
                assertFalse(panel.hasActiveAssistantMessage());
                assertEquals(List.of(), panel.renderedRolesForTest());
                assertEquals(List.of(), panel.getHistory());
                assertEquals(0, closed.get(), "explicit finalisation does not notify");
            }
            return null;
        });
    }

    @Test
    void systemRowClosesTextBubbleBeforeNotifyingAndNextTextFollowsIt() throws Exception {
        onEdt(() -> {
            ConversationPanel panel = panel();
            AtomicInteger closed = new AtomicInteger();
            panel.setAssistantMessageClosedListener(() -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                assertFalse(panel.hasActiveAssistantMessage());
                assertEquals(List.of("ASSISTANT"), panel.renderedRolesForTest(),
                             "listener runs after closing and before adding the system row");
                closed.incrementAndGet();
            });
            panel.beginAssistantMessage();
            panel.appendDelta("before");
            panel.addSystemMessage("notice");
            assertEquals(1, closed.get());
            assertEquals(List.of("ASSISTANT", "SYSTEM"), panel.renderedRolesForTest());
            panel.appendDelta("must not reach the closed bubble");
            panel.beginAssistantMessage();
            panel.appendDelta("after");
            panel.finaliseAssistantMessage();
            assertEquals(List.of("ASSISTANT", "SYSTEM", "ASSISTANT"), panel.renderedRolesForTest());
            assertEquals(List.of("before", "notice", "after"),
                         panel.getHistory().stream().map(AiMessage::markdownText).toList());
            return null;
        });
    }

    @Test
    void systemRowRemovesBlankBubbleAndNotifies() throws Exception {
        onEdt(() -> {
            for (String text : List.of("", " \t\n")) {
                ConversationPanel panel = panel();
                AtomicInteger closed = new AtomicInteger();
                panel.setAssistantMessageClosedListener(() -> {
                    assertFalse(panel.hasActiveAssistantMessage());
                    assertEquals(List.of(), panel.renderedRolesForTest());
                    closed.incrementAndGet();
                });
                panel.beginAssistantMessage();
                panel.appendDelta(text);
                panel.addSystemMessage("notice");
                assertEquals(1, closed.get());
                assertEquals(List.of("SYSTEM"), panel.renderedRolesForTest());
                assertEquals(List.of("notice"),
                             panel.getHistory().stream().map(AiMessage::markdownText).toList());
            }
            return null;
        });
    }

    @Test
    void systemRowWithoutActiveBubbleDoesNotNotify() throws Exception {
        onEdt(() -> {
            ConversationPanel panel = panel();
            AtomicInteger closed = new AtomicInteger();
            panel.setAssistantMessageClosedListener(closed::incrementAndGet);
            panel.addSystemMessage("first");
            panel.addSystemMessage("second");
            assertEquals(0, closed.get());
            assertEquals(List.of("SYSTEM", "SYSTEM"), panel.renderedRolesForTest());
            return null;
        });
    }

    @Test
    void restoreDropsOnlyBlankAssistantEntriesFromHistoryAndRendering() throws Exception {
        onEdt(() -> {
            ConversationPanel panel = panel();
            AiMessage user = AiMessage.user("");
            AiMessage system = AiMessage.system(" ");
            AiMessage assistant = AiMessage.assistant("kept");
            panel.restoreHistory(List.of(user, AiMessage.assistant(""), AiMessage.assistant(" \t\n"),
                                         AiMessage.assistant(null), system, assistant));
            assertEquals(List.of(user, system, assistant), panel.getHistory());
            assertEquals(List.of("USER", "SYSTEM", "ASSISTANT"), panel.renderedRolesForTest());
            assertFalse(panel.hasActiveAssistantMessage());
            return null;
        });
    }

    @Test
    void historySnapshotRestoreRoundTripHasNoBlankAssistantEntries() throws Exception {
        onEdt(() -> {
            ConversationPanel source = panel();
            source.addUserMessage("prompt");
            source.beginAssistantMessage();
            source.appendDelta(" \n");
            source.addSystemMessage("notice");
            source.beginAssistantMessage();
            source.appendDelta("answer");
            source.finaliseAssistantMessage();
            List<AiMessage> saved = source.getHistory();
            ConversationPanel restored = panel();
            restored.restoreHistory(saved);
            assertEquals(saved, restored.getHistory());
            assertEquals(List.of("USER", "SYSTEM", "ASSISTANT"), restored.renderedRolesForTest());
            assertTrue(restored.getHistory().stream()
                    .filter(m -> m.role() == AiMessage.Role.ASSISTANT)
                    .noneMatch(m -> m.markdownText().isBlank()));
            return null;
        });
    }

    @Test
    void userRowClosesActiveAssistantAndNotifies() throws Exception {
        onEdt(() -> {
            ConversationPanel panel = panel();
            AtomicInteger closed = new AtomicInteger();
            panel.setAssistantMessageClosedListener(closed::incrementAndGet);
            panel.beginAssistantMessage();
            panel.appendDelta("answer");
            panel.addUserMessage("next");
            assertFalse(panel.hasActiveAssistantMessage());
            assertEquals(1, closed.get());
            assertEquals(List.of("ASSISTANT", "USER"), panel.renderedRolesForTest());
            return null;
        });
    }

    @Test
    void clearWithActiveBubbleFiresClosedListenerOnce() throws Exception {
        onEdt(() -> {
            ConversationPanel panel = panel();
            AtomicInteger closed = new AtomicInteger();
            panel.setAssistantMessageClosedListener(() -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                assertFalse(panel.hasActiveAssistantMessage());
                assertEquals(List.of(), panel.renderedRolesForTest(),
                             "listener runs after the panel has been cleared");
                closed.incrementAndGet();
            });
            panel.beginAssistantMessage();
            panel.appendDelta("discarded");
            panel.clear();
            assertEquals(1, closed.get());
            assertFalse(panel.hasActiveAssistantMessage());
            assertEquals(List.of(), panel.renderedRolesForTest());
            assertEquals(List.of(), panel.getHistory());
            return null;
        });
    }

    @Test
    void clearWithoutActiveBubbleDoesNotNotify() throws Exception {
        onEdt(() -> {
            ConversationPanel panel = panel();
            AtomicInteger closed = new AtomicInteger();
            panel.setAssistantMessageClosedListener(closed::incrementAndGet);
            panel.addSystemMessage("notice");
            panel.clear();
            panel.clear();
            assertEquals(0, closed.get());
            assertEquals(List.of(), panel.renderedRolesForTest());
            return null;
        });
    }

    @Test
    void restoreHistoryWithActiveBubbleFiresClosedListenerOnce() throws Exception {
        onEdt(() -> {
            ConversationPanel panel = panel();
            AiMessage user = AiMessage.user("prompt");
            AiMessage assistant = AiMessage.assistant("done");
            AtomicInteger closed = new AtomicInteger();
            panel.setAssistantMessageClosedListener(() -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                assertFalse(panel.hasActiveAssistantMessage());
                assertEquals(List.of("USER", "ASSISTANT"), panel.renderedRolesForTest(),
                             "listener runs after the history has been rebuilt");
                closed.incrementAndGet();
            });
            panel.beginAssistantMessage();
            panel.appendDelta("discarded");
            panel.restoreHistory(List.of(user, assistant));
            assertEquals(1, closed.get());
            assertFalse(panel.hasActiveAssistantMessage());
            assertEquals(List.of(user, assistant), panel.getHistory());
            return null;
        });
    }

    @Test
    void restoreHistoryWithoutActiveBubbleDoesNotNotify() throws Exception {
        onEdt(() -> {
            ConversationPanel panel = panel();
            AtomicInteger closed = new AtomicInteger();
            panel.setAssistantMessageClosedListener(closed::incrementAndGet);
            panel.restoreHistory(List.of(AiMessage.user("prompt"), AiMessage.assistant("done")));
            panel.restoreHistory(List.of(AiMessage.user("again")));
            assertEquals(0, closed.get());
            return null;
        });
    }

    @Test
    void autoScrollFollowsSystemRowAddedMidStream() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a showing Swing hierarchy");
        AtomicReference<ConversationPanel> conversation = new AtomicReference<>();
        JFrame frame = onEdt(() -> {
            ConversationPanel panel = panel();
            conversation.set(panel);
            JFrame window = new JFrame();
            window.setUndecorated(true);
            window.setFocusableWindowState(false);
            window.setAutoRequestFocus(false);
            window.setContentPane(panel);
            window.setPreferredSize(new Dimension(320, 180));
            window.pack();
            window.setLocation(-10_000, -10_000);
            window.setVisible(true);
            panel.beginAssistantMessage();
            panel.appendDelta("streamed text ".repeat(400));
            panel.addSystemMessage("system notice ".repeat(400));
            return window;
        });
        try {
            long deadline = System.nanoTime() + 3_000_000_000L;
            boolean bottom;
            do {
                onEdt(() -> null);
                bottom = onEdt(() -> {
                    JScrollBar bar = conversation.get().getVerticalScrollBar();
                    return bar.getMaximum() > bar.getVisibleAmount()
                            && bar.getValue() + bar.getVisibleAmount() == bar.getMaximum();
                });
                if (bottom) {
                    break;
                }
                Thread.sleep(30);
            }
            while (System.nanoTime() < deadline);
            assertTrue(bottom, "auto-scroll must follow the system row");
            onEdt(() -> {
                assertEquals(List.of("ASSISTANT", "SYSTEM"), conversation.get().renderedRolesForTest());
                assertFalse(conversation.get().hasActiveAssistantMessage());
                return null;
            });
        }
        finally {
            onEdt(() -> {
                conversation.get().setAutoScrollToLatest(false);
                frame.dispose();
                return null;
            });
        }
    }

    private static ConversationPanel panel() {
        ConversationPanel panel = new ConversationPanel();
        panel.setHistoryCap(() -> 0);
        return panel;
    }

    private static <T> T onEdt(Callable<T> callable) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(callable.call());
            }
            catch (Exception ex) {
                failure.set(ex);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return value.get();
    }
}
