package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a message which grows while its conversation tab is hidden. The message marks itself dirty
 * while hidden; showing the tab rebuilds it and the deferred hierarchy callback must restore the conversation scroll
 * only when auto-scroll is enabled.
 */
class ConversationPanelHiddenScrollTest {

    @Test
    void showingAGrownHiddenTabReturnsToBottomWhenAutoScrollIsEnabled() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a real showing Swing hierarchy");
        Fixture fixture = onEdt(Fixture::new);
        try {
            onEdt(() -> {
                fixture.conversation.beginAssistantMessage();
                fixture.tabs.setSelectedIndex(0);
                fixture.conversation.appendDelta(longText());
                fixture.tabs.setSelectedIndex(1);
                return null;
            });
            flushEdt();
            flushEdt();

            JScrollBar bar = onEdt(() -> fixture.conversation.getVerticalScrollBar());
            assertTrue(bar.getMaximum() > bar.getVisibleAmount(), "fixture must overflow its viewport");
            assertEquals(bar.getMaximum(), bar.getValue() + bar.getVisibleAmount(),
                         "showing a grown hidden tab must restore the bottom scroll position");
        }
        finally {
            onEdt(() -> {
                fixture.frame.dispose();
                return null;
            });
        }
    }

    @Test
    void showingAGrownHiddenTabKeepsScrollPositionWhenAutoScrollIsDisabled() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a real showing Swing hierarchy");
        Fixture fixture = onEdt(Fixture::new);
        try {
            int original = onEdt(() -> {
                fixture.conversation.addSystemMessage(longText());
                fixture.conversation.setAutoScrollToLatest(false);
                JScrollBar bar = fixture.conversation.getVerticalScrollBar();
                bar.setValue(0);
                return bar.getValue();
            });
            onEdt(() -> {
                fixture.conversation.beginAssistantMessage();
                fixture.tabs.setSelectedIndex(0);
                fixture.conversation.appendDelta(longText());
                fixture.tabs.setSelectedIndex(1);
                return null;
            });
            flushEdt();
            flushEdt();

            int value = onEdt(() -> fixture.conversation.getVerticalScrollBar().getValue());
            assertEquals(original, value, "hidden-tab growth must not scroll when auto-scroll is disabled");
        }
        finally {
            onEdt(() -> {
                fixture.frame.dispose();
                return null;
            });
        }
    }

    private static String longText() {
        return "scroll growth ".repeat(2_000);
    }

    private static void flushEdt() throws Exception {
        onEdt(() -> null);
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

    private static final class Fixture {

        private final JFrame frame = new JFrame();
        private final JTabbedPane tabs = new JTabbedPane();
        private final ConversationPanel conversation = new ConversationPanel();

        private Fixture() {
            tabs.addTab("Other", new JPanel());
            tabs.addTab("Conversation", conversation);
            tabs.setSelectedIndex(1);
            frame.setUndecorated(true);
            frame.setFocusableWindowState(false);
            frame.setAutoRequestFocus(false);
            frame.setContentPane(tabs);
            frame.setPreferredSize(new Dimension(320, 180));
            frame.pack();
            frame.setLocation(-10_000, -10_000);
            frame.setVisible(true);
        }
    }
}
