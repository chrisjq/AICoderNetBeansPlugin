package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Splits a byte stream into UTF-8 lines with a hard per-line limit. Not thread-safe; one per response.
 */
public final class SseLineSplitter {

    private final int maxLineBytes;
    private final Consumer<String> onLine;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean overflowed;

    public SseLineSplitter(int maxLineBytes, Consumer<String> onLine) {
        this.maxLineBytes = maxLineBytes;
        this.onLine = onLine;
    }

    /**
     * @return false when a line exceeded maxLineBytes; the caller ends the response with SIZE_LIMIT
     */
    public boolean accept(ByteBuffer chunk) {
        if (overflowed) {
            return false;
        }
        while (chunk.hasRemaining()) {
            byte b = chunk.get();
            if (b == '\n') {
                emit();
            }
            else {
                line.write(b);
                if (line.size() > maxLineBytes) {
                    overflowed = true;
                    line.reset();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Flushes a final unterminated line.
     */
    public void finish() {
        if (!overflowed && line.size() > 0) {
            emit();
        }
    }

    private void emit() {
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        line.reset();
        onLine.accept(new String(bytes, 0, length, StandardCharsets.UTF_8));
    }
}
