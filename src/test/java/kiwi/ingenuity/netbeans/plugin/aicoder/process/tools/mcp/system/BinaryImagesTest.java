package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BinaryImagesTest {

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out), "no writer for " + format);
        return out.toByteArray();
    }

    private static BufferedImage solid(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xff3366cc);
            }
        }
        return image;
    }

    private static McpToolResult.ImagePart onlyImage(McpToolResult result) {
        assertEquals(1, result.images().size(), result.text());
        return result.images().get(0);
    }

    @Test
    void pngIsPassedThroughUnchanged() throws IOException {
        byte[] png = encode(solid(100, 80, BufferedImage.TYPE_INT_RGB), "png");

        McpToolResult result = BinaryImages.read("shot.png", png);

        McpToolResult.ImagePart image = onlyImage(result);
        assertEquals("image/png", image.mimeType());
        assertEquals(100, image.width());
        assertEquals(80, image.height());
        assertEquals(Base64.getEncoder().encodeToString(png), image.base64());
        assertTrue(result.text().contains("png, 100x80"), result.text());
        assertFalse(result.text().contains("scaled"), result.text());
    }

    @Test
    void jpegIsPassedThroughUnchanged() throws IOException {
        byte[] jpeg = encode(solid(64, 64, BufferedImage.TYPE_INT_RGB), "jpg");

        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("photo.jpg", jpeg));

        assertEquals("image/jpeg", image.mimeType());
        assertArrayEquals(jpeg, Base64.getDecoder().decode(image.base64()));
    }

    @Test
    void gifIsPassedThroughUnchanged() throws IOException {
        byte[] gif = encode(solid(40, 30, BufferedImage.TYPE_BYTE_INDEXED), "gif");

        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("anim.gif", gif));

        assertEquals("image/gif", image.mimeType());
        assertEquals(40, image.width());
        assertEquals(30, image.height());
    }

    @Test
    void oversizeOpaquePngIsScaledAndSentAsJpeg() throws IOException {
        byte[] png = encode(solid(2000, 1000, BufferedImage.TYPE_INT_RGB), "png");

        McpToolResult result = BinaryImages.read("wide.png", png);

        McpToolResult.ImagePart image = onlyImage(result);
        assertEquals("image/jpeg", image.mimeType());
        assertEquals(BinaryImages.MAX_LONG_EDGE, image.width());
        assertEquals(784, image.height());
        assertTrue(result.text().contains("scaled to 1568x784"), result.text());
    }

    @Test
    void oversizeImageWithAlphaIsScaledAndSentAsPng() throws IOException {
        byte[] png = encode(solid(2000, 1000, BufferedImage.TYPE_INT_ARGB), "png");

        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("alpha.png", png));

        assertEquals("image/png", image.mimeType());
        assertEquals(BinaryImages.MAX_LONG_EDGE, image.width());
    }

    @Test
    void pixelCountGuardRefusesBeforeDecoding() throws IOException {
        byte[] header = pngHeaderOnly(10_000, 10_000);

        McpToolResult result = BinaryImages.read("huge.png", header);

        assertTrue(result.images().isEmpty());
        assertTrue(result.text().contains("over the limit"), result.text());
        assertTrue(result.text().contains("not decoded"), result.text());
    }

    @Test
    void bmpIsConvertedToPng() throws IOException {
        byte[] bmp = encode(solid(50, 50, BufferedImage.TYPE_INT_RGB), "bmp");

        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("old.bmp", bmp));

        assertEquals("image/png", image.mimeType());
        assertEquals(50, image.width());
        assertEquals(50, image.height());
    }

    @Test
    void largeWebpGivesTextOnly() throws IOException {
        byte[] webp = webpVp8x(2000, 1000);

        McpToolResult result = BinaryImages.read("big.webp", webp);

        assertTrue(result.images().isEmpty(), "a WebP that needs scaling cannot be sent");
        assertTrue(result.text().contains("2000x1000"), result.text());
        assertTrue(result.text().contains("cannot be decoded here"), result.text());
    }

    @Test
    void smallWebpIsPassedThrough() throws IOException {
        byte[] webp = webpVp8x(100, 80);

        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("small.webp", webp));

        assertEquals("image/webp", image.mimeType());
        assertEquals(100, image.width());
        assertEquals(80, image.height());
    }

    @Test
    void webpSizeReadsEachChunkKind() {
        assertArrayEquals(new int[]{2000, 1000}, BinaryImages.webpSize(webpVp8x(2000, 1000)));
        assertEquals(null, BinaryImages.webpSize(new byte[40]));
    }

    @Test
    void unreadableBytesAreNotAnImage() throws IOException {
        byte[] notAnImage = "plain words, no picture here".getBytes(StandardCharsets.US_ASCII);

        McpToolResult result = BinaryImages.read("notes.bin", notAnImage);

        assertTrue(result.images().isEmpty());
        assertTrue(result.text().contains("not an image format"), result.text());
    }

    @Test
    void hugeOpaqueImageIsDecodedReducedAndScaled() throws IOException {
        byte[] png = encode(solid(4000, 2000, BufferedImage.TYPE_INT_RGB), "png");

        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("huge.png", png));

        assertEquals(BinaryImages.MAX_LONG_EDGE, image.width());
        assertEquals(784, image.height());
        assertEquals("image/jpeg", image.mimeType());
    }

    @Test
    void wbmpIsRefusedBecauseItIsNotAWhitelistedFormat() throws IOException {
        byte[] wbmp = encode(new BufferedImage(20, 20, BufferedImage.TYPE_BYTE_BINARY), "wbmp");

        McpToolResult result = BinaryImages.read("icon.wbmp", wbmp);

        assertTrue(result.images().isEmpty());
        assertTrue(result.text().contains("not an image format"), result.text());
    }

    @Test
    void tiffIsConvertedToPng() throws IOException {
        byte[] tiff = encode(solid(40, 30, BufferedImage.TYPE_INT_RGB), "tiff");

        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("scan.tiff", tiff));

        assertEquals("image/png", image.mimeType());
        assertEquals(40, image.width());
        assertEquals(30, image.height());
    }

    @Test
    void imageTooLargeForTheLimitIsFittedByJpegThenScaling() {
        BufferedImage noise = new BufferedImage(2500, 2500, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(7);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt());
            }
        }

        BinaryImages.EncodedImage fitted;
        try {
            fitted = BinaryImages.encodeWithinLimit(noise, true);
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }

        assertNotNull(fitted, "some size must fit within the limit");
        assertTrue(fitted.bytes().length <= BinaryImages.MAX_PASSTHROUGH_BYTES);
        assertTrue(fitted.width() < 2500, "the noise is too big to send at full size, so it must be scaled down");
        assertEquals("image/jpeg", fitted.mime());
    }

    @Test
    void justOverTheLongEdgeIsResizedDownNotDecodedSmallerAndUpscaled() throws IOException {
        byte[] png = encode(solid(1920, 1080, BufferedImage.TYPE_INT_RGB), "png");

        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("hd.png", png));

        assertEquals(BinaryImages.MAX_LONG_EDGE, image.width());
        assertEquals(882, image.height());
    }

    @Test
    void aFineCheckerboardJustOverTheLongEdgeKeepsItsDetail() throws IOException {
        BufferedImage board = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                board.setRGB(x, y, (x + y) % 2 == 0 ? 0x000000 : 0xFFFFFF);
            }
        }
        McpToolResult.ImagePart image = onlyImage(BinaryImages.read("board.png", encode(board, "png")));

        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.base64())));
        java.util.Set<Integer> colours = new java.util.HashSet<>();
        for (int y = 0; y < sent.getHeight(); y += 7) {
            for (int x = 0; x < sent.getWidth(); x += 7) {
                colours.add(sent.getRGB(x, y) & 0xFFFFFF);
            }
        }
        assertTrue(colours.size() > 10, "a checkerboard sent as a uniform grey has lost its detail");
    }

    @Test
    void decodedImageNeverDropsBelowTheLongEdgeTarget() throws IOException {
        byte[] png = encode(solid(3200, 1600, BufferedImage.TYPE_INT_RGB), "png");
        ImageReader reader = ImageIO.getImageReadersByFormatName("png").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            reader.setInput(in);
            BufferedImage decoded = BinaryImages.decodeReduced(reader, 3200);

            assertTrue(Math.max(decoded.getWidth(), decoded.getHeight()) >= BinaryImages.MAX_LONG_EDGE,
                       "the decoded long edge must stay at or above the target so only downscaling follows");
        }
        finally {
            reader.dispose();
        }
    }

    @Test
    void humanSizeUsesBytesKilobytesAndMegabytes() {
        assertEquals("12 bytes", BinaryImages.humanSize(12));
        assertEquals("3 KB", BinaryImages.humanSize(3 * 1024));
        assertEquals("2.0 MB", BinaryImages.humanSize(2L * 1024 * 1024));
    }

    /**
     * A PNG signature and IHDR chunk, with a valid CRC and no image data: enough to read the size and nothing more.
     */
    private static byte[] pngHeaderOnly(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).array();
        writeChunk(out, "IHDR", ihdr);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /**
     * A WebP container with a VP8X chunk, which carries the canvas size and nothing else needed here.
     */
    private static byte[] webpVp8x(int width, int height) {
        int canvasWidth = width - 1;
        int canvasHeight = height - 1;
        byte[] bytes = new byte[30];
        put(bytes, 0, "RIFF");
        bytes[4] = 22;
        put(bytes, 8, "WEBP");
        put(bytes, 12, "VP8X");
        bytes[16] = 10;
        bytes[24] = (byte) canvasWidth;
        bytes[25] = (byte) (canvasWidth >> 8);
        bytes[26] = (byte) (canvasWidth >> 16);
        bytes[27] = (byte) canvasHeight;
        bytes[28] = (byte) (canvasHeight >> 8);
        bytes[29] = (byte) (canvasHeight >> 16);
        return bytes;
    }

    private static void put(byte[] target, int offset, String text) {
        byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, target, offset, ascii.length);
    }
}
