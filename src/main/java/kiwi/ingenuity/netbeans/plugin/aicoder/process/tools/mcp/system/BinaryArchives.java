package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;

/**
 * Lists and reads zip-based files for {@code GetFileBinary}. Listing is capped at {@link #MAX_LISTED_ENTRIES}. Reading
 * an entry is refused when it is over {@link #MAX_ENTRY_BYTES} uncompressed or compresses more than
 * {@link #MAX_COMPRESSION_RATIO} to one. Text output is capped at {@link #MAX_TEXT_CHARS}.
 */
final class BinaryArchives {

    static final int MAX_LISTED_ENTRIES = 1000;
    static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;
    static final long MAX_COMPRESSION_RATIO = 100L;
    static final int MAX_TEXT_CHARS = 200_000;

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of("zip", "jar", "war", "ear");

    private BinaryArchives() {
    }

    /**
     * Whether the name has a classic archive extension. Not used to decide anything about content; kept for callers
     * that want to name an archive type.
     */
    static boolean isArchiveName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && ARCHIVE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * A listing of the archive's entries: name, uncompressed size, compressed size and modification time.
     */
    static McpToolResult list(String label, Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            int total = zip.size();
            StringBuilder out = new StringBuilder();
            out.append(label).append(": archive with ").append(total).append(" entr")
                    .append(total == 1 ? "y" : "ies");
            if (total > MAX_LISTED_ENTRIES) {
                out.append("; showing the first ").append(MAX_LISTED_ENTRIES).append(", ")
                        .append(total - MAX_LISTED_ENTRIES).append(" more not shown");
            }
            out.append(".\n");
            out.append(String.format(Locale.ROOT, "%-60s %12s %12s  %s%n", "name", "size", "packed", "modified"));
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int listed = 0;
            while (entries.hasMoreElements() && listed < MAX_LISTED_ENTRIES) {
                ZipEntry entry = entries.nextElement();
                String modified = entry.getTime() < 0 ? "-" : Instant.ofEpochMilli(entry.getTime()).toString();
                out.append(String.format(Locale.ROOT, "%-60s %12s %12s  %s%n", entry.getName(),
                                         sizeText(entry.getSize()), sizeText(entry.getCompressedSize()), modified));
                listed++;
            }
            out.append("To read one entry, call again with entry=<name>.");
            return McpToolResult.text(out.toString());
        }
    }

    /**
     * The result for one entry. {@code mode=hex} gives the raw bytes at offset and length. Otherwise a text entry is
     * returned as text (capped), an image is shown, and anything else is a hex dump.
     */
    static McpToolResult readEntry(String label, Path archive, String name, String mode, long offset, int length)
            throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                return McpToolResult.text(label + " has no entry named \"" + name
                        + "\". Call with mode=list to see the names.");
            }
            String entryLabel = label + " entry " + name;
            if (entry.isDirectory()) {
                return McpToolResult.text(entryLabel + " is a directory.");
            }
            long size = entry.getSize();
            long packed = entry.getCompressedSize();
            if (size > MAX_ENTRY_BYTES) {
                return McpToolResult.text(entryLabel + " is " + size + " bytes uncompressed; the limit is "
                        + MAX_ENTRY_BYTES + " bytes.");
            }
            if (size > 0 && (packed <= 0 || size > packed * MAX_COMPRESSION_RATIO)) {
                return McpToolResult.text(entryLabel + " compresses more than " + MAX_COMPRESSION_RATIO
                        + " to one; it was not read.");
            }
            byte[] bytes = readBounded(zip, entry);
            if (bytes == null) {
                return McpToolResult.text(entryLabel + " is larger than its listed size; it was not read.");
            }
            if ("hex".equals(mode)) {
                return McpToolResult.text(BinaryHexDump.format(entryLabel, bytes, offset, length));
            }
            byte[] head = Arrays.copyOf(bytes, Math.min(bytes.length, BinaryFileSniffer.HEAD_BYTES));
            if (!BinaryFileSniffer.isBinary(head)) {
                return McpToolResult.text(textEntry(entryLabel, bytes));
            }
            if (BinaryFileSniffer.hasImageSignature(head)) {
                return BinaryImages.read(entryLabel, bytes);
            }
            return McpToolResult.text(BinaryHexDump.format(entryLabel, bytes, offset, length));
        }
    }

    /**
     * The text of an entry, capped at {@link #MAX_TEXT_CHARS}, with a note that says how to read the raw bytes instead.
     */
    private static String textEntry(String entryLabel, byte[] bytes) {
        // A byte-order mark decides the charset, and the mark itself is not part of the text.
        Charset charset = BinaryFileSniffer.bomCharset(Arrays.copyOf(bytes, Math.min(bytes.length, 4)));
        int skip = charset == null ? 0 : charset.name().startsWith("UTF-32") ? 4 : 2;
        String content = new String(bytes, skip, bytes.length - skip,
                                    charset == null ? StandardCharsets.UTF_8 : charset);
        String header = entryLabel + " (text, " + bytes.length + " bytes):\n";
        if (content.length() <= MAX_TEXT_CHARS) {
            return header + content;
        }
        // Never split a surrogate pair: back off one character if the cut lands between its halves.
        int cut = Character.isHighSurrogate(content.charAt(MAX_TEXT_CHARS - 1)) ? MAX_TEXT_CHARS - 1 : MAX_TEXT_CHARS;
        return header + content.substring(0, cut)
                + "\n[truncated: the entry is " + bytes.length + " bytes; showing the first " + MAX_TEXT_CHARS
                + " characters. For the raw bytes, call again with mode=hex, offset and length.]";
    }

    /**
     * The entry's bytes, or null when the stream holds more than the listed size, which is a lie that must not be
     * trusted.
     */
    private static byte[] readBounded(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
            return bytes.length > MAX_ENTRY_BYTES ? null : bytes;
        }
    }

    private static String sizeText(long size) {
        return size < 0 ? "?" : Long.toString(size);
    }
}
