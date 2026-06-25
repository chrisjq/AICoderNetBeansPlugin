package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.PreferenceChangeListener;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import static javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AskUserQuestionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettingsKeyEnum;
import org.openide.util.NbPreferences;

/**
 * Scrollable chat history panel. Contains a BoxLayout column of MessageRenderer
 * instances. Supports streaming (beginAssistantMessage / appendDelta /
 * finaliseAssistantMessage) and restoring saved history.
 */
public class ConversationPanel extends JScrollPane {

    private final JPanel inner;
    private MessagePanel activeAssistant;
    private final List<AiMessage> history = new ArrayList<>();
    private final PreferenceChangeListener fontPrefListener;
    private boolean fontPrefListenerRegistered = false;

    public ConversationPanel() {
        inner = new ScrollablePanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setViewportView(inner);
        getVerticalScrollBar().setUnitIncrement(16);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_NEVER);
        setBorder(null);

        fontPrefListener = evt -> {
            if (PluginSettingsKeyEnum.CHAT_FONT_SIZE.key().equals(evt.getKey())) {
                SwingUtilities.invokeLater(this::rebuildAllMessages);
            }
        };
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (!fontPrefListenerRegistered) {
            NbPreferences.forModule(PluginSettings.class).addPreferenceChangeListener(fontPrefListener);
            fontPrefListenerRegistered = true;
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        if (fontPrefListenerRegistered) {
            NbPreferences.forModule(PluginSettings.class).removePreferenceChangeListener(fontPrefListener);
            fontPrefListenerRegistered = false;
        }
    }

    private void rebuildAllMessages() {
        for (Component c : inner.getComponents()) {
            if (c instanceof MessagePanel r) {
                r.rebuildContent();
            }
        }
        inner.revalidate();
        inner.repaint();
    }

    /**
     * Restore saved messages (called at startup if history exists).
     */
    public void restoreHistory(List<AiMessage> messages) {
        activeAssistant = null;
        history.clear();
        inner.removeAll();
        for (AiMessage m : messages) {
            history.add(m);
            inner.add(MessagePanel.forRestored(m));
        }
        inner.revalidate();
        // JEditorPane layout is async and takes an unpredictable number of passes.
        // Poll every 30ms until the scrollbar maximum stops growing, then stop.
        SwingUtilities.invokeLater(() -> {
            validate();
            JScrollBar sb = getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
            Timer timer = new Timer(30, null);
            int[] stableCount = {0};
            int[] lastMax = {-1};
            timer.addActionListener(e -> {
                validate();
                int max = sb.getMaximum();
                sb.setValue(max);
                if (max == lastMax[0]) {
                    if (++stableCount[0] >= 5) {
                        ((Timer) e.getSource()).stop();
                    }
                }
                else {
                    stableCount[0] = 0;
                    lastMax[0] = max;
                }
            });
            timer.start();
        });
    }

    /**
     * Called when the user sends a prompt.
     */
    public void addUserMessage(String text) {
        AiMessage m = AiMessage.user(text);
        history.add(m);
        MessagePanel r = new MessagePanel(AiMessage.Role.USER, false);
        r.appendDelta(text);
        r.finalise();
        inner.add(r);
        inner.revalidate();
        scrollToBottom();
    }

    /**
     * Called when the AI starts streaming a new response.
     */
    public void beginAssistantMessage() {
        if (activeAssistant != null) {
            finaliseAssistantMessage();
        }
        activeAssistant = new MessagePanel(AiMessage.Role.ASSISTANT, false);
        inner.add(activeAssistant);
        inner.revalidate();
        scrollToBottom();
    }

    /**
     * Called for each streaming delta from the AI.
     */
    public void appendDelta(String delta) {
        if (activeAssistant == null) {
            return;
        }
        activeAssistant.appendDelta(delta);
        scrollToBottomIfNearEnd();
    }

    /**
     * Called when the AI's response is complete.
     */
    public void finaliseAssistantMessage() {
        if (activeAssistant == null) {
            return;
        }
        activeAssistant.finalise();
        String text = activeAssistant.getAccumulatedText();
        history.add(AiMessage.assistant(text));
        activeAssistant = null;
        enforceHistoryCap();
    }

    /**
     * Remove oldest messages beyond the cap.
     */
    private void enforceHistoryCap() {
        int cap = PluginSettings.getMaxHistory();
        if (cap <= 0) {
            return;
        }
        while (history.size() > cap) {
            history.remove(0);
            inner.remove(0);
        }
        inner.revalidate();
        inner.repaint();
    }

    /**
     * Returns a snapshot of the current conversation history.
     */
    public List<AiMessage> getHistory() {
        return List.copyOf(history);
    }

    /**
     * Remove all messages.
     */
    public void clear() {
        history.clear();
        activeAssistant = null;
        inner.removeAll();
        inner.revalidate();
        inner.repaint();
    }

    /**
     * Render an AskUserQuestion tool call inline. The QuestionPanel fires
     * event.response() when the user submits, which unblocks the MCP server.
     */
    public void showQuestion(AskUserQuestionEvent event) {
        QuestionPanel qp = new QuestionPanel(event);
        qp.setAlignmentX(Component.LEFT_ALIGNMENT);
        inner.add(qp);
        inner.revalidate();
        scrollToBottom();
    }

    /**
     * Add a system notification to the conversation with orange indicator.
     * Stored in history and persists when saved/restored. Callers must finalise
     * any active assistant message before calling this method.
     */
    public void addSystemMessage(String text) {
        AiMessage m = AiMessage.system(text);
        history.add(m);
        MessagePanel r = new MessagePanel(AiMessage.Role.SYSTEM, false);
        r.appendDelta(text);
        r.finalise();
        inner.add(r);
        inner.revalidate();
        scrollToBottom();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
    }

    /**
     * Only auto-scroll during streaming if the user hasn't scrolled up
     * manually.
     */
    private void scrollToBottomIfNearEnd() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = getVerticalScrollBar();
            int distanceFromBottom = sb.getMaximum() - sb.getVisibleAmount() - sb.getValue();
            if (distanceFromBottom <= 40) {
                sb.setValue(sb.getMaximum());
            }
        });
    }

    /**
     * Tracks viewport width so the BoxLayout constrains its children
     * (JEditorPane) to the available width rather than letting them grow
     * unbounded horizontally.
     */
    private static class ScrollablePanel extends JPanel implements Scrollable {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
