package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.PromptHistory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TempFile;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TempFileRegistry;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fix A regression guards: pasted-image encode+write used to run entirely on the EDT (the paste Action's calling
 * thread) — a 4K screenshot is tens of MB of ARGB pixels, so the {@link BufferedImage} allocation, PNG encode and disk
 * write together could freeze the UI for hundreds of ms to seconds. These tests drive
 * {@link AiInputField#pasteImageAsync} directly (package-private, bypassing the real system clipboard — see its
 * javadoc) and substitute the temp-file and write steps via the package-private extension points {@link AiInputField#createPasteTempFile},
 * {@link AiInputField#writePastedImage} and {@link AiInputField#cleanupFailedPasteTempFile}, since {@link TempFile}'s
 * constructor is package-private to {@code process.tools} and {@link TempFileRegistry}'s real resolution needs a live
 * MCP server that a plain unit test does not have.
 */
class AiInputFieldImagePasteTest {

    private static <T> T onEdt(Callable<T> fn) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(fn.call());
            }
            catch (Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) {
            throw err.get();
        }
        return result.get();
    }

    private static AiSession fakeSession() {
        return new AiSession("paste-test-session", "T", null, AiTypeEnum.CLAUDE, null, null,
                Instant.now(), Instant.now());
    }

    private static BufferedImage tinyImage() {
        return new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * {@link TempFile}'s constructor is package-private to {@code process.tools}; a test in {@code ai.ui} fabricates an
     * instance via reflection rather than routing through a live {@link TempFileRegistry}.
     */
    private static TempFile fakeTempFile(Path path) throws ReflectiveOperationException {
        Constructor<TempFile> ctor = TempFile.class.getDeclaredConstructor(Path.class, String.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(path, "paste-test-session", System.currentTimeMillis());
    }

    /**
     * Core Fix A claim: the encode+write step must run off the thread that called pasteImageAsync (in production, the
     * EDT). Would FAIL before the fix, where tryPasteImage() ran createTempFile/ImageIO.write synchronously on the
     * calling thread — the captured worker thread would equal the calling thread.
     */
    @Test
    void pasteImageAsync_runsTempFileCreationOffTheCallingThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Boolean> workerWasOnEdt = new AtomicReference<>();

        AiInputField field = onEdt(() -> new AiInputField(new PromptHistory(), fakeSession()) {
            @Override
            TempFile createPasteTempFile(String sessionId) {
                workerWasOnEdt.set(SwingUtilities.isEventDispatchThread());
                started.countDown();
                return null; // short-circuit: this test only cares which thread got here
            }
        });

        long t0 = System.nanoTime();
        // pasteImageAsync is itself invoked from the EDT here, mirroring the real paste
        // Action — the point of the test is that createPasteTempFile above must NOT also
        // see isEventDispatchThread() == true.
        onEdt(() -> {
            field.pasteImageAsync(tinyImage());
            return null;
        });
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 500, "pasteImageAsync blocked the caller for " + elapsedMs + " ms");
        assertTrue(started.await(2, TimeUnit.SECONDS), "background encode/write never started");
        assertEquals(Boolean.FALSE, workerWasOnEdt.get(),
                "temp-file creation (and the encode/write around it) must not run on the EDT");
    }

    /**
     * On success, insertAtCursor must run on the EDT (Swing text mutation is not thread-safe) even though the write
     * itself happened on a background thread.
     */
    @Test
    void pasteImageAsync_onSuccess_insertsMarkerOnTheEdt(@TempDir Path tempDir) throws Exception {
        Path pngPath = tempDir.resolve("fake-paste.png");
        TempFile tmp = fakeTempFile(pngPath);
        CountDownLatch inserted = new CountDownLatch(1);
        AtomicReference<Boolean> insertedOnEdt = new AtomicReference<>();

        AiInputField field = onEdt(() -> new AiInputField(new PromptHistory(), fakeSession()) {
            @Override
            TempFile createPasteTempFile(String sessionId) {
                return tmp;
            }

            @Override
            void writePastedImage(BufferedImage image, File target) throws IOException {
                Files.write(target.toPath(), new byte[]{1, 2, 3});
            }

            @Override
            void insertAtCursor(String text) {
                insertedOnEdt.set(SwingUtilities.isEventDispatchThread());
                super.insertAtCursor(text);
                inserted.countDown();
            }
        });

        onEdt(() -> {
            field.pasteImageAsync(tinyImage());
            return null;
        });

        assertTrue(inserted.await(2, TimeUnit.SECONDS), "marker was never inserted");
        assertEquals(Boolean.TRUE, insertedOnEdt.get(), "insertAtCursor must run on the EDT");
        String text = onEdt(field::getText);
        assertTrue(text.contains("@tmp." + pngPath.getFileName()),
                "expected the @tmp.<filename> marker in the field text, got: " + text);
    }

    /**
     * "The silent no-op when createTempFile returns null deserves user feedback rather than nothing happening."
     */
    @Test
    void pasteImageAsync_whenTempFileCreationFails_reportsFailureAndInsertsNothing() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();

        AiInputField field = onEdt(() -> new AiInputField(new PromptHistory(), fakeSession()) {
            @Override
            TempFile createPasteTempFile(String sessionId) {
                return null;
            }
        });
        field.setPasteErrorCallback(msg -> {
            message.set(msg);
            reported.countDown();
        });

        onEdt(() -> {
            field.pasteImageAsync(tinyImage());
            return null;
        });

        assertTrue(reported.await(2, TimeUnit.SECONDS), "no failure was reported to the user");
        assertNotNull(message.get());
        assertFalse(message.get().isBlank());
        String text = onEdt(field::getText);
        assertFalse(text.contains("@tmp."), "nothing should be inserted when no temp file could be created");
    }

    /**
     * "Also fix the orphan I found alongside it: if createTempFile succeeds and the write then throws, the empty .png
     * stays registered until the age sweep. Delete it on write failure."
     */
    @Test
    void pasteImageAsync_whenWriteFails_cleansUpTheOrphanedTempFileAndReportsFailure(@TempDir Path tempDir) throws Exception {
        TempFile tmp = fakeTempFile(tempDir.resolve("orphan.png"));
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<TempFile> cleanedUp = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();

        AiInputField field = onEdt(() -> new AiInputField(new PromptHistory(), fakeSession()) {
            @Override
            TempFile createPasteTempFile(String sessionId) {
                return tmp;
            }

            @Override
            void writePastedImage(BufferedImage image, File target) throws IOException {
                throw new IOException("simulated disk failure");
            }

            @Override
            void cleanupFailedPasteTempFile(TempFile tempFile) {
                cleanedUp.set(tempFile);
            }
        });
        field.setPasteErrorCallback(msg -> {
            message.set(msg);
            reported.countDown();
        });

        onEdt(() -> {
            field.pasteImageAsync(tinyImage());
            return null;
        });

        assertTrue(reported.await(2, TimeUnit.SECONDS), "no failure was reported to the user");
        assertSame(tmp, cleanedUp.get(), "the orphaned temp file must be cleaned up after a write failure");
        assertNotNull(message.get());
        String text = onEdt(field::getText);
        assertFalse(text.contains("@tmp."), "nothing should be inserted when the write failed");
    }
}
