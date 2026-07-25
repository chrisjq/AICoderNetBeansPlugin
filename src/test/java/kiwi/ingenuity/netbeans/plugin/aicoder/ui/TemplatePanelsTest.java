package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatePanelsTest {

    @TempDir
    Path tempDir;

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(action.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return value.get();
    }

    @Test
    void configTemplatesPanelInitializesWithoutExceptions() throws Exception {
        TemplatePersistenceManager tpm = new TemplatePersistenceManager(tempDir);
        AtomicBoolean changed = new AtomicBoolean(false);
        ConfigTemplatesPanel panel = onEdt(() -> new ConfigTemplatesPanel(tpm, () -> changed.set(true)));
        assertNotNull(panel);
    }

    @Test
    void instructionTemplatesPanelInitializesWithoutExceptions() throws Exception {
        TemplatePersistenceManager tpm = new TemplatePersistenceManager(tempDir);
        AtomicBoolean changed = new AtomicBoolean(false);
        InstructionTemplatesPanel panel = onEdt(() -> new InstructionTemplatesPanel(tpm, () -> changed.set(true)));
        assertNotNull(panel);
    }
}
