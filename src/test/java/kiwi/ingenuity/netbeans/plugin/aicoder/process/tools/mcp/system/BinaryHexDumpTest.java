package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryHexDumpTest {

    private static byte[] alphabet(int count) {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            bytes[i] = (byte) ('A' + (i % 26));
        }
        return bytes;
    }

    @Test
    void linesHoldSixteenBytesWithAnAsciiColumn() {
        String dump = BinaryHexDump.format("data.bin", "ABCDEFGHIJKLMNOPQR".getBytes(StandardCharsets.US_ASCII), 0, 0);

        String[] lines = dump.split("\n");
        assertTrue(lines[0].startsWith("data.bin: 18 bytes; showing bytes 0 to 17 (18 bytes)."), lines[0]);
        assertTrue(lines[1].startsWith("00000000  41 42 43 44 45 46 47 48  49 4a 4b 4c 4d 4e 4f 50 "), lines[1]);
        assertTrue(lines[1].endsWith("|ABCDEFGHIJKLMNOP|"), lines[1]);
        assertTrue(lines[2].startsWith("00000010  51 52 "), lines[2]);
        assertTrue(lines[2].endsWith("|QR|"), lines[2]);
    }

    @Test
    void nonPrintableBytesShowAsDots() {
        String dump = BinaryHexDump.format("bin", new byte[]{0, 1, 'a', (byte) 0xff}, 0, 0);

        assertTrue(dump.contains("|..a.|"), dump);
    }

    @Test
    void headerSaysHowToPageOnWhenMoreFollows() {
        String dump = BinaryHexDump.format("big.bin", alphabet(40), 0, 16);

        assertTrue(dump.contains("showing bytes 0 to 15 (16 bytes)."), dump);
        assertTrue(dump.contains("To read more, call again with offset=16."), dump);
        assertTrue(dump.contains("00000000  "), dump);
        assertFalse(dump.contains("00000010  "), "a 16-byte window is one line; the second line starts past it");
    }

    @Test
    void lastPageHasNoPagingHint() {
        String dump = BinaryHexDump.format("small.bin", alphabet(40), 32, 16);

        assertTrue(dump.contains("showing bytes 32 to 39 (8 bytes)."), dump);
        assertFalse(dump.contains("To read more"), dump);
    }

    @Test
    void lengthIsCappedAtTheMaximum() {
        byte[] big = new byte[BinaryHexDump.MAX_LENGTH + 100];

        String dump = BinaryHexDump.format("huge.bin", big, 0, Integer.MAX_VALUE);

        assertTrue(dump.contains("showing bytes 0 to " + (BinaryHexDump.MAX_LENGTH - 1) + " ("
                + BinaryHexDump.MAX_LENGTH + " bytes)."), dump);
        assertTrue(dump.contains("offset=" + BinaryHexDump.MAX_LENGTH + "."), dump);
    }

    @Test
    void zeroLengthUsesTheDefault() {
        byte[] big = new byte[BinaryHexDump.DEFAULT_LENGTH + 10];

        String dump = BinaryHexDump.format("default.bin", big, 0, 0);

        assertTrue(dump.contains("(" + BinaryHexDump.DEFAULT_LENGTH + " bytes)."), dump);
    }

    @Test
    void offsetPastTheEndIsRefused() {
        String dump = BinaryHexDump.format("short.bin", alphabet(4), 10, 16);

        assertTrue(dump.contains("offset 10 is past the end"), dump);
    }

    @Test
    void negativeOffsetIsRefused() {
        String dump = BinaryHexDump.format("short.bin", alphabet(4), -1, 16);

        assertTrue(dump.contains("offset must be 0 or more"), dump);
    }

    @Test
    void emptyDataSaysSoInsteadOfAHeader() {
        assertEquals("nothing.bin is empty (0 bytes).", BinaryHexDump.format("nothing.bin", new byte[0], 0, 0));
    }

    @Test
    void emptyFileSaysSoInsteadOfAHeader(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        assertEquals("empty.bin is empty (0 bytes).", BinaryHexDump.formatFile("empty.bin", file, 0, 0));
    }

    @Test
    void fileDumpReadsOnlyTheRequestedWindow(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("window.bin");
        Files.write(file, alphabet(64));

        String dump = BinaryHexDump.formatFile("window.bin", file, 48, 16);

        assertTrue(dump.contains("64 bytes; showing bytes 48 to 63 (16 bytes)."), dump);
        assertTrue(dump.contains("00000030  "), dump);
        assertFalse(dump.contains("00000000  "), dump);
    }
}
