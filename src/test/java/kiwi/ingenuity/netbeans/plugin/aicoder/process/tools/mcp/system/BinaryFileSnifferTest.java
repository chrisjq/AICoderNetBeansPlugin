package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryFileSnifferTest {

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "no writer for " + format);
        return out.toByteArray();
    }

    private static byte[] zipBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("a.txt"));
            zip.write("hello".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private static Path write(Path dir, String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    @Test
    void gitLfsPointerNamedAsAPngIsText(@TempDir Path dir) throws IOException {
        Path file = write(dir, "logo.png", ("version https://git-lfs.github.com/spec/v1\n"
                          + "oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\n"
                          + "size 12345\n").getBytes(StandardCharsets.US_ASCII));

        assertFalse(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void asciiPortableGraymapIsText(@TempDir Path dir) throws IOException {
        Path file = write(dir, "pic.ppm", "P3\n1 1\n255\n0 0 0\n".getBytes(StandardCharsets.US_ASCII));

        assertFalse(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void xpmPixmapIsText(@TempDir Path dir) throws IOException {
        Path file = write(dir, "icon.xpm", "/* XPM */\nstatic char *icon[] = {\n\"1 1 1 1\",\n};\n"
                          .getBytes(StandardCharsets.US_ASCII));

        assertFalse(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void emptyFileNamedAsAPngIsText(@TempDir Path dir) throws IOException {
        Path file = write(dir, "foo.png", new byte[0]);

        assertFalse(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void textContentInAZipNamedFileIsText(@TempDir Path dir) throws IOException {
        Path file = write(dir, "notes.zip", "not really a zip, just words".getBytes(StandardCharsets.US_ASCII));

        assertFalse(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void utf16FileWithByteOrderMarkIsText(@TempDir Path dir) throws IOException {
        Path file = write(dir, "utf16.txt", new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0});

        assertFalse(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void utf16BigEndianByteOrderMarkIsText(@TempDir Path dir) throws IOException {
        Path file = write(dir, "utf16be.txt", new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'h', 0, 'i'});

        assertFalse(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void pngIsBinary(@TempDir Path dir) throws IOException {
        Path file = write(dir, "shot.png", encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png"));

        assertTrue(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void jpegIsBinary(@TempDir Path dir) throws IOException {
        Path file = write(dir, "photo.jpg", encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg"));

        assertTrue(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void gifIsBinary(@TempDir Path dir) throws IOException {
        Path file = write(dir, "anim.gif", encode(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_INDEXED), "gif"));

        assertTrue(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void webpIsBinary(@TempDir Path dir) throws IOException {
        byte[] webp = new byte[30];
        byte[] riff = "RIFF".getBytes(StandardCharsets.US_ASCII);
        byte[] webpTag = "WEBP".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(riff, 0, webp, 0, 4);
        System.arraycopy(webpTag, 0, webp, 8, 4);
        Path file = write(dir, "pic.webp", webp);

        assertTrue(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void realZipIsBinary(@TempDir Path dir) throws IOException {
        Path file = write(dir, "pack.zip", zipBytes());

        assertTrue(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void fileWithANulByteIsBinary(@TempDir Path dir) throws IOException {
        Path file = write(dir, "data.txt", new byte[]{'a', 0, 'b'});

        assertTrue(BinaryFileSniffer.isBinary(file));
    }

    @Test
    void aBmpHeaderIsAnImageSignatureButABareBmIsNot() throws IOException {
        byte[] bmp = encode(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "bmp");

        assertTrue(BinaryFileSniffer.hasImageSignature(bmp));
        assertFalse(BinaryFileSniffer.hasImageSignature(
                "BM is a word, not a picture, so this is text".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void byteOrderMarksNameTheirCharset() {
        assertEquals("UTF-32BE", BinaryFileSniffer.bomCharset(new byte[]{0, 0, (byte) 0xFE, (byte) 0xFF}).name());
        assertEquals("UTF-32LE", BinaryFileSniffer.bomCharset(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0}).name());
        assertEquals("UTF-16BE", BinaryFileSniffer.bomCharset(new byte[]{(byte) 0xFE, (byte) 0xFF}).name());
        assertEquals("UTF-16LE", BinaryFileSniffer.bomCharset(new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0}).name());
        assertNull(BinaryFileSniffer.bomCharset("plain".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void theDecisionUsesContentNotName(@TempDir Path dir) throws IOException {
        Path asText = write(dir, "image.bin", "plain".getBytes(StandardCharsets.US_ASCII));
        Path asBinary = write(dir, "readme.txt", new byte[]{0, 0, 0});

        assertFalse(BinaryFileSniffer.isBinary(asText));
        assertTrue(BinaryFileSniffer.isBinary(asBinary));
    }
}
