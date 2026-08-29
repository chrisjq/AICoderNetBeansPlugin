package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Window;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.MailDeliveryTimingEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiInboxMessageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AskUserQuestionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.MultiPermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.MultiPermissionItem;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.SystemNotificationEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.NotificationTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.permission.MultiPermissionReview;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSessionCallback;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.events.AiInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.events.DiffDecisionListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.GlobalPropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.PromptHistory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TmpMarkerExpander;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FileUtils;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.RefactoringProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.HistoryPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.HistoryPersistenceManager.LoadedHistory;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.NotificationUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.ProjectPathUtil;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.util.RequestProcessor;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

@TopComponent.Description(
        preferredID = "AiTopComponent",
        persistenceType = TopComponent.PERSISTENCE_NEVER
)
public final class AiTopComponent extends TopComponent implements AiProcessEventListener, SessionLifecycleSource, AiSessionHost, ExecutablePrompter {

    private static final Logger LOG = Logger.getLogger(AiTopComponent.class.getName());
    // Shared across every open tab. Was single-threaded until start() moved onto
    // it in full (including its up-to-2-minute MCP registration wait) — a fixed
    // pool lets several tabs' start()/loadHistory() chains run concurrently again.
    // Safe to parallelize because ordering *within* one tab's own chain is now
    // enforced explicitly, not by relying on single-thread submission order — see
    // the CompletableFuture threaded through startAiProcess()/loadHistory().
    // Volatile, not final: shutdownPersistExecutor() retires it at plugin shutdown
    // and resetPersistExecutorForTests() swaps in a fresh pool between tests.
    private static volatile ExecutorService PERSIST_EXECUTOR = newPersistExecutor();

    // Tab status circle:
    //   green  = ready / idle (AI is running and waiting for user input),
    //   orange = AI is thinking (a turn is in flight),
    //   white  = blocked waiting on user (confirm / question / diff panel on screen),
    //   red    = not running — the plugin/tab just started, or a fatal error
    //            occurred (auth failure, abnormal process exit).
    // The HTML tab-name dot is driven from setTabStatus() via tabStatusColor().
    // Each status has exactly one Color constant below; the hex string is
    // derived from it so the two can never go out of sync.
    private static final Color STATUS_COLOR_GREEN = new Color(0x4C, 0xAF, 0x50);
    private static final Color STATUS_COLOR_ORANGE = new Color(0xFF, 0x98, 0x00);
    private static final Color STATUS_COLOR_RED = new Color(0xF4, 0x43, 0x36);
    // White for "blocked waiting on user" (confirm / question / diff panel).
    // If this renders badly on a light theme (invisible or off-white), change
    // only this one constant — the hex string below derives from it.
    private static final Color STATUS_COLOR_WHITE = new Color(0xFF, 0xFF, 0xFF);
    // Briefly shown in place of orange each time AI output arrives while the
    // tab is THINKING. See flashThinking(). A paler gold (0xFFC855) rendered
    // black in the tab strip in testing — magenta is confirmed to render
    // correctly, so it's used here even though it's a more assertive colour
    // than the other status dots.
    private static final Color THINKING_FLASH_COLOR = new Color(0xFF, 0x00, 0xFF);

    private static final String STATUS_HEX_GREEN = toHex(STATUS_COLOR_GREEN);
    private static final String STATUS_HEX_ORANGE = toHex(STATUS_COLOR_ORANGE);
    private static final String STATUS_HEX_RED = toHex(STATUS_COLOR_RED);
    private static final String STATUS_HEX_WHITE = toHex(STATUS_COLOR_WHITE);
    private static final String STATUS_HEX_ORANGE_LIGHT = toHex(THINKING_FLASH_COLOR);

    /**
     * Duration of the "AI output received" flash pulse, in milliseconds.
     */
    private static final int THINKING_FLASH_MS = (int) TimeoutEnum.THINKING_FLASH_MILLIS.millis();

    // Height (px) of the resizable input region below the infobar — used as both
    // its minimum and its initial size, so the window always opens at the
    // minimum. Set here in one place to adjust the input area size.
    private static final int INPUT_AREA_HEIGHT = 80;
    /**
     * The one explanation of a mail interrupt, shared by both delivery paths so their wording cannot drift.
     *
     * <p>
     * Deliberately says nothing about whether the assistant has already read the message: the empty-queue path runs
     * because it read the mail itself, the flush path runs with the mail appended after this text, and this sentence
     * has to be true in both.</p>
     *
     * <p>
     * The second paragraph is the harder half. An aborted call MAY ALREADY HAVE TAKEN EFFECT — the interrupt can land
     * after the call did its work, so "rejected" describes the interrupt, not the outcome. Observed twice in one day: a
     * SendAiMessage reported as rejected WAS delivered, and the session told the user it had never been sent, which
     * cost a round trip to undo. "Rejected" reads as "it did not happen", so the notice has to say outright that it may
     * have.</p>
     */
    private static final String INBOX_INTERRUPT_EXPLANATION
            = "Your turn was interrupted so an inbox message could reach you. That interrupt is what aborted any tool "
            + "call or task that was in flight — NOT a rejection, cancellation, or refusal by the user. Do not tell the user "
            + "they declined or rejected anything.\n\n"
            + "IMPORTANT: a tool call reported to you as rejected or cancelled MAY HAVE ALREADY RUN. Check its result. Read your inbox and resume your work.";
    /**
     * @param userInitiated false when the plugin submits a turn on the user's behalf — currently the
     * queued-inbox-notification flush at turn end. Only the auto-scroll decision depends on it: a turn the user did not
     * ask for must not drag their view to the bottom.
     */
    /**
     * DELIMITS the agent-only block inside a prompt the PLUGIN composed, so what the assistant sees is marked by
     * WRAPPING rather than by position.
     *
     * <p>
     * A positional cut was the earlier design and it had a real bug: whether text was visible was decided before
     * deferred inbox notifications were appended, so a notice-only turn that arrived with mail queued dropped that mail
     * from the prompt AND from the transcript. Repairing that flag would have fixed the instance and left the shape
     * fragile — anyone appending to the visible text afterwards would silently have to know the cut was positional. A
     * delimited block cannot be broken by appending, wherever the appending happens.</p>
     *
     * <p>
     * THE UI NEVER SEARCHES FOR THESE TAGS, and nothing is ever stripped. The split is by PROVENANCE: agent-only text
     * arrives as a separate parameter to {@link #handleSubmit(String, boolean, String)}, the transcript is rendered
     * from the visible variable, and the two are never the same string. The tags exist only in the AGENT-FACING prompt,
     * to tell the model where its system block begins and ends.</p>
     *
     * <p>
     * That is why a collision is harmless, and why MALFORMED IS NOT A CASE THAT CAN ARISE. A user who types
     * "&lt;SYSTEM&gt;" has their message rendered in full — their text is never the {@code agentOnlyText} parameter. An
     * assistant that writes it while discussing this feature — which this session has done repeatedly today — is
     * rendered in full too, because assistant output travels a different path and is not subject to this at all. An
     * unclosed or nested tag cannot hide anything either: the user's view is BUILT from the visible text, never DERIVED
     * by removing a block from a combined string, so there is no parse to go wrong. This fails toward showing by
     * construction rather than by a rule someone has to remember.</p>
     */
    static final String SYSTEM_BLOCK_OPEN = "<SYSTEM>";
    static final String SYSTEM_BLOCK_CLOSE = "</SYSTEM>";

    private static ExecutorService newPersistExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ai-session-persist");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Retires the shared persist pool at plugin shutdown: lets already-queued history/session saves finish inside a
     * bounded window ({@link TimeoutEnum#PERSIST_EXECUTOR_SHUTDOWN_WAIT_MILLIS}) rather than discarding them, then
     * releases the worker threads. Without this the four core threads never terminate, and after a disable/uninstall
     * without an IDE restart they pin the module's classloader for the rest of the IDE run. Called only by the module
     * installer's shutdown path.
     */
    public static void shutdownPersistExecutor() {
        ExecutorService pool = PERSIST_EXECUTOR;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(TimeoutEnum.PERSIST_EXECUTOR_SHUTDOWN_WAIT_MILLIS.millis(),
                    TimeUnit.MILLISECONDS)) {
                LOG.warning("Session-persist tasks still running at plugin shutdown; abandoning the bounded wait");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The live shared pool (test aid).
     */
    static ExecutorService persistExecutor() {
        return PERSIST_EXECUTOR;
    }

    /**
     * Replaces a retired pool with a fresh one so later tests sharing this JVM still have a working executor (test aid
     * — production shuts down exactly once, at plugin shutdown).
     */
    static void resetPersistExecutorForTests() {
        PERSIST_EXECUTOR = newPersistExecutor();
    }

    private static String toHex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    // --- end AiSessionHost ---
    private static String appendDecisionMessage(String base, String message) {
        if (message == null || message.isBlank()) {
            return base;
        }
        return base + "\n\nUser note: " + message.trim();
    }

    /**
     * Pure form of {@link #confirmLabel(ConfirmEvent)}, with path shortening already applied, so the fallback rule can
     * be tested without a running IDE.
     *
     * @param shortSrc shortened source path, or null for a non-file confirm
     * @param shortTgt shortened target path, or null when there is no target
     * @param displayText the event's own description of what is being approved
     */
    static String buildConfirmLabel(String shortSrc, String shortTgt, String displayText) {
        if (shortSrc == null || shortSrc.isBlank()) {
            return displayText != null && !displayText.isBlank() ? displayText : "(no details)";
        }
        return shortTgt != null ? shortSrc + " → " + shortTgt : shortSrc;
    }

    /**
     * Whether auto-accept may answer this confirmation on the user's behalf.
     *
     * <p>
     * Auto-accept means "approve things I can see", never "approve things nobody could identify": a request that
     * arrives without an identifiable subject, or one whose consequences warrant a human every time, sets
     * {@link ConfirmEvent#requireExplicitApproval()} and is prompted regardless of the setting. Copilot's shell path
     * sets it — the alternative was a command being run unseen, as happened when an unclassified kind was approved
     * silently and wrote an arbitrary script to /tmp.
     *
     * <p>
     * Pure so the rule can be tested without a running IDE.
     */
    static boolean shouldAutoAccept(ConfirmEvent event, boolean autoAccept) {
        return autoAccept && !event.requireExplicitApproval();
    }

    /**
     * Tooltip for the auto-scroll toggle, reporting the state it is currently IN rather than the one clicking would
     * move it to. Both readings are common in toolbars, so the wording says which it means outright.
     */
    private static String autoScrollTooltip(boolean enabled) {
        return "Auto Scroll - " + (enabled ? "Enabled" : "Disabled");
    }

    /**
     * Pure colour mapping for the tab status dot — the single source of truth, delegated to by
     * {@link #tabStatusColor()}. Package-private and static so it can be unit-tested without the NetBeans window
     * system.
     *
     * <p>
     * Takes the enum rather than its name so the switch stays exhaustive: adding a {@code TabStatus} without a colour
     * is then a compile error rather than a runtime one. Do not add a {@code default} branch — it would defeat exactly
     * that check.
     */
    static String resolvedTabStatusColor(TabStatus status, boolean flashActive) {
        return switch (status) {
            case READY ->
                STATUS_HEX_GREEN;
            case THINKING ->
                flashActive ? STATUS_HEX_ORANGE_LIGHT : STATUS_HEX_ORANGE;
            case FATAL ->
                STATUS_HEX_RED;
            case AWAITING_USER ->
                STATUS_HEX_WHITE;
        };
    }

    private final ConversationPanel conversationPanel;
    /**
     * Set when an inbox message lands while a turn is in flight, so the interruption it causes can be explained
     * afterwards even if the assistant reads the message itself and leaves the pending-notification queue empty. See
     * {@code explainInboxInterruptIfNeeded}.
     */
    private volatile boolean mailArrivedDuringTurn;
    private final AiInfoBar infoBar;
    private final AiInputField inputField;
    private final JButton sendButton;
    private final PromptHistory promptHistory;
    private final JLabel contextLabel;
    private volatile AiImplementation aiBackend;
    private AiInfoBarExtension infoBarExtension;
    private ContextProvider contextProvider;
    private HistoryPersistenceManager historyManager;
    private File chosenSessionDir;
    private PropertyChangeListener openProjectsListener;

    /**
     * Tracks whether an assistant turn is currently streaming
     */
    private boolean assistantTurnActive = false;

    /**
     * Set when the user clicks Stop. Suppresses any buffered TextDeltaEvent or ToolUseEvent that arrive via invokeLater
     * after cancellation. Cleared when the cancelled turn is officially done (STOPPED status or TurnCompleteEvent).
     */
    private boolean cancelledThisTurn = false;

    /**
     * Set when a non-text event (tool use, permission) interrupts a streaming turn, so the next text block gets a blank
     * line separator before it.
     */
    private boolean pendingNewlineBeforeText = false;
    private boolean turnOutputSuppressed = false;
    private String suppressedTurnCompletionMessage = null;

    /**
     * Pre-prompt snapshot of the active file — used to show a diff after the AI edits it. The stream-json format does
     * not emit tool_use events for internally-executed tools, so we detect edits by comparing disk content before/after
     * each turn.
     */
    private String preEditFilePath = null;
    private String preEditFileContent = null;
    private boolean diffShownForCurrentTurn = false;

    private AiPropertyListener aiTypePropertyListener;
    private AiPropertyListener globalPropertyListener;

    private final List<SessionLifecycleListener> lifecycleListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Queued text to send once AI finishes starting up after an auto-restart.
     */
    private String pendingSubmitText = null;

    /**
     * False until the first startup attempt resolves to READY or a fatal startup error. While false, the visible chat
     * input/send controls stay disabled so the user cannot submit a prompt against a still-loading backend.
     */
    private boolean startupResolved = false;

    /**
     * Outstanding AskUserQuestion/Permission cancellers and open diff windows. Multiple can be in flight at once (e.g.
     * AskUserQuestion overlapping a Permission), so track all and complete/close every one on teardown. EDT-confined —
     * all access is on the event dispatch thread.
     */
    private final Set<Runnable> pendingResponseCancellers
            = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<AiDiffTopComponent> openDiffs
            = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private final AiSession session;
    private final List<AbstractNotification> pendingNotifications = new ArrayList<>();
    private final List<AbstractNotification> deferredNotifications = new ArrayList<>();
    private final SessionPersistenceManager sessionPersistenceManager;

    // Starts red: nothing is running until the AI process reports READY. Moves to
    // green/orange via setSendEnabled(), and back to red on a fatal error.
    private volatile TabStatus tabStatus = TabStatus.FATAL;

    // Set while the THINKING dot is showing its brief yellow "flash" pulse
    // (see flashThinking()). Only ever read/written on the EDT.
    private boolean thinkingFlashActive = false;
    // Single-shot timer backing the flash pulse; lazily created, reused, and
    // restarted to the latest requested deadline so a burst of AI output just
    // extends the pulse instead of stacking work or flickering.
    private Timer thinkingFlashTimer;
    // Monotonic deadline for when the current flash should end. Updated from
    // any thread; the EDT reads it when (re)arming thinkingFlashTimer.
    private volatile long thinkingFlashDeadlineNanos = 0L;
    // Guards the single coalesced invokeLater used when non-EDT callers request
    // a flash extension while one EDT update is already pending.
    private final AtomicBoolean thinkingFlashRequestQueued = new AtomicBoolean(false);

    private volatile boolean skipClosePrompt = false;
    /**
     * Last tab label actually pushed to the window system, so {@link #updateTabHtmlName()} can skip identical re-sets.
     * EDT-only, like the tab state it mirrors — no synchronisation needed.
     */
    private String lastTabHtml = null;

    public AiTopComponent(AiSession session, SessionPersistenceManager sessionPersistenceManager) {
        this.session = session;
        this.sessionPersistenceManager = sessionPersistenceManager;
        setName(session.name());
        setDisplayName(session.name());
        updateTabTooltip();
        setLayout(new BorderLayout());

        conversationPanel = new ConversationPanel();
        promptHistory = new PromptHistory();
        infoBar = new AiInfoBar();
        inputField = new AiInputField(promptHistory, session);
        contextLabel = new JLabel("No file open");
        contextLabel.setFont(contextLabel.getFont().deriveFont(11f));
        contextLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> {
            String text = inputField.getPromptText();
            if (!text.isEmpty()) {
                inputField.clear();
                handleSubmit(text);
            }
        });

        inputField.setSubmitCallback(this::handleSubmit);
        inputField.setPasteErrorCallback(conversationPanel::addSystemMessage);
        inputField.setEnabled(false);
        inputField.setCanSend(false);
        sendButton.setEnabled(false);
        infoBar.setSaveHistory(session.settings() != null
                ? session.settings().effectiveSaveHistory() : PluginSettings.isSaveHistory());
        infoBar.setAutoAccept(session.settings() != null
                ? session.settings().effectiveAutoAccept()
                : kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings.isAutoAccept());
        infoBar.addListener(new AiInfoBarListener() {
            @Override
            public void onStopRequested() {
                cancelCurrentRequest();
            }

            @Override
            public void onSettingsRequested() {
                openSessionConfig();
            }

            @Override
            public void onAutoAcceptChanged(boolean autoAccept) {
                session.settings().setAutoAccept(autoAccept);
                updateSessionSettings(session.settings());
            }

            @Override
            public void onSaveHistoryChanged(boolean saveHistory) {
                session.settings().setSaveHistory(saveHistory);
                updateSessionSettings(session.settings());
            }
        });

        add(contextLabel, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new BorderLayout());
        Color sepColor = UIManager.getColor("Separator.foreground");
        if (sepColor == null) {
            sepColor = UIManager.getColor("controlShadow");
        }
        if (sepColor == null) {
            sepColor = new Color(0x45, 0x47, 0x5a);
        }
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, sepColor));
        bottom.add(infoBar, BorderLayout.NORTH);

        // Minimum and initial height come from INPUT_AREA_HEIGHT (preferred ==
        // minimum), so the window always opens at the minimum size.
        JScrollPane inputScrollPane = new JScrollPane(inputField,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inputScrollPane.setMinimumSize(new Dimension(0, INPUT_AREA_HEIGHT));
        inputScrollPane.setPreferredSize(new Dimension(0, INPUT_AREA_HEIGHT));

        JButton configButton = new JButton("⚙");
        configButton.setToolTipText("Session configuration");
        configButton.setMargin(new Insets(0, 4, 0, 4));
        configButton.addActionListener(e -> openSessionConfig());

        // On by default for a freshly opened session, which is what it has always done — the
        // toggle exists to let the user stop it, not to change the starting behaviour.
        //
        // The initial state is READ FROM the panel rather than hardcoded here. Two independent
        // "true"s would be two defaults that merely happen to agree; change one and the button
        // silently misreports what the panel is actually doing.
        JToggleButton autoScrollButton
                = new JToggleButton("⬇", conversationPanel.isAutoScrollToLatest());
        autoScrollButton.setToolTipText(autoScrollTooltip(autoScrollButton.isSelected()));
        autoScrollButton.setMargin(new Insets(0, 4, 0, 4));
        autoScrollButton.setFocusable(false);
        autoScrollButton.addActionListener(e -> {
            boolean enabled = autoScrollButton.isSelected();
            conversationPanel.setAutoScrollToLatest(enabled);
            autoScrollButton.setToolTipText(autoScrollTooltip(enabled));
        });

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.add(inputScrollPane, BorderLayout.CENTER);
        JPanel eastPanel = new JPanel(new BorderLayout());
        // BorderLayout rather than FlowLayout so the two buttons sit at OPPOSITE ends of the
        // strip instead of bunching together. The strip is added to eastPanel's NORTH, so it
        // spans the full width of the Send button below it — which makes ⚙ line up with Send's
        // left edge and the auto-scroll toggle with its right.
        JPanel buttonStrip = new JPanel(new BorderLayout(0, 0));
        buttonStrip.add(configButton, BorderLayout.WEST);
        buttonStrip.add(autoScrollButton, BorderLayout.EAST);
        eastPanel.add(buttonStrip, BorderLayout.NORTH);
        eastPanel.add(sendButton, BorderLayout.CENTER);
        inputRow.add(eastPanel, BorderLayout.EAST);
        bottom.add(inputRow, BorderLayout.CENTER);
        // Minimum keeps the infobar fully visible and the stacked ⚙/Send column
        // usable — dragging the divider down must not crush the buttons, so the
        // floor is the taller of one input row and the east button column.
        bottom.setMinimumSize(new Dimension(0,
                infoBar.getPreferredSize().height
                + Math.max(INPUT_AREA_HEIGHT, eastPanel.getPreferredSize().height)));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                conversationPanel, bottom);
        splitPane.setResizeWeight(1.0);
        splitPane.setContinuousLayout(true);
        splitPane.setOneTouchExpandable(false);
        add(splitPane, BorderLayout.CENTER);

        this.aiBackend = new AiTypeRegistry().create(session.aiType(), this, this);
        AiInfoBarExtension ext = this.aiBackend.createInfoBarExtension(session, this);
        if (ext != null) {
            infoBar.setExtension(ext);
            this.infoBarExtension = ext;
        }

        session.setAiSessionCallback(new AiSessionCallback() {
            @Override
            public boolean isRunning() {
                return aiBackend != null && aiBackend.isProcessing();
            }

            @Override
            public void requestGracefulInterrupt(InterruptTypeEnum type) {
                if (type == InterruptTypeEnum.Mail) {
                    mailArrivedDuringTurn = true;
                }
                if (aiBackend != null) {
                    aiBackend.interrupt(type);
                }
            }

            @Override
            public void deliverIncomingMessage(String fromSessionId, AbstractNotification notification) {
                synchronized (AiTopComponent.this) {
                    boolean autoNotify = session.settings() != null && session.settings().effectiveAutoNotifyInbox();
                    if (autoNotify) {
                        pendingNotifications.add(notification);
                        SwingUtilities.invokeLater(() -> flushPendingNotifications());
                    }
                    else {
                        deferredNotifications.add(notification);
                    }
                }
            }

            @Override
            public void applyDescriptionUpdate(String description) {
                SwingUtilities.invokeLater(() -> {
                    session.setDescription(description);
                    try {
                        sessionPersistenceManager.save(session);
                    }
                    catch (IOException e) {
                        LOG.log(Level.WARNING, "Could not persist session description update", e);
                    }
                });
            }
        });
    }

    /**
     * Shortens a path for display in system messages. Delegates to the shared utility so notifications, confirm prompts
     * and diff messages all render a path the same way — including the project directory name, which says which project
     * a file belongs to when several are open.
     */
    private String shortPath(String fp) {
        return ProjectPathUtil.shortPath(fp);
    }

    /**
     * Label for a confirm notification. Not every confirm is about a file: a shell command has no path at all, and
     * rendering the absent one printed the literal string "null" to the user ("Execute: null — auto-accepted"), which
     * says nothing about what was approved. Those events carry the subject in their display text instead — the command
     * itself — so fall back to it.
     */
    private String confirmLabel(ConfirmEvent ce) {
        return buildConfirmLabel(shortPath(ce.filePath()),
                ce.targetPath() != null ? shortPath(ce.targetPath()) : null,
                ce.displayText());
    }

    @Override
    public void componentActivated() {
        PERSIST_EXECUTOR.execute(() -> {
            try {
                sessionPersistenceManager.save(session);
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not update session last-used timestamp", e);
            }
        });
        if (aiBackend != null) {
            aiBackend.onTabActivated();
        }
    }

    /**
     * Drains queued notifications into a single new turn.
     *
     * @return whether a turn was actually submitted. Callers finishing a turn use this to decide whether to show the
     * session as idle: a queued notification means the session carries straight on, and announcing idle first is what
     * let a green tab and a live input field appear mid-conversation. A non-empty queue is NOT the same answer - every
     * entry can be filtered out below and nothing sent - so the decision has to come from here, after filtering, or the
     * UI would be left permanently busy with no turn running.
     */
    private boolean flushPendingNotifications(AbstractNotification... extra) {
        assert SwingUtilities.isEventDispatchThread();
        // If a turn is already in flight, aiBackend.sendPrompt() would no-op and
        // the notifications would be silently dropped. Don't drain now — stash any
        // new ones and leave the queue intact to be flushed at the next TurnComplete.
        if (aiBackend != null && aiBackend.isProcessing()) {
            if (extra != null && extra.length > 0) {
                synchronized (this) {
                    pendingNotifications.addAll(Arrays.asList(extra));
                }
            }
            return false;
        }
        List<AbstractNotification> all;
        synchronized (this) {
            all = new ArrayList<>(pendingNotifications);
            if (extra != null) {
                all.addAll(Arrays.asList(extra));
            }
            pendingNotifications.clear();
        }
        List<String> texts = all.stream()
                .filter(AbstractNotification::shouldDeliver)
                .map(AbstractNotification::text)
                .collect(java.util.stream.Collectors.toList());
        if (texts.isEmpty()) {
            return false;
        }
        // If a mail interrupt aborted the turn, say so IN THIS TURN rather than leaving it to the arriving mail to
        // imply. The message explains why new mail exists; it says nothing about the tool call that just died, and a
        // session read that silence as a user rejection while the mail sat in front of it. Passed as AGENT-ONLY text so
        // the assistant gets it and the user's transcript keeps only the inbox lines. Consumed here so the empty-queue
        // path cannot repeat it.
        String interrupt = consumeInboxInterruptExplanation();
        // Combine into a SINGLE turn — handleSubmit/sendPrompt runs one turn at a
        // time, so submitting in a loop would drop all but the first.
        submitNotificationTurn(NotificationTypeEnum.NEW_INBOX_MESSAGE,
                String.join("\n\n", texts), interrupt);
        return true;
    }

    /**
     * The explanation if a mail interrupt aborted this turn and the assistant should be told, or null if not.
     *
     * <p>
     * CONSUMES the flag, which is what stops the notice being sent twice for one interrupt. Whichever delivery path
     * asks first gets the text and clears the flag; the other then gets null and stays silent. A session told twice
     * that it was interrupted starts narrating the interruption to the user, which is its own noise.</p>
     *
     * <p>
     * Only for backends whose mail delivery actually aborts the turn. Codex steers, Copilot injects, Grok and Ollama
     * drop it — none of them abort anything, so telling those sessions their turn was interrupted would be a plain
     * falsehood about their own history, which is precisely the failure this notice exists to prevent. The flag is
     * cleared for them too: the interrupt they did not have must not be reported on some later turn either.</p>
     */
    private String consumeInboxInterruptExplanation() {
        if (!mailArrivedDuringTurn) {
            return null;
        }
        mailArrivedDuringTurn = false;
        var liveSession = SessionRegistry.get(session.id());
        if (liveSession == null || liveSession.getMailDeliveryTiming() != MailDeliveryTimingEnum.ABORTS_TURN) {
            return null;
        }
        return INBOX_INTERRUPT_EXPLANATION;
    }

    /**
     * Tells the assistant that its turn was cut short to deliver mail, when the inbox flush had nothing left to say.
     * Returns true if a turn was submitted.
     * <p>
     * This closes a gap that produces a FALSE BELIEF ABOUT THE USER. A mail interrupt aborts whatever tool call is in
     * flight, and every backend reports a mid-turn abort as a user cancellation — Claude sends the same
     * {@code control_request(interrupt)} for mail as for the Stop button, so the two are indistinguishable on the wire.
     * The assistant therefore reads "the user rejected this call".
     * <p>
     * This path covers the case where the assistant READ the message itself during the interrupted turn: the queue is
     * empty, the flush has nothing to deliver, and the INTERRUPTED status has already been deliberately suppressed, so
     * the assistant is left with an aborted tool call and no explanation at all.
     * <p>
     * The other case — mail IS waiting — is handled inside {@code flushPendingNotifications}, which carries the same
     * explanation into the turn that delivers it. That used to be left implicit, on the reasoning that arriving mail
     * explains the interruption by itself. It does not: the message says why new mail exists, not why the tool call
     * died, and a session in this IDE read the abort as a user rejection while the mail was sitting right there in
     * front of it.
     * <p>
     * Observed, not theoretical: a session in this IDE reported to the user that they had rejected a command they never
     * saw, and separately reported a message as rejected that had in fact been delivered.
     */
    private boolean explainInboxInterruptIfNeeded() {
        String explanation = consumeInboxInterruptExplanation();
        if (explanation == null) {
            return false;
        }
        // Agent-only, and there is nothing else in this turn — the session already read the mail, so the explanation IS
        // the whole content. With no visible text handleSubmit renders nothing at all rather than an empty bubble.
        submitNotificationTurn(NotificationTypeEnum.INBOX_INTERRUPT_NOTICE, null, explanation);
        return true;
    }

    private void submitNotificationTurn(NotificationTypeEnum type, String notificationText) {
        submitNotificationTurn(type, notificationText, null);
    }

    /**
     * @param notificationText what the user sees, prefixed with the type marker; blank submits nothing visible
     * @param agentOnlyText what only the assistant sees
     */
    private void submitNotificationTurn(NotificationTypeEnum type, String notificationText, String agentOnlyText) {
        // userInitiated=false: this fires from flushPendingNotifications at turn end because
        // mail is waiting, not because anyone clicked Send. It renders as a user message, but
        // the user did not ask for it, so it must respect the auto-scroll setting.
        String visible = notificationText == null || notificationText.isBlank()
                ? "" : type.prefix() + " " + notificationText;
        handleSubmit(visible, false, agentOnlyText);
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            return;
        }
        setDisplayName(newName);
        setName(newName);
        session.setName(newName);
        updateTabTooltip();
        try {
            sessionPersistenceManager.save(session);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not persist session rename", e);
        }
        refreshSessionIdentity();
    }

    private void refreshSessionIdentity() {
        if (contextProvider == null) {
            return;
        }
        // Always hand the session to the ContextProvider so the identity block is
        // emitted on every turn. Whether that block carries sessionId/secretKey is
        // decided inside buildPreamble() by the AI type's CREDENTIALS option: types
        // that call tools through a bridge get credentials injected server-side and
        // so are never shown them. The inter-AI capability blurb is gated separately.
        contextProvider.setSession(session);
    }

    private void openSessionConfig() {
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiSessionSettingsDialog dlg
                = kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiSessionSettingsDialog.show(session);
        if (dlg.getResultConfig() == null) {
            return;
        }
        if (dlg.getResultName() != null && !dlg.getResultName().isBlank()) {
            setDisplayName(dlg.getResultName());
            setName(dlg.getResultName());
            session.setName(dlg.getResultName());
            updateTabTooltip();
        }
        if (dlg.getResultDescription() != null) {
            session.setDescription(dlg.getResultDescription().isBlank() ? null : dlg.getResultDescription());
        }
        try {
            sessionPersistenceManager.save(session);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not persist session config update", e);
        }
        // Update broker registration based on the inter-AI comms setting WITHOUT
        // routing through unregister() unnecessarily. unregister() is the
        // session-EXIT path: it drains this session's inbox and falsely notifies
        // peers the session exited. register() is idempotent and preserves the
        // existing inbox, so only unregister when actually turning comms OFF.
        boolean interAiOn = session.settings().effectiveAllowInterAiComms();
        AiSessionInboxBroker broker = AiSessionInboxBroker.getInstance();
        if (interAiOn) {
            broker.register(session);
        }
        else {
            if (broker.isActive(session.id())) {
                broker.unregister(session.id());
            }
        }
        // Keep the session set on the ContextProvider either way — credentials
        // must keep flowing even with inter-AI comms off. buildPreamble() gates
        // the inter-AI capability blurb on the live comms setting on its own.
        refreshSessionIdentity();
        infoBar.setSaveHistory(session.settings().effectiveSaveHistory());
        infoBar.setAutoAccept(session.settings().effectiveAutoAccept());
        if (aiBackend != null) {
            aiBackend.applySessionSettings(session.settings());
        }
        if (infoBarExtension != null) {
            infoBarExtension.onSessionSettingsChanged(session.settings());
        }
        // Immediately propagate restrictToProjectFiles to the MCP hook server's
        // scope map so file-tool permission changes take effect on the next tool
        // call, not only after the next user submit.
        refreshMcpSessionScope();
    }

    public void closeWithoutPrompt() {
        historyManager = null; // session already deleted externally
        skipClosePrompt = true;
        close();
    }

    @Override
    public boolean canClose() {
        if (skipClosePrompt) {
            return true;
        }
        if (PluginSettings.isSaveSessionOnCloseIfTicked()) {
            if (!isSaveHistoryEnabled()) {
                deleteSessionOnClose();
            }
            return true;
        }
        Object[] options = {"Delete session", "Keep for later", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "Close \"" + session.name() + "\"?\n\nDelete removes the session and its history permanently.",
                "Close Session",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[1]);
        if (choice == 2 || choice < 0) {
            return false;
        }
        if (choice == 0) {
            deleteSessionOnClose();
        }
        return true;
    }

    private void deleteSessionOnClose() {
        try {
            historyManager = null; // prevent componentHidden/componentClosed from recreating deleted dir
            sessionPersistenceManager.delete(session.id());
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete session " + session.id(), e);
        }
    }

    @Override
    public CompletableFuture<String> promptForExecutable(String dialogTitle, String executableName) {
        CompletableFuture<String> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle(dialogTitle);
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fc.setFileFilter(new FileFilter() {
                @Override
                public boolean accept(File f) {
                    return f.isDirectory() || f.getName().equals(executableName) || f.getName().startsWith(executableName + ".");
                }

                @Override
                public String getDescription() {
                    return executableName + " executable";
                }
            });
            fc.setAcceptAllFileFilterUsed(true);
            File startDir = new File("/usr/bin");
            if (!startDir.isDirectory()) {
                startDir = new File(System.getProperty("user.home"));
            }
            fc.setCurrentDirectory(startDir);
            int result = fc.showOpenDialog(this);
            future.complete(result == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile().getAbsolutePath() : null);
        });
        return future;
    }

    @Override
    public void componentOpened() {
        // Red until the AI process reports READY (or stays red on a startup failure).
        setTabStatus(TabStatus.FATAL);

        initializeSessionComponents();

        if (aiBackend == null) {
            aiBackend = new AiTypeRegistry().create(session.aiType(), this, this);
            AiInfoBarExtension ext = aiBackend.createInfoBarExtension(session, this);
            if (ext != null) {
                infoBar.setExtension(ext);
                infoBarExtension = ext;
            }
        }

        if (openProjectsListener == null) {
            openProjectsListener = evt -> {
                if (!OpenProjects.PROPERTY_OPEN_PROJECTS.equals(evt.getPropertyName())) {
                    return;
                }
                refreshMcpSessionScope();
                File sessionDir = aiBackend != null ? aiBackend.getSessionWorkingDir() : null;
                if (sessionDir == null) {
                    sessionDir = chosenSessionDir;
                }
                if (sessionDir == null) {
                    return;
                }
                String sessionPath = sessionDir.getPath();
                boolean stillOpen = Arrays.stream(OpenProjects.getDefault().getOpenProjects())
                        .anyMatch(p -> sessionPath.startsWith(p.getProjectDirectory().getPath()));
                if (!stillOpen) {
                    SwingUtilities.invokeLater(()
                            -> infoBar.setStatusMessage("Session project closed — ↻ New to switch projects"));
                }
            };
            OpenProjects.getDefault().addPropertyChangeListener(openProjectsListener);
        }

        // loadHistory() now loads/parses off the EDT and resolves the session dir
        // itself once it (asynchronously) finishes — see loadHistory() below.
        loadHistory(startAiProcess());
        if (aiBackend != null && lifecycleListeners.isEmpty()) {
            // registerLifecycleListeners() (Claude) synchronously stats/reads
            // ~/.claude/.credentials.json via AnthropicApiClient.refreshCredentialsState()
            // before kicking off its own async model/usage fetches. lifecycleListeners
            // is a CopyOnWriteArrayList so it's safe to populate off the EDT.
            AiImplementation backendForListeners = aiBackend;
            PERSIST_EXECUTOR.execute(() -> backendForListeners.registerLifecycleListeners(this));
        }
        SwingUtilities.invokeLater(() -> fireListenerEvent(SessionLifecycleListener::onSessionStarted));
        if (session.settings() != null && session.settings().effectiveAllowInterAiComms()) {
            AiSessionInboxBroker.getInstance().register(session);
        }
        refreshSessionIdentity();
        // Register session file scope with MCP server when it is already available.
        // startAiProcess() refreshes it again after async backend startup completes,
        // which closes the null-server race during session open.
        refreshMcpSessionScope();
        if (aiTypePropertyListener == null) {
            aiTypePropertyListener = this::handleAiTypeProperty;
            AiTypePropertyBus.getInstance().addListener(session.aiType(), aiTypePropertyListener);
        }
        if (globalPropertyListener == null) {
            globalPropertyListener = this::handleGlobalProperty;
            GlobalPropertyBus.getInstance().addListener(globalPropertyListener);
        }
    }

    @Override
    public void addListener(SessionLifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    private void fireListenerEvent(java.util.function.Consumer<SessionLifecycleListener> event) {
        lifecycleListeners.forEach(event);
    }

    private void handleAiTypeProperty(AiPropertyEvent event) {
        if (infoBarExtension != null) {
            SwingUtilities.invokeLater(() -> infoBarExtension.onPropertyEvent(event));
        }
    }

    private void handleGlobalProperty(AiPropertyEvent event) {
        if (event instanceof AiInboxMessageEvent ime && session.id().equals(ime.targetSessionId())) {
            SwingUtilities.invokeLater(() -> {
                // Mail arrives asynchronously, so it can land mid-stream. Without
                // this the notice is appended after a still-growing assistant
                // bubble: the transcript then shows the notice at the bottom while
                // the text it interrupted carries on above it. addSystemMessage's
                // own javadoc makes finalising the caller's job.
                finaliseActiveAssistantIfNeeded();
                conversationPanel.addSystemMessage(
                        NotificationUtil.formatInboxMessage(ime.fromName(), ime.subject()));
            });
        }
    }

    private void initializeSessionComponents() {
        if (contextProvider == null) {
            contextProvider = new ContextProvider(fo -> updateContextLabel());
            contextProvider.start();
        }
        if (historyManager == null) {
            historyManager = new HistoryPersistenceManager(
                    sessionPersistenceManager.historyPath(session.id()));
        }
        updateContextLabel(); // initial
    }

    private void refreshMcpSessionScope() {
        List<File> dirs = contextProvider != null ? contextProvider.getAllOpenProjectDirs() : List.of();
        refreshMcpSessionScope(dirs);
    }

    private void refreshMcpSessionScope(List<File> dirs) {
        Object mcpObj = aiBackend != null ? aiBackend.getMcpServer() : null;
        if (mcpObj instanceof McpHookServer mcp) {
            boolean restrict = session.settings() != null
                    && session.settings().effectiveRestrictToProjectFiles();
            mcp.updateSessionScope(session.id(), session.aiType(), dirs != null ? dirs : List.of(), restrict);
        }
    }

    private CompletableFuture<Void> startAiProcess() {
        CompletableFuture<Void> ret = new CompletableFuture<>();
        if (!new AiTypeRegistry().getSettings(session.aiType()).enabled()) {
            String msg = session.aiType().displayName() + " is disabled — enable it in Tools > Options > Advanced > " + session.aiType().displayName();
            if (infoBar != null) {
                infoBar.setStatusMessage(msg);
            }
            if (conversationPanel != null) {
                conversationPanel.addSystemMessage(msg);
            }
            startupResolved = true;
            refreshInputEnabled();
            ret.complete(null);
            return ret;
        }
        if (aiBackend != null) {
            AiImplementation backend = aiBackend;
            backend.setCurrentSession(session);
            // startWithDiscovery() itself locates the CLI executable (PATH scan +
            // well-known-location stat calls) and, if missing, blocks on the
            // ExecutablePrompter dialog — all on the calling thread. Run the whole
            // call off the EDT so opening/creating a session never blocks the UI.
            PERSIST_EXECUTOR.execute(() -> {
                try {
                    backend.startWithDiscovery(null);
                }
                finally {
                    // Must run even if startWithDiscovery() throws — otherwise `ret`
                    // never completes and loadHistoryInBackground()'s future.get()
                    // blocks its PERSIST_EXECUTOR thread forever (pool is bounded and
                    // shared with history load/save, so repeated failures exhaust it).
                    SwingUtilities.invokeLater(() -> {
                        refreshMcpSessionScope();
                        backend.onStarted(AiTopComponent.this);
                        ret.complete(null);
                    });
                }
            });

            return ret;
        }
        ret.complete(null);
        return ret;
    }

    /**
     * Resolve (or prompt the user to choose) the working directory for this session. No-op if already set. Must be
     * called on the EDT.
     */
    private void resolveSessionDir() {
        if (chosenSessionDir != null) {
            return;
        }
        // Honour the project the session was created for before falling back to ambiguity resolution
        if (session.projectPath() != null) {
            File dir = new File(session.projectPath());
            if (dir.isDirectory()) {
                chosenSessionDir = dir;
                return;
            }
        }
        if (contextProvider == null) {
            return;
        }

        if (!contextProvider.isWorkingDirectoryAmbiguous()) {
            chosenSessionDir = contextProvider.resolveWorkingDirectory();
            return;
        }

        Project[] candidates = contextProvider.getProjectCandidates();
        switch (candidates.length) {
            case 0 ->
                chosenSessionDir = new File(System.getProperty("user.home"));
            case 1 ->
                chosenSessionDir = new File(candidates[0].getProjectDirectory().getPath());
            default -> {
                String[] names = new String[candidates.length];
                for (int i = 0; i < candidates.length; i++) {
                    names[i] = candidates[i].getProjectDirectory().getName();
                }
                Window dialogParent = SwingUtilities.getWindowAncestor(this);
                if (dialogParent == null) {
                    dialogParent = WindowManager.getDefault().getMainWindow();
                }
                String chosen = (String) JOptionPane.showInputDialog(
                        dialogParent,
                        "Multiple projects are open.\nWhich should " + session.aiType().displayName() + " use as its working directory?",
                        "Select Working Project",
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        names,
                        names[0]);
                if (chosen == null) {
                    chosen = names[0]; // cancelled — fall back to first
                }
                final String finalChosen = chosen;
                int idx = 0;
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(finalChosen)) {
                        idx = i;
                        break;
                    }
                }
                chosenSessionDir = new File(candidates[idx].getProjectDirectory().getPath());
            }
        }
    }

    private void updateContextLabel() {
        if (contextLabel == null) {
            return;
        }
        if (contextProvider == null) {
            contextLabel.setText("No file open");
            return;
        }
        // getContextHeaderText is safe to call from any thread; UI update on EDT
        String text = contextProvider.getContextHeaderText();
        SwingUtilities.invokeLater(() -> contextLabel.setText(text));
    }

    @Override
    public void componentHidden() {
        saveHistory();
    }

    @Override
    public void componentClosed() {
        drainPendingInteractions();
        AiSessionInboxBroker.getInstance().unregister(session.id());
        // Remove the open-projects listener BEFORE unregistering the session, so it can
        // never re-register (resurrect) the session via updateSessionScope. Once removed,
        // no queued property-change event can fire, making the ordering self-evidently safe
        // rather than relying on componentClosed and the listener both running on the EDT.
        try {
            if (openProjectsListener != null) {
                OpenProjects.getDefault().removePropertyChangeListener(openProjectsListener);
                openProjectsListener = null;
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Error removing openProjectsListener during session close", e);
        }
        // Unregister session file scope from MCP server
        Object mcpObj = aiBackend != null ? aiBackend.getMcpServer() : null;
        if (mcpObj instanceof McpHookServer mcpServer) {
            mcpServer.unregisterSession(session.id());
        }
        // The tab is closing — NOT necessarily the session being deleted (the user may have
        // chosen "Keep for later" in canClose(), which runs before this). Its pasted-image
        // temp files are working data for the CURRENT tab, not part of what "keep for later"
        // promises to preserve, so they are swept here regardless of that choice. Async: a
        // recursive directory delete must not stall the EDT while other sessions are mid-turn.
        // A no-op if the session was instead permanently deleted (deleteSessionOnClose already
        // removed everything, tmp/ included, moments before this runs).
        try {
            TempFileRegistry.cleanupSessionAsync(session.id());
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Error sweeping temp dir during session close", e);
        }
        turnOutputSuppressed = false;
        suppressedTurnCompletionMessage = null;
        try {
            if (aiTypePropertyListener != null) {
                AiTypePropertyBus.getInstance().removeListener(session.aiType(), aiTypePropertyListener);
                aiTypePropertyListener = null;
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Error removing aiTypePropertyListener during session close", e);
        }
        try {
            if (globalPropertyListener != null) {
                GlobalPropertyBus.getInstance().removeListener(globalPropertyListener);
                globalPropertyListener = null;
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Error removing globalPropertyListener during session close", e);
        }
        saveHistory();
        try {
            if (contextProvider != null) {
                contextProvider.setSession(null);
                contextProvider.stop();
                contextProvider = null;
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Error stopping contextProvider during session close", e);
        }
        try {
            if (infoBarExtension != null) {
                infoBarExtension.dispose();
                infoBarExtension = null;
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Error disposing infoBarExtension during session close", e);
        }
        lifecycleListeners.clear();
        infoBar.resetSessionClock();
        try {
            if (aiBackend != null) {
                aiBackend.stop();
                aiBackend = null;
            }
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Error stopping aiBackend during session close", e);
        }
        assistantTurnActive = false;
        cancelledThisTurn = false;
        pendingNewlineBeforeText = false;
    }

    @Override
    public int getPersistenceType() {
        return TopComponent.PERSISTENCE_NEVER;
    }

    @Override
    public void setDisplayName(String name) {
        super.setDisplayName(name + " - AI");
        updateTabHtmlName();
    }

    @Override
    public String getHtmlDisplayName() {
        return buildTabHtml();
    }

    /**
     * The tab label: a status-coloured bullet followed by the escaped display name. One source of truth, so what
     * {@link #getHtmlDisplayName()} returns and what {@link #updateTabHtmlName()} pushes cannot drift apart.
     */
    private String buildTabHtml() {
        String safeName = getDisplayName()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><font color='" + tabStatusColor() + "'>&#9679;</font> " + safeName + "</html>";
    }

    /**
     * Pushes the tab label to the window system, but only when it actually changed.
     *
     * <p>
     * setHtmlDisplayName fires a property change and NetBeans re-parses this HTML to repaint the tab strip, so a
     * redundant call is real work for no visible difference — and most calls ARE redundant. setSendEnabled routes
     * through setTabStatus on every refreshInputEnabled, and during a turn the status is already THINKING, so a
     * streaming session repainted its tab continuously to render a byte-identical string. Genuine state changes still
     * repaint; only the no-ops are dropped.
     */
    private void updateTabHtmlName() {
        String html = buildTabHtml();
        if (html.equals(lastTabHtml)) {
            return;
        }
        lastTabHtml = html;
        setHtmlDisplayName(html);
    }

    /**
     * Tab tooltip: "&lt;name&gt; - &lt;AiType&gt; - Ai Coder".
     */
    private void updateTabTooltip() {
        setToolTipText(session.name() + " - " + session.aiType().displayName() + " - Ai Coder");
    }

    private String tabStatusColor() {
        return resolvedTabStatusColor(tabStatus, thinkingFlashActive);
    }

    /**
     * Single source of truth for the tab status circle (the HTML dot in the tab name — NetBeans's tab strip doesn't
     * reliably honour setIcon() here).
     */
    private void setTabStatus(TabStatus status) {
        tabStatus = status;
        if (status != TabStatus.THINKING) {
            // Leaving THINKING entirely cancels any in-flight flash pulse so it
            // can't fire later and stomp on the new (non-orange) status.
            thinkingFlashActive = false;
            if (thinkingFlashTimer != null) {
                thinkingFlashTimer.stop();
            }
        }
        updateTabHtmlName();
    }

    /**
     * Enter the "blocked waiting on user" state: mark the diff as pending, disable input, and switch the tab dot to
     * white ({@code AWAITING_USER}). Called from all four panels that block on user interaction (question, confirm,
     * non-MCP diff, permission diff).
     * <p>
     * Do NOT collapse this into {@code refreshInputEnabled()} — when {@code aiBackend == null} that method takes the
     * early-return branch and would ENABLE the input while a panel is on screen. The direct disables here are
     * intentional.
     */
    private void enterAwaitingUserState() {
        if (aiBackend != null) {
            aiBackend.setPendingDiff(true);
        }
        inputField.setEnabled(false);
        sendButton.setEnabled(false);
        setTabStatus(TabStatus.AWAITING_USER);
    }

    /**
     * Records a THINKING-flash request. Non-EDT callers coalesce to at most one pending invokeLater; repeated requests
     * while that runnable or the timer is already active just push the deadline out.
     */
    private void requestThinkingFlash() {
        thinkingFlashDeadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(THINKING_FLASH_MS);
        if (SwingUtilities.isEventDispatchThread()) {
            thinkingFlashRequestQueued.set(false);
            flashThinking();
            return;
        }
        if (thinkingFlashRequestQueued.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                thinkingFlashRequestQueued.set(false);
                flashThinking();
            });
        }
    }

    /**
     * Briefly flashes the THINKING (orange) status dot yellow for {@link #THINKING_FLASH_MS} whenever AI output arrives
     * while a turn is in flight, then reverts to plain orange. EDT-only: callers must use requestThinkingFlash()
     * off-thread.
     */
    private void flashThinking() {
        if (tabStatus != TabStatus.THINKING) {
            return;
        }
        if (!thinkingFlashActive) {
            thinkingFlashActive = true;
            updateTabHtmlName();
        }
        if (thinkingFlashTimer == null) {
            thinkingFlashTimer = new Timer(THINKING_FLASH_MS, e -> {
                thinkingFlashActive = false;
                if (tabStatus == TabStatus.THINKING) {
                    updateTabHtmlName();
                }
            });
            thinkingFlashTimer.setRepeats(false);
        }
        long remainingNanos = Math.max(0L, thinkingFlashDeadlineNanos - System.nanoTime());
        long delayMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (TimeUnit.MILLISECONDS.toNanos(delayMillis) < remainingNanos) {
            delayMillis++;
        }
        int delay = (int) Math.max(1L, delayMillis);
        thinkingFlashTimer.setInitialDelay(delay);
        thinkingFlashTimer.setDelay(delay);
        thinkingFlashTimer.restart();
    }

    /**
     * Called from the backend event thread — coalesce the flash pulse request, then dispatch full event handling to the
     * EDT.
     */
    @Override
    public void onAiProcessEvent(AiProcessEvent event) {
        if (tabStatus == TabStatus.THINKING && isVisibleChatOutput(event)) {
            requestThinkingFlash();
        }
        SwingUtilities.invokeLater(() -> handleEvent(event));
    }

    /**
     * True for events that actually render new AI-generated content in the chat panel. Internal/plumbing events (status
     * updates, impl events, turn boundaries) must not trigger the THINKING flash pulse — only output the user can
     * actually see arriving should. Mirrors handleEvent()'s own rendering conditions: an empty TextDeltaEvent renders
     * nothing, and a ToolUseEvent only renders a diff when it's a file modification AND MCP isn't active (MCP-active
     * sessions render file edits via PermissionEvent instead) — otherwise every Read/Bash/Grep/MCP tool call would
     * flash the tab despite adding nothing visible to the chat.
     */
    private boolean isVisibleChatOutput(AiProcessEvent event) {
        if (event instanceof TextDeltaEvent td) {
            return td.text() != null && !td.text().isEmpty();
        }
        if (event instanceof ToolUseEvent tu) {
            return tu.isFileModification() && (aiBackend == null || !aiBackend.isMcpActive());
        }
        return event instanceof PermissionEvent || event instanceof MultiPermissionEvent
                || event instanceof AskUserQuestionEvent || event instanceof ConfirmEvent;
    }

    private void handleEvent(AiProcessEvent event) {
        if (aiBackend == null) {
            return; // stale invokeLater fired after close
        }

        // The flash pulse is requested in onAiProcessEvent() before this event
        // is dispatched; handleEvent() only does the full per-event UI work.
        if (turnOutputSuppressed) {
            if (event instanceof TextDeltaEvent) {
                return;
            }
            if (event instanceof AiProcessImplEvent) {
                return;
            }
            if (event instanceof TurnCompleteEvent) {
                turnOutputSuppressed = false;
                assistantTurnActive = false;
                saveHistory();
                // Hand straight over to any queued notification turn rather than
                // showing idle and only scheduling the handover afterwards. We are
                // already on the EDT (onAiProcessEvent dispatches through
                // invokeLater), so the old extra invokeLater bought nothing except
                // a window where the tab was green and the input live while the
                // session was about to carry straight on.
                //
                // DELIBERATELY SYMMETRIC with the ordinary turn-completion path below — see the longer note there.
                // Both must offer the empty-queue explanation; change one and you must change the other.
                if (!flushPendingNotifications() && !explainInboxInterruptIfNeeded()) {
                    infoBar.setProcessing(false);
                    setSendEnabled(true);
                    infoBar.setStatusMessage(suppressedTurnCompletionMessage != null
                            ? suppressedTurnCompletionMessage : "Ready...");
                }
                suppressedTurnCompletionMessage = null;
                return;
            }
            if (event instanceof StatusEvent se) {
                if (se.type() == StatusEventTypeEnum.EXITED || se.type() == StatusEventTypeEnum.FAILED
                        || se.type() == StatusEventTypeEnum.STOPPED) {
                    turnOutputSuppressed = false;
                    suppressedTurnCompletionMessage = null;
                }
                // fall through so status bar updates
            }
        }

        if (event instanceof TextDeltaEvent td) {
            if (cancelledThisTurn) {
                return;
            }
            if (!assistantTurnActive) {
                assistantTurnActive = true;
                conversationPanel.beginAssistantMessage();
            }
            else if (pendingNewlineBeforeText) {
                conversationPanel.appendDelta("\n\n");
            }
            pendingNewlineBeforeText = false;
            conversationPanel.appendDelta(td.text());
        }
        else if (event instanceof TurnCompleteEvent) {
            cancelledThisTurn = false;
            assistantTurnActive = false;
            pendingNewlineBeforeText = false;
            conversationPanel.finaliseAssistantMessage();
            // The AI has returned to a non-thinking state. If a question or
            // permission request is still open, the AI has stopped waiting for it
            // — e.g. its own tool-call timeout fired before our 300s one — so it is
            // orphaned. Resolve each: this unblocks the still-waiting tool, greys
            // the panel, and records the outcome, rather than leaving a live-looking
            // question the AI will never receive an answer to.
            for (Runnable c : new ArrayList<>(pendingResponseCancellers)) {
                c.run();
            }
            pendingResponseCancellers.clear();
            checkForFileChanges();
            saveHistory();
            fireListenerEvent(SessionLifecycleListener::onTurnComplete);
            // Only report idle if nothing is queued to run next - see
            // flushPendingNotifications. A mail interrupt ends the Claude turn and
            // the queued message immediately starts another, so announcing idle
            // first showed a green tab and an enabled input between the two.
            //
            // DELIBERATELY SYMMETRIC with the suppressed-turn path above: both must offer the empty-queue
            // explanation, because that is the case where the session read the mail itself during the interrupted
            // turn and nothing else is left to tell it the abort was not a user rejection. This path handles ordinary
            // turn completion — the one most turns take — and used to omit the fallback, so the notice was missing
            // exactly where it was most needed. Change one of these two sites and you must change the other.
            if (!flushPendingNotifications() && !explainInboxInterruptIfNeeded()) {
                infoBar.setProcessing(false);
                infoBar.setStatusMessage("Ready...");
                refreshInputEnabled();
            }
        }
        else if (event instanceof AskUserQuestionEvent aqe) {
            if (assistantTurnActive) {
                assistantTurnActive = false;
                conversationPanel.finaliseAssistantMessage();
            }
            enterAwaitingUserState();
            Runnable aqeCanceller = () -> aqe.response().complete(null);
            pendingResponseCancellers.add(aqeCanceller);
            conversationPanel.showQuestion(aqe);
            aqe.response().whenComplete((answer, ex) -> SwingUtilities.invokeLater(() -> {
                pendingResponseCancellers.remove(aqeCanceller);
                if (aiBackend != null) {
                    aiBackend.setPendingDiff(false);
                }
                if (answer != null && !answer.isBlank()) {
                    conversationPanel.addSystemMessage(NotificationUtil.formatAnswer(answer));
                }
                else {
                    // No answer — timed out or the turn was cancelled. Record it in
                    // history so the conversation shows the question was asked and
                    // never submitted; the live QuestionPanel is transient and gone
                    // after a save/restore.
                    conversationPanel.addSystemMessage(
                            NotificationUtil.formatUnansweredQuestion(aqe.questions()));
                }
                refreshInputEnabled();
            }));
        }
        else if (event instanceof ConfirmEvent ce) {
            if (shouldAutoAccept(ce, infoBar.isAutoAccept())) {
                finaliseActiveAssistantIfNeeded();
                conversationPanel.addSystemMessage(
                        NotificationUtil.formatAutoAccepted(ce.toolName(), confirmLabel(ce)));
                ce.response().complete(PermissionDecision.allowed());
                return;
            }
            if (assistantTurnActive) {
                assistantTurnActive = false;
                conversationPanel.finaliseAssistantMessage();
            }
            enterAwaitingUserState();
            Runnable canceller = () -> ce.response().complete(PermissionDecision.denied("cancelled"));
            pendingResponseCancellers.add(canceller);
            conversationPanel.showConfirm(ce, session.aiType().confirmAcceptTooltip(),
                    session.aiType().confirmRejectTooltip());
            ce.response().whenComplete((decision, ex) -> SwingUtilities.invokeLater(() -> {
                pendingResponseCancellers.remove(canceller);
                if (aiBackend != null) {
                    aiBackend.setPendingDiff(false);
                }
                if (decision != null && decision.allow()) {
                    conversationPanel.addSystemMessage(NotificationUtil.formatFileAcceptedTool(ce.toolName(), confirmLabel(ce)));
                }
                else if (decision != null && decision.message() == null) {
                    conversationPanel.addSystemMessage(NotificationUtil.formatFileRejectedTool(ce.toolName(), confirmLabel(ce)));
                }
                else if (decision != null) {
                    conversationPanel.addSystemMessage(NotificationUtil.formatFileRejectedTool(ce.toolName(), confirmLabel(ce), decision.message()));
                }
                refreshInputEnabled();
            }));
        }
        else if (event instanceof PermissionEvent pe) {
            pendingNewlineBeforeText = true;
            showPermissionDiff(pe);
        }
        else if (event instanceof MultiPermissionEvent mpe) {
            pendingNewlineBeforeText = true;
            showMultiPermissionReview(mpe);
        }
        else if (event instanceof ToolUseEvent tu) {
            if (cancelledThisTurn) {
                return;
            }
            pendingNewlineBeforeText = true;
            if (tu.isFileModification() && (aiBackend == null || !aiBackend.isMcpActive())) {
                diffShownForCurrentTurn = true;
                showDiff(tu);
            }
        }
        else if (event instanceof StatusEvent se) {
            switch (se.type()) {
                case READY -> {
                    infoBar.setStatusMessage(se.text());
                    infoBar.startSessionClock();
                    startupResolved = true;
                    refreshInputEnabled();
                    // Process is up and idle — clear any red/fatal state to green.
                    setTabStatus(TabStatus.READY);
                    if (pendingSubmitText != null) {
                        String queued = pendingSubmitText;
                        pendingSubmitText = null;
                        handleSubmit(queued);
                    }
                }
                case STOPPED -> {
                    cancelledThisTurn = false;
                    infoBar.setStatusMessage(se.text());
                    infoBar.setProcessing(false);
                    assistantTurnActive = false;
                    if (conversationPanel != null) {
                        conversationPanel.finaliseAssistantMessage();
                    }
                    drainPendingInteractions();
                    refreshInputEnabled();
                }
                case EXITED, FAILED -> {
                    pendingSubmitText = null;
                    startupResolved = true;
                    infoBar.setProcessing(false);
                    conversationPanel.addSystemMessage(se.text());
                    infoBar.setStatusMessage("Ready...");
                    if (aiBackend != null) {
                        aiBackend.setPendingDiff(false);
                    }
                    refreshInputEnabled();
                    // Abnormal exit / failure (incl. auth) — show red. Input stays
                    // enabled (refreshInputEnabled above) so the user can retry; this
                    // must run after it to override the green it would otherwise set.
                    setTabStatus(TabStatus.FATAL);
                }
                case INTERRUPTED -> {
                    // Turn aborted mid-stream but the process is alive. Post a visible
                    // system message so the user isn't left with silent no-output; the
                    // TurnCompleteEvent that follows finalises the turn and returns to Ready.
                    // Close the streaming bubble first: by definition there is one open
                    // here, so without it the abort notice renders after text it aborted.
                    infoBar.setProcessing(false);
                    if (conversationPanel != null) {
                        finaliseActiveAssistantIfNeeded();
                        conversationPanel.addSystemMessage(se.text());
                    }
                }
                default ->
                    infoBar.setStatusMessage(se.text());
            }
        }
        else if (event instanceof SystemNotificationEvent sn) {
            // Backends raise these mid-turn (e.g. the Copilot permission handler
            // reporting a denied internal tool), so a stream may be open.
            finaliseActiveAssistantIfNeeded();
            conversationPanel.addSystemMessage(sn.text());
        }
        else if (infoBarExtension != null && event instanceof AiProcessImplEvent si) {
            infoBarExtension.onAiProcessImplEvent(si);
        }
    }

    private void handleSubmit(String text) {
        handleSubmit(text, true);
    }

    private void handleSubmit(String text, boolean userInitiated) {
        handleSubmit(text, userInitiated, null);
    }

    /**
     * @param agentOnlyText text the assistant must receive but the user must NOT see. APPENDED to the prompt and
     * omitted from {@code conversationPanel.addUserMessage}, which is the single call that feeds both the transcript
     * and saved history — so nothing hidden here can reappear on reload.
     * <p>
     * ORDER IS LOAD-BEARING, not incidental: the prompt is composed as the visible text, then
     * {@link #SYSTEM_CUT_MARKER}, then the agent-only block. Everything the user should see comes before the cut and
     * everything hidden after it. Putting the agent-only block FIRST — as an earlier iteration did — moves the marker
     * ahead of the inbox notification lines and hides the very thing the user asked to keep. It also keeps deferred
     * notifications safe: they are appended to the visible text at the top of this method, so they land before the cut
     * and stay visible without needing a special case.
     * <p>
     * Same split the {@code TmpMarkerExpander} block below already uses in the other direction: what the agent receives
     * and what the user sees are deliberately not the same string. Used for the mail-interrupt explanation, which is
     * written for the model — it is long, it is about protocol rather than about the user's work, and the user asked
     * not to have it in their conversation. The wording itself is unchanged: it is a correctness mechanism that cost
     * two false "the user rejected this" reports to earn, so it is hidden, never shortened.
     * <p>
     * When {@code text} is blank and only hidden text exists, NOTHING is rendered — no empty bubble.
     */
    private void handleSubmit(String text, boolean userInitiated, String agentOnlyText) {
        boolean hasVisible = text != null && !text.isBlank();
        boolean hasHidden = agentOnlyText != null && !agentOnlyText.isBlank();
        if (!hasVisible && !hasHidden) {
            return;
        }
        if (text == null) {
            text = "";
        }
        synchronized (this) {
            if (!deferredNotifications.isEmpty()) {
                String deferred = deferredNotifications.stream()
                        .filter(AbstractNotification::shouldDeliver)
                        .map(AbstractNotification::text)
                        .collect(java.util.stream.Collectors.joining("\n"));
                deferredNotifications.clear();
                if (!deferred.isEmpty()) {
                    // No leading blank line when there was nothing before it — a notice-only turn that picks up
                    // deferred mail should read as the mail, not as a gap followed by it.
                    text = text.isBlank() ? "[Pending inbox messages]\n" + deferred
                            : text + "\n\n[Pending inbox messages]\n" + deferred;
                }
            }
        }
        if (aiBackend != null && !aiBackend.isRunning()) {
            boolean alreadyStarting = pendingSubmitText != null;
            pendingSubmitText = alreadyStarting ? pendingSubmitText + "\n\n" + text : text;
            if (!alreadyStarting) {
                infoBar.setStatusMessage("Starting " + session.aiType().displayName() + "...");
                startAiProcess();
            }
            return;
        }
        File workDir = chosenSessionDir != null ? chosenSessionDir
                : contextProvider != null ? contextProvider.resolveWorkingDirectory()
                        : new File(System.getProperty("user.home"));
        if (workDir == null) {
            workDir = new File(System.getProperty("user.home"));
        }
        List<File> projectDirs = contextProvider != null
                ? contextProvider.getAllOpenProjectDirs()
                : List.of();

        String sessionInstructions = session.settings() != null
                ? session.settings().sessionInstructions() : null;
        // Expand @tmp.<filename> markers to the absolute path ONLY in what the agent
        // receives — `text` itself is left untouched below for display and history, so
        // the transcript keeps showing the short marker the user actually typed/pasted.
        TmpMarkerExpander.Result tmpExpansion = TmpMarkerExpander.expand(text, session);
        // The visible text ALWAYS goes to the agent, and the agent-only block is WRAPPED and appended after it.
        //
        // Recomputed from the current text rather than from the flag taken on entry: `text` has grown since then if
        // deferred inbox notifications were appended above. Branching on the stale flag dropped that mail from the
        // prompt entirely, because the no-visible-text branch never used expandedText() at all.
        String visibleForAgent = tmpExpansion.expandedText();
        String agentText = !hasHidden ? visibleForAgent
                : (visibleForAgent.isBlank() ? "" : visibleForAgent + "\n\n")
                + SYSTEM_BLOCK_OPEN + "\n" + agentOnlyText + "\n" + SYSTEM_BLOCK_CLOSE;
        String fullPrompt = contextProvider != null
                ? contextProvider.buildPreamble(agentText, sessionInstructions)
                : agentText;
        boolean instructionsIncluded = contextProvider != null
                && contextProvider.consumeSessionInstructionsInjected();
        if (instructionsIncluded) {
            conversationPanel.addSystemMessage("Session Instructions Sent");
            recordInstructionsDelivered(sessionInstructions);
        }
        // A genuine new turn starts clean: anything that arrived during the PREVIOUS turn has
        // either been flushed or explained by now, and carrying the flag forward would explain
        // an interruption that did not happen.
        if (userInitiated) {
            mailArrivedDuringTurn = false;
        }
        // The single call that feeds BOTH the transcript and saved history, so skipping it for a hidden-only submit
        // keeps the text out of the panel and out of any reload.
        //
        // Tested against the CURRENT text, not the flag from entry: deferred inbox notifications may have arrived in it
        // since. Using the stale flag hid real mail from the user on a notice-only turn.
        if (!text.isBlank()) {
            conversationPanel.addUserMessage(text, userInitiated);
        }
        for (String missingName : tmpExpansion.missingFiles()) {
            conversationPanel.addSystemMessage("Could not find pasted file @tmp." + missingName
                    + " — it may have been cleaned up, so the agent will see the marker text instead of the file.");
        }
        infoBar.setProcessing(true);
        infoBar.setStatusMessage("Thinking…");
        diffShownForCurrentTurn = false;
        snapshotActiveFile();
        // Refresh the MCP file-access scope to the currently-open projects so a
        // project opened after session start is reachable by plugin tools too
        // (the CLI already gets it via --add-dir / projectDirs below).
        refreshMcpSessionScope(projectDirs);
        if (aiBackend != null) {
            if (contextProvider != null) {
                aiBackend.updatePinnedContext(
                        contextProvider.buildIdentityBlock(),
                        contextProvider.buildProjectBaseline(),
                        session.settings() == null ? null : session.settings().sessionInstructions());
            }
            aiBackend.sendPrompt(fullPrompt, workDir, projectDirs);
        }
        setSendEnabled(false);
    }

    private void setSendEnabled(boolean enabled) {

        inputField.setCanSend(enabled);
        sendButton.setEnabled(enabled);
        // enabled  => ready/idle (green); disabled => a turn is in flight (orange).
        // A fatal error overrides this back to red explicitly via setTabStatus(FATAL).
        setTabStatus(enabled ? TabStatus.READY : TabStatus.THINKING);
    }

    private void refreshInputEnabled() {
        boolean wasDisabled = !inputField.isEnabled();
        if (!startupResolved) {
            inputField.setEnabled(false);
            inputField.setCanSend(false);
            sendButton.setEnabled(false);
            return;
        }
        if (aiBackend == null) {
            inputField.setEnabled(true);
            setSendEnabled(true);
            if (wasDisabled) {
                fireListenerEvent(SessionLifecycleListener::onChatEnabled);
            }
            return;
        }
        if (aiBackend.isPendingDiff()) {
            inputField.setEnabled(false);
            inputField.setCanSend(false);
            sendButton.setEnabled(false);
            setTabStatus(TabStatus.AWAITING_USER);
        }
        else {
            inputField.setEnabled(true);
            setSendEnabled(!aiBackend.isProcessing());
            if (wasDisabled) {
                fireListenerEvent(SessionLifecycleListener::onChatEnabled);
            }
        }
    }

    private void cancelCurrentRequest() {
        cancelledThisTurn = true;
        fireListenerEvent(SessionLifecycleListener::onStopRequested);
        if (aiBackend != null) {
            aiBackend.cancel();
        }
        infoBar.setProcessing(false);
        assistantTurnActive = false;
        conversationPanel.finaliseAssistantMessage();
        drainPendingInteractions();
        infoBar.setStatusMessage("Stopped at user's request");
        refreshInputEnabled();
        fireListenerEvent(SessionLifecycleListener::onStopped);
    }

    private void drainPendingInteractions() {
        for (AiDiffTopComponent diff : new ArrayList<>(openDiffs)) {
            diff.cancelAndClose();
        }
        openDiffs.clear();
        for (Runnable canceller : new ArrayList<>(pendingResponseCancellers)) {
            canceller.run();
        }
        pendingResponseCancellers.clear();
        clearPendingDiffAndRefreshInput();
    }

    private void clearPendingDiffAndRefreshInput() {
        if (aiBackend != null) {
            aiBackend.setPendingDiff(false);
        }
        refreshInputEnabled();
    }

    /**
     * If a streaming assistant turn is in progress, finalise it immediately. Call this before inserting any system
     * message that must appear after the assistant text that was streaming at the time of the interruption.
     */
    private void finaliseActiveAssistantIfNeeded() {
        if (assistantTurnActive) {
            assistantTurnActive = false;
            pendingNewlineBeforeText = false;
            conversationPanel.finaliseAssistantMessage();
        }
    }

    // --- AiSessionHost ---
    @Override
    public File resolveWorkDir() {
        File workDir = chosenSessionDir != null ? chosenSessionDir
                : contextProvider != null ? contextProvider.resolveWorkingDirectory()
                        : new File(System.getProperty("user.home"));
        return workDir != null ? workDir : new File(System.getProperty("user.home"));
    }

    public AiSession getSession() {
        return session;
    }

    @Override
    public AiSessionSettings getSessionSettings() {
        return session.settings();
    }

    @Override
    public void updateSessionSettings(AiSessionSettings newConfig) {
        infoBar.setAutoAccept(newConfig.effectiveAutoAccept());
        infoBar.setSaveHistory(newConfig.effectiveSaveHistory());
        try {
            sessionPersistenceManager.save(session);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not persist session config update", e);
        }
        refreshSessionIdentity();
    }

    @Override
    public void suppressNextTurn(String statusMessage, String completionMessage) {
        turnOutputSuppressed = true;
        suppressedTurnCompletionMessage = completionMessage;
        infoBar.setProcessing(true);
        setSendEnabled(false);
        if (statusMessage != null) {
            infoBar.setStatusMessage(statusMessage);
        }
    }

    private void showDiff(ToolUseEvent tu) {
        String fp = tu.filePath();
        if (fp == null || fp.isBlank()) {
            LOG.log(Level.WARNING, "File modification event has null/empty path — ignoring");
            return;
        }
        String proposed = tu.proposedContent() != null ? tu.proposedContent() : "";
        String original = tu.originalContent() != null ? tu.originalContent() : "";

        enterAwaitingUserState();
        finaliseActiveAssistantIfNeeded();
        conversationPanel.addSystemMessage(NotificationUtil.formatToolActionQuestion(tu.toolName(), shortPath(fp)));

        AiDiffTopComponent diff = new AiDiffTopComponent(fp, original, proposed, session.name());
        final AiImplementation backendSnap = aiBackend;
        File wdResolved = chosenSessionDir != null ? chosenSessionDir
                : contextProvider != null ? contextProvider.resolveWorkingDirectory()
                        : new File(System.getProperty("user.home"));
        final File wd = wdResolved != null ? wdResolved : new File(System.getProperty("user.home"));
        List<File> pd = contextProvider != null
                ? contextProvider.getAllOpenProjectDirs() : List.of();
        diff.addDecisionListener(new DiffDecisionListener() {
            @Override
            public void onAccepted(String message) {
                openDiffs.remove(diff);
                if (backendSnap != null) {
                    backendSnap.setPendingDiff(false);
                    backendSnap.sendPrompt(appendDecisionMessage("Changes accepted.", message), wd, pd);
                }
                conversationPanel.addSystemMessage(NotificationUtil.formatFileAcceptedTool(tu.toolName(), shortPath(fp)));
                refreshInputEnabled();
            }

            @Override
            public void onRejected(String message) {
                openDiffs.remove(diff);
                try {
                    Files.writeString(Path.of(fp), original, RefactoringProvider.resolveCharset(fp));
                    // This write bypasses NetBeans, so its cached copy still holds the edit the user just rejected.
                    // Without the refresh the editor can keep showing the rejected text, and worse, the next
                    // ApplyEdit matches against that stale copy and writes it back — silently undoing the revert.
                    FileUtils.refreshAfterWrite(fp);
                    LOG.log(Level.INFO, "Reverted {0} after user rejected AI''s edit", fp);
                }
                catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not revert " + fp, e);
                }
                if (backendSnap != null) {
                    backendSnap.setPendingDiff(false);
                    backendSnap.sendPrompt(appendDecisionMessage("Changes rejected, file reverted.", message), wd, pd);
                }
                conversationPanel.addSystemMessage(NotificationUtil.formatFileRejectedTool(tu.toolName(), shortPath(fp), message));
                refreshInputEnabled();
            }
        });
        openDiffs.add(diff);
        diff.open();
        diff.requestActive();
    }

    private void snapshotActiveFile() {
        preEditFilePath = null;
        preEditFileContent = null;
        if (contextProvider == null) {
            return;
        }
        FileObject fo = contextProvider.getActiveFile();
        if (fo == null) {
            return;
        }
        final String foPath = fo.getPath();
        RequestProcessor.getDefault().execute(() -> {
            try {
                Path p = Path.of(foPath);
                if (Files.exists(p) && !Files.isDirectory(p)) {
                    String content = Files.readString(p, RefactoringProvider.resolveCharset(foPath));
                    SwingUtilities.invokeLater(() -> {
                        preEditFilePath = foPath;
                        preEditFileContent = content;
                    });
                }
            }
            catch (IOException e) {
                LOG.log(Level.FINE, "Could not snapshot active file", e);
            }
        });
    }

    private void showPermissionDiff(PermissionEvent pe) {
        if (infoBar.isAutoAccept()) {
            LOG.log(Level.INFO, "Auto-accepted: {0} {1}", new Object[]{pe.toolName(), pe.filePath()});
            finaliseActiveAssistantIfNeeded();
            conversationPanel.addSystemMessage(NotificationUtil.formatAutoAccepted(pe.toolName(), shortPath(pe.filePath())));
            pe.response().complete(PermissionDecision.allowed());
            return;
        }

        String fp = pe.filePath();
        // Fail closed: never allow a file mutation we cannot preview.
        if (fp == null || fp.isBlank()) {
            LOG.log(Level.WARNING, "Permission event missing file path — denying");
            pe.response().complete(PermissionDecision.denied("Access denied: missing file path"));
            return;
        }

        enterAwaitingUserState();

        RequestProcessor.getDefault().execute(() -> {
            String original = "";
            try {
                Path p = Path.of(fp);
                if (Files.exists(p)) {
                    original = Files.readString(p, RefactoringProvider.resolveCharset(fp));
                }
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not read file for permission diff — denying: " + fp, e);
                SwingUtilities.invokeLater(() -> {
                    clearPendingDiffAndRefreshInput();
                    conversationPanel.addSystemMessage(
                            NotificationUtil.formatPermissionDenied(pe.toolName(), shortPath(fp),
                                    "could not read file for diff preview"));
                    pe.response().complete(PermissionDecision.denied("Could not read file for diff preview"));
                });
                return;
            }
            final String orig = original;
            SwingUtilities.invokeLater(() -> finishPermissionDiff(pe, fp, orig));
        });
    }

    private void finishPermissionDiff(PermissionEvent pe, String fp, String original) {
        if (aiBackend == null || !aiBackend.isProcessing()) {
            pe.response().complete(PermissionDecision.denied("Permission request cancelled"));
            clearPendingDiffAndRefreshInput();
            return;
        }
        PermissionDiffPolicy.Decision decision = PermissionDiffPolicy.decide(
                pe.toolName(), fp, original, pe.oldString(), pe.newString(), pe.writeContent(), pe.replaceAll());
        switch (decision.outcome()) {
            case DENY -> {
                LOG.log(Level.WARNING, "Permission denied for {0}: {1}",
                        new Object[]{fp, decision.reason()});
                conversationPanel.addSystemMessage(
                        NotificationUtil.formatPermissionDenied(pe.toolName(), shortPath(fp), decision.reason()));
                pe.response().complete(PermissionDecision.denied(decision.reason()));
                clearPendingDiffAndRefreshInput();
                return;
            }
            case ALLOW_SILENT -> {
                pe.response().complete(PermissionDecision.allowed());
                clearPendingDiffAndRefreshInput();
                return;
            }
            case SHOW_DIFF -> {
                // fall through to open the panel
            }
        }

        final String prop = decision.proposedContent();
        if (prop == null) {
            pe.response().complete(PermissionDecision.denied("Could not build permission diff preview"));
            clearPendingDiffAndRefreshInput();
            return;
        }

        diffShownForCurrentTurn = true;
        finaliseActiveAssistantIfNeeded();
        conversationPanel.addSystemMessage(NotificationUtil.formatToolActionQuestion(pe.toolName(), shortPath(fp)));
        final Runnable canceller = () -> pe.response().complete(PermissionDecision.denied("Permission request cancelled"));
        pendingResponseCancellers.add(canceller);

        final String orig = original;

        SwingUtilities.invokeLater(() -> {
            if (aiBackend == null) {
                pendingResponseCancellers.remove(canceller);
                pe.response().complete(PermissionDecision.denied("Permission request cancelled"));
                clearPendingDiffAndRefreshInput();
                return;
            }
            AiDiffTopComponent diff = new AiDiffTopComponent(fp, orig, prop, session.name(), true);
            diff.addDecisionListener(new DiffDecisionListener() {
                @Override
                public void onAccepted(String message) {
                    openDiffs.remove(diff);
                    pendingResponseCancellers.remove(canceller);
                    pe.response().complete(PermissionDecision.allowed());
                    clearPendingDiffAndRefreshInput();
                    conversationPanel.addSystemMessage(NotificationUtil.formatFileAcceptedTool(pe.toolName(), shortPath(fp)));
                    new Timer((int) TimeoutEnum.ACCEPTED_DIFF_REFRESH_DELAY_MILLIS.millis(),
                            ev -> FileUtils.refreshAfterWrite(fp)) {
                        {
                            setRepeats(false);
                            start();
                        }
                    };
                }

                @Override
                public void onRejected(String message) {
                    openDiffs.remove(diff);
                    pendingResponseCancellers.remove(canceller);
                    pe.response().complete(PermissionDecision.denied(message));
                    clearPendingDiffAndRefreshInput();
                    conversationPanel.addSystemMessage(NotificationUtil.formatFileRejectedTool(pe.toolName(), shortPath(fp), message));
                }
            });
            openDiffs.add(diff);
            diff.open();
            diff.requestActive();
        });
    }

    /**
     * Entry point for a multi-file change set. The review object owns the batch for its whole life — it walks the items
     * in the AI-supplied order, accumulates the per-file decisions, produces the single aggregate reply and renders the
     * record written here. This method only decides between the auto-accept path and the interactive one; the walking
     * is {@link MultiReviewDriver}'s job.
     *
     * <p>
     * Auto-accept still constructs the review and still logs every file. Skipping it and losing the per-file record is
     * exactly the defect this feature replaces — a blind "Codex wants to modify 3 files".</p>
     */
    private void showMultiPermissionReview(MultiPermissionEvent mpe) {
        MultiPermissionReview review = new MultiPermissionReview(mpe, ProjectPathUtil::shortPath);

        if (infoBar.isAutoAccept()) {
            LOG.log(Level.INFO, "Auto-accepted multi-file change set of {0} files", mpe.items().size());
            finaliseActiveAssistantIfNeeded();
            review.autoAcceptAll();
            conversationPanel.addSystemMessage(review.log());
            return;
        }

        enterAwaitingUserState();
        finaliseActiveAssistantIfNeeded();
        new MultiReviewDriver(review, mpe.items()).start();
    }

    private void checkForFileChanges() {
        if (diffShownForCurrentTurn) {
            return;
        }
        if (aiBackend != null && aiBackend.isMcpActive()) {
            return;
        }
        String fp = preEditFilePath;
        String original = preEditFileContent;
        preEditFilePath = null;
        preEditFileContent = null;
        if (fp == null || original == null) {
            return;
        }
        try {
            String current = Files.readString(Path.of(fp), RefactoringProvider.resolveCharset(fp));
            if (!current.equals(original)) {
                LOG.log(Level.INFO, "File change detected via snapshot fallback: {0}", fp);
                ToolUseEvent tu = new ToolUseEvent("Edit", fp, current, original, ToolUseEvent.Kind.EDIT);
                showDiff(tu);
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not check for file changes: {0}", fp);
        }
    }

    /**
     * Persists the instruction text just delivered, so it is not delivered again after an IDE restart.
     * {@code ContextProvider}'s own record is in-memory and is recreated whenever the session is opened, which is why
     * an ON_FIRST_REQUEST session used to re-send its instructions on the first message of every run while ON_START —
     * whose guard was already persisted — did not.
     */
    private void recordInstructionsDelivered(String instructions) {
        if (session == null) {
            return;
        }
        session.setLastInjectedInstructions(instructions);
        PERSIST_EXECUTOR.execute(() -> {
            try {
                sessionPersistenceManager.save(session);
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not persist delivered session instructions", e);
            }
        });
    }

    private void deliverStartupInstructions() {
        if (session == null
                || session.sessionInstructionsDelivery() != SessionInstructionsDeliveryEnum.ON_START
                || session.isStartupInstructionsInjected()
                || aiBackend == null
                || !aiBackend.isRunning()
                || aiBackend.isProcessing()
                || session.settings() == null) {
            return;
        }
        String instructions = session.settings().sessionInstructions();
        if (instructions == null || instructions.isBlank()) {
            return;
        }
        List<File> projectDirs = contextProvider != null
                ? contextProvider.getAllOpenProjectDirs() : List.of();
        String prompt = contextProvider != null
                ? contextProvider.buildPreamble("", instructions)
                : "## Session Instructions\n" + instructions;
        infoBar.setProcessing(true);
        if (contextProvider != null) {
            aiBackend.updatePinnedContext(
                    contextProvider.buildIdentityBlock(),
                    contextProvider.buildProjectBaseline(),
                    instructions);
        }
        aiBackend.sendPrompt(prompt, resolveWorkDir(), projectDirs);
        if (contextProvider != null && contextProvider.consumeSessionInstructionsInjected()) {
            conversationPanel.addSystemMessage("Session Instructions Sent");
            recordInstructionsDelivered(instructions);
        }
        setSendEnabled(false);
        session.setStartupInstructionsInjected(true);
        try {
            sessionPersistenceManager.save(session);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not persist startup-instruction delivery", e);
        }
    }

    /**
     * Loads and applies saved history for this session. Disk I/O and JSON parsing (and the stored-session-validity disk
     * scan) run off the EDT on {@link #PERSIST_EXECUTOR} so opening or creating a session never blocks the UI while its
     * (possibly large) history file loads; only the final UI/state mutations in {@link #applyLoadedHistory} run on the
     * EDT.
     */
    private void loadHistory(CompletableFuture<Void> future) {
        if (historyManager == null || !isSaveHistoryEnabled()) {
            future.whenCompleteAsync((ignored, ex) -> SwingUtilities.invokeLater(this::deliverStartupInstructions),
                    PERSIST_EXECUTOR);
            SwingUtilities.invokeLater(this::resolveSessionDir);
            return;
        }
        HistoryPersistenceManager manager = historyManager;
        PERSIST_EXECUTOR.execute(() -> {
            loadHistoryInBackground(manager, future);
        });
    }

    /**
     * Runs off the EDT (see {@link #PERSIST_EXECUTOR}). Reads and parses the history file and checks stored-session
     * validity — both potentially slow disk operations — then hands the result to the EDT.
     */
    private void loadHistoryInBackground(HistoryPersistenceManager manager, CompletableFuture<Void> future) {
        LoadedHistory loaded;
        try {
            loaded = manager.load();
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load history", e);
            future.whenCompleteAsync((ignored, ex) -> SwingUtilities.invokeLater(this::deliverStartupInstructions),
                    PERSIST_EXECUTOR);
            SwingUtilities.invokeLater(this::resolveSessionDir);
            return;
        }

        // Don't block this pool thread waiting for the AI backend to start —
        // register a continuation instead, so the thread is released back to
        // PERSIST_EXECUTOR while waiting and the stored-session check runs (as a
        // fresh PERSIST_EXECUTOR task) only once start() actually completes.
        // whenCompleteAsync (not thenRunAsync) so the check still runs even if
        // start() completes exceptionally, matching the old swallow-and-proceed
        // behavior of the blocking future.get().
        future.whenCompleteAsync((ignored, ex) -> applyLoadedHistoryAfterStart(loaded), PERSIST_EXECUTOR);
    }

    private void applyLoadedHistoryAfterStart(LoadedHistory loaded) {
        AiImplementation backend = aiBackend;
        boolean storedSessionValid = loaded.sessionId() != null && backend != null
                && backend.isStoredSessionValid(loaded.sessionId());
        SwingUtilities.invokeLater(() -> applyLoadedHistory(loaded, storedSessionValid));
    }

    /**
     * EDT-only: applies a background-loaded history result to this tab's conversation panel and session state, then
     * resolves the session working directory (which depends on {@code chosenSessionDir} possibly having just been set
     * from the loaded history). No-ops safely if the tab was closed while the history was loading.
     */
    private void applyLoadedHistory(LoadedHistory loaded, boolean storedSessionValid) {
        if (aiBackend == null) {
            resolveSessionDir();
            return; // stale invokeLater fired after close
        }
        if (session != null) {
            session.setInstructionsLoaded(loaded.instructionsLoaded());
        }
        if (loaded.messages().isEmpty() && loaded.sessionId() == null) {
            resolveSessionDir();
            deliverStartupInstructions();
            return;
        }
        if (loaded.workingDir() != null) {
            File savedDir = new File(loaded.workingDir());
            if (savedDir.isDirectory()) {
                chosenSessionDir = savedDir;
            }
        }
        if (!loaded.messages().isEmpty()) {
            conversationPanel.restoreHistory(loaded.messages());
        }
        if (loaded.sessionId() != null) {
            if (storedSessionValid) {
                aiBackend.resumeSession(loaded.sessionId());
                if (contextProvider != null) {
                    contextProvider.resetSentContext();
                }
            }
            else {
                LOG.log(Level.INFO, "Saved session {0} not found in AI storage — history kept, session will not resume", loaded.sessionId());
                if (historyManager != null) {
                    try {
                        historyManager.save(loaded.messages(), null, loaded.workingDir(), loaded.instructionsLoaded());
                    }
                    catch (IOException e) {
                        LOG.log(Level.WARNING, "Could not resave history after invalid stored session", e);
                    }
                }
            }
        }
        resolveSessionDir();
    }

    private boolean isSaveHistoryEnabled() {
        return session != null && session.settings() != null
                ? session.settings().effectiveSaveHistory() : PluginSettings.isSaveHistory();
    }

    private void saveHistory() {
        if (historyManager == null || !isSaveHistoryEnabled()) {
            return;
        }
        try {
            String sid = aiBackend != null ? aiBackend.getSessionId() : null;
            File wd = aiBackend != null ? aiBackend.getSessionWorkingDir() : null;
            if (wd == null) {
                wd = chosenSessionDir;
            }
            historyManager.save(conversationPanel.getHistory(), sid, wd != null ? wd.getPath() : null,
                    session != null && session.isInstructionsLoaded());
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not save history", e);
        }
    }

    /**
     * Drives one {@link MultiPermissionReview} through the UI: shows the main-panel affordance, opens each file's diff
     * in turn, and tears the batch down on whichever exit path fires first.
     *
     * <p>
     * Every exit route funnels through {@link #finish()}, which closes anything this batch opened and writes the log
     * exactly once. That matters most on the paths the user did not choose: on a timeout or a turn cancellation a diff
     * panel may still be on screen, and leaving it there would show a panel whose decision has already been made.</p>
     *
     * <p>
     * All methods run on the EDT, matching the rest of this class; the file reads are the only work pushed off it.</p>
     */
    private final class MultiReviewDriver {

        private final MultiPermissionReview review;
        private final List<MultiPermissionItem> items;
        /**
         * Resolves the main-panel affordance. Held so teardown can complete it, which is what greys its buttons out —
         * otherwise a live-looking Accept/Reject would sit under a batch that has already been answered.
         */
        private final CompletableFuture<PermissionDecision> gate = new CompletableFuture<>();
        /**
         * Registered in {@code pendingResponseCancellers} so a stop, a turn end or the panel closing routes through
         * {@code review.cancelled(...)} rather than completing the response directly. The single-file canceller
         * completes the future as denied, which for a batch would bypass the review entirely: the log would be wrong,
         * and the backends would read a deliberate "no" where an interruption happened.
         */
        private final Runnable canceller;
        /**
         * ONE deadline for the whole batch, armed when the review starts and never re-armed per file. The set gets the
         * same total human-attention budget a single diff gets, so each panel after the first inherits whatever is left
         * of it — rather than N files silently buying N times the wait. Fires on the EDT, like everything else here.
         */
        private final Timer deadline;
        private AiDiffTopComponent openDiff;
        private boolean finished;

        MultiReviewDriver(MultiPermissionReview review, List<MultiPermissionItem> items) {
            this.review = review;
            this.items = items;
            this.canceller = () -> cancel(new CancellationException("Multi-file review cancelled"));
            this.deadline = new Timer((int) TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis(), ev -> expire());
            this.deadline.setRepeats(false);
        }

        void start() {
            pendingResponseCancellers.add(canceller);
            deadline.start();
            // Fail fast on anything unrenderable. An item with no proposed content is a file the user cannot review,
            // and the decided behaviour is that it declines the whole set — so say so NOW rather than after they have
            // stepped through the files that did render. Making them work first and then refusing is a worse version
            // of the same answer.
            MultiPermissionItem unrenderable = firstUnrenderable();
            if (unrenderable != null) {
                review.renderFailed(unrenderable.filePath());
                finish();
                return;
            }
            conversationPanel.showMultiConfirm(
                    ConfirmPanel.buildMultiConfirmPrompt(items.stream().map(i -> shortPath(i.filePath())).toList()),
                    gate);
            gate.whenComplete((decision, ex) -> SwingUtilities.invokeLater(() -> {
                if (review.isFinished()) {
                    return;
                }
                if (ex != null) {
                    cancel(ex);
                }
                else if (decision != null && decision.allow()) {
                    openNext();
                }
                else {
                    review.rejectAll();
                    finish();
                }
            }));
        }

        /**
         * The first item the producer could not render, or null if every file has proposed content. Checked once up
         * front rather than on arrival at each file — see {@link #start()}.
         */
        private MultiPermissionItem firstUnrenderable() {
            for (MultiPermissionItem item : items) {
                if (item.proposedContent() == null) {
                    return item;
                }
            }
            return null;
        }

        /**
         * Opens the diff for the item currently awaiting a decision. The file read is pushed off the EDT; a file that
         * cannot be read is a file the user cannot review, so it declines the whole set rather than falling back to a
         * blind yes/no.
         */
        private void openNext() {
            MultiPermissionItem item = review.currentItem();
            if (item == null) {
                finish();
                return;
            }
            String fp = item.filePath();
            if (fp == null || fp.isBlank()) {
                review.renderFailed(fp);
                finish();
                return;
            }
            RequestProcessor.getDefault().execute(() -> {
                String original = "";
                boolean readable = true;
                try {
                    Path p = Path.of(fp);
                    if (Files.exists(p)) {
                        original = Files.readString(p, RefactoringProvider.resolveCharset(fp));
                    }
                }
                catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not read file for multi-file review diff: " + fp, e);
                    readable = false;
                }
                final String orig = original;
                final boolean ok = readable;
                SwingUtilities.invokeLater(() -> {
                    if (review.isFinished()) {
                        return;
                    }
                    if (!ok) {
                        review.renderFailed(fp);
                        finish();
                        return;
                    }
                    openDiffFor(item, fp, orig);
                });
            });
        }

        private void openDiffFor(MultiPermissionItem item, String fp, String original) {
            if (aiBackend == null) {
                cancel(new CancellationException("Session closed during multi-file review"));
                return;
            }
            String proposed = item.proposedContent();
            if (proposed == null) {
                review.renderFailed(fp);
                finish();
                return;
            }
            diffShownForCurrentTurn = true;
            // Message field hidden: this batch answers with ONE aggregate decision, and that reply carries no
            // message or reason field, so anything typed here could never be delivered.
            AiDiffTopComponent diff = new AiDiffTopComponent(fp, original, proposed, session.name(), true, true);
            diff.addDecisionListener(new DiffDecisionListener() {
                @Override
                public void onAccepted(String message) {
                    forget(diff);
                    if (review.isFinished()) {
                        return;
                    }
                    review.accept();
                    if (review.isFinished()) {
                        finish();
                    }
                    else {
                        openNext();
                    }
                }

                @Override
                public void onRejected(String message) {
                    // Also fires when the user closes the diff tab without deciding, matching the
                    // single-file path: closing a diff is a rejection, not an interruption.
                    forget(diff);
                    if (review.isFinished()) {
                        return;
                    }
                    review.reject();
                    finish();
                }
            });
            openDiff = diff;
            openDiffs.add(diff);
            diff.open();
            diff.requestActive();
        }

        private void forget(AiDiffTopComponent diff) {
            openDiffs.remove(diff);
            if (openDiff == diff) {
                openDiff = null;
            }
        }

        /**
         * The turn was cancelled, the session stopped, or the component closed. Routes through the review so the
         * response completes EXCEPTIONALLY — that is how the backends tell an interruption from a deliberate "no".
         */
        private void cancel(Throwable cause) {
            review.cancelled(cause);
            finish();
        }

        /**
         * The whole-set deadline expired. Declines everything and tears down, so the user is not left looking at a
         * panel whose decision has already been made. Recorded as a timeout rather than a rejection: the reply is the
         * same either way, but the log must not attribute an expiry to the user.
         */
        private void expire() {
            if (review.isFinished()) {
                return;
            }
            LOG.log(Level.INFO, "Multi-file review timed out after {0} ms",
                    TimeoutEnum.USER_APPROVAL_WAIT_MILLIS.millis());
            review.timedOut();
            finish();
        }

        /**
         * Tears the batch down on whichever route got here first. Idempotent: the review resolves the response exactly
         * once, and this guard makes the UI side match, so the log is written once and no closed panel is closed twice.
         */
        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            // Stop the deadline on EVERY exit, not just its own. A timer left armed after the set resolved would fire
            // into a finished review, which is harmless because late calls are ignored — but relying on that would be
            // relying on a coincidence to cover a leak.
            deadline.stop();
            pendingResponseCancellers.remove(canceller);
            if (!gate.isDone()) {
                // Greys out the main-panel buttons. Re-enters this driver's own gate callback,
                // which returns immediately because the review is already finished.
                gate.complete(PermissionDecision.denied(null));
            }
            AiDiffTopComponent stillOpen = openDiff;
            openDiff = null;
            if (stillOpen != null) {
                openDiffs.remove(stillOpen);
                stillOpen.cancelAndClose();
            }
            conversationPanel.addSystemMessage(review.log());
            clearPendingDiffAndRefreshInput();
        }
    }

    /**
     * Tab status-circle states. See the STATUS_HEX_* colour legend above.
     */
    enum TabStatus {
        READY, THINKING, FATAL, AWAITING_USER
    }
}
