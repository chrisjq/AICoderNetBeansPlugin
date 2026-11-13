package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GetFileBinaryToolTest {

    private static final String SESSION_ID = "gfb-session";

    private static McpHookServer registeredServer() {
        McpHookServer server = new McpHookServer(0);
        server.registerSession(SESSION_ID, CLAUDE, List.of(), false);
        return server;
    }

    private static ToolRequestArguments args(String filePath, String mode, String entry) {
        JsonObject o = new JsonObject();
        o.addProperty(GetFileBinaryParamEnum.FILE_PATH.key(), filePath);
        if (mode != null) {
            o.addProperty(GetFileBinaryParamEnum.MODE.key(), mode);
        }
        if (entry != null) {
            o.addProperty(GetFileBinaryParamEnum.ENTRY.key(), entry);
        }
        return new ToolRequestArguments(o);
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static Path writeZip(Path dir, String name, Map<String, byte[]> entries) throws Exception {
        Path zip = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void schemaListsEveryParameterAndRequiresFilePath() {
        JsonObject schema = new GetFileBinaryTool(registeredServer()).schema(Set.of())
                .getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        JsonObject props = schema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(GetFileBinaryParamEnum.FILE_PATH.key()));
        assertTrue(props.has(GetFileBinaryParamEnum.MODE.key()));
        assertTrue(props.has(GetFileBinaryParamEnum.OFFSET.key()));
        assertTrue(props.has(GetFileBinaryParamEnum.LENGTH.key()));
        assertTrue(props.has(GetFileBinaryParamEnum.ENTRY.key()));
        assertEquals("integer", props.getAsJsonObject(GetFileBinaryParamEnum.OFFSET.key())
                     .get(ToolSchemaKeyEnum.TYPE.key()).getAsString());
        JsonArray required = schema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertEquals(1, required.size());
        assertEquals(GetFileBinaryParamEnum.FILE_PATH.key(), required.get(0).getAsString());
    }

    @Test
    void sectionIsSystemAndTheToolIsReadOnly() {
        McpToolInterface tool = new GetFileBinaryTool(registeredServer());

        assertEquals(McpSectionEnum.SYSTEM, tool.section());
        assertFalse(tool.isMutating());
        assertEquals("GetFileBinary", McpToolEnum.GET_FILE_BINARY.toolName());
    }

    @Test
    void pngComesBackAsAPictureWithItsTextFirst(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shot.png");
        Files.write(file, png());

        McpToolResult result = new GetFileBinaryTool(registeredServer())
                .handleResult(args(file.toString(), null, null), session());

        assertEquals(1, result.images().size(), result.text());
        assertEquals("image/png", result.images().get(0).mimeType());
        assertTrue(result.text().contains("png, 16x8"), result.text());
    }

    @Test
    void textFileIsRefusedWithAPointerToGetFileContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "hello world\n");

        String text = new GetFileBinaryTool(registeredServer()).handle(args(file.toString(), null, null), session());

        assertTrue(text.contains("This is a text file; use GetFileContent, or mode=hex for the raw bytes."), text);
    }

    @Test
    void modeHexOverridesTheImageChoice(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shot.png");
        Files.write(file, png());

        McpToolResult result = new GetFileBinaryTool(registeredServer())
                .handleResult(args(file.toString(), "hex", null), session());

        assertTrue(result.images().isEmpty(), "hex mode must not send the picture");
        assertTrue(result.text().contains("00000000  89 50 4e 47"), result.text());
    }

    @Test
    void modeImageOnATextFileIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "not a picture");

        McpToolResult result = new GetFileBinaryTool(registeredServer())
                .handleResult(args(file.toString(), "image", null), session());

        assertTrue(result.images().isEmpty());
        assertTrue(result.text().contains("not an image format"), result.text());
    }

    @Test
    void unknownModeIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shot.png");
        Files.write(file, png());

        String text = new GetFileBinaryTool(registeredServer()).handle(args(file.toString(), "video", null), session());

        assertTrue(text.contains("Unknown mode \"video\""), text);
    }

    @Test
    void zipIsListedAutomatically(@TempDir Path dir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", "hello".getBytes(StandardCharsets.US_ASCII));
        Path zip = writeZip(dir, "pack.zip", entries);

        String text = new GetFileBinaryTool(registeredServer()).handle(args(zip.toString(), null, null), session());

        assertTrue(text.contains("archive with 1 entry"), text);
        assertTrue(text.contains("a.txt"), text);
    }

    @Test
    void entryReadsOneEntryOfAnArchive(@TempDir Path dir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", "hello".getBytes(StandardCharsets.US_ASCII));
        Path zip = writeZip(dir, "pack.zip", entries);

        String text = new GetFileBinaryTool(registeredServer())
                .handle(args(zip.toString(), null, "a.txt"), session());

        assertTrue(text.contains("(text, 5 bytes):\nhello"), text);
    }

    @Test
    void entryOnANonArchiveIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "hello");

        String text = new GetFileBinaryTool(registeredServer()).handle(args(file.toString(), null, "x"), session());

        assertTrue(text.contains("is not a readable zip-based file"), text);
    }

    @Test
    void listModeRefusesANonArchive(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shot.png");
        Files.write(file, png());

        String text = new GetFileBinaryTool(registeredServer()).handle(args(file.toString(), "list", null), session());

        assertTrue(text.contains("is not a readable zip-based file"), text);
    }

    @Test
    void accessDeniedFileGetsTheDenialMessageAndNoPicture(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("shot.png");
        Files.write(file, png());
        McpHookServer server = new McpHookServer(0);
        String sessionId = "gfb-unregistered";
        assumeFalse(server.isFileAccessible(sessionId, file.toString()),
                    "this session can read the file, so there is no denial to check");

        McpToolResult result = new GetFileBinaryTool(server)
                .handleResult(args(file.toString(), null, null), new FakeSession(sessionId));

        assertEquals(McpHookServer.fileAccessDeniedMessage(server, sessionId, file.toString()), result.text());
        assertTrue(result.images().isEmpty());
    }

    @Test
    void hexModeWorksOnATextFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "hello");

        String text = new GetFileBinaryTool(registeredServer()).handle(args(file.toString(), "hex", null), session());

        assertTrue(text.contains("00000000  68 65 6c 6c 6f"), text);
    }

    @Test
    void emptyFileInHexModeSaysItIsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        String text = new GetFileBinaryTool(registeredServer()).handle(args(file.toString(), "hex", null), session());

        assertTrue(text.contains("is empty (0 bytes)."), text);
    }

    @Test
    void listModeAcceptsAnyZipBasedFileByItsContent(@TempDir Path dir) throws Exception {
        Path file = writeZip(dir, "app.apk", Map.of("a.txt", "hi".getBytes(StandardCharsets.US_ASCII)));

        String text = new GetFileBinaryTool(registeredServer()).handle(args(file.toString(), "list", null), session());

        assertTrue(text.contains("archive with 1 entry"), text);
    }

    @Test
    void theToolAndTheSnifferAgreeOnEveryFixture(@TempDir Path dir) throws Exception {
        Map<String, byte[]> fixtures = new LinkedHashMap<>();
        fixtures.put("logo.png", ("version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 1\n")
                     .getBytes(StandardCharsets.US_ASCII));
        fixtures.put("pic.ppm", "P3\n1 1\n255\n0 0 0\n".getBytes(StandardCharsets.US_ASCII));
        fixtures.put("icon.xpm", "/* XPM */\nstatic char *x[] = {\n};\n".getBytes(StandardCharsets.US_ASCII));
        fixtures.put("foo.png", new byte[0]);
        fixtures.put("notes.zip", "just words".getBytes(StandardCharsets.US_ASCII));
        fixtures.put("utf16.txt", new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0});
        fixtures.put("shot.png", encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png"));
        fixtures.put("photo.jpg", encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg"));
        fixtures.put("anim.gif", encode(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_INDEXED), "gif"));
        fixtures.put("pic.webp", webpHeader());
        fixtures.put("pack.zip", zipBytes(dir));
        fixtures.put("data.bin", new byte[]{'a', 0, 'b'});
        GetFileBinaryTool tool = new GetFileBinaryTool(registeredServer());
        StringBuilder disagreements = new StringBuilder();
        for (Map.Entry<String, byte[]> fixture : fixtures.entrySet()) {
            Path file = dir.resolve("fixture-" + fixture.getKey());
            Files.write(file, fixture.getValue());
            boolean binary = BinaryFileSniffer.isBinary(file);
            boolean refused = tool.handle(args(file.toString(), null, null), session()).startsWith("This is a text file");
            if (refused == binary) {
                disagreements.append(fixture.getKey()).append(": binary=").append(binary)
                        .append(", refused=").append(refused).append(System.lineSeparator());
            }
        }
        assertEquals("", disagreements.toString());
    }

    @Test
    void unexpectedRuntimeErrorsBecomeAResultNotAnInternalError() {
        McpToolResult result = GetFileBinaryTool.readOrReport(null, "broken.png", null, null, 0, 0);

        assertTrue(result.text().startsWith("Could not read broken.png: "), result.text());
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "no writer for " + format);
        return out.toByteArray();
    }

    private static byte[] zipBytes(Path dir) throws Exception {
        return Files.readAllBytes(writeZip(dir, "zip-fixture.zip", Map.of("a.txt", new byte[]{'a'})));
    }

    /**
     * A WebP container with a VP8X chunk, enough to be recognised as WebP.
     */
    private static byte[] webpHeader() {
        byte[] bytes = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        System.arraycopy("VP8X".getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
        bytes[16] = 10;
        return bytes;
    }

    @Test
    void bmpIsShownAsAPictureInAutoMode(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("old.bmp");
        Files.write(file, encode(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "bmp"));

        McpToolResult result = new GetFileBinaryTool(registeredServer())
                .handleResult(args(file.toString(), null, null), session());

        assertEquals(1, result.images().size(), result.text());
        assertEquals("image/png", result.images().get(0).mimeType());
    }

    @Test
    void zipWithATextPrefixIsListedAndItsEntryReadInAutoAndExplicitModes(@TempDir Path dir) throws Exception {
        byte[] prefix = "#!/bin/sh\necho launching\n".getBytes(StandardCharsets.US_ASCII);
        byte[] zip = zipBytes(dir);
        byte[] prefixed = new byte[prefix.length + zip.length];
        System.arraycopy(prefix, 0, prefixed, 0, prefix.length);
        System.arraycopy(zip, 0, prefixed, prefix.length, zip.length);
        Path file = dir.resolve("launch.jar");
        Files.write(file, prefixed);
        GetFileBinaryTool tool = new GetFileBinaryTool(registeredServer());

        String auto = tool.handle(args(file.toString(), null, null), session());
        String listed = tool.handle(args(file.toString(), "list", null), session());
        String entry = tool.handle(args(file.toString(), null, "a.txt"), session());

        assertTrue(auto.contains("archive with 1 entry"), auto);
        assertTrue(listed.contains("archive with 1 entry"), listed);
        assertTrue(entry.contains("(text, 1 bytes):\na"), entry);
    }

    @Test
    void anEntryRequestOnAPlainFileSaysItIsNotAZip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "hello");

        String text = new GetFileBinaryTool(registeredServer()).handle(args(file.toString(), null, "x"), session());

        assertTrue(text.contains("is not a readable zip-based file"), text);
    }

    private static FakeSession session() {
        return new FakeSession(SESSION_ID);
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
