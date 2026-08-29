package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettingsKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AskUserQuestionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import org.openide.util.NbPreferences;

/**
 * Scrollable chat history panel. Contains a BoxLayout column of MessageRenderer instances. Supports streaming
 * (beginAssistantMessage / appendDelta / finaliseAssistantMessage) and restoring saved history.
 */
public class ConversationPanel extends JScrollPane {

    private static final Logger LOG = Logger.getLogger(ConversationPanel.class.getName());

    /**
     * Layout-settle polling. JEditorPane lays out asynchronously and takes an unpredictable number of passes, so
     * "scroll to the bottom" cannot be done in a fixed number of them — it has to watch the scrollbar maximum until it
     * stops growing.
     */
    private static final int SETTLE_POLL_MILLIS = (int) TimeoutEnum.SCROLL_SETTLE_POLL_MILLIS.millis();
    private static final int SETTLE_STABLE_TICKS = 5;
    /**
     * Hard stop, ~1.2s. Without it the poll's only exit is convergence, so a maximum that oscillated rather than
     * settled would pin the scrollbar 33 times a second for the life of the IDE.
     */
    private static final int SETTLE_MAX_TICKS = 40;

    private final JPanel inner;
    private MessagePanel activeAssistant;
    private final List<AiMessage> history = new ArrayList<>();
    private final PreferenceChangeListener fontPrefListener;
    private boolean fontPrefListenerRegistered = false;
    // View state, not a persisted setting — it belongs to the same class of thing as the scroll
    // position itself, which is also never saved. Defaults to on for every newly opened session.
    private boolean autoScrollToLatest = true;
    /**
     * Bumped to cancel an in-flight settle poll. Without it a poll started before the user switched auto-scroll off
     * would keep pinning the view to the bottom for up to a second afterwards, defeating the setting it was told to
     * respect.
     */
    private int scrollGeneration;
    /**
     * The settle poll currently running, so a new scroll can stop it outright. Streaming calls this once per delta, and
     * without a direct stop every superseded timer would stay alive until its own next tick noticed the generation had
     * moved on.
     */
    private Timer settleTimer;
    /**
     * Last observed size of the conversation content, for the grew-taller check. Width is tracked as well as height so
     * a WIDTH change can be told apart from new content: narrowing the window rewraps text and makes it taller, which
     * must not be mistaken for a message arriving.
     */
    private int lastContentHeight = -1;
    private int lastContentWidth = -1;

    public ConversationPanel() {
        inner = new ScrollablePanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setViewportView(inner);
        getVerticalScrollBar().setUnitIncrement(16);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_NEVER);
        setBorder(null);

        // Follow the bottom whenever the CONTENT grows.
        //
        // This reacts to the thing that actually matters — the conversation got taller — instead
        // of trying to predict when that happens. It covers a streaming delta, an async HTML
        // relayout finishing later, and layout running when a hidden tab is shown, all through
        // one event, with no pass counting and no polling.
        //
        // Two things must NOT trigger it, and testing for growth-at-constant-width excludes both:
        //   - trimming at the history cap SHRINKS the content, so it is not growth
        //   - resizing the window narrower rewraps text and makes it taller, but the width
        //     changes too, so it is not new content
        inner.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int width = inner.getWidth();
                int height = inner.getHeight();
                boolean sameWidth = width == lastContentWidth;
                boolean grew = height > lastContentHeight;
                lastContentWidth = width;
                lastContentHeight = height;
                if (sameWidth && grew && autoScrollToLatest) {
                    // Deferred, not immediate. This runs INSIDE a layout pass, and setting the
                    // scrollbar re-enters layout, so doing it here risks feeding the very resize
                    // that triggered it. Deferring also means the maximum is read AFTER layout
                    // has finished rather than partway through, which is the value we want.
                    SwingUtilities.invokeLater(() -> {
                        if (!autoScrollToLatest) {
                            return;
                        }
                        JScrollBar bar = getVerticalScrollBar();
                        bar.setValue(bar.getMaximum());
                    });
                }
            }
        });

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
        // Restoring history jumps to the newest message regardless of the auto-scroll setting:
        // this is session open, not a message arriving, and honouring the flag here would open a
        // restored session stranded in the middle of its own history.
        pinToBottomUntilSettled();
    }

    /**
     * Called when the user sends a prompt.
     */
    public void addUserMessage(String text) {
        addUserMessage(text, true);
    }

    /**
     * Adds a user-role message. {@code userInitiated} says whether a HUMAN caused it, which is what decides auto-scroll
     * — not the fact that it renders as a user message.
     * <p>
     * Not every user-role message comes from the user. Queued inbox notifications are submitted as a turn when the
     * previous one ends, and they travel this same path. Force-scrolling those would jump the view for mail arriving
     * while the user is reading history — the exact thing auto-scroll is switched off to prevent.
     */
    public void addUserMessage(String text, boolean userInitiated) {
        AiMessage m = AiMessage.user(text);
        history.add(m);
        MessagePanel r = new MessagePanel(AiMessage.Role.USER, false);
        r.appendDelta(text);
        r.finalise();
        inner.add(r);
        inner.revalidate();
        if (userInitiated) {
            forceScrollToBottom();
        }
        else {
            scrollToBottom();
        }
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
        // Follows unconditionally when auto-scroll is on. This used to consult a 40px
        // "near the end" guard, which had a compounding failure: any single delta that rendered
        // taller than 40px pushed the view out of range, and every LATER delta then declined to
        // scroll too — so one big chunk detached you from the bottom for the rest of the
        // message. A large code block arriving at once did it every time.
        //
        // That guard was a heuristic standing in for "does the user want to follow?" — a
        // question the auto-scroll toggle now answers outright. Keeping both let a guess
        // override a stated preference.
        //
        // No scroll call here. Appending text makes the bubble taller, and the content-resize
        // listener installed in the constructor follows that growth — including the part of it
        // that lands later, when the HTML finishes laying out. Scrolling here as well would be a
        // second mechanism racing the first, which is how the two-pass bug happened.
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
        // The authoritative scroll for a streamed message. Streaming used the cheap per-delta
        // follow to avoid polling for the length of the response; finalise() re-renders the
        // bubble into its final form, so this is where the height actually settles and where it
        // is worth waiting for it.
        scrollToBottom();
    }

    /**
     * Remove oldest messages beyond the cap.
     */
    private void enforceHistoryCap() {
        int cap = PluginSettings.getMaxHistory();
        if (cap <= 0) {
            return;
        }
        int removedHeight = 0;
        while (history.size() > cap) {
            history.remove(0);
            // Measure before removing — a detached component reports height 0.
            removedHeight += inner.getComponent(0).getHeight();
            inner.remove(0);
        }
        inner.revalidate();
        inner.repaint();
        if (removedHeight > 0) {
            compensateForTrimmedHeight(removedHeight);
        }
    }

    /**
     * Shifts the scrollbar up by the height just deleted from the TOP of the transcript, so the message the user is
     * reading stays where it is on screen.
     * <p>
     * At the history cap every new message deletes the oldest one. Everything below it moves up by that message's
     * height, but the scrollbar value does not change — so the same pixel offset now lands on different text and the
     * transcript appears to crawl upwards under the reader. The scroll position was never wrong; the content moved
     * beneath it.
     * <p>
     * Clamped at 0: once the top is reached there is nothing left to compensate with, and the view simply stays at the
     * top as older messages disappear.
     * <p>
     * ONLY when auto-scroll is off. With it on the user is pinned to the bottom, and trimming lowers the scrollbar's
     * maximum, which Swing already clamps the value to — the bottom position is preserved for free. Subtracting the
     * height as well would then drag the view UP off the bottom by exactly one message, which is the opposite of what
     * auto-scroll promises.
     */
    private void compensateForTrimmedHeight(int removedHeight) {
        if (autoScrollToLatest) {
            return;
        }
        JScrollBar sb = getVerticalScrollBar();
        sb.setValue(Math.max(0, sb.getValue() - removedHeight));
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
     * Render an AskUserQuestion tool call inline. The QuestionPanel fires event.response() when the user submits, which
     * unblocks the MCP server.
     */
    public void showQuestion(AskUserQuestionEvent event) {
        QuestionPanel qp = new QuestionPanel(event);
        qp.setAlignmentX(Component.LEFT_ALIGNMENT);
        inner.add(qp);
        inner.revalidate();
        // Unconditional: this BLOCKS the AI until the user answers. Auto-scroll off means "stop
        // dragging me to content I did not ask for", not "hide the thing that is waiting on me"
        // — a question parked off-screen makes the session look hung.
        forceScrollToBottom();
    }

    /**
     * Render a ConfirmEvent inline. The ConfirmPanel completes event.response() when the user clicks Yes or No, which
     * unblocks the MCP tool thread.
     */
    public void showConfirm(ConfirmEvent event) {
        ConfirmPanel cp = new ConfirmPanel(event);
        cp.setAlignmentX(Component.LEFT_ALIGNMENT);
        inner.add(cp);
        inner.revalidate();
        // Unconditional, same reasoning as showQuestion: this blocks an MCP tool thread until
        // the user clicks Yes or No, so it must be visible.
        forceScrollToBottom();
    }

    /**
     * Render the main-panel affordance for a multi-file change set inline, using the same widget a ConfirmEvent gets.
     * Completing the supplied future with allow starts stepping through the per-file diffs; deny declines the whole set
     * without opening any, so a change the user has already decided against costs one click rather than N.
     */
    public void showMultiConfirm(String prompt, CompletableFuture<PermissionDecision> response) {
        ConfirmPanel cp = new ConfirmPanel(prompt, "Accept Diffs", "Reject", response);
        cp.setAlignmentX(Component.LEFT_ALIGNMENT);
        inner.add(cp);
        inner.revalidate();
        // Unconditional, same reasoning as showConfirm: this blocks the AI until the user
        // decides, so it must be visible.
        forceScrollToBottom();
    }

    /**
     * Add a system notification to the conversation with orange indicator. Stored in history and persists when
     * saved/restored. Callers must finalise any active assistant message before calling this method.
     */
    public void addSystemMessage(String text) {
        if (activeAssistant != null && PluginSettings.isDebugJson()) {
            // The contract above is easy to break silently: the panel lands after
            // a bubble that keeps growing, and nothing complains. Finalising here
            // instead is NOT an option — assistantTurnActive lives on
            // AiTopComponent, so clearing activeAssistant behind its back leaves
            // the two out of step and appendDelta() then discards the rest of the
            // response. Naming the offender is the most this class can safely do.
            LOG.log(Level.WARNING,
                    "addSystemMessage called while an assistant message is still streaming — "
                    + "caller must finalise first or the transcript order will be wrong: {0}", text);
        }
        AiMessage m = AiMessage.system(text);
        history.add(m);
        MessagePanel r = new MessagePanel(AiMessage.Role.SYSTEM, false);
        r.appendDelta(text);
        r.finalise();
        inner.add(r);
        inner.revalidate();
        scrollToBottom();
    }

    /**
     * Turns automatic scrolling to the newest message on or off for this session's view. Off leaves the scroll position
     * exactly where the user put it, whatever arrives.
     * <p>
     * Restoring saved history at session open is deliberately NOT governed by this: that is not "a new message
     * arrived", and honouring the flag there would open a restored session stranded in the middle of its own history.
     */
    public void setAutoScrollToLatest(boolean newAutoScrollToLatest) {
        boolean turningOn = newAutoScrollToLatest && !this.autoScrollToLatest;
        this.autoScrollToLatest = newAutoScrollToLatest;
        if (!newAutoScrollToLatest) {
            // Cancel any settle poll already running, or it would keep pinning the view to the
            // bottom for up to a second after the user asked it to stop.
            scrollGeneration++;
            stopSettleTimer();
        }
        // Catch up immediately on the OFF -> ON transition. Without this the view stays wherever
        // the user left it until the next message happens to arrive, which reads as the toggle
        // having done nothing.
        //
        // Guarded on the transition rather than on the new value: a caller setting true when it
        // is already true must not yank the view, or any future code that re-asserts the current
        // state would silently steal the user's scroll position.
        if (turningOn) {
            forceScrollToBottom();
        }
    }

    public boolean isAutoScrollToLatest() {
        return autoScrollToLatest;
    }

    /**
     * Scrolls to the newest message unless the user has switched auto-scroll off. Use this for anything the AI
     * initiates.
     */
    private void scrollToBottom() {
        if (!autoScrollToLatest) {
            return;
        }
        forceScrollToBottom();
    }

    /**
     * Scrolls to the newest message REGARDLESS of the auto-scroll setting. Two kinds of caller qualify:
     * <ul>
     * <li>The user just acted — sending a message, or switching auto-scroll back on. They asked for something and
     * expect to see the result; a parked view reads as the click having done nothing.</li>
     * <li>Something is WAITING on the user — a question or a confirm prompt. These block the AI or an MCP tool thread
     * until answered, so hiding them makes the session look hung.</li>
     * </ul>
     * Everything else — assistant text, system notices, inbox notifications — goes through {@link #scrollToBottom()}
     * and respects the setting.
     */
    private void forceScrollToBottom() {
        pinToBottomUntilSettled();
    }

    /**
     * Pins the view to the bottom, re-pinning until the scrollbar maximum stops growing.
     * <p>
     * A fixed number of layout passes does NOT work here. This used to do exactly two, on the reasoning that the first
     * lays out the new message and the second can then read a maximum that includes it. That is right often enough to
     * look correct and wrong whenever layout needs a third — most visibly when the session's tab is not the one on
     * screen, where the pass count differs entirely. {@code restoreHistory} had already reached the opposite conclusion
     * and polled until settled; the two strategies contradicted each other in the same file, and this is the one that
     * was wrong.
     * <p>
     * The old two-pass version also had an accidental safety net: the message pane's caret dragged the viewport down a
     * moment later and finished the job. That side-effect has been removed because it ignored the auto-scroll setting,
     * which is what exposed this.
     */
    private void pinToBottomUntilSettled() {
        final int generation = ++scrollGeneration;
        stopSettleTimer();
        SwingUtilities.invokeLater(() -> {
            if (generation != scrollGeneration) {
                return;
            }
            validate();
            JScrollBar sb = getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
            int[] stableTicks = {0};
            int[] lastMax = {-1};
            int[] ticks = {0};
            Timer timer = new Timer(SETTLE_POLL_MILLIS, null);
            timer.addActionListener(e -> {
                // A newer scroll, or the user switching auto-scroll off, supersedes this poll.
                if (generation != scrollGeneration) {
                    ((Timer) e.getSource()).stop();
                    return;
                }
                validate();
                int max = sb.getMaximum();
                sb.setValue(max);
                if (++ticks[0] >= SETTLE_MAX_TICKS) {
                    ((Timer) e.getSource()).stop();
                    return;
                }
                if (max == lastMax[0]) {
                    if (++stableTicks[0] >= SETTLE_STABLE_TICKS) {
                        ((Timer) e.getSource()).stop();
                    }
                }
                else {
                    stableTicks[0] = 0;
                    lastMax[0] = max;
                }
            });
            settleTimer = timer;
            timer.start();
        });
    }

    private void stopSettleTimer() {
        if (settleTimer != null) {
            settleTimer.stop();
            settleTimer = null;
        }
    }

    /**
     * Tracks viewport width so the BoxLayout constrains its children (JEditorPane) to the available width rather than
     * letting them grow unbounded horizontally.
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
