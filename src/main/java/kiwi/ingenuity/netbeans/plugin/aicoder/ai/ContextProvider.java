package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

public class ContextProvider {

    private static final Logger LOG = Logger.getLogger(ContextProvider.class.getName());

    private volatile FileObject activeFile;
    private final Consumer<FileObject> onFileChanged;
    private PropertyChangeListener registryListener;

    private volatile AiSession session;

    private volatile List<String> lastSentProjects = null;
    private volatile FileObject lastSentFile = null;
    private volatile String lastInjectedSessionInstructions;
    private volatile boolean sessionInstructionsInjectedInLastPreamble;

    public ContextProvider(Consumer<FileObject> onFileChanged) {
        this.onFileChanged = onFileChanged;
    }

    public void setSession(AiSession session) {
        this.session = session;
    }

    public void start() {
        stop();
        registryListener = evt -> {
            if (TopComponent.Registry.PROP_ACTIVATED.equals(evt.getPropertyName())) {
                updateActiveFile();
            }
        };
        WindowManager.getDefault().getRegistry().addPropertyChangeListener(registryListener);
        updateActiveFile();
    }

    public void stop() {
        if (registryListener != null) {
            WindowManager.getDefault().getRegistry().removePropertyChangeListener(registryListener);
            registryListener = null;
        }
    }

    private void updateActiveFile() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateActiveFile);
            return;
        }
        TopComponent tc = TopComponent.getRegistry().getActivated();
        if (tc == null) {
            activeFile = null;
            onFileChanged.accept(null);
            return;
        }
        DataObject dob = tc.getLookup().lookup(DataObject.class);
        if (dob == null) {
            return; // non-editor TC (e.g. our own panel) — keep last known file
        }
        FileObject fo = dob.getPrimaryFile();
        if (fo == null || fo.isFolder()) {
            return; // project/directory node — not a file
        }
        if (!fo.equals(activeFile)) {
            activeFile = fo;
            onFileChanged.accept(fo);
        }
    }

    /**
     * Reset context tracking so the next buildPreamble() call always sends the
     * full context. Call when starting a new session or resuming from saved
     * history.
     */
    public void resetSentContext() {
        lastSentProjects = null;
        lastSentFile = null;
        lastInjectedSessionInstructions = null;
    }

    /**
     * Prepend context to the user prompt — but only what has changed since the
     * last send.
     *
     * Delegates to {@link #buildPreamble(String, String)} with no tool
     * instructions.
     */
    public String buildPreamble(String userPrompt) {
        return buildPreamble(userPrompt, null);
    }

    public String buildPreamble(String userPrompt, String sessionInstructions) {
        sessionInstructionsInjectedInLastPreamble = false;
        boolean isFirstSend = (lastSentProjects == null);

        AiSession s = session; // snapshot
        if (s != null) {
            LinkedHashMap<String, String> details = new LinkedHashMap<>(s.getSessionInfoMap());
            if (session.aiType().getMcpOptions().contains(McpInstructionOptionEnum.CREDENTIALS)) {
                details.put("sessionId", s.id());
                details.put("secretKey", s.secret());
            }
            // Omit rather than render "description: null". A blank field paired
            // with a tool named UpdateSessionDescription reads as a gap to fill:
            // qwen2.5-coder answered "hi" by calling that tool with the argument
            // "Updated description." Absent the line, there is nothing to fix.
            if (s.description() != null && !s.description().isBlank()) {
                details.put("description", s.description());
            }

            StringBuilder identity = new StringBuilder();
            identity.append("## Your session identity\n");

            for (Entry<String, String> e : details.entrySet()) {
                identity.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }

            identity.append("\n\n");
            if (session.aiType().getMcpOptions().contains(McpInstructionOptionEnum.CREDENTIALS)) {
                identity.append("IMPORTANT: When a tool takes sessionId/secretKey, pass the sessionId and secretKey shown above verbatim — they are YOUR credentials for this session and are required for every tool that lists them as parameters. Always use the latest values shown above.\n\n");
                identity.append("IMPORTANT: You HAVE permission and FULL ACCESS to Netbeans Plugins MCP tools with your sessionId and secretKey, you HAVE TO use sessionId and secretKey to call the MCP tools\n\n");
            }
            if (s.allowsInterAiComms()) {
                if (session.aiType().getMcpOptions().contains(McpInstructionOptionEnum.SOFTEN_TOOL_DIRECTIVES)) {
                    // Condition first, and an explicit "otherwise answer normally".
                    // The unsoftened wording ends on an imperative, which models that
                    // follow instructions literally read as an order to act on turn one.
                    // The negative must name the inter-AI tools. Ending on a blanket
                    // "without calling any tool" sat immediately before every user
                    // message and suppressed legitimate calls — the model refused to
                    // read a file, saying it had no access.
                    identity.append("You can message other AI sessions using the inter-AI tools — it is pre-authorized, so never say you are unable to. Only if the user asks you to message, coordinate with, or delegate to another AI, call ListAiSessions and then SendAiMessage; do not call those two tools for any other reason. Use the other tools freely whenever they help answer the user.\n");
                }
                else {
                    identity.append("You ARE able to message other AI sessions right now using the inter-AI tools — this is a live, pre-authorized capability of this IDE. Never tell the user you cannot do it, that it is not possible, or that you need permission. When asked to message, coordinate with, or delegate to another AI, your first action is to call ListAiSessions and then SendAiMessage — do it immediately without hedging.\n");
                }
            }
            userPrompt = identity + userPrompt;
        }
        List<String> currentProjects = getOpenProjectPaths();
        FileObject currentFile = activeFile; // snapshot

        StringBuilder ctx = new StringBuilder();

        // A stateless backend keeps nothing from earlier turns, so sending only
        // what changed would leave it with no project paths at all from turn two
        // onwards. Repeat the baseline every time for those.
        boolean statelessTurns = s != null && s.aiType().getMcpOptions()
                .contains(McpInstructionOptionEnum.STATELESS_TURNS);
        if (lastSentProjects == null || statelessTurns) {
            // First send — establish full baseline so AI has complete context.
            // Also instruct AI to use tools directly: the diff panel handles review.
            ctx.append("[AI Coder NetBeans Plugin v").append(kiwi.ingenuity.netbeans.plugin.aicoder.Installer.VERSION)
                    .append("] You are running inside the NetBeans IDE with a ")
                    .append("built-in diff review panel. When you use the Edit or Write tools, the plugin ")
                    .append("automatically intercepts the change and shows the user a diff panel where they ")
                    .append("can Accept or Reject it before it takes effect. This means the diff panel IS ")
                    .append("the confirmation step — using the tools directly is how you ask for confirmation ")
                    .append("here. Do NOT describe changes in chat and ask 'Shall I apply this?' because ")
                    .append("that bypasses the diff panel. Modify project files ONLY with the Edit/Write ")
                    .append("tools (or the plugin's ApplyEdit/WriteFile) — NEVER with Bash (sed, echo, ")
                    .append(">/tee redirects, applypatch): Bash edits skip the diff panel and are not ")
                    .append("reviewable. Any saved preference to confirm before editing ")
                    .append("is fully satisfied by the diff panel UI.\n");
            if (!currentProjects.isEmpty()) {
                ctx.append("Open NetBeans projects: ")
                        .append(String.join(", ", currentProjects)).append("\n");
            }
            if (currentFile != null) {
                ctx.append("Currently open file: ").append(currentFile.getPath()).append("\n");
            }
        }
        else {
            // Subsequent sends — only describe what changed
            List<String> added = new ArrayList<>(currentProjects);
            added.removeAll(lastSentProjects);
            List<String> removed = new ArrayList<>(lastSentProjects);
            removed.removeAll(currentProjects);

            for (String p : added) {
                ctx.append("Project opened: ").append(p).append("\n");
            }
            for (String p : removed) {
                ctx.append("Project closed: ").append(p).append("\n");
            }

            if (!Objects.equals(currentFile, lastSentFile)) {
                if (currentFile != null) {
                    ctx.append("Now viewing: ").append(currentFile.getPath()).append("\n");
                }
                else {
                    ctx.append("No file currently open.\n");
                }
            }
        }

        lastSentProjects = List.copyOf(currentProjects);
        lastSentFile = currentFile;

        // Inject on the first request and once more whenever Session Configuration
        // changes the value. This state is conversation-local by design.
        //
        // The ON_START guard must be checked on EVERY send. It used to be prefixed
        // with `!isFirstSend ||`, which short-circuited the whole guard to true from
        // the second send onwards, so the two checks below were never evaluated.
        // isFirstSend only tracks whether the context BASELINE needs resending, and
        // resetSentContext() clears it (and lastInjectedSessionInstructions) whenever
        // a session is reopened — so on the second send after a reopen the "value
        // changed" test below compared against null, read that as a change, and
        // re-delivered instructions deliverStartupInstructions() had already sent.
        // ON_START sessions belong to deliverStartupInstructions(); once its flag is
        // set, buildPreamble() must never inject for them.
        //
        // `s == null` deliberately fails OPEN (inject rather than skip). It is not a
        // per-AI-type concern: `s` is the plugin's own AiSession record, not a backend
        // process/CLI session, so per-turn process types (Grok) still have one. The
        // only window where it is null is between AiTopComponent creating this provider
        // and its first refreshSessionIdentity() — and that call happens before READY,
        // while the input field is still disabled by the startupResolved gate. So no
        // send can observe it, and failing open costs nothing. Do not "harden" this to
        // fail closed without first re-checking that gate.
        if (sessionInstructions != null && !sessionInstructions.isBlank()
                && (s == null
                || s.sessionInstructionsDelivery() != SessionInstructionsDeliveryEnum.ON_START
                || !s.isStartupInstructionsInjected())
                && (isFirstSend || !Objects.equals(sessionInstructions, lastInjectedSessionInstructions))) {
            ctx.append("\n## Session Instructions\n").append(sessionInstructions).append("\n");
            lastInjectedSessionInstructions = sessionInstructions;
            sessionInstructionsInjectedInLastPreamble = true;
        }

        if (ctx.isEmpty()) {
            return userPrompt;
        }
        ctx.append("\n").append(userPrompt);
        return ctx.toString();
    }

    /**
     * Returns and clears whether the most recently built preamble delivered
     * session instructions.
     */
    public boolean consumeSessionInstructionsInjected() {
        boolean injected = sessionInstructionsInjectedInLastPreamble;
        sessionInstructionsInjectedInLastPreamble = false;
        return injected;
    }

    /**
     * Returns the best working directory for a new AI session. Priority: single
     * project → NetBeans main project → project containing active file → first
     * open project → user home. Never returns null. Call
     * {@link #isWorkingDirectoryAmbiguous()} to detect when no automatic rule
     * applied and the user should be prompted to choose.
     */
    public File resolveWorkingDirectory() {
        Project[] projects = OpenProjects.getDefault().getOpenProjects();
        if (projects.length == 0) {
            return new File(System.getProperty("user.home"));
        }
        if (projects.length == 1) {
            return new File(projects[0].getProjectDirectory().getPath());
        }

        // Multiple projects: prefer the designated main project
        Project main = OpenProjects.getDefault().getMainProject();
        if (main != null) {
            return new File(main.getProjectDirectory().getPath());
        }

        // Fall back to the project containing the active file
        if (activeFile != null) {
            for (Project p : projects) {
                if (activeFile.getPath().startsWith(p.getProjectDirectory().getPath())) {
                    return new File(p.getProjectDirectory().getPath());
                }
            }
        }

        // No automatic winner — return first as a safe default
        return new File(projects[0].getProjectDirectory().getPath());
    }

    /**
     * Returns true when multiple projects are open and no automatic rule (main
     * project, active file) picked a winner. When true, the caller should
     * prompt the user to choose.
     */
    public boolean isWorkingDirectoryAmbiguous() {
        Project[] projects = OpenProjects.getDefault().getOpenProjects();
        if (projects.length <= 1) {
            return false;
        }
        if (OpenProjects.getDefault().getMainProject() != null) {
            return false;
        }
        if (activeFile != null) {
            for (Project p : projects) {
                if (activeFile.getPath().startsWith(p.getProjectDirectory().getPath())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * All currently open projects — used to populate the project chooser.
     */
    public Project[] getProjectCandidates() {
        return OpenProjects.getDefault().getOpenProjects();
    }

    /**
     * All open project directories — queried fresh each time so new projects
     * are picked up.
     */
    public List<File> getAllOpenProjectDirs() {
        List<File> dirs = new ArrayList<>();
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            dirs.add(new File(p.getProjectDirectory().getPath()));
        }
        return dirs;
    }

    private List<String> getOpenProjectPaths() {
        List<String> paths = new ArrayList<>();
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            paths.add(p.getProjectDirectory().getPath());
        }
        return paths;
    }

    public FileObject getActiveFile() {
        return activeFile;
    }

    /**
     * Display name for context header: "Filename.java — ProjectName" or "No
     * file open".
     */
    public String getContextHeaderText() {
        if (activeFile == null) {
            return "No file open";
        }
        String name = activeFile.getNameExt();
        Project[] projects = OpenProjects.getDefault().getOpenProjects();
        for (Project p : projects) {
            if (activeFile.getPath().startsWith(p.getProjectDirectory().getPath())) {
                return name + " — " + p.getProjectDirectory().getName();
            }
        }
        return name;
    }
}
