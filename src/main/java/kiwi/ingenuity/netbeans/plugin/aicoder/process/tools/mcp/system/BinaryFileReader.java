package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolResult;

/**
 * Chooses how {@code GetFileBinary} shows a file. Mode {@code auto} asks {@link BinaryFileSniffer} first: a text file
 * is refused with a pointer to GetFileContent, so the two tools agree. A binary file then goes to an image, an archive
 * listing or a hex dump. An explicit mode overrides that choice; {@code hex} works on any file, text included.
 */
final class BinaryFileReader {

    private static final String NOT_A_ZIP = " is not a readable zip-based file: ";

    private BinaryFileReader() {
    }

    /**
     * @param file the file, already access-checked
     * @param label how the file is named in the result text
     * @param mode null or blank for auto, otherwise image, hex or list
     * @param entry an archive entry to read, or null
     * @param offset the first byte of a hex dump
     * @param length the number of bytes of a hex dump
     */
    static McpToolResult read(Path file, String label, String mode, String entry, long offset, int length)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            return McpToolResult.text(label + " is not a regular file.");
        }
        String kind = mode == null || mode.isBlank() ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if (entry != null && !entry.isBlank()) {
            // No magic check here: the zip library decides, so a zip with a text prefix (a launch script before the
            // archive, for example) still opens.
            try {
                return BinaryArchives.readEntry(label, file, entry, kind, offset, length);
            }
            catch (ZipException e) {
                return McpToolResult.text(label + NOT_A_ZIP + e.getMessage());
            }
        }
        switch (kind) {
            case "auto":
                return readAuto(file, label, offset, length);
            case "image":
                return readImage(file, label);
            case "hex":
                return McpToolResult.text(BinaryHexDump.formatFile(label, file, offset, length));
            case "list":
                try {
                    return BinaryArchives.list(label, file);
                }
                catch (ZipException e) {
                    return McpToolResult.text(label + NOT_A_ZIP + e.getMessage());
                }
            default:
                return McpToolResult.text("Unknown mode \"" + mode + "\": use auto, image, hex or list.");
        }
    }

    private static McpToolResult readAuto(Path file, String label, long offset, int length) throws IOException {
        byte[] head = BinaryFileSniffer.readHead(file);
        if (!BinaryFileSniffer.isBinary(head)) {
            return McpToolResult.text("This is a text file; use " + McpToolEnum.GET_FILE_CONTENT.toolName()
                    + ", or mode=hex for the raw bytes.");
        }
        if (BinaryFileSniffer.hasImageSignature(head)) {
            return readImage(file, label);
        }
        // A zip is tried by its name as well as its signature, so a prefixed archive is still listed. If it does not
        // open as a zip after all, the bytes are shown instead.
        if (BinaryFileSniffer.hasZipSignature(head) || BinaryArchives.isArchiveName(file.getFileName().toString())) {
            try {
                return BinaryArchives.list(label, file);
            }
            catch (IOException e) {
                // Not a readable archive: fall through to the hex dump.
            }
        }
        return McpToolResult.text(BinaryHexDump.formatFile(label, file, offset, length));
    }

    private static McpToolResult readImage(Path file, String label) throws IOException {
        long size = Files.size(file);
        if (size > BinaryImages.MAX_IMAGE_FILE_BYTES) {
            return McpToolResult.text(label + " is " + BinaryImages.humanSize(size)
                    + "; over the image file limit, so it was not read.");
        }
        return BinaryImages.read(label, Files.readAllBytes(file));
    }
}
