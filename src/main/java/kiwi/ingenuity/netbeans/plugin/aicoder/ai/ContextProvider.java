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

    /**
     * Prepend context to the user prompt — but only what has changed since the
     * last send.
     *
     * First call after resetSentContext(): sends the full baseline (all open
     * projects + file) and, if {@code toolInstructions} is non-blank, appends a
     * {@code ## Tool Instructions} section. Subsequent calls: sends only the
     * delta — projects added/closed, file switched. If nothing changed, returns
     * userPrompt unchanged (no preamble overhead).
     */
    public String buildPreamble(String userPrompt, String sessionInstructions) {
        boolean isFirstSend = (lastSentProjects == null);

        AiSession s = session; // snapshot
        if (s != null) {
            LinkedHashMap<String, String> details = new LinkedHashMap<>(s.getSessionInfoMap());
            details.put("sessionId", s.id());
            details.put("secretKey", s.secret());
            details.put("description", s.description());

            StringBuilder identity = new StringBuilder();
            identity.append("## Your session identity\n");

            for (Entry<String, String> e : details.entrySet()) {
                identity.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }

            identity.append("\n\n");
            identity.append("IMPORTANT: When a tool takes sessionId/secretKey, pass the sessionId and secretKey shown above verbatim — they are YOUR credentials for this session and are required for every tool that lists them as parameters. Always use the latest values shown above.\n\n");
            identity.append("IMPORTANT: You HAVE permission and FULL ACCESS to Netbeans Plugins MCP tools with your sessionId and secretKey, you HAVE TO use sessionId and secretKey to call the MCP tools\n\n");
            // The inter-AI capability blurb is only true when inter-AI comms is
            // enabled for this session. Credentials above, however, are required
            // by EVERY plugin tool and must always be emitted regardless.
            if (s.allowsInterAiComms()) {
                identity.append("You ARE able to message other AI sessions right now using the inter-AI tools — this is a live, pre-authorized capability of this IDE. Never tell the user you cannot do it, that it is not possible, or that you need permission. When asked to message, coordinate with, or delegate to another AI, your first action is to call ListAiSessions and then SendAiMessage — do it immediately without hedging.\n");
            }
            userPrompt = identity + userPrompt;
        }
        List<String> currentProjects = getOpenProjectPaths();
        FileObject currentFile = activeFile; // snapshot

        StringBuilder ctx = new StringBuilder();

        if (lastSentProjects == null) {
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

        // Append tool instructions on first send only
        if (isFirstSend && sessionInstructions != null && !sessionInstructions.isBlank()) {
            ctx.append("\n## Session Instructions\n").append(sessionInstructions).append("\n");
        }

        if (ctx.isEmpty()) {
            return userPrompt;
        }
        ctx.append("\n").append(userPrompt);
        return ctx.toString();
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
