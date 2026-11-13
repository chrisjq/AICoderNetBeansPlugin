package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides from a file's content alone whether it is binary. Shared by GetFileContent and GetFileBinary so the two tools
 * always agree. The extension and the MIME type are never consulted.
 */
public final class BinaryFileSniffer {

    /**
     * Bytes read to decide what a file is.
     */
    static final int HEAD_BYTES = 8192;

    private BinaryFileSniffer() {
    }

    /**
     * Whether the file is binary. Reads the first 8 KB. An empty file is text. A file that starts with a UTF-16 or
     * UTF-32 byte-order mark is text, even though it contains NUL bytes. Otherwise a file is binary when it has a NUL
     * byte in its head, an image signature from the whitelist (PNG, JPEG, GIF, TIFF, WebP), or a zip signature.
     */
    public static boolean isBinary(Path path) throws IOException {
        return isBinary(readHead(path));
    }

    static byte[] readHead(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(HEAD_BYTES);
        }
    }

    static boolean isBinary(byte[] head) {
        if (head.length == 0 || hasTextByteOrderMark(head)) {
            return false;
        }
        return containsNul(head) || hasImageSignature(head) || hasZipSignature(head);
    }

    static boolean hasImageSignature(byte[] head) {
        return startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)
                || startsWith(head, 0, 0xFF, 0xD8, 0xFF)
                || startsWith(head, 0, 'G', 'I', 'F', '8')
                || startsWith(head, 0, 'I', 'I', '*', 0)
                || startsWith(head, 0, 'M', 'M', 0, '*')
                || isBmp(head)
                || isWebp(head);
    }

    /**
     * A BMP header: "BM", then the reserved fields at offsets 6 to 9, which are zero in every BMP written by a normal
     * encoder. A bare "BM" is not enough, since plain text can start with it.
     */
    static boolean isBmp(byte[] head) {
        return head.length >= 26 && startsWith(head, 0, 'B', 'M') && head[6] == 0 && head[7] == 0
                && head[8] == 0 && head[9] == 0;
    }

    /**
     * The charset a byte-order mark names, or null when there is no mark. UTF-32 is tested before UTF-16, because a
     * UTF-32 mark begins with the UTF-16 one.
     */
    static Charset bomCharset(byte[] head) {
        String name;
        if (startsWith(head, 0, 0x00, 0x00, 0xFE, 0xFF)) {
            name = "UTF-32BE";
        }
        else if (startsWith(head, 0, 0xFF, 0xFE, 0x00, 0x00)) {
            name = "UTF-32LE";
        }
        else if (startsWith(head, 0, 0xFE, 0xFF)) {
            name = "UTF-16BE";
        }
        else if (startsWith(head, 0, 0xFF, 0xFE)) {
            name = "UTF-16LE";
        }
        else {
            return null;
        }
        try {
            return Charset.forName(name);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isWebp(byte[] head) {
        return startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P');
    }

    /**
     * Zip signature, for local file headers ({@code PK\3\4}) and an empty archive ({@code PK\5\6}).
     */
    static boolean hasZipSignature(byte[] head) {
        return startsWith(head, 0, 'P', 'K', 3, 4) || startsWith(head, 0, 'P', 'K', 5, 6);
    }

    static boolean containsNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTextByteOrderMark(byte[] head) {
        return startsWith(head, 0, 0x00, 0x00, 0xFE, 0xFF)
                || startsWith(head, 0, 0xFF, 0xFE, 0x00, 0x00)
                || startsWith(head, 0, 0xFE, 0xFF)
                || startsWith(head, 0, 0xFF, 0xFE);
    }

    /**
     * Whether {@code expected} occurs at {@code offset}. Values are compared as unsigned bytes.
     */
    private static boolean startsWith(byte[] head, int offset, int... expected) {
        if (head.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[offset + i] & 0xff) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
