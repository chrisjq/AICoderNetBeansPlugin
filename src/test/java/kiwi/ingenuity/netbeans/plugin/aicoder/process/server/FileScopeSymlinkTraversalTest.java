package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search.FindFileParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search.FindFileTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileInfoParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system.GetFileInfoTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FindFileProvider;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the boundary the scope machinery exists to defend: {@code isFileAccessible} decides by WHERE a path RESOLVES
 * (real path, symlinks followed, {@code ..} collapsed), never by how the caller spelled it. Before this suite, every
 * one of these shapes was unpinned — {@link McpHookServerScopeTest} had no symlink, {@code toRealPath} or {@code ..}
 * case, and the FindFile tests passed {@code path -> true} as the per-candidate predicate, so a regression to lexical
 * comparison would satisfy every existing test. This class reproduces, with {@code @TempDir} projects, the scenarios
 * verified live: a link inside an allowed directory pointing outside it, a link pointing inside it, {@code ..}
 * traversal in both directions, the same real file reached through two spellings, and FindFile's per-candidate gate on
 * a link. The gate ({@link McpHookServer#isFileAccessible(String, String)}) and the tool call sites
 * ({@link GetFileInfoTool}, {@link FindFileTool}) are separate code paths, so each boundary is asserted at both levels
 * where it matters.
 * <p>
 * Uses the real server harness (not a stubbed gate, which would prove nothing): {@link McpServerRegistry} boots a
 * genuine {@link McpHookServer} on an ephemeral port and the session is registered into its scope machinery exactly as
 * a real session is.
 */
class FileScopeSymlinkTraversalTest {

    /**
     * Create a symlink or skip the test. Some filesystems refuse symbolic links (Windows without privilege, read-only
     * mounts), and a boundary test that cannot create a link must not fail the suite there. Chose
     * {@link Assumptions#abort(String)} over {@code @DisabledOnOs(OS.WINDOWS)} so only the link-dependent tests skip,
     * and only on a filesystem that actually refuses — everywhere links work, they keep running.
     */
    private static Path linkOrAbort(Path link, Path target, String what) {
        try {
            Files.createSymbolicLink(link, target);
            return link;
        }
        catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("filesystem refused a symlink for " + what + " (" + e
                    + "); boundary cannot be exercised here");
            return link; // unreachable
        }
    }

    private static ToolRequestArguments fileInfoArgs(String filePath) {
        JsonObject o = new JsonObject();
        o.addProperty(GetFileInfoParamEnum.FILE_PATH.key(), filePath);
        return new ToolRequestArguments(o);
    }

    private static ToolRequestArguments findArgs(String directoryPath, String pattern) {
        JsonObject o = new JsonObject();
        o.addProperty(FindFileParamEnum.DIRECTORY_PATH.key(), directoryPath);
        o.addProperty(FindFileParamEnum.PATTERN.key(), pattern);
        return new ToolRequestArguments(o);
    }

    private McpHookServer server;
    private String sessionId;

    @TempDir
    Path projectDir;

    @TempDir
    Path outsideDir;

    @BeforeEach
    void setUp() throws Exception {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = 0;
        boolean ok = McpServerRegistry.register(new NoopRegistrar("f2-scope-boot")).get(5, TimeUnit.SECONDS);
        assertTrue(ok, "test server must start");
        server = McpServerRegistry.getServer();
        sessionId = "f2-scope-" + UUID.randomUUID();
        server.registerSession(sessionId, CLAUDE, List.of(projectDir.toFile()), true);
    }

    @AfterEach
    void tearDown() {
        McpServerRegistry.stopAll();
        McpServerRegistry.portOverride = null;
    }

    private Path writeInScope(String name) throws IOException {
        return Files.writeString(Files.createFile(projectDir.resolve(name)), "content-" + name);
    }

    private Path writeSecretOutside() throws IOException {
        return Files.writeString(Files.createFile(outsideDir.resolve("secret.txt")), "SECRET CONTENT");
    }

    // A symlink INSIDE an allowed directory pointing OUTSIDE it must be denied — the real-path comparison is a
    // guarantee of the central gate itself.
    @Test
    void isFileAccessible_deniesSymlinkInsideProjectPointingOutside() throws Exception {
        Path secret = writeSecretOutside();
        Path leak = linkOrAbort(projectDir.resolve("leak.txt"), secret, "escaping file link");
        assertTrue(leak.toRealPath().toString().startsWith(outsideDir.toRealPath().toString()),
                "fixture sanity: the link must really resolve outside the project");
        writeInScope("legit.txt");

        assertFalse(server.isFileAccessible(sessionId, leak.toString()),
                "a link inside the project whose target is outside must be denied");
        assertTrue(server.isFileAccessible(sessionId, projectDir.resolve("legit.txt").toString()),
                "control: a genuinely in-scope file must still be allowed");
    }

    // The same boundary through GetFileInfoTool's own gate — a regression in EITHER call site (here the tool's
    // isFileAccessible check before it resolves the target) must fail a test, not just the gate-level one.
    @Test
    void getFileInfoTool_deniesSymlinkInsideProjectPointingOutside() throws Exception {
        Path secret = writeSecretOutside();
        Path leak = linkOrAbort(projectDir.resolve("leak.txt"), secret, "escaping file link");
        writeInScope("legit.txt");

        GetFileInfoTool tool = new GetFileInfoTool(server);
        String denied = tool.handle(fileInfoArgs(leak.toString()), new FakeSession(sessionId));
        assertTrue(denied.startsWith("Access denied"),
                "GetFileInfoTool must refuse the escaping link before reporting any metadata: " + denied);
        assertTrue(denied.contains(leak.toString()),
                "the denial must quote the link the caller asked for, never its (outside) target: " + denied);

        String ok = tool.handle(fileInfoArgs(projectDir.resolve("legit.txt").toString()), new FakeSession(sessionId));
        assertFalse(ok.startsWith("Access denied"), "control: in-scope file must report metadata: " + ok);
        assertTrue(ok.contains("bytes"), ok);
    }

    // A symlink pointing at an in-scope target must be ALLOWED. Without this, the previous test would pass if links
    // were blanket-refused — which would break legitimate use, so the pair of tests pins the distinction.
    @Test
    void isFileAccessible_allowsSymlinkInsideProjectPointingInside() throws Exception {
        Path target = writeInScope("target.txt");
        Path linkIn = linkOrAbort(projectDir.resolve("link-in.txt"), target, "in-scope file link");
        assertTrue(linkIn.toRealPath().toString().startsWith(projectDir.toRealPath().toString()),
                "fixture sanity: the link must really resolve inside the project");

        assertTrue(server.isFileAccessible(sessionId, linkIn.toString()),
                "a link whose target is inside the project must stay allowed");
    }

    @Test
    void getFileInfoTool_reportsMetadataForSymlinkInsideProjectPointingInside() throws Exception {
        Path target = writeInScope("target.txt");
        Path linkIn = linkOrAbort(projectDir.resolve("link-in.txt"), target, "in-scope file link");

        GetFileInfoTool tool = new GetFileInfoTool(server);
        String result = tool.handle(fileInfoArgs(linkIn.toString()), new FakeSession(sessionId));
        assertFalse(result.startsWith("Access denied"), result);
        assertTrue(result.contains("(symbolic link)"), "link status must be reported: " + result);
        assertTrue(result.contains(target.toRealPath().toString()),
                "the resolved in-scope target must be shown: " + result);
        assertTrue(result.contains("bytes"), result);
    }

    // `..` traversal: what counts is where the path RESOLVES, not how it was written. An escape spelled with `..` is
    // denied; a redundant `..` that comes back inside is allowed — pinning real-path resolution rather than a blanket
    // lexical "contains .." rejection.
    @Test
    void dotDotTraversal_resolvingOutsideDenied_resolvingInsideAllowed() throws Exception {
        writeInScope("legit.txt");
        Files.createDirectory(projectDir.resolve("sub"));
        writeSecretOutside();

        String escape = projectDir.resolve("sub").resolve("..").resolve("..")
                .resolve(outsideDir.getFileName()).resolve("secret.txt").toString();
        assertFalse(server.isFileAllowed(sessionId, escape),
                "a `..` spelling that resolves outside must be denied: " + escape);
        assertFalse(server.isFileAccessible(sessionId, escape),
                "isFileAccessible must agree with isFileAllowed on the `..` escape: " + escape);

        String inside = projectDir.resolve("sub").resolve("..").resolve("legit.txt").toString();
        assertTrue(server.isFileAccessible(sessionId, inside),
                "a `..` spelling that resolves back inside must be allowed: " + inside);
    }

    // Dual-path identity: the same real file reached through two spellings is treated identically. The project scope is
    // registered under the SYMLINKED alias root — the /share -> .SyncShare shape — so the real spelling too must be
    // allowed, and both spellings must report identical metadata through the tool.
    @Test
    void dualPathIdentity_sameRealFileThroughTwoSpellingsTreatedIdentically() throws Exception {
        Path real = writeInScope("legit.txt");
        Path alias = linkOrAbort(outsideDir.resolve("alias-to-project"), projectDir, "project alias dir");
        server.registerSession(sessionId, CLAUDE, List.of(alias.toFile()), true);

        String realSpelling = real.toString();
        String aliasSpelling = alias.resolve(real.getFileName()).toString();

        assertTrue(server.isFileAccessible(sessionId, realSpelling),
                "real spelling must be allowed when the scope root is itself a symlink");
        assertTrue(server.isFileAccessible(sessionId, aliasSpelling),
                "alias spelling must be allowed — same real file, same verdict");

        GetFileInfoTool tool = new GetFileInfoTool(server);
        String realInfo = tool.handle(fileInfoArgs(realSpelling), new FakeSession(sessionId));
        String aliasInfo = tool.handle(fileInfoArgs(aliasSpelling), new FakeSession(sessionId));
        assertFalse(realInfo.startsWith("Access denied"), realInfo);
        assertFalse(aliasInfo.startsWith("Access denied"), aliasInfo);
        assertEquals(realInfo.substring(realInfo.indexOf(':')), aliasInfo.substring(aliasInfo.indexOf(':')),
                "both spellings must report identical metadata for the same real file");
    }

    // FindFile's per-candidate gate on a link — the case the existing FindFile tests skip by passing path -> true.
    // Pinned against the full provider overload FIRST with a predicate that actually consults scope, so the gate itself
    // is what drops the escaping link, with a path -> true control proving the link really is a walk candidate.
    @Test
    void findFiles_perCandidateGateOnSymlinkConsultsScope() throws Exception {
        Path secret = writeSecretOutside();
        Path leak = linkOrAbort(projectDir.resolve("leak.txt"), secret, "escaping file link");
        writeInScope("legit.txt");
        writeInScope("target.txt");
        assertTrue(leak.toRealPath().toString().startsWith(outsideDir.toRealPath().toString()),
                "fixture sanity: the escaping link must really point outside");

        Predicate<Path> scope = p -> server.isFileAccessible(sessionId, p.toString());
        String unfiltered = FindFileProvider.findFiles(List.of(projectDir), "txt", false, false,
                0, true, -1, p -> true);
        String gated = FindFileProvider.findFiles(List.of(projectDir), "txt", false, false,
                0, true, -1, scope);

        assertTrue(unfiltered.contains("leak.txt"),
                "fixture sanity: without a scope predicate the escaping link IS a walk candidate: " + unfiltered);
        assertFalse(gated.contains("leak.txt"),
                "the per-candidate scope predicate must drop the link whose target is outside: " + gated);
        assertTrue(gated.contains("legit.txt") && gated.contains("target.txt"),
                "in-scope matches must survive the same gate: " + gated);
        assertTrue(gated.startsWith("Found 2 file(s):"), gated);
    }

    // The same boundary through FindFileTool end-to-end: the directoryPath gate passes (the root is in scope), then the
    // per-candidate predicate FindFileTool wires to server.isFileAccessible must exclude the escaping link from results.
    @Test
    void findFileTool_endToEnd_excludesSymlinkEscapingCandidate() throws Exception {
        writeSecretOutside();
        linkOrAbort(projectDir.resolve("leak.txt"), outsideDir.resolve("secret.txt"), "escaping file link");
        writeInScope("legit.txt");
        writeInScope("target.txt");

        FindFileTool tool = new FindFileTool(server);
        String result = tool.handle(findArgs(projectDir.toString(), "txt"), new FakeSession(sessionId));

        assertFalse(result.startsWith("Access denied"), "the project root itself is in scope: " + result);
        assertFalse(result.contains("leak.txt"),
                "the escaping link must not appear in results: " + result);
        assertTrue(result.contains("legit.txt") && result.contains("target.txt"),
                "in-scope matches must still appear: " + result);
        assertTrue(result.startsWith("Found 2 file(s):"), result);
    }

    private static final class NoopRegistrar extends AiMcpRegistrar {

        NoopRegistrar(String sessionId) {
            super(sessionId, AiTypeEnum.CLAUDE);
        }

        @Override
        public void addMcpEndpoint(String endpointUrl) {
        }

        @Override
        public void removeMcpEndpoint() {
        }

        @Override
        public boolean registerHooks(String serverBaseUrl) {
            return true;
        }

        @Override
        public void unregisterHooks() {
        }
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;

        FakeSession(String id) {
            super(AiSession.create(null, AiTypeEnum.CLAUDE));
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
