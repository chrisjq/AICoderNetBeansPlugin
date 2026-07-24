package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Window;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiInboxMessageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AskUserQuestionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.NotificationTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSessionCallback;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.events.AiInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.events.DiffDecisionListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.GlobalPropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.PromptHistory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.HistoryPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.HistoryPersistenceManager.LoadedHistory;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.NotificationUtil;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
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
    private static final ExecutorService PERSIST_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-session-persist");
        t.setDaemon(true);
        return t;
    });
    // Tab status circle:
    //   green  = ready / idle (AI is running and waiting for user input),
    //   orange = AI is thinking (a turn is in flight),
    //   red    = not running — the plugin/tab just started, or a fatal error
    //            occurred (auth failure, abnormal process exit).
    // The icon and the HTML tab-name dot are both driven from setTabStatus() so
    // the two renderers can never drift. Each status has exactly one Color
    // constant below; the icon and the HTML hex string are both derived from
    // it so they can never go out of sync.
    private static final Color STATUS_COLOR_GREEN = new Color(0x4C, 0xAF, 0x50);
    private static final Color STATUS_COLOR_ORANGE = new Color(0xFF, 0x98, 0x00);
    private static final Color STATUS_COLOR_RED = new Color(0xF4, 0x43, 0x36);
    // Briefly shown in place of orange each time AI output arrives while the
    // tab is THINKING. See flashThinking(). A paler gold (0xFFC855) rendered
    // black in the tab strip in testing — magenta is confirmed to render
    // correctly, so it's used here even though it's a more assertive colour
    // than the other status dots.
    private static final Color THINKING_FLASH_COLOR = new Color(0xFF, 0x00, 0xFF);

    private static final String STATUS_HEX_GREEN = toHex(STATUS_COLOR_GREEN);
    private static final String STATUS_HEX_ORANGE = toHex(STATUS_COLOR_ORANGE);
    private static final String STATUS_HEX_RED = toHex(STATUS_COLOR_RED);
    private static final String STATUS_HEX_ORANGE_LIGHT = toHex(THINKING_FLASH_COLOR);

    /**
     * Duration of the "AI output received" flash pulse, in milliseconds.
     */
    private static final int THINKING_FLASH_MS = 250;

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

    private final ConversationPanel conversationPanel;
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
     * Set when the user clicks Stop. Suppresses any buffered TextDeltaEvent or
     * ToolUseEvent that arrive via invokeLater after cancellation. Cleared when
     * the cancelled turn is officially done (STOPPED status or
     * TurnCompleteEvent).
     */
    private boolean cancelledThisTurn = false;

    /**
     * Set when a non-text event (tool use, permission) interrupts a streaming
     * turn, so the next text block gets a blank line separator before it.
     */
    private boolean pendingNewlineBeforeText = false;
    private boolean turnOutputSuppressed = false;
    private String suppressedTurnCompletionMessage = null;

    /**
     * Pre-prompt snapshot of the active file — used to show a diff after the AI
     * edits it. The stream-json format does not emit tool_use events for
     * internally-executed tools, so we detect edits by comparing disk content
     * before/after each turn.
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
     * False until the first startup attempt resolves to READY or a fatal
     * startup error. While false, the visible chat input/send controls stay
     * disabled so the user cannot submit a prompt against a still-loading
     * backend.
     */
    private boolean startupResolved = false;

    /**
     * Outstanding AskUserQuestion/Permission cancellers and open diff windows.
     * Multiple can be in flight at once (e.g. AskUserQuestion overlapping a
     * Permission), so track all and complete/close every one on teardown.
     * EDT-confined — all access is on the event dispatch thread.
     */
    private final Set<Runnable> pendingResponseCancellers
            = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<AiDiffTopComponent> openDiffs
            = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private final AiSession session;
    private final List<AbstractNotification> pendingNotifications = new ArrayList<>();
    private final List<AbstractNotification> deferredNotifications = new ArrayList<>();
    private final SessionPersistenceManager sessionPersistenceManager;

    private volatile boolean sendEnabled = false;

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
        inputField = new AiInputField(promptHistory);
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
        inputField.setEnabled(false);
        inputField.setCanSend(false);
        sendButton.setEnabled(false);
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
        });

        add(contextLabel, BorderLayout.NORTH);
        add(conversationPanel, BorderLayout.CENTER);

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
        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.add(new JScrollPane(inputField,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);
        bottom.add(inputRow, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

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

    private String shortPath(String fp) {
        if (fp == null || fp.isBlank()) {
            return fp;
        }
        if (contextProvider != null) {
            for (File projectDir : contextProvider.getAllOpenProjectDirs()) {
                String base = projectDir.getAbsolutePath();
                if (fp.startsWith(base)) {
                    String rel = fp.substring(base.length());
                    if (rel.startsWith(File.separator)) {
                        rel = rel.substring(1);
                    }
                    return rel.length() > 128 ? "..." + rel.substring(rel.length() - 125) : rel;
                }
            }
        }
        String name = Path.of(fp).getFileName().toString();
        return name.length() > 128 ? "..." + name.substring(name.length() - 125) : name;
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

    private void flushPendingNotifications(AbstractNotification... extra) {
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
            return;
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
            return;
        }
        // Combine into a SINGLE turn — handleSubmit/sendPrompt runs one turn at a
        // time, so submitting in a loop would drop all but the first.
        submitNotificationTurn(NotificationTypeEnum.NEW_INBOX_MESSAGE,
                String.join("\n\n", texts));
    }

    private void submitNotificationTurn(NotificationTypeEnum type, String notificationText) {
        handleSubmit(type.prefix() + " " + notificationText);
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
        // Immediately propagate restrictToProjectFiles to the MCP hook server's
        // scope map so file-tool permission changes take effect on the next tool
        // call, not only after the next user submit.
        Object mcpObj = aiBackend != null ? aiBackend.getMcpServer() : null;
        if (mcpObj instanceof McpHookServer mcp) {
            List<java.io.File> dirs = contextProvider != null
                    ? contextProvider.getAllOpenProjectDirs() : List.of();
            boolean restrict = session.settings() != null
                    && session.settings().effectiveRestrictToProjectFiles();
            mcp.updateSessionScope(session.id(), session.aiType(), dirs, restrict);
        }
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
            if (!PluginSettings.isSaveHistory()) {
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
                Object mcpObj = aiBackend != null ? aiBackend.getMcpServer() : null;
                if (mcpObj instanceof McpHookServer mcp) {
                    List<File> dirs = contextProvider != null ? contextProvider.getAllOpenProjectDirs() : List.of();
                    boolean restrict = session.settings() != null && session.settings().effectiveRestrictToProjectFiles();
                    mcp.updateSessionScope(session.id(), session.aiType(), dirs, restrict);
                }
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
        // Register session file scope with MCP server
        Object mcpObj = aiBackend != null ? aiBackend.getMcpServer() : null;
        if (mcpObj instanceof McpHookServer mcpServer) {
            List<File> dirs = contextProvider != null
                    ? contextProvider.getAllOpenProjectDirs() : List.of();
            boolean restrict = session.settings() != null
                    && session.settings().effectiveRestrictToProjectFiles();
            mcpServer.registerSession(session.id(), session.aiType(), dirs, restrict);
        }
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
            SwingUtilities.invokeLater(() -> conversationPanel.addSystemMessage(
                    NotificationUtil.formatInboxMessage(ime.fromName(), ime.subject())));
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
     * Resolve (or prompt the user to choose) the working directory for this
     * session. No-op if already set. Must be called on the EDT.
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
        for (AiDiffTopComponent d : new ArrayList<>(openDiffs)) {
            d.cancelAndClose();
        }
        openDiffs.clear();
        for (Runnable c : new ArrayList<>(pendingResponseCancellers)) {
            c.run();
        }
        pendingResponseCancellers.clear();
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
        String safeName = getDisplayName()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><font color='" + tabStatusColor() + "'>&#9679;</font> " + safeName + "</html>";
    }

    private void updateTabHtmlName() {
        String safeName = getDisplayName()
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        setHtmlDisplayName("<html><font color='" + tabStatusColor() + "'>&#9679;</font> " + safeName + "</html>");
    }

    /**
     * Tab tooltip: "&lt;name&gt; - &lt;AiType&gt; - Ai Coder".
     */
    private void updateTabTooltip() {
        setToolTipText(session.name() + " - " + session.aiType().displayName() + " - Ai Coder");
    }

    private String tabStatusColor() {
        return switch (tabStatus) {
            case READY ->
                STATUS_HEX_GREEN;
            case THINKING ->
                thinkingFlashActive ? STATUS_HEX_ORANGE_LIGHT : STATUS_HEX_ORANGE;
            case FATAL ->
                STATUS_HEX_RED;
        };
    }

    /**
     * Single source of truth for the tab status circle (the HTML dot in the tab
     * name — NetBeans's tab strip doesn't reliably honour setIcon() here).
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
     * Records a THINKING-flash request. Non-EDT callers coalesce to at most one
     * pending invokeLater; repeated requests while that runnable or the timer
     * is already active just push the deadline out.
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
     * Briefly flashes the THINKING (orange) status dot yellow for
     * {@link #THINKING_FLASH_MS} whenever AI output arrives while a turn is in
     * flight, then reverts to plain orange. EDT-only: callers must use
     * requestThinkingFlash() off-thread.
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
     * Called from the backend event thread — coalesce the flash pulse request,
     * then dispatch full event handling to the EDT.
     */
    @Override
    public void onAiProcessEvent(AiProcessEvent event) {
        if (tabStatus == TabStatus.THINKING && isVisibleChatOutput(event)) {
            requestThinkingFlash();
        }
        SwingUtilities.invokeLater(() -> handleEvent(event));
    }

    /**
     * True for events that actually render new AI-generated content in the chat
     * panel. Internal/plumbing events (status updates, impl events, turn
     * boundaries) must not trigger the THINKING flash pulse — only output the
     * user can actually see arriving should. Mirrors handleEvent()'s own
     * rendering conditions: an empty TextDeltaEvent renders nothing, and a
     * ToolUseEvent only renders a diff when it's a file modification AND MCP
     * isn't active (MCP-active sessions render file edits via PermissionEvent
     * instead) — otherwise every Read/Bash/Grep/MCP tool call would flash the
     * tab despite adding nothing visible to the chat.
     */
    private boolean isVisibleChatOutput(AiProcessEvent event) {
        if (event instanceof TextDeltaEvent td) {
            return td.text() != null && !td.text().isEmpty();
        }
        if (event instanceof ToolUseEvent tu) {
            return tu.isFileModification() && (aiBackend == null || !aiBackend.isMcpActive());
        }
        return event instanceof PermissionEvent || event instanceof AskUserQuestionEvent;
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
                infoBar.setProcessing(false);
                setSendEnabled(true);
                infoBar.setStatusMessage(suppressedTurnCompletionMessage != null
                        ? suppressedTurnCompletionMessage : "Ready...");
                suppressedTurnCompletionMessage = null;
                saveHistory();
                SwingUtilities.invokeLater(this::flushPendingNotifications);
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
            infoBar.setProcessing(false);
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
            infoBar.setStatusMessage("Ready...");
            refreshInputEnabled();
            fireListenerEvent(SessionLifecycleListener::onTurnComplete);
            SwingUtilities.invokeLater(this::flushPendingNotifications);
        }
        else if (event instanceof AskUserQuestionEvent aqe) {
            if (assistantTurnActive) {
                assistantTurnActive = false;
                conversationPanel.finaliseAssistantMessage();
            }
            if (aiBackend != null) {
                aiBackend.setPendingDiff(true);
            }
            inputField.setEnabled(false);
            sendButton.setEnabled(false);
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
        else if (event instanceof PermissionEvent pe) {
            pendingNewlineBeforeText = true;
            showPermissionDiff(pe);
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
                    infoBar.setProcessing(false);
                    if (conversationPanel != null) {
                        conversationPanel.addSystemMessage(se.text());
                    }
                }
                default ->
                    infoBar.setStatusMessage(se.text());
            }
        }
        else if (infoBarExtension != null && event instanceof AiProcessImplEvent si) {
            infoBarExtension.onAiProcessImplEvent(si);
        }
    }

    private void handleSubmit(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        synchronized (this) {
            if (!deferredNotifications.isEmpty()) {
                String deferred = deferredNotifications.stream()
                        .filter(AbstractNotification::shouldDeliver)
                        .map(AbstractNotification::text)
                        .collect(java.util.stream.Collectors.joining("\n"));
                deferredNotifications.clear();
                if (!deferred.isEmpty()) {
                    text = text + "\n\n[Pending inbox messages]\n" + deferred;
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
        String fullPrompt = contextProvider != null
                ? contextProvider.buildPreamble(text, sessionInstructions)
                : text;
        conversationPanel.addUserMessage(text);
        infoBar.setProcessing(true);
        infoBar.setStatusMessage("Thinking…");
        diffShownForCurrentTurn = false;
        snapshotActiveFile();
        // Refresh the MCP file-access scope to the currently-open projects so a
        // project opened after session start is reachable by plugin tools too
        // (the CLI already gets it via --add-dir / projectDirs below).
        Object mcpObj = aiBackend != null ? aiBackend.getMcpServer() : null;
        if (mcpObj instanceof McpHookServer mcp) {
            boolean restrict = session.settings() != null
                    && session.settings().effectiveRestrictToProjectFiles();
            mcp.updateSessionScope(session.id(), session.aiType(), projectDirs, restrict);
        }
        if (aiBackend != null) {
            aiBackend.sendPrompt(fullPrompt, workDir, projectDirs);
        }
        setSendEnabled(false);
    }

    private void setSendEnabled(boolean enabled) {
        sendEnabled = enabled;
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
        infoBar.setStatusMessage("Stopped at user's request");
        refreshInputEnabled();
        fireListenerEvent(SessionLifecycleListener::onStopped);
    }

    /**
     * If a streaming assistant turn is in progress, finalise it immediately.
     * Call this before inserting any system message that must appear after the
     * assistant text that was streaming at the time of the interruption.
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

        if (aiBackend != null) {
            aiBackend.setPendingDiff(true);
        }
        inputField.setEnabled(false);
        sendButton.setEnabled(false);
        finaliseActiveAssistantIfNeeded();
        conversationPanel.addSystemMessage(NotificationUtil.formatEdit(shortPath(fp)));

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
                conversationPanel.addSystemMessage(NotificationUtil.formatFileAccepted(shortPath(fp)));
                refreshInputEnabled();
            }

            @Override
            public void onRejected(String message) {
                openDiffs.remove(diff);
                try {
                    Files.writeString(Path.of(fp), original, StandardCharsets.UTF_8);
                    LOG.log(Level.INFO, "Reverted {0} after user rejected AI''s edit", fp);
                }
                catch (IOException e) {
                    LOG.log(Level.WARNING, "Could not revert " + fp, e);
                }
                if (backendSnap != null) {
                    backendSnap.setPendingDiff(false);
                    backendSnap.sendPrompt(appendDecisionMessage("Changes rejected, file reverted.", message), wd, pd);
                }
                conversationPanel.addSystemMessage(NotificationUtil.formatFileRejected(shortPath(fp), message));
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
                    String content = Files.readString(p, StandardCharsets.UTF_8);
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

        RequestProcessor.getDefault().execute(() -> {
            String original = "";
            try {
                Path p = Path.of(fp);
                if (Files.exists(p)) {
                    original = Files.readString(p, StandardCharsets.UTF_8);
                }
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not read file for permission diff — denying: " + fp, e);
                SwingUtilities.invokeLater(() -> {
                    conversationPanel.addSystemMessage(
                            NotificationUtil.formatPermissionDenied(shortPath(fp),
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
        PermissionDiffPolicy.Decision decision = PermissionDiffPolicy.decide(
                pe.toolName(), fp, original, pe.oldString(), pe.newString(), pe.writeContent());
        switch (decision.outcome()) {
            case DENY -> {
                LOG.log(Level.WARNING, "Permission denied for {0}: {1}",
                        new Object[]{fp, decision.reason()});
                conversationPanel.addSystemMessage(
                        NotificationUtil.formatPermissionDenied(shortPath(fp), decision.reason()));
                pe.response().complete(PermissionDecision.denied(decision.reason()));
                return;
            }
            case ALLOW_SILENT -> {
                pe.response().complete(PermissionDecision.allowed());
                return;
            }
            case SHOW_DIFF -> {
                // fall through to open the panel
            }
        }

        final String prop = decision.proposedContent();
        if (prop == null) {
            pe.response().complete(PermissionDecision.denied("Could not build permission diff preview"));
            return;
        }

        diffShownForCurrentTurn = true;
        finaliseActiveAssistantIfNeeded();
        conversationPanel.addSystemMessage(NotificationUtil.formatToolAction(pe.toolName(), shortPath(fp)));
        final Runnable canceller = () -> pe.response().complete(PermissionDecision.denied("Permission request cancelled"));
        pendingResponseCancellers.add(canceller);

        final String orig = original;

        SwingUtilities.invokeLater(() -> {
            if (aiBackend == null) {
                pendingResponseCancellers.remove(canceller);
                pe.response().complete(PermissionDecision.denied("Permission request cancelled"));
                return;
            }
            AiDiffTopComponent diff = new AiDiffTopComponent(fp, orig, prop, session.name(), true);
            diff.addDecisionListener(new DiffDecisionListener() {
                @Override
                public void onAccepted(String message) {
                    openDiffs.remove(diff);
                    pendingResponseCancellers.remove(canceller);
                    pe.response().complete(PermissionDecision.allowed());
                    conversationPanel.addSystemMessage(NotificationUtil.formatFileAccepted(shortPath(fp)));
                    new Timer(600, ev -> {
                        try {
                            FileObject fo = FileUtil.toFileObject(new File(fp));
                            if (fo != null) {
                                fo.refresh();
                            }
                        }
                        catch (Exception ignored) {
                        }
                    }) {
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
                    conversationPanel.addSystemMessage(NotificationUtil.formatFileRejected(shortPath(fp), message));
                }
            });
            openDiffs.add(diff);
            diff.open();
            diff.requestActive();
        });
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
            String current = Files.readString(Path.of(fp), StandardCharsets.UTF_8);
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
     * Loads and applies saved history for this session. Disk I/O and JSON
     * parsing (and the stored-session-validity disk scan) run off the EDT on
     * {@link #PERSIST_EXECUTOR} so opening or creating a session never blocks
     * the UI while its (possibly large) history file loads; only the final
     * UI/state mutations in {@link #applyLoadedHistory} run on the EDT.
     */
    private void loadHistory(CompletableFuture<Void> future) {
        if (historyManager == null || !PluginSettings.isSaveHistory()) {
            SwingUtilities.invokeLater(this::resolveSessionDir);
            return;
        }
        HistoryPersistenceManager manager = historyManager;
        PERSIST_EXECUTOR.execute(() -> {
            loadHistoryInBackground(manager, future);
        });
    }

    /**
     * Runs off the EDT (see {@link #PERSIST_EXECUTOR}). Reads and parses the
     * history file and checks stored-session validity — both potentially slow
     * disk operations — then hands the result to the EDT.
     */
    private void loadHistoryInBackground(HistoryPersistenceManager manager, CompletableFuture<Void> future) {
        LoadedHistory loaded;
        try {
            loaded = manager.load();
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load history", e);
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
     * EDT-only: applies a background-loaded history result to this tab's
     * conversation panel and session state, then resolves the session working
     * directory (which depends on {@code chosenSessionDir} possibly having just
     * been set from the loaded history). No-ops safely if the tab was closed
     * while the history was loading.
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

    private void saveHistory() {
        if (historyManager == null || !PluginSettings.isSaveHistory()) {
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
     * Tab status-circle states. See the STATUS_HEX_* colour legend above.
     */
    private enum TabStatus {
        READY, THINKING, FATAL
    }
}
