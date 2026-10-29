package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.McpTemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Create waits for the key state of the AI type's settings panel before it creates anything. These tests drive the real
 * Create path with a settings panel whose key state the test controls, and count the creations that reach the test
 * hook.
 */
@Timeout(20)
class SessionPickerDialogTest {

    @TempDir
    Path tempDir;

    @Test
    void createProceedsOnceWhenKeyStateIsAlreadyReady() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SessionPickerDialog dialog = newDialog(CompletableFuture.completedFuture(null));
        try {
            AtomicInteger runs = new AtomicInteger();
            dialog.createHookForTest = runs::incrementAndGet;

            onEdt(() -> {
                onCreateNew(dialog);
                return null;
            });

            assertEquals(1, runs.get());
        }
        finally {
            dispose(dialog);
        }
    }

    @Test
    void createWaitsForAPendingKeyStateThenProceedsOnce() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CompletableFuture<Void> ready = new CompletableFuture<>();
        SessionPickerDialog dialog = newDialog(ready);
        try {
            AtomicInteger runs = new AtomicInteger();
            dialog.createHookForTest = runs::incrementAndGet;

            onEdt(() -> {
                onCreateNew(dialog);
                return null;
            });

            assertEquals(0, runs.get());
            assertFalse(onEdt(() -> {
                JButton create = field(dialog, "createButton");
                return create.isEnabled();
            }));

            ready.complete(null);
            SwingUtilities.invokeAndWait(() -> {
            });
            SwingUtilities.invokeAndWait(() -> {
            });

            assertEquals(1, runs.get());
        }
        finally {
            dispose(dialog);
        }
    }

    @Test
    void awaitKeyStateProceedsAtOnceWhenItIsAlreadyDone() {
        AtomicInteger proceeds = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        SessionPickerDialog.awaitKeyStateThen(CompletableFuture.completedFuture(null), Runnable::run,
                                              proceeds::incrementAndGet, error -> failures.incrementAndGet());

        assertEquals(1, proceeds.get());
        assertEquals(0, failures.get());
    }

    @Test
    void awaitKeyStateProceedsOnceWhenThePendingStateCompletes() {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        AtomicInteger proceeds = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        CompletableFuture<Void> finished = SessionPickerDialog.awaitKeyStateThen(ready, Runnable::run,
                                                                                 proceeds::incrementAndGet,
                                                                                 error -> failures.incrementAndGet());
        assertEquals(0, proceeds.get());
        assertFalse(finished.isDone());

        ready.complete(null);

        assertEquals(1, proceeds.get());
        assertEquals(0, failures.get());
        assertTrue(finished.isDone());
    }

    @Test
    void awaitKeyStateFailsOnceWhenThePendingStateFails() {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        AtomicInteger proceeds = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        SessionPickerDialog.awaitKeyStateThen(ready, Runnable::run, proceeds::incrementAndGet,
                                              error -> failures.incrementAndGet());
        ready.completeExceptionally(new IllegalStateException("keyring locked"));

        assertEquals(0, proceeds.get());
        assertEquals(1, failures.get());
    }

    /**
     * Builds the picker with a named session, an open project, an AI type and a settings panel whose
     * {@link AiSessionCreateSettingsPanel#keyStateReady()} is {@code ready}.
     */
    private SessionPickerDialog newDialog(CompletableFuture<Void> ready) throws Exception {
        SessionPickerDialog dialog = onEdt(() -> {
            Constructor<SessionPickerDialog> constructor = SessionPickerDialog.class.getDeclaredConstructor(
                    SessionPersistenceManager.class, TemplatePersistenceManager.class, McpTemplatePersistenceManager.class);
            constructor.setAccessible(true);
            return constructor.newInstance(new SessionPersistenceManager(tempDir),
                                           new TemplatePersistenceManager(tempDir.resolve("templates")),
                                           new McpTemplatePersistenceManager(tempDir.resolve("mcp-templates")));
        });
        awaitTemplatesLoaded(dialog);
        onEdt(() -> {
            JTextField name = field(dialog, "nameField");
            name.setText("picked");
            @SuppressWarnings("unchecked")
            JComboBox<String> projects = field(dialog, "projectCombo");
            if (projects.getSelectedItem() == null) {
                projects.addItem(tempDir.toString());
                projects.setSelectedItem(tempDir.toString());
            }
            @SuppressWarnings("unchecked")
            JComboBox<AiTypeEnum> types = field(dialog, "aiTypeCombo");
            if (types.getSelectedItem() == null) {
                types.addItem(AiTypeEnum.OPENAI_COMPATIBLE);
                types.setSelectedItem(AiTypeEnum.OPENAI_COMPATIBLE);
            }
            AiSessionCreateSettingsPanel<?> previous = field(dialog, "typeSettingsPanel");
            if (previous != null) {
                previous.dispose();
            }
            setField(dialog, "typeSettingsPanel", new AiSessionCreateSettingsPanel<AiSessionSettings>() {
                 @Override
                 public JComponent component() {
                     return new JPanel();
                 }

                 @Override
                 public void load(AiSessionSettings settings) {
                 }

                 @Override
                 public void applyTo(AiSessionSettings settings) {
                 }

                 @Override
                 public CompletableFuture<Void> keyStateReady() {
                     return ready;
                 }

                 @Override
                 public void dispose() {
                 }
             });
            return null;
        });
        return dialog;
    }

    private void awaitTemplatesLoaded(SessionPickerDialog dialog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (onEdt(() -> {
            Boolean loading = field(dialog, "templatesLoading");
            return loading;
        })) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the templates did not finish loading");
            }
            Thread.sleep(10);
        }
    }

    private static void onCreateNew(SessionPickerDialog dialog) throws ReflectiveOperationException {
        Method method = SessionPickerDialog.class.getDeclaredMethod("onCreateNew");
        method.setAccessible(true);
        method.invoke(dialog);
    }

    private static void dispose(SessionPickerDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(dialog::dispose);
    }

    private static <T> T onEdt(Callable<T> call) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.complete(call.call());
            }
            catch (Exception ex) {
                result.completeExceptionally(ex);
            }
        });
        try {
            return result.get();
        }
        catch (ExecutionException ex) {
            throw ex.getCause() instanceof Exception cause ? cause : ex;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
