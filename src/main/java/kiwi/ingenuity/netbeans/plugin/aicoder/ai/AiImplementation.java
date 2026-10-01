package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

public abstract class AiImplementation {

    protected final AiTypeEnum type;
    protected final AiProcessEventListener listener;
    /**
     * UI-side hook for locating a missing CLI executable (shows a file chooser) without this class needing to
     * know about Swing/EDT. See {@link ExecutablePrompter}.
     */
    protected final ExecutablePrompter prompter;
    /**
     * The session model object; set via {@link #setCurrentSession}.
     */
    protected volatile AiSession currentSession;

    private static final Logger LOG = Logger.getLogger(AiImplementation.class.getName());

    private final SerialBackgroundExecutor sendQueue = new SerialBackgroundExecutor();
    private final Object sendLock = new Object();
    private final ArrayDeque<PendingSend> queuedSends = new ArrayDeque<>();   // guarded by sendLock
    private boolean sendInsideManager;     // guarded by sendLock
    private boolean cancelPending;         // guarded by sendLock
    private volatile Runnable busyStateListener;

    protected AiImplementation(AiTypeEnum type, AiProcessEventListener listener, ExecutablePrompter prompter) {
        this.listener = listener;
        this.type = type;
        this.prompter = prompter;
    }

    /**
     * The backend process manager this implementation delegates to.
     */
    protected abstract AiProcessManager delegate();

    public void start(String executableOrConfig, String modelOrConfig) {
        delegate().start(executableOrConfig, modelOrConfig);
        afterStart();
    }

    /**
     * Where the work that takes the process manager's monitor runs, in submission order, never on the
     * caller's
     * thread. The default is one queue per implementation; a subclass whose other session-control work takes
     * the same monitor returns its own executor so that work and the sends keep their order.
     */
    protected Executor sessionControlExecutor() {
        return sendQueue;
    }

    /**
     * Hands the prompt to the backend on the session-control executor and returns at once. Every backend's
     * {@code sendPrompt} can wait for a monitor that {@code start()} or a recycle holds for up to two
     * minutes,
     * and this is called from the EDT. While the prompt is still queued, {@link #isBusy()} and
     * {@link #isProcessing()} report it, so a Stop sees work to wait for; the turn's own closing event, or
     * the
     * STOPPED / FAILED raised here, releases the UI's busy lock.
     */
    public void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        sendPrompt(text, workingDir, projectDirs, null, null);
    }

    /**
     * As {@link #sendPrompt(String, File, List)}, and reports what became of the prompt so a caller can
     * record
     * "delivered" state only when it really got there. Exactly one of the two callbacks runs (either may be
     * null): {@code onHandedOver} on the session-control thread once the backend has accepted the prompt, or
     * {@code onNotDelivered}, on whichever thread dropped it, when a Cancel or Stop dropped the prompt before
     * the backend had it, the backend threw, or the executor refused it.
     */
    public void sendPrompt(String text, File workingDir, List<File> projectDirs, Runnable onHandedOver,
                           Runnable onNotDelivered) {
        List<File> dirs = projectDirs == null ? null : List.copyOf(projectDirs);
        PendingSend pending = new PendingSend(text, workingDir, dirs, onHandedOver, onNotDelivered);
        synchronized (sendLock) {
            queuedSends.add(pending);
        }
        try {
            sessionControlExecutor().execute(() -> runQueuedSend(pending));
        }
        catch (RuntimeException e) {
            boolean stillQueued;
            synchronized (sendLock) {
                stillQueued = queuedSends.remove(pending);
            }
            LOG.log(Level.WARNING, "sendPrompt could not be queued", e);
            if (stillQueued) {
                runCallback(pending.onNotDelivered);
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(e.getMessage())));
            }
        }
    }

    private record PendingSend(String text, File workingDir, List<File> projectDirs, Runnable onHandedOver,
                               Runnable onNotDelivered) {

    }

    private static void runCallback(Runnable callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        }
        catch (RuntimeException e) {
            LOG.log(Level.WARNING, "sendPrompt callback failed", e);
        }
    }

    /**
     * Called, off the caller's thread, whenever a send stops counting as busy without the backend having a
     * state of its own to show for it. The turn's closing event can be handled before the send has returned
     * from the backend, while {@link #isBusy()} still reports it, so whoever refreshes the UI from
     * {@code isBusy()} needs this to look again.
     */
    public void setBusyStateListener(Runnable listener) {
        this.busyStateListener = listener;
    }

    private void runQueuedSend(PendingSend pending) {
        synchronized (sendLock) {
            if (!queuedSends.remove(pending)) {
                return;
            }
            sendInsideManager = true;
            cancelPending = false;
        }
        boolean handedOver = false;
        try {
            try {
                delegate().sendPrompt(pending.text, pending.workingDir, pending.projectDirs);
                handedOver = true;
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "sendPrompt failed", e);
                runCallback(pending.onNotDelivered);
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(e.getMessage())));
            }
            if (handedOver) {
                runCallback(pending.onHandedOver);
            }
        }
        finally {
            boolean deliverCancel;
            synchronized (sendLock) {
                sendInsideManager = false;
                // A send that threw has already been closed by its FAILED; a held Cancel would be a second closer.
                deliverCancel = cancelPending && handedOver;
                cancelPending = false;
            }
            try {
                if (deliverCancel && delegate().isBusy()) {
                    delegate().interrupt(InterruptTypeEnum.Cancel);
                }
            }
            finally {
                notifyBusyStateChanged();
            }
        }
    }

    private void notifyBusyStateChanged() {
        Runnable notify = busyStateListener;
        if (notify == null) {
            return;
        }
        try {
            notify.run();
        }
        catch (RuntimeException e) {
            LOG.log(Level.WARNING, "busy-state listener failed", e);
        }
    }

    /**
     * Removes every send that has not reached the backend yet and returns them; the caller reports them as
     * not delivered once it is out of the lock.
     */
    private List<PendingSend> dropQueuedSendsLocked() {
        List<PendingSend> dropped = List.copyOf(queuedSends);
        queuedSends.clear();
        return dropped;
    }

    private static void reportNotDelivered(List<PendingSend> dropped) {
        for (PendingSend send : dropped) {
            runCallback(send.onNotDelivered);
        }
    }

    /**
     * True from the moment a send is queued until the backend's own {@code sendPrompt} has returned. Until
     * then the backend can still be waiting for its monitor and reports nothing, so this is what keeps the UI
     * from seeing an idle session in the middle of a send.
     */
    private boolean sendInFlight() {
        synchronized (sendLock) {
            return !queuedSends.isEmpty() || sendInsideManager;
        }
    }

    public void cancel() {
        interrupt(InterruptTypeEnum.Cancel);
    }

    /**
     * Graceful interrupt that aborts the in-flight turn. Backends that support a graceful interrupt keep
     * partial output; others terminate the process. A Cancel also drops any send still queued, and closes the
     * busy lock the UI took for it.
     */
    public void interrupt(InterruptTypeEnum type) {
        if (type != InterruptTypeEnum.Cancel) {
            delegate().interrupt(type);
            return;
        }
        boolean forward = true;
        boolean closeHere = false;
        List<PendingSend> dropped;
        synchronized (sendLock) {
            dropped = dropQueuedSendsLocked();
            if (sendInsideManager) {
                if (!delegate().isBusy()) {
                    cancelPending = true;
                    forward = false;
                }
            }
            else if (!dropped.isEmpty() && !delegate().isBusy()) {
                forward = false;
                closeHere = true;
            }
        }
        reportNotDelivered(dropped);
        if (closeHere) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED, StatusMessageUtil.formatStopped()));
        }
        if (forward) {
            delegate().interrupt(type);
        }
    }

    public void stop() {
        List<PendingSend> dropped;
        synchronized (sendLock) {
            dropped = dropQueuedSendsLocked();
        }
        reportNotDelivered(dropped);
        delegate().stop();
    }

    public String getSessionId() {
        return delegate().getSessionId();
    }

    public File getSessionWorkingDir() {
        return delegate().getSessionWorkingDir();
    }

    public abstract void setModel(String model);

    /**
     * Pushes session settings into the running backend. Called on every OK of the session config dialog,
     * whether or not anything about the model was touched.
     *
     * <p>
     * Only forwards a model that actually differs from the one the backend is already on. {@code setModel} is
     * not a plain setter for every backend — Claude and Copilot recycle the CLI session from it — so calling
     * it with an unchanged value discarded a warm session on every config save. Worse for Claude, whose
     * recycle carried no in-flight-turn guard: saving the dialog mid-turn killed the session while the turn
     * stayed marked in flight, and from there Stop was a silent no-op and the chat input never re-enabled.
     * The guard in ClaudeAiProcessManager.recycleForModelChange() now covers that too; this comparison stops
     * the pointless recycle happening at all, for every backend at once.
     */
    public void applySessionSettings(AiSessionSettings settings) {
        if (settings instanceof AiModelSessionSettings modelSettings
            && modelSettings.model() != null && !modelSettings.model().isBlank()
            && !Objects.equals(modelSettings.model(), delegate().getModel())) {
            setModel(modelSettings.model());
        }
    }

    public void setPendingDiff(boolean pending) {
        delegate().setPendingDiff(pending);
    }

    public boolean isRunning() {
        return delegate().isRunning();
    }

    public boolean isProcessing() {
        return sendInFlight() || delegate().isProcessing();
    }

    /**
     * True while the backend is doing anything the user must wait for — a turn or non-turn work such as a
     * compaction. {@link #isProcessing()} is turns only.
     */
    public boolean isBusy() {
        return sendInFlight() || delegate().isBusy();
    }

    /**
     * True while non-turn work such as compaction owns the shared busy state.
     */
    public boolean isWorkInFlight() {
        return delegate().isWorkInFlight();
    }

    public boolean isPendingDiff() {
        return delegate().isPendingDiff();
    }

    public Object getMcpServer() {
        return delegate().getMcpServer();
    }

    public boolean isMcpActive() {
        return delegate().isMcpActive();
    }

    public void updatePinnedContext(String identity, String baseline, String instructions) {
        delegate().updatePinnedContext(identity, baseline, instructions);
    }

    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        return null;
    }

    /**
     * Type-wide services that should start when the first session of this AI type is created. The registry
     * owns this object rather than retaining this UI-bound implementation instance until plugin shutdown.
     */
    public AiTypeLifecycle typeLifecycle() {
        return AiTypeLifecycle.NO_OP;
    }

    public void registerLifecycleListeners(SessionLifecycleSource source) {
    }

    public void onTabActivated() {
    }

    public void resumeSession(String sessionId) {
        delegate().resumeSession(sessionId);
    }

    public boolean isStoredSessionValid(String sessionId) {
        return true;
    }

    public void startWithDiscovery(String model) {
        start(null, model);
    }

    public void setCurrentSession(AiSession session) {
        this.currentSession = session;
        delegate().setCurrentSession(session);
    }

    public Path getSessionConfigPath() {
        String sid = getSessionId();
        if (sid == null || sid.isBlank()) {
            return null;
        }
        try {
            return PluginUtil.getPluginAiSessionConfigDir(type, sid);
        }
        catch (IOException e) {
            return null;
        }
    }

    public abstract void onStarted(AiSessionHost session);

    protected abstract void afterStart();
}
