package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;

/**
 * Reads image files for {@code GetFileBinary}. Pure: it takes bytes and returns a {@link McpToolResult}, so it can be
 * tested without NetBeans. Only whitelisted formats are decoded. The pixel count is read from the header before
 * anything is decoded, and a large image is decoded at reduced resolution, so memory stays bounded.
 */
final class BinaryImages {

    /**
     * Longest edge sent to the model without scaling.
     */
    static final int MAX_LONG_EDGE = 1568;
    /**
     * Largest image passed through, or encoded, in bytes (3.75 MB).
     */
    static final long MAX_PASSTHROUGH_BYTES = 3_932_160L;
    /**
     * Largest pixel count accepted at all (decompression-bomb guard).
     */
    static final long MAX_PIXELS = 25_000_000L;
    /**
     * Largest image file read into memory.
     */
    static final long MAX_IMAGE_FILE_BYTES = 64L * 1024 * 1024;

    private static final float JPEG_QUALITY = 0.85f;
    private static final int MAX_FIT_ATTEMPTS = 6;
    private static final double FIT_SCALE_STEP = 0.75;
    private static final Set<String> PASSTHROUGH_FORMATS = Set.of("png", "jpeg", "gif", "webp");
    private static final Set<String> ALWAYS_PNG_FORMATS = Set.of("bmp", "tif", "tiff");
    /**
     * The only ImageIO formats accepted, lower case. WBMP and other plugins are refused.
     */
    private static final Set<String> ACCEPTED_FORMATS = Set.of("png", "jpeg", "jpg", "gif", "bmp", "tif", "tiff");

    static {
        ImageIO.setUseCache(false);
    }

    /**
     * An encoded image and the size it has.
     */
    record EncodedImage(byte[] bytes, String mime, int width, int height) {

    }

    private BinaryImages() {
    }

    /**
     * The image result for these bytes: text first, then one image part when the image is sent. {@code label} names the
     * file (or archive entry) in the text.
     */
    static McpToolResult read(String label, byte[] bytes) throws IOException {
        if (BinaryFileSniffer.isWebp(bytes)) {
            return readWebp(label, bytes);
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = in == null ? null : acceptedReader(in);
            if (reader == null) {
                return McpToolResult.text(label + ": not an image format this plugin can read.");
            }
            try {
                reader.setInput(in);
                return decode(label, reader, bytes);
            }
            finally {
                reader.dispose();
            }
        }
    }

    private static ImageReader acceptedReader(ImageInputStream in) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (ACCEPTED_FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                return reader;
            }
            reader.dispose();
        }
        return null;
    }

    private static McpToolResult decode(String label, ImageReader reader, byte[] original) throws IOException {
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (width <= 0 || height <= 0) {
            return McpToolResult.text(label + ": the image header gives no usable size.");
        }
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            return McpToolResult.text(label + " is " + width + "x" + height + " (" + pixels
                    + " pixels), over the limit of " + MAX_PIXELS + " pixels; it was not decoded.");
        }
        String info = info(label, format, width, height, original.length);
        boolean fitsAsIs = PASSTHROUGH_FORMATS.contains(format)
                && Math.max(width, height) <= MAX_LONG_EDGE && original.length <= MAX_PASSTHROUGH_BYTES;
        if (fitsAsIs) {
            return McpToolResult.withImage(info, new McpToolResult.ImagePart("image/" + format, base64(original),
                                                                             width, height));
        }
        int maxEdge = Math.max(width, height);
        double scale = maxEdge > MAX_LONG_EDGE ? (double) MAX_LONG_EDGE / maxEdge : 1.0;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage decoded = decodeReduced(reader, maxEdge);
        if (decoded == null) {
            return McpToolResult.text(info + "; the image could not be decoded.");
        }
        BufferedImage sized = resize(decoded, targetWidth, targetHeight);
        boolean preferPng = ALWAYS_PNG_FORMATS.contains(format) || decoded.getColorModel().hasAlpha();
        EncodedImage encoded = encodeWithinLimit(sized, preferPng);
        if (encoded == null) {
            return McpToolResult.text(info + "; it could not be reduced under " + humanSize(MAX_PASSTHROUGH_BYTES)
                    + ", so it was not sent.");
        }
        boolean resized = encoded.width() != width || encoded.height() != height;
        String text = info + (resized ? "; scaled to " + encoded.width() + "x" + encoded.height() : "")
                + "; sent as " + encoded.mime() + " (" + humanSize(encoded.bytes().length) + ").";
        return McpToolResult.withImage(text, new McpToolResult.ImagePart(encoded.mime(),
                                                                         base64(encoded.bytes()),
                                                                         encoded.width(), encoded.height()));
    }

    /**
     * Decodes at a reduced resolution when the image is larger than the longest edge allowed, so a big image never
     * exists in memory at full size. Returns the decoded image, which may be a little larger than the target.
     */
    static BufferedImage decodeReduced(ImageReader reader, int maxEdge) throws IOException {
        // Floor, not ceiling: the decoded long edge stays at or above the target, so resizing only ever shrinks.
        int step = Math.max(1, maxEdge / MAX_LONG_EDGE);
        ImageReadParam param = reader.getDefaultReadParam();
        if (step > 1) {
            param.setSourceSubsampling(step, step, 0, 0);
        }
        return reader.read(0, param);
    }

    /**
     * Encodes an image so that it fits the passthrough limit. A PNG that is too large is retried as a JPEG at the same
     * size; a JPEG that is still too large is scaled down by a quarter and tried again. Returns null when nothing fits
     * within {@link #MAX_FIT_ATTEMPTS} attempts.
     */
    static EncodedImage encodeWithinLimit(BufferedImage start, boolean preferPng) throws IOException {
        BufferedImage current = start;
        boolean png = preferPng;
        for (int attempt = 0; attempt < MAX_FIT_ATTEMPTS; attempt++) {
            byte[] bytes = png ? writePng(current) : writeJpeg(current);
            if (bytes.length <= MAX_PASSTHROUGH_BYTES) {
                return new EncodedImage(bytes, png ? "image/png" : "image/jpeg", current.getWidth(), current.getHeight());
            }
            if (png) {
                png = false;
            }
            else {
                current = resize(current, Math.max(1, (int) Math.round(current.getWidth() * FIT_SCALE_STEP)),
                                 Math.max(1, (int) Math.round(current.getHeight() * FIT_SCALE_STEP)));
            }
        }
        return null;
    }

    private static McpToolResult readWebp(String label, byte[] bytes) {
        int[] size = webpSize(bytes);
        if (size == null) {
            return McpToolResult.text(label + ": WebP header not recognised; the image was not read.");
        }
        int width = size[0];
        int height = size[1];
        String info = info(label, "webp", width, height, bytes.length);
        if (Math.max(width, height) <= MAX_LONG_EDGE && bytes.length <= MAX_PASSTHROUGH_BYTES) {
            return McpToolResult.withImage(info, new McpToolResult.ImagePart("image/webp", base64(bytes), width, height));
        }
        return McpToolResult.text(info + "; too large to send as it is, and WebP cannot be decoded here, so only this"
                + " information is returned.");
    }

    /**
     * Width and height from a WebP container's first chunk, or null when the header is not recognised.
     */
    static int[] webpSize(byte[] b) {
        if (b.length < 30 || !BinaryFileSniffer.isWebp(b)) {
            return null;
        }
        if (startsWith(b, 12, "VP8X")) {
            int width = 1 + (b[24] & 0xff) + ((b[25] & 0xff) << 8) + ((b[26] & 0xff) << 16);
            int height = 1 + (b[27] & 0xff) + ((b[28] & 0xff) << 8) + ((b[29] & 0xff) << 16);
            return new int[]{width, height};
        }
        if (startsWith(b, 12, "VP8 ")) {
            int width = ((b[26] & 0xff) | ((b[27] & 0xff) << 8)) & 0x3fff;
            int height = ((b[28] & 0xff) | ((b[29] & 0xff) << 8)) & 0x3fff;
            return new int[]{width, height};
        }
        if (startsWith(b, 12, "VP8L") && (b[20] & 0xff) == 0x2f) {
            long bits = (b[21] & 0xffL) | ((b[22] & 0xffL) << 8) | ((b[23] & 0xffL) << 16) | ((b[24] & 0xffL) << 24);
            return new int[]{(int) (bits & 0x3fff) + 1, (int) ((bits >> 14) & 0x3fff) + 1};
        }
        return null;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        }
        finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer available");
        }
        return out.toByteArray();
    }

    /**
     * JPEG has no alpha, so the image is laid over white first; transparent areas become white, not black.
     */
    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        BufferedImage flat = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = flat.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            graphics.drawImage(image, 0, 0, null);
        }
        finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(flat, null, null), param);
        }
        finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String info(String label, String format, int width, int height, long size) {
        return label + ", " + format + ", " + width + "x" + height + ", " + humanSize(size);
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean startsWith(byte[] bytes, int offset, String prefix) {
        if (bytes.length < offset + prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (bytes[offset + i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
