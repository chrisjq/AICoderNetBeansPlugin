package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;

/**
 * Owns every session's file/project access scope and answers every "may this session touch this path" question in one
 * place. Extracted out of {@link
 * McpHookServer}, which now holds one instance and exposes its original public/package methods as thin delegating
 * wrappers — same signatures, same behaviour, so none of the ~20 tool call sites (or the native Claude Edit/Write hook
 * dispatch) needed to change.
 * <p>
 * Permission logic here spans TWO entirely separate directory trees, and mixing them up is exactly the mistake that
 * motivated this class's predecessor bugs — keep them straight:
 * <ul>
 * <li>{@code ~/.ai-coder/{type}/{sessionId}/} ({@link PluginUtil#getPluginAiSessionConfigDir}) — the session's own
 * memory, logs, and {@code tool_results} spool. EXEMPTED from restrict-to-project via {@link #isOwnSessionConfigFile}:
 * a session may freely read and write here with any tool, the same as a project file.</li>
 * <li>{@code SessionPersistenceManager.defaultBaseDir()/{sessionId}/} ({@code
 * ~/.netbeans/.aicoder/{sessionId}/}) — the session's serialized conversation, {@code history.json} and
 * {@code context.json}. The OPPOSITE treatment via {@link #isSessionPersistenceDirFile}: VETOED entirely, for every
 * session and every tool, for read as well as write — this is audit trail data, not working data any session gets to
 * manage, its own included.</li>
 * </ul>
 * These two trees never overlap. A session's own-config exemption and its history veto can never cancel each other out,
 * but conflating them (treating history.json as if it lived under the exempted tree, or the exempted tree as if it were
 * audit data) is precisely the premise error this class exists to make impossible to repeat silently.
 */
class SessionFileScopeRegistry {

    /**
     * Filenames that legitimately live directly at {@code
     * SessionPersistenceManager.defaultBaseDir()}'s ROOT rather than inside any session's own subdirectory:
     * {@code sessions.json} (the shared session index) and the two template files. These are exempt from
     * {@link #isSessionPersistenceDirFile} even though they sit in the same tree — everything else under that base is
     * per-session audit data.
     */
    private static final Set<String> BASE_LEVEL_FILES_EXEMPT
            = Set.of("sessions.json", "config-templates.json", "instruction-templates.json");

    private static Path resolveRealPath(Path p) {
        try {
            return p.toRealPath();
        }
        catch (IOException e) {
            // Path may not exist yet (e.g. WriteFile creating a new file).
            // Resolve the deepest existing ancestor so symlinked paths still
            // match the registered project dirs, then re-append the tail.
            Path abs = p.toAbsolutePath().normalize();
            Path tail = abs.getFileName();
            Path parent = abs.getParent();
            while (parent != null) {
                try {
                    return parent.toRealPath().resolve(tail);
                }
                catch (IOException ex) {
                    tail = parent.getFileName().resolve(tail);
                    parent = parent.getParent();
                }
            }
            return abs;
        }
    }

    private final Map<String, List<File>> sessionProjectDirs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessionRestrictToProject = new ConcurrentHashMap<>();
    private final Map<String, AiTypeEnum> sessionAiType = new ConcurrentHashMap<>();

    /**
     * Sets a session's scope unconditionally — used at first registration, where aiType is always supplied. Pure
     * bookkeeping: the maps are all concurrent, so no synchronization is required.
     */
    void registerScope(String sessionId, AiTypeEnum aiType, List<File> projectDirs, boolean restrictToProjectFiles) {
        sessionAiType.put(sessionId, aiType);
        sessionProjectDirs.put(sessionId, projectDirs);
        sessionRestrictToProject.put(sessionId, restrictToProjectFiles);
    }

    /**
     * Refreshes a session's scope. Unlike {@link #registerScope}, aiType may be null (unchanged) — the caller may be
     * refreshing project dirs/restrict flag alone without knowing or needing to touch the AI type.
     */
    void updateScope(String sessionId, AiTypeEnum aiType, List<File> projectDirs, boolean restrictToProjectFiles) {
        if (aiType != null) {
            sessionAiType.put(sessionId, aiType);
        }
        sessionProjectDirs.put(sessionId, projectDirs);
        sessionRestrictToProject.put(sessionId, restrictToProjectFiles);
    }

    /**
     * True once a session has a registered restrict-to-project flag. Used to distinguish "known session, scope says no"
     * from "this session's scope was never registered at all" in denial messages. Deliberately NOT cleared on teardown
     * (see McpHookServer#unregisterSession) — an in-flight call during teardown must still see the scope it started
     * with.
     */
    boolean hasScope(String sessionId) {
        return sessionRestrictToProject.containsKey(sessionId);
    }

    boolean isFileAllowed(String sessionId, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        // Vetoed unconditionally, even for an unrestricted session and regardless of
        // WHICH session owns it — see isSessionPersistenceDirFile for why this is
        // checked before, not after, the unrestricted-access shortcut below.
        if (isSessionPersistenceDirFile(filePath)) {
            return false;
        }
        if (isUnrestrictedFileAccess(sessionId)) {
            return true;
        }
        // Restrict is on: the file must resolve inside one of the session's registered
        // project roots. An empty dir list fails closed (never fail-open, which would
        // open the whole FS if scope was never populated).
        return isWithinProjectDirs(sessionId, filePath);
    }

    /**
     * True when {@code filePath} is ANY session's own subdirectory of {@code
     * SessionPersistenceManager.defaultBaseDir()} — {@code
     * <base>/{anySessionId}/} — or anything inside it (history.json, context.json, their
     * atomic-save/corruption-recovery siblings, or the bare directory itself), EXCEPT the handful of files that
     * legitimately live at {@code <base>}'s root ({@link #BASE_LEVEL_FILES_EXEMPT}).
     * <p>
     * Deliberately NOT scoped to the calling session — matched structurally (is this under some immediate child
     * directory of {@code <base>}, and is that child not one of the root-level exempt files), not against the caller's
     * own id. History/context data belongs to whichever session it names; no OTHER session may reach it either. An
     * earlier version of this check resolved only the CALLER's own {@code <base>/{sessionId}/} directory, which meant
     * session B asking for session A's history file resolved a directory that didn't contain the target path at all,
     * fell through as "not my own dir", and — with restrict-to-project off — was allowed by the generic
     * unrestricted-access shortcut.
     * <p>
     * Covers the WHOLE per-session subdirectory rather than just the "history.json"/"context.json" filenames: an
     * earlier, filename-matching version of this check missed that {@code CopyFile}/{@code MoveFile} take a target
     * DIRECTORY, not a target filename — moving or copying an unrelated file onto another session's subdirectory
     * slipped through because the incoming name (or {@code CopyFile}'s {@code newName}) didn't literally read
     * "history.json", even though the destination directory was the protected one. {@code CopyFile}'s {@code newName}
     * in particular could be chosen to manufacture a file literally named {@code history.json} in someone else's
     * directory — exactly the tampering this exists to prevent. Denying the whole subdirectory closes that regardless
     * of what name an incoming file would take.
     * <p>
     * Deliberately invisible to every file tool for READ as well as write: a session already receives its OWN
     * conversation as context through the normal mechanism, so a raw read here would be a second, unmediated channel to
     * audit-trail data, and a write/delete/move/rename/copy-onto would let a session (its own or another's) rewrite
     * what is recorded as having happened. This is a DIFFERENT directory tree from {@code
     * ~/.ai-coder/{type}/{sessionId}/} (which {@link #isOwnSessionConfigFile} exempts, for memory/logs/tool_results) —
     * the two never overlap, so this veto and that exemption cannot cancel each other out.
     * <p>
     * Checked from inside {@link #isFileAllowed} so every caller inherits the veto automatically, including an
     * otherwise-unrestricted session, which {@link #isUnrestrictedFileAccess} would otherwise wave straight through.
     * The native Claude Edit/Write hook does not call {@link #isFileAllowed} (it inlines the equivalent checks), so it
     * re-checks this directly instead of inheriting it — see McpHookServer's hook dispatch.
     */
    boolean isSessionPersistenceDirFile(String filePath) {
        Path relative = persistenceBaseRelativeOrNull(filePath);
        if (relative == null || relative.toString().isEmpty()) {
            // Outside the tree entirely, or the base directory itself — relativize
            // returns an empty path when the two are equal.
            return false;
        }
        return !(relative.getNameCount() == 1 && BASE_LEVEL_FILES_EXEMPT.contains(relative.toString()));
    }

    /**
     * True when {@code filePath} may not be WRITTEN — created, overwritten, deleted, moved, or written onto as a
     * Move/Copy destination — because it lies anywhere under {@code SessionPersistenceManager.defaultBaseDir()}.
     * Strictly wider than {@link #isSessionPersistenceDirFile}, and deliberately so, in the two places that predicate
     * stops short:
     * <ul>
     * <li>{@link #BASE_LEVEL_FILES_EXEMPT} — {@code sessions.json} and the two template files. The read exemption is
     * kept (nothing in the plugin reads them through a file tool today, but removing a granted read is a behaviour
     * change nobody asked for, and an earlier whole-tree veto was rejected for exactly that reason). The WRITE
     * exemption is not defensible: {@code sessions.json} is where {@code SessionPersistenceManager#persist} records
     * every session's own security posture — {@code restrictToProjectFiles}, {@code autoAccept}, {@code
     * allowWebRequests} — so a session able to rewrite it could widen its own permissions and have that survive an IDE
     * restart. Silently, if it is running with Auto-Accept on.</li>
     * <li>The base directory itself, which {@link #isSessionPersistenceDirFile} returns false for. Deleting or moving
     * {@code <base>} destroys every session's history at once; it is a strictly worse outcome than the single-file
     * tampering the veto already blocks, and nothing legitimately writes it through a tool.</li>
     * </ul>
     * Read gates keep calling {@link #isSessionPersistenceDirFile}; only write/delete/move gates call this. See
     * {@link McpHookServer#isProjectFileAllowed} and {@link McpHookServer#isFileWritable} for where the split is
     * applied.
     */
    boolean isSessionPersistenceWriteDenied(String filePath) {
        return persistenceBaseRelativeOrNull(filePath) != null;
    }

    /**
     * {@code filePath} expressed relative to {@code SessionPersistenceManager.defaultBaseDir()}, or null when it
     * resolves outside that tree (or cannot be resolved at all). An EMPTY path means it IS the base directory — callers
     * that care about that case must test for it, since an empty path still reports a name count of 1.
     * <p>
     * Both sides go through {@link #resolveRealPath}, so symlinked and not-yet-existing paths are compared as the
     * filesystem will ultimately resolve them, not as the caller happened to spell them.
     */
    private Path persistenceBaseRelativeOrNull(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path baseDir = resolveRealPath(SessionPersistenceManager.defaultBaseDir());
            Path resolvedFile = resolveRealPath(Path.of(filePath));
            return resolvedFile.startsWith(baseDir) ? baseDir.relativize(resolvedFile) : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    boolean isUnrestrictedFileAccess(String sessionId) {
        return Boolean.FALSE.equals(sessionRestrictToProject.get(sessionId));
    }

    /**
     * Denial message for a session/path whose server and sessionId are already known good (null-server and
     * null/blank-sessionId are handled by {@link McpHookServer#fileAccessDeniedMessage(McpHookServer, String, String)}
     * before this is ever called).
     */
    String fileAccessDeniedMessage(String sessionId, String filePath) {
        // Ordered widest-predicate-last: a base-level index/template file is write-denied
        // but NOT dir-file, so it needs its own wording — saying "protected in its
        // entirety ... read, write, delete" of a file the caller can in fact read would
        // be a lie the caller cannot act on.
        if (isSessionPersistenceWriteDenied(filePath) && !isSessionPersistenceDirFile(filePath)) {
            return "Access denied: " + filePath + " is AI session index or template data at the root of "
                    + "the serialized conversation directory. It is readable, but never writable by any "
                    + "tool or session: it records which sessions exist and each session's own security "
                    + "settings (project scope, auto-accept, web access), so a session that could rewrite "
                    + "it could widen its own permissions. Ask the user to change these in the IDE.";
        }
        if (isSessionPersistenceDirFile(filePath)) {
            return "Access denied: " + filePath + " is inside an AI session's serialized conversation "
                    + "directory, which is protected in its entirety — every path it contains, whatever "
                    + "the filename — from every file tool (read, write, delete, move, or copy, including "
                    + "as a Move/Copy destination) for every session including its own, regardless of "
                    + "project scope or the restrict-to-project setting. Renaming or choosing a different "
                    + "filename will not change this result.";
        }
        if (!hasScope(sessionId)) {
            return "Access denied: file access scope is not yet registered for this session. "
                    + "Retry after MCP session setup completes.";
        }
        return "Access denied: " + filePath + " is outside the allowed project scope for this session.";
    }

    /**
     * True if {@code filePath} resolves to a location inside one of the session's registered project roots. Independent
     * of the restrict-to-project flag, so a caller can ask "is this a project file?" directly. Fails closed when the
     * session has no registered roots.
     */
    boolean isWithinProjectDirs(String sessionId, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        List<File> dirs = sessionProjectDirs.get(sessionId);
        if (dirs == null || dirs.isEmpty()) {
            return false;
        }
        Path resolvedFile = resolveRealPath(Path.of(filePath));
        return dirs.stream().anyMatch(d -> {
            Path dir = resolveRealPath(d.toPath());
            return resolvedFile.equals(dir) || resolvedFile.startsWith(dir);
        });
    }

    boolean isUnderAnyOpenProject(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Path f = resolveRealPath(Path.of(filePath));
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            Path d = resolveRealPath(Path.of(p.getProjectDirectory().getPath()));
            if (f.equals(d) || f.startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if {@code filePath} is inside this session's own per-session config directory
     * ({@code ~/.ai-coder/{type}/{sessionId}/}), where the AI keeps its memory and logs. These live outside every open
     * project, so no diff panel can be built for them; they pass straight through to the built-in tool. Scoped to the
     * requesting session, so one session can never write into another session's memory.
     */
    boolean isOwnSessionConfigFile(String sessionId, String filePath) {
        if (sessionId == null || filePath == null || filePath.isBlank()) {
            return false;
        }
        AiTypeEnum aiType = sessionAiType.get(sessionId);
        if (aiType == null) {
            return false;
        }
        try {
            Path sessionDir = PluginUtil.getPluginConfigDir()
                    .resolve(aiType.key()).resolve(sessionId);
            Path resolvedDir = resolveRealPath(sessionDir);
            Path resolvedFile = resolveRealPath(Path.of(filePath));
            return resolvedFile.equals(resolvedDir) || resolvedFile.startsWith(resolvedDir);
        }
        catch (IOException e) {
            return false;
        }
    }

    /**
     * May this session access {@code filePath} at all — for a plain read/query/action gate, not a write that needs the
     * diff-panel routing decision. True when either {@link #isFileAllowed} (in project scope) or
     * {@link #isOwnSessionConfigFile} (this session's own memory/logs/tool_results, exempt from restrict-to-project)
     * holds. See {@link McpHookServer#isFileAccessible(String, String)} for the full rationale and routing rules.
     */
    boolean isFileAccessible(String sessionId, String filePath) {
        return isFileAllowed(sessionId, filePath) || isOwnSessionConfigFile(sessionId, filePath);
    }

    /**
     * Resolves this session's own per-session config directory ({@code ~/.ai-coder/{type}/{sessionId}/}), or null when
     * the session type is unknown. The build/test providers park complete build logs there so the session can read them
     * back via {@link #isOwnSessionConfigFile} even under restrict-to-project.
     */
    Path sessionConfigDirOrNull(String sessionId) {
        AiTypeEnum aiType = sessionId == null ? null : sessionAiType.get(sessionId);
        if (aiType == null) {
            return null;
        }
        try {
            return PluginUtil.getPluginConfigDir().resolve(aiType.key()).resolve(sessionId);
        }
        catch (IOException e) {
            return null;
        }
    }
}
