package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryArchivesTest {

    private static Path writeZip(Path dir, String name, Map<String, byte[]> entries) throws IOException {
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

    private static byte[] smallPng() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void recognisesZipBasedExtensionsOnly() {
        assertTrue(BinaryArchives.isArchiveName("library.JAR"));
        assertTrue(BinaryArchives.isArchiveName("app.war"));
        assertTrue(BinaryArchives.isArchiveName("bundle.ear"));
        assertTrue(BinaryArchives.isArchiveName("pack.zip"));
        assertFalse(BinaryArchives.isArchiveName("notes.txt"));
        assertFalse(BinaryArchives.isArchiveName("noextension"));
    }

    @Test
    void listingNamesEntriesWithSizesAndCount(@TempDir Path dir) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", "hello".getBytes(StandardCharsets.US_ASCII));
        entries.put("img.png", smallPng());
        entries.put("data.bin", new byte[100]);
        Path zip = writeZip(dir, "three.zip", entries);

        McpToolResult result = BinaryArchives.list("three.zip", zip);

        assertTrue(result.text().contains("archive with 3 entries"), result.text());
        assertTrue(result.text().contains("a.txt"), result.text());
        assertTrue(result.text().contains("img.png"), result.text());
        assertTrue(result.text().contains("data.bin"), result.text());
        assertFalse(result.text().contains("more not shown"), result.text());
        assertTrue(result.images().isEmpty());
    }

    @Test
    void listingStopsAtTheEntryCap(@TempDir Path dir) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < BinaryArchives.MAX_LISTED_ENTRIES + 1; i++) {
            entries.put("e" + i + ".txt", new byte[]{'x'});
        }
        Path zip = writeZip(dir, "many.zip", entries);

        McpToolResult result = BinaryArchives.list("many.zip", zip);

        assertTrue(result.text().contains("archive with 1001 entries"), result.text());
        assertTrue(result.text().contains("showing the first 1000, 1 more not shown"), result.text());
    }

    @Test
    void textEntryIsReturnedAsText(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, "text.zip", Map.of("a.txt", "hello".getBytes(StandardCharsets.US_ASCII)));

        McpToolResult result = BinaryArchives.readEntry("text.zip", zip, "a.txt", "auto", 0, 0);

        assertTrue(result.text().contains("(text, 5 bytes):\nhello"), result.text());
        assertTrue(result.images().isEmpty());
    }

    @Test
    void imageEntryIsShownAsAnImage(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, "pics.zip", Map.of("img.png", smallPng()));

        McpToolResult result = BinaryArchives.readEntry("pics.zip", zip, "img.png", "auto", 0, 0);

        assertEquals(1, result.images().size(), result.text());
        assertEquals("image/png", result.images().get(0).mimeType());
    }

    @Test
    void binaryEntryIsHexDumped(@TempDir Path dir) throws IOException {
        byte[] binary = new byte[]{0, 1, 2, 3};
        Path zip = writeZip(dir, "bin.zip", Map.of("data.bin", binary));

        McpToolResult result = BinaryArchives.readEntry("bin.zip", zip, "data.bin", "auto", 0, 0);

        assertTrue(result.text().contains("00000000  00 01 02 03"), result.text());
    }

    @Test
    void missingEntryIsReportedNotThrown(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, "one.zip", Map.of("a.txt", new byte[]{'a'}));

        McpToolResult result = BinaryArchives.readEntry("one.zip", zip, "missing.txt", "auto", 0, 0);

        assertTrue(result.text().contains("has no entry named \"missing.txt\""), result.text());
    }

    @Test
    void entryOverTheSizeLimitIsRefused(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, "big.zip", Map.of("huge.bin", new byte[11 * 1024 * 1024]));

        McpToolResult result = BinaryArchives.readEntry("big.zip", zip, "huge.bin", "auto", 0, 0);

        assertTrue(result.text().contains("the limit is"), result.text());
        assertTrue(result.images().isEmpty());
    }

    @Test
    void longTextEntryIsCappedWithANoteAboutTheRawBytes(@TempDir Path dir) throws IOException {
        Random random = new Random(11);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 250_000; i++) {
            text.append((char) ('a' + random.nextInt(26)));
        }
        Path zip = writeZip(dir, "long.zip", Map.of("long.txt", text.toString().getBytes(StandardCharsets.US_ASCII)));

        McpToolResult result = BinaryArchives.readEntry("long.zip", zip, "long.txt", "auto", 0, 0);

        assertTrue(result.text().contains("[truncated: the entry is 250000 bytes"), result.text());
        assertTrue(result.text().contains("mode=hex"), result.text());
        assertTrue(result.text().length() < BinaryArchives.MAX_TEXT_CHARS + 500, "the text must be capped");
    }

    @Test
    void hexModeReadsTheRawBytesOfAnEntry(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, "hex.zip", Map.of("a.txt", "hello".getBytes(StandardCharsets.US_ASCII)));

        McpToolResult result = BinaryArchives.readEntry("hex.zip", zip, "a.txt", "hex", 0, 0);

        assertTrue(result.text().contains("00000000  68 65 6c 6c 6f"), result.text());
        assertFalse(result.text().contains("(text, "), result.text());
    }

    @Test
    void bomTaggedUtf16EntryIsDecodedAndTheMarkIsNotShown(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, "utf16.zip", Map.of("u16.txt", new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0}));

        McpToolResult result = BinaryArchives.readEntry("utf16.zip", zip, "u16.txt", "auto", 0, 0);

        assertTrue(result.text().contains("(text, 6 bytes):\nhi"), result.text());
    }

    @Test
    void bmpEntryIsShownAsAPicture(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "bmp", out);
        Path zip = writeZip(dir, "bmp.zip", Map.of("pic.bmp", out.toByteArray()));

        McpToolResult result = BinaryArchives.readEntry("bmp.zip", zip, "pic.bmp", "auto", 0, 0);

        assertEquals(1, result.images().size(), result.text());
        assertEquals("image/png", result.images().get(0).mimeType());
    }

    @Test
    void textEntryCutNeverSplitsASurrogatePair(@TempDir Path dir) throws IOException {
        Random random = new Random(3);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < BinaryArchives.MAX_TEXT_CHARS - 1; i++) {
            text.append((char) ('a' + random.nextInt(26)));
        }
        // The emoji's two UTF-16 units straddle the cut at MAX_TEXT_CHARS.
        text.append(new String(Character.toChars(0x1F600)));
        text.append("tail");
        Path zip = writeZip(dir, "emoji.zip", Map.of("e.txt", text.toString().getBytes(StandardCharsets.UTF_8)));

        McpToolResult result = BinaryArchives.readEntry("emoji.zip", zip, "e.txt", "auto", 0, 0);

        String body = result.text().substring(result.text().indexOf("):\n") + 3,
                                              result.text().indexOf("\n[truncated"));
        assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)), "the cut must not leave half a pair");
        assertEquals(BinaryArchives.MAX_TEXT_CHARS - 1, body.length());
    }

    @Test
    void entryWithAHighCompressionRatioIsRefused(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir, "bomb.zip", Map.of("zeros.bin", new byte[2 * 1024 * 1024]));

        McpToolResult result = BinaryArchives.readEntry("bomb.zip", zip, "zeros.bin", "auto", 0, 0);

        assertTrue(result.text().contains("compresses more than 100 to one"), result.text());
    }
}
