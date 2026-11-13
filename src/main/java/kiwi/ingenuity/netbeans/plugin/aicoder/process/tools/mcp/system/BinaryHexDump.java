package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Hex dump of a byte range, 16 bytes a line with an ASCII column, for {@code GetFileBinary}'s hex mode. The header line
 * gives the file size, the range shown and how to page on.
 */
final class BinaryHexDump {

    static final int BYTES_PER_LINE = 16;
    static final int DEFAULT_LENGTH = 4096;
    static final int MAX_LENGTH = 32_768;

    private BinaryHexDump() {
    }

    /**
     * A dump of a byte range of a file, read directly from the file so only the requested window is loaded.
     */
    static String formatFile(String label, Path file, long offset, int length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long size = raf.length();
            if (size == 0) {
                return label + " is empty (0 bytes).";
            }
            long[] range = clampRange(size, offset, length);
            if (range == null) {
                return refusal(label, size, offset);
            }
            byte[] window = new byte[(int) (range[1] - range[0])];
            raf.seek(range[0]);
            raf.readFully(window);
            return format(label, size, range[0], window);
        }
    }

    /**
     * A dump of a byte range of an in-memory buffer, such as an archive entry.
     */
    static String format(String label, byte[] data, long offset, int length) {
        long size = data.length;
        if (size == 0) {
            return label + " is empty (0 bytes).";
        }
        long[] range = clampRange(size, offset, length);
        if (range == null) {
            return refusal(label, size, offset);
        }
        return format(label, size, range[0], Arrays.copyOfRange(data, (int) range[0], (int) range[1]));
    }

    /**
     * The window [start, end) that a request covers, with the length capped at {@link #MAX_LENGTH}; null when the
     * offset is negative or past the end.
     */
    static long[] clampRange(long size, long offset, int length) {
        if (offset < 0 || (size > 0 && offset >= size)) {
            return null;
        }
        int wanted = length <= 0 ? DEFAULT_LENGTH : Math.min(length, MAX_LENGTH);
        long end = Math.min(size, offset + wanted);
        return new long[]{offset, end};
    }

    private static String refusal(String label, long size, long offset) {
        if (offset < 0) {
            return label + ": offset must be 0 or more.";
        }
        return label + " is " + size + " bytes; offset " + offset + " is past the end.";
    }

    private static String format(String label, long size, long start, byte[] window) {
        long end = start + window.length;
        StringBuilder out = new StringBuilder();
        out.append(label).append(": ").append(size).append(" bytes; showing bytes ").append(start)
                .append(" to ").append(end - 1).append(" (").append(window.length).append(" bytes).");
        if (end < size) {
            out.append(" To read more, call again with offset=").append(end).append(".");
        }
        out.append('\n');
        for (int lineStart = 0; lineStart < window.length; lineStart += BYTES_PER_LINE) {
            appendLine(out, start + lineStart, window, lineStart);
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, long address, byte[] window, int lineStart) {
        out.append(String.format("%08x  ", address));
        int count = Math.min(BYTES_PER_LINE, window.length - lineStart);
        for (int i = 0; i < BYTES_PER_LINE; i++) {
            if (i < count) {
                out.append(String.format("%02x ", window[lineStart + i] & 0xff));
            }
            else {
                out.append("   ");
            }
            if (i == 7) {
                out.append(' ');
            }
        }
        out.append(" |");
        for (int i = 0; i < count; i++) {
            int value = window[lineStart + i] & 0xff;
            out.append(value >= 0x20 && value < 0x7f ? (char) value : '.');
        }
        out.append("|\n");
    }
}
