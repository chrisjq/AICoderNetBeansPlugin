package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpHookServerScopeTest {

    // ---- ANY session's serialized-conversation directory (history.json,
    // context.json) must be invisible to every file tool, read or write, even
    // for an otherwise-unrestricted session and regardless of which session
    // is asking — see SessionFileScopeRegistry.isSessionPersistenceDirFile. ----
    @Test
    void isFileAllowed_deniesOwnHistoryFile_evenWhenUnrestricted() throws Exception {
        String sessionId = "history-veto-test-" + UUID.randomUUID();
        Path historyFile = new SessionPersistenceManager().historyPath(sessionId);
        Files.createDirectories(historyFile.getParent());
        Files.writeString(historyFile, "{}");
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            // restrictToProjectFiles=false: an unrestricted session normally passes
            // isFileAllowed for ANY path, so this proves the veto runs before that
            // shortcut rather than being subsumed by it.
            s.registerSession(sessionId, CLAUDE, List.of(), false);
            assertFalse(s.isFileAllowed(sessionId, historyFile.toString()));
            assertFalse(s.isFileAccessible(sessionId, historyFile.toString()),
                    "isOwnSessionConfigFile must not rescue this — it is a different directory");
        }
        finally {
            s.stop();
            Files.deleteIfExists(historyFile);
        }
    }

    @Test
    void isFileAllowed_deniesOwnContextFileAndSiblingsInsideHistoryDir() throws Exception {
        String sessionId = "history-veto-test-" + UUID.randomUUID();
        Path historyDir = new SessionPersistenceManager().historyPath(sessionId).getParent();
        Files.createDirectories(historyDir);
        Path contextFile = historyDir.resolve("context.json");
        Path quarantineFile = historyDir.resolve("history.json.corrupt-123.json");
        Files.writeString(contextFile, "{}");
        Files.writeString(quarantineFile, "{}");
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(), true);
            assertFalse(s.isFileAllowed(sessionId, contextFile.toString()));
            assertFalse(s.isFileAllowed(sessionId, quarantineFile.toString()),
                    "corruption-quarantine siblings hold the same content and must be denied too");
        }
        finally {
            s.stop();
            Files.deleteIfExists(contextFile);
            Files.deleteIfExists(quarantineFile);
        }
    }

    // These three used to be one test asserting message.contains("history") — which
    // passed only because the interpolated historyFile path happened to contain that
    // substring, not because the message actually explained the whole-directory rule.
    // Split so "explains the rule", "echoes the path", and "the fixture doesn't spell
    // out the answer for us" are three independent facts instead of one coincidence.
    @Test
    void fileAccessDeniedMessage_explainsWholeDirectoryProtection_notGenericProjectScope() throws Exception {
        String sessionId = "history-veto-test-" + UUID.randomUUID();
        Path historyFile = new SessionPersistenceManager().historyPath(sessionId);
        Files.createDirectories(historyFile.getParent());
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(), true);
            String message = s.fileAccessDeniedMessage(sessionId, historyFile.toString());
            assertTrue(message.contains("protected in its entirety"),
                    "must explain the whole-directory rule, not just confirm denial: " + message);
            assertFalse(message.endsWith("is outside the allowed project scope for this session."),
                    "must not give the generic project-scope reason for this specific case: " + message);
        }
        finally {
            s.stop();
            Files.deleteIfExists(historyFile.getParent());
        }
    }

    @Test
    void fileAccessDeniedMessage_echoesThePathBack() throws Exception {
        String sessionId = "history-veto-test-" + UUID.randomUUID();
        Path historyFile = new SessionPersistenceManager().historyPath(sessionId);
        Files.createDirectories(historyFile.getParent());
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(), true);
            String message = s.fileAccessDeniedMessage(sessionId, historyFile.toString());
            assertTrue(message.contains(historyFile.toString()), "must quote the denied path back: " + message);
        }
        finally {
            s.stop();
            Files.deleteIfExists(historyFile.getParent());
        }
    }

    @Test
    void fileAccessDeniedMessage_explainsRuleEvenWithoutHistoryOrContextInThePath() throws Exception {
        // Deliberately avoids "history"/"context" anywhere in the session id or
        // filename. This is the test that would have caught the coincidence above
        // immediately: if this passes only by accident of naming, using neutral
        // names removes the accident.
        String neutralSessionId = "persistence-protect-" + UUID.randomUUID();
        Path neutralDir = new SessionPersistenceManager().historyPath(neutralSessionId).getParent();
        Files.createDirectories(neutralDir);
        Path unrelatedFile = neutralDir.resolve("notes.txt");
        Files.writeString(unrelatedFile, "not history or context data");
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(neutralSessionId, CLAUDE, List.of(), false);
            String message = s.fileAccessDeniedMessage(neutralSessionId, unrelatedFile.toString());
            assertTrue(message.contains("protected in its entirety"),
                    "must explain the whole-directory rule even for a neutrally-named path: " + message);
            assertTrue(message.contains(unrelatedFile.toString()), "must quote the denied path back: " + message);
        }
        finally {
            s.stop();
            Files.deleteIfExists(unrelatedFile);
            Files.deleteIfExists(neutralDir);
        }
    }

    @Test
    void isFileAllowed_deniesNonHistoryFilenameInsideSessionDir_whenUnrestricted() throws Exception {
        // The whole point of the depth-match redesign: a file that is NOT named
        // history.json/context.json, sitting in a session's persistence directory,
        // must still be denied — the guarantee must not depend on nothing else
        // ever being written there. restrict OFF is the reachable configuration.
        String sessionId = "history-veto-test-" + UUID.randomUUID();
        Path historyDir = new SessionPersistenceManager().historyPath(sessionId).getParent();
        Files.createDirectories(historyDir);
        Path unrelatedFile = historyDir.resolve("notes.txt");
        Files.writeString(unrelatedFile, "not history or context data");
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(), false);
            assertFalse(s.isFileAllowed(sessionId, unrelatedFile.toString()),
                    "a non-history filename inside the session's persistence dir must still be denied");
            assertFalse(s.isFileAllowed(sessionId, historyDir.toString()),
                    "the bare session persistence directory itself must be denied (Copy/Move target case)");
        }
        finally {
            s.stop();
            Files.deleteIfExists(unrelatedFile);
            Files.deleteIfExists(historyDir);
        }
    }

    @Test
    void isFileAllowed_allowsBaseLevelSessionsAndTemplateFiles_whenUnrestricted() throws Exception {
        // sessions.json and the template files live directly at defaultBaseDir()'s
        // root, one segment shallower than any session's history.json — the depth
        // match must leave them reachable, exactly the property the user's earlier
        // rejection of a whole-tree veto was protecting.
        Path baseDir = SessionPersistenceManager.defaultBaseDir();
        Files.createDirectories(baseDir);
        Path sessionsFile = baseDir.resolve("sessions.json");
        Path configTemplates = baseDir.resolve("config-templates.json");
        Path instructionTemplates = baseDir.resolve("instruction-templates.json");
        boolean createdSessions = !Files.exists(sessionsFile);
        boolean createdConfigTemplates = !Files.exists(configTemplates);
        boolean createdInstructionTemplates = !Files.exists(instructionTemplates);
        if (createdSessions) {
            Files.writeString(sessionsFile, "[]");
        }
        if (createdConfigTemplates) {
            Files.writeString(configTemplates, "[]");
        }
        if (createdInstructionTemplates) {
            Files.writeString(instructionTemplates, "[]");
        }
        String sessionId = "history-veto-test-" + UUID.randomUUID();
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(), false);
            assertTrue(s.isFileAllowed(sessionId, sessionsFile.toString()));
            assertTrue(s.isFileAllowed(sessionId, configTemplates.toString()));
            assertTrue(s.isFileAllowed(sessionId, instructionTemplates.toString()));
        }
        finally {
            s.stop();
            if (createdSessions) {
                Files.deleteIfExists(sessionsFile);
            }
            if (createdConfigTemplates) {
                Files.deleteIfExists(configTemplates);
            }
            if (createdInstructionTemplates) {
                Files.deleteIfExists(instructionTemplates);
            }
        }
    }

    @Test
    void isFileAccessible_allowsOwnSessionConfigDir_evenWhenUnrestricted() throws Exception {
        // The history-persistence veto must not leak into the OTHER own-config
        // tree (~/.ai-coder/{type}/{sessionId}/) — memory/tool_results stay
        // reachable even with restrict off, same as under restrict on.
        String sessionId = "scope-test-" + UUID.randomUUID();
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(), false);
            Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
            Path memoryFile = Files.createFile(configDir.resolve("memory.md"));
            assertTrue(s.isFileAccessible(sessionId, memoryFile.toString()));
        }
        finally {
            s.stop();
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    // ---- isFileAccessible: the centralised "may this session touch this path"
    // rule (isFileAllowed OR isOwnSessionConfigFile), pinned directly since
    // GetFileContentTool was found with only half of it. ----
    @Test
    void isFileAccessible_allowsProjectFile(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("Foo.java"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(tmp.toFile()), true);
            assertTrue(s.isFileAccessible("c1", file.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAccessible_allowsOwnSessionConfigDir_evenWithNoProjectDirs() throws Exception {
        String sessionId = "scope-test-" + UUID.randomUUID();
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            // Restrict on, no project dirs — isFileAllowed alone would deny
            // everything; only the isOwnSessionConfigFile half can let this through.
            s.registerSession(sessionId, CLAUDE, List.of(), true);
            Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
            Path logFile = Files.createFile(configDir.resolve("build-maven-test.log"));

            assertTrue(s.isFileAccessible(sessionId, logFile.toString()));
        }
        finally {
            s.stop();
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    @Test
    void isFileAccessible_deniesUnrelatedOutsidePath(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path file = Files.createFile(outside.resolve("Secret.java"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(project.toFile()), true);
            assertFalse(s.isFileAccessible("c1", file.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAccessible_deniesAnotherSessionsConfigDir() throws Exception {
        String sessionA = "scope-test-a-" + UUID.randomUUID();
        String sessionB = "scope-test-b-" + UUID.randomUUID();
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionA, CLAUDE, List.of(), true);
            s.registerSession(sessionB, CLAUDE, List.of(), true);
            Path bConfigDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionB);
            Path bFile = Files.createFile(bConfigDir.resolve("secret.log"));

            // The per-session scoping is the property that must not be lost: session A
            // must never read session B's config dir, even though both are "own config
            // dir" style paths.
            assertFalse(s.isFileAccessible(sessionA, bFile.toString()));
        }
        finally {
            s.stop();
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionA);
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionB);
        }
    }

    // ---- isProjectFileAllowed: the plain project-scope rule with no config-dir
    // exemption, used by every refactor/Delete/Copy/Move/Close/Navigate/Reformat/
    // organise-imports tool. Pinned separately from isFileAccessible above so a
    // future "consolidation" cannot silently widen these tools onto the
    // config-dir exemption again. ----
    @Test
    void isProjectFileAllowed_deniesOwnSessionConfigDir_evenThoughIsFileAccessibleWouldAllowIt() throws Exception {
        String sessionId = "scope-test-narrow-" + UUID.randomUUID();
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(), true);
            Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
            Path file = Files.createFile(configDir.resolve("x.log"));

            assertTrue(s.isFileAccessible(sessionId, file.toString()),
                    "sanity check: isFileAccessible allows the session's own config dir");
            assertFalse(McpHookServer.isProjectFileAllowed(s, sessionId, file.toString()),
                    "isProjectFileAllowed must NOT grant the config-dir exemption — "
                    + "widening it would let ReformatFile/DeleteFile/MoveFile and the "
                    + "refactor tools reach into the session's own config directory");
        }
        finally {
            s.stop();
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    @Test
    void updateSessionScope_widensAllowedProjectDirs(@TempDir Path tmp) throws Exception {
        Path dirA = Files.createDirectory(tmp.resolve("a"));
        Path dirB = Files.createDirectory(tmp.resolve("b"));
        Path fileB = Files.createFile(dirB.resolve("Foo.java"));

        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(dirA.toFile()), true);
            assertFalse(s.isFileAllowed("c1", fileB.toString()));
            s.updateSessionScope("c1", CLAUDE, List.of(dirA.toFile(), dirB.toFile()), true);
            assertTrue(s.isFileAllowed("c1", fileB.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void updateSessionScope_unknownSession_registersSession() throws Exception {
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.updateSessionScope("never-registered", CLAUDE, List.of(), true);
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_restrictOnEmptyDirs_denies(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("Secret.java"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(), true);
            assertFalse(s.isFileAllowed("c1", file.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_restrictOff_allowsOutsideProjects(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("Anywhere.java"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(), false);
            assertTrue(s.isFileAllowed("c1", file.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_restrictOn_allowsExplicitProjectAndSubmodule(@TempDir Path tmp) throws Exception {
        Path openRoot = Files.createDirectory(tmp.resolve("open-root"));
        Path submodule = Files.createDirectory(openRoot.resolve("module-a"));
        Path file = Files.createFile(submodule.resolve("pom.xml"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(openRoot.toFile()), true);
            assertTrue(s.isFileAllowed("c1", submodule.toString()));
            assertTrue(s.isFileAllowed("c1", file.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_restrictOn_rejectsExplicitOutsideProject(@TempDir Path tmp) throws Exception {
        Path openRoot = Files.createDirectory(tmp.resolve("open-root"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(openRoot.toFile()), true);
            assertFalse(s.isFileAllowed("c1", outside.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_restrictOn_allowsOpenProjectRoot(@TempDir Path tmp) throws Exception {
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession("c1", CLAUDE, List.of(tmp.toFile()), true);
            assertTrue(s.isFileAllowed("c1", tmp.toString()));
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_unknownSession_deniesWithMissingScopeMessage(@TempDir Path tmp) throws Exception {
        Path file = Files.createFile(tmp.resolve("Secret.java"));
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            assertFalse(s.isFileAllowed("missing", file.toString()));
            assertTrue(s.fileAccessDeniedMessage("missing", file.toString()).contains("scope is not yet registered"));
        }
        finally {
            s.stop();
        }
    }

    // ---- Malformed-path wording must be the SAME regardless of restrict-to-project.
    // Before the fix, isFileAllowed's isUnrestrictedFileAccess shortcut returned true
    // for a malformed path without ever checking whether the path could be represented
    // at all, so an unrestricted session sailed past this gate and only failed later
    // inside the tool's own Path.of call, with a different message
    // ("Not a usable path") than a restricted session got from fileAccessDeniedMessage
    // ("Malformed path"). See SessionFileScopeRegistry.isFileAllowed and
    // McpHookServer.fileAccessDeniedMessage. ----
    @Test
    void isFileAllowed_deniesMalformedPath_restrictedSession() throws Exception {
        String sessionId = "malformed-path-test-" + UUID.randomUUID();
        String nulPath = "/tmp/project/file" + ((char) 0) + ".txt";
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(new File("/tmp/project")), true);
            assertFalse(s.isFileAllowed(sessionId, nulPath));
            assertTrue(s.fileAccessDeniedMessage(sessionId, nulPath).startsWith("Malformed path:"),
                    "a malformed path must be reported as malformed, not as an out-of-scope path");
        }
        finally {
            s.stop();
        }
    }

    @Test
    void isFileAllowed_deniesMalformedPath_unrestrictedSession() throws Exception {
        // Pins the actual fix: an unrestricted session must get the SAME denial and the
        // SAME wording as a restricted one for a path the filesystem cannot represent,
        // instead of the unrestricted shortcut waving it through to fail differently
        // downstream inside the tool handler.
        String sessionId = "malformed-path-test-" + UUID.randomUUID();
        String nulPath = "/tmp/project/file" + ((char) 0) + ".txt";
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(), false);
            assertFalse(s.isFileAllowed(sessionId, nulPath),
                    "an unrestricted session must still deny a malformed path");
            assertTrue(s.fileAccessDeniedMessage(sessionId, nulPath).startsWith("Malformed path:"),
                    "must get the same malformed-path wording as a restricted session, not the "
                    + "unrestricted shortcut waving it through to a different failure downstream");
        }
        finally {
            s.stop();
        }
    }

    @Test
    void fileAccessDeniedMessage_wellFormedOutOfScopePath_stillGetsScopeWording() throws Exception {
        // Negative control: the malformed-path check added to isFileAllowed must not
        // swallow the ordinary "outside the allowed project scope" wording for a path
        // the filesystem CAN represent — only genuinely unrepresentable paths route to
        // the malformed-path message.
        String sessionId = "malformed-path-test-" + UUID.randomUUID();
        McpHookServer s = new McpHookServer(0);
        s.init();
        try {
            s.registerSession(sessionId, CLAUDE, List.of(new File("/tmp/project")), true);
            String message = s.fileAccessDeniedMessage(sessionId, "/tmp/outside/Secret.java");
            assertTrue(message.endsWith("is outside the allowed project scope for this session."),
                    "a well-formed but out-of-scope path must keep the ordinary scope wording: " + message);
        }
        finally {
            s.stop();
        }
    }
}
