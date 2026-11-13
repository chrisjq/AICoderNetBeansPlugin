package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolInvoker;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.FileUtils;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AUDIT 3/6 — proves every GetFileContentTool parameter is read and changes the output: filePath selects the file,
 * startLine narrows from that line, endLine narrows to that line (both 1-based inclusive), and out-of-range values
 * clamp instead of failing.
 */
class GetFileContentToolTest {

    private static final String SESSION_ID = "gfc-session";

    private static ToolRequestArguments args(String filePath, Integer startLine, Integer endLine) {
        JsonObject o = new JsonObject();
        if (filePath != null) {
            o.addProperty(GetFileContentParamEnum.FILE_PATH.key(), filePath);
        }
        if (startLine != null) {
            o.addProperty(GetFileContentParamEnum.START_LINE.key(), startLine);
        }
        if (endLine != null) {
            o.addProperty(GetFileContentParamEnum.END_LINE.key(), endLine);
        }
        return new ToolRequestArguments(o);
    }

    private static ToolRequestArguments rawArgs(String filePath, Integer startLine, Integer endLine) {
        JsonObject o = new JsonObject();
        o.addProperty(GetFileContentParamEnum.FILE_PATH.key(), filePath);
        if (startLine != null) {
            o.addProperty(GetFileContentParamEnum.START_LINE.key(), startLine);
        }
        if (endLine != null) {
            o.addProperty(GetFileContentParamEnum.END_LINE.key(), endLine);
        }
        o.addProperty(GetFileContentParamEnum.RAW.key(), true);
        return new ToolRequestArguments(o);
    }

    private static McpHookServer unrestrictedServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    /**
     * A session that has GetFileBinary registered, so the binary pointer names it. The server is never asked to
     * register a session here: registerSession releases the locks of any session it does not know, and this helper runs
     * while a writer holds a lock the read must wait behind.
     */
    private static FakeSession session() {
        return new FakeSession(SESSION_ID, Map.of(McpToolEnum.GET_FILE_BINARY, new GetFileBinaryTool(new McpHookServer(0))));
    }

    private static Path threeLineFile(Path dir) throws Exception {
        Path f = dir.resolve("sample.txt");
        Files.writeString(f, "alpha\nbeta\ngamma");
        return f;
    }

    @Test
    void schemaAdvertisesFilePathRequiredPlusStartEndLine() {
        JsonObject schema = new GetFileContentTool(unrestrictedServer()).schema(java.util.Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(GetFileContentParamEnum.FILE_PATH.key()));
        assertTrue(props.has(GetFileContentParamEnum.START_LINE.key()));
        assertTrue(props.has(GetFileContentParamEnum.END_LINE.key()));
        assertTrue(props.has(GetFileContentParamEnum.RAW.key()));
        assertEquals("boolean", props.getAsJsonObject(GetFileContentParamEnum.RAW.key())
                     .get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(1, required.size());
        assertEquals(GetFileContentParamEnum.FILE_PATH.key(), required.get(0).getAsString());
        assertEquals("integer", props.getAsJsonObject(GetFileContentParamEnum.START_LINE.key())
                     .get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
    }

    @Test
    void wholeFileReturnedWhenStartAndEndOmitted(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), session());

        assertTrue(result.contains("(lines 1–3 of 3"), result);
        assertTrue(result.contains("alpha") && result.contains("beta") && result.contains("gamma"), result);
    }

    @Test
    void startLineNarrowsFromThatLine(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), 2, null), session());

        assertTrue(result.contains("(lines 2–3 of 3"), result);
        assertTrue(result.contains("beta") && result.contains("gamma"), result);
        assertFalse(result.contains("alpha"), result);
    }

    @Test
    void endLineNarrowsToThatLine(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, 2), session());

        assertTrue(result.contains("(lines 1–2 of 3"), result);
        assertTrue(result.contains("alpha") && result.contains("beta"), result);
        assertFalse(result.contains("gamma"), result);
    }

    @Test
    void startAndEndSelectWindowBothInclusive(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), 2, 2), session());

        assertTrue(result.contains("(lines 2–2 of 3"), result);
        assertTrue(result.contains("beta"), result);
        assertFalse(result.contains("alpha") || result.contains("gamma"), result);
    }

    @Test
    void endLineBeyondEofClampsToWholeFile(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, 100), session());

        assertTrue(result.contains("(lines 1–3 of 3"), result);
        assertTrue(result.contains("gamma"), result);
    }

    @Test
    void missingFileReturnsNotFound(@TempDir Path dir) throws Exception {
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());
        String missing = dir.resolve("nope.txt").toString();

        String result = tool.handle(args(missing, null, null), session());

        assertTrue(result.startsWith(FileUtils.fileNotFoundMessage(missing)), result);
    }

    @Test
    void missingFilePathThrows() {
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        assertThrows(McpArgumentException.class, () -> tool.handle(args(null, null, null), session()));
    }

    @Test
    void rawReturnsExactTextWithNoGutterAndNoHeader(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(rawArgs(f.toString(), null, null), session());

        assertEquals("alpha\nbeta\ngamma", result);
    }

    @Test
    void rawWithLineRangeStillApplies_keepingTheLinesOwnTerminator(@TempDir Path dir) throws Exception {
        Path f = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(rawArgs(f.toString(), 2, 2), session());

        // threeLineFile is "alpha\nbeta\ngamma" (no trailing newline) — line 2 ("beta") is followed by the \n
        // that separates it from "gamma", so the exact original characters for that line include it.
        assertEquals("beta\n", result);
    }

    @Test
    void rawOversizeReturnsErrorWithNoTruncationMarker(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("huge.txt");
        Files.writeString(f, "x".repeat(200_001));
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(rawArgs(f.toString(), null, null), session());

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains(GetFileContentParamEnum.START_LINE.key())
                && result.contains(GetFileContentParamEnum.END_LINE.key()), result);
        assertFalse(result.contains("[Truncated"), "raw output must never carry the inline truncation marker: " + result);
    }

    /**
     * Mutation-prove target: a ranged raw read used to rejoin {@code Files.readAllLines} output with a hardcoded
     * {@code \n}, silently turning CRLF into LF for every line but the file's own terminators.
     */
    @Test
    void rawRangeOnACrlfFilePreservesOriginalLineTerminators(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("crlf.txt");
        Files.writeString(f, "alpha\r\nbeta\r\ngamma\r\n");
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(rawArgs(f.toString(), 2, 2), session());

        assertEquals("beta\r\n", result);
    }

    @Test
    void readWaitsForAnInFlightWriteOnTheSameFile(@TempDir Path dir) throws Exception {
        Path file = threeLineFile(dir);
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch writeRelease = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> write = executor.submit(() -> McpToolInvoker.withFileMutation(
                    "writer", List.of(file.toString()), () -> {
                writeEntered.countDown();
                try {
                    if (!writeRelease.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out holding write lock");
                    }
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted holding write lock", ex);
                }
                return "write-done";
            }));
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS));
            String key = FileUtils.normaliseLockPaths(List.of(file.toString())).iterator().next();
            assertEquals("writer", LockManager.getInstance().getFileLockHolder(key));

            Future<String> read = executor.submit(() -> tool
                    .handle(rawArgs(file.toString(), null, null), session()));
            Thread.sleep(250);
            assertFalse(read.isDone(), "GetFileContent must wait while the same file is being written; result=" + (read.isDone() ? read.get() : "<pending>"));

            writeRelease.countDown();
            assertEquals("write-done", write.get(5, TimeUnit.SECONDS));
            assertEquals("alpha\nbeta\ngamma", read.get(5, TimeUnit.SECONDS));
        }
        finally {
            writeRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aBinaryFilePointsAtGetFileBinaryWhenThatToolIsEnabled(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("blob.bin");
        Files.write(f, new byte[]{0x7f, 'E', 'L', 'F', 0, 1, 2, 3});
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), session());

        assertTrue(result.startsWith("Binary file ("), result);
        assertTrue(result.endsWith("Use " + McpToolEnum.GET_FILE_BINARY.toolName() + " to view it."), result);
        assertFalse(result.contains("Error reading file"), result);
    }

    @Test
    void aBinaryFileNamesNoToolWhenGetFileBinaryIsDisabled(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("blob.bin");
        Files.write(f, new byte[]{0, 1, 2, 3});
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());
        FakeSession session = session();
        session.getSettings().setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.FILE_BINARY)));

        String result = tool.handle(args(f.toString(), null, null), session);

        assertTrue(result.startsWith("Binary file ("), result);
        assertTrue(result.endsWith("; it cannot be read as text."), result);
        assertFalse(result.contains(McpToolEnum.GET_FILE_BINARY.toolName()), result);
    }

    @Test
    void anEmptyFileIsTextNotBinary(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("empty.txt");
        Files.writeString(f, "");
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), session());

        assertFalse(result.startsWith("Binary file"), result);
    }

    @Test
    void aNulByteLateInTheSniffWindowStillMarksTheFileBinary(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("late.dat");
        byte[] content = new byte[4000];
        Arrays.fill(content, (byte) 'a');
        content[3999] = 0;
        Files.write(f, content);

        String pointer = GetFileContentTool.binaryPointer(session(), f.toString());

        assertNotNull(pointer);
        assertTrue(pointer.startsWith("Binary file ("), pointer);
    }

    @Test
    void anSvgFileIsTextAndStaysReadable(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("icon.svg");
        Files.writeString(f, "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"4\" height=\"4\"/></svg>");
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), session());

        assertFalse(result.startsWith("Binary file"), result);
        assertTrue(result.contains("<svg"), result);
    }

    @Test
    void aRealPngIsBinaryWhateverItsProbedType(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("tiny.png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", f.toFile());

        String pointer = GetFileContentTool.binaryPointer(session(), f.toString());

        assertNotNull(pointer);
        assertTrue(pointer.startsWith("Binary file ("), pointer);
    }

    @Test
    void humanSizeUsesBytesKilobytesAndMegabytes() {
        assertEquals("512 bytes", GetFileContentTool.humanSize(512));
        assertEquals("120 KB", GetFileContentTool.humanSize(120 * 1024L));
        assertEquals("3.8 MB", GetFileContentTool.humanSize(4_000_000L));
    }

    @Test
    void anLfsPointerNamedAsAPngReadsAsText(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("logo.png");
        Files.writeString(f, "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 12\n");
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), session());

        assertFalse(result.startsWith("Binary file"), result);
        assertTrue(result.contains("git-lfs"), result);
    }

    @Test
    void anXpmFileReadsAsText(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("icon.xpm");
        Files.writeString(f, "/* XPM */\nstatic char *icon[] = {\n\"1 1 1 1\",\n};\n");
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), session());

        assertFalse(result.startsWith("Binary file"), result);
        assertTrue(result.contains("XPM"), result);
    }

    @Test
    void aUtf16FileWithAByteOrderMarkReadsAsText(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("utf16.txt");
        Files.write(f, "hello".getBytes(StandardCharsets.UTF_16));
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), session());

        assertFalse(result.startsWith("Binary file"), result);
        assertTrue(result.contains("hello"), result);
    }

    @Test
    void aWideEncodingFileNamesItsEncodingOnlyInTheGutterOutput(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("wide.txt");
        Files.write(f, "hello".getBytes(StandardCharsets.UTF_16));
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String gutter = tool.handle(args(f.toString(), null, null), session());
        String raw = tool.handle(rawArgs(f.toString(), null, null), session());

        assertTrue(gutter.contains("Encoding: UTF-16BE (byte-order mark)"), gutter);
        assertFalse(raw.contains("Encoding:"), raw);
        assertEquals("hello", raw);
    }

    @Test
    void aPathThatCannotBeParsedFallsThroughWithoutThrowing() {
        String unparseable = "bad" + (char) 0 + "path";

        assertNull(GetFileContentTool.binaryPointer(session(), unparseable));
    }

    @Test
    void aSessionWithNoGetFileBinaryHandlerNamesNoTool(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("blob.bin");
        Files.write(f, new byte[]{0, 1, 2, 3});
        GetFileContentTool tool = new GetFileContentTool(unrestrictedServer());

        String result = tool.handle(args(f.toString(), null, null), new FakeSession(SESSION_ID, Map.of()));

        assertTrue(result.endsWith("; it cannot be read as text."), result);
        assertFalse(result.contains(McpToolEnum.GET_FILE_BINARY.toolName()), result);
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;
        private final Map<McpToolEnum, McpToolInterface> handlers;

        FakeSession(String id, Map<McpToolEnum, McpToolInterface> handlers) {
            super(AiSession.create(null, AiTypeEnum.CLAUDE));
            this.id = id;
            this.handlers = handlers;
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
            return handlers;
        }
    }
}
