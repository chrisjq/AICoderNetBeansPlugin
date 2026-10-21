package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolCategoryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.McpTemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatePanelsTest {

    @TempDir
    Path tempDir;

    private static <T> T onEdt(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(action.call());
            }
            catch (Exception e) {
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
        awaitLoaded(panel);
    }

    @Test
    void configTemplatesPanelPinsTheDefaultTemplateAndSeedsNewTemplatesFromIt() throws Exception {
        TemplatePersistenceManager tpm = new TemplatePersistenceManager(tempDir);
        ConfigTemplatesPanel panel = onEdt(() -> new ConfigTemplatesPanel(tpm, () -> {
                                                                  }));
        awaitLoaded(panel);
        ConfigTemplate defaultTemplate = tpm.getDefault();

        JTable table = onEdt(() -> findComponent(panel, JTable.class));
        assertNotNull(table, "the config template list must be present");
        assertEquals(defaultTemplate.name(), table.getModel().getValueAt(0, 0),
                     "the built-in Default config template must be pinned to the top of the list");

        AiSessionSettings seeded = onEdt(() -> findComponent(panel, AiSessionConfigPanel.class).snapshot());
        int expected = defaultTemplate.settings().effectiveMaxHistory();
        int actual = seeded.maxHistory();
        assertEquals(expected, actual,
                     "an empty editor must be seeded from the Default config template rather than the globals");

        onEdt(() -> {
            table.setRowSelectionInterval(0, 0);
            return null;
        });
        JButton delete = onEdt(() -> findButton(panel, "Delete"));
        assertNotNull(delete, "the Delete action must exist");
        assertFalse(delete.isVisible(), "the built-in Default config template must not be deletable");
    }

    @Test
    void instructionTemplatesPanelInitializesWithoutExceptions() throws Exception {
        TemplatePersistenceManager tpm = new TemplatePersistenceManager(tempDir);
        AtomicBoolean changed = new AtomicBoolean(false);
        InstructionTemplatesPanel panel = onEdt(() -> new InstructionTemplatesPanel(tpm, () -> changed.set(true)));
        assertNotNull(panel);
        awaitLoaded(panel);
    }

    @Test
    void theBackgroundLoadFillsTheListOnceItCompletes() throws Exception {
        TemplatePersistenceManager tpm = new TemplatePersistenceManager(tempDir);
        ConfigTemplatesPanel panel = onEdt(() -> new ConfigTemplatesPanel(tpm, () -> {
                                                                  }));
        awaitLoaded(panel);

        JTable table = onEdt(() -> findComponent(panel, JTable.class));
        assertTrue(onEdt(() -> table.getRowCount() > 0), "the load fills the list once it completes");
    }

    @Test
    void aReloadAfterStoreShutdownReportsFailureWithoutThrowingOnTheEdt() throws Exception {
        List<String> reported = new CopyOnWriteArrayList<>();
        TemplatePanel.resetStoreExecutorForTests();
        TemplatePanel.failureSink = (title, error) -> reported.add(title);
        try {
            TemplatePanel.shutdownStoreExecutor();
            ConfigTemplatesPanel panel = onEdt(() -> new ConfigTemplatesPanel(
                    new TemplatePersistenceManager(tempDir), () -> {
                    }));

            awaitLoaded(panel);

            assertEquals(List.of("Could not load templates"), reported);
            assertNotNull(onEdt(() -> findComponent(panel, JTable.class)), "the panel remains usable after shutdown");
        }
        finally {
            TemplatePanel.failureSink = null;
            TemplatePanel.resetStoreExecutorForTests();
        }
    }

    @Test
    void aLoadThatFailsIsReportedToTheUserNotJustLogged() throws Exception {
        // A regular file where the store directory should be makes the store's lock step fail with an IOException on the
        // store thread. An unreadable store file is not a failure: it is served as an in-memory Default.
        Path notADirectory = tempDir.resolve("not-a-directory");
        Files.writeString(notADirectory, "occupied");
        TemplatePersistenceManager tpm = new TemplatePersistenceManager(notADirectory);
        List<String> reported = new CopyOnWriteArrayList<>();
        TemplatePanel.failureSink = (title, error) -> reported.add(title);
        try {
            ConfigTemplatesPanel panel = onEdt(() -> new ConfigTemplatesPanel(tpm, () -> {
                                                                      }));
            awaitLoaded(panel);

            assertEquals(List.of("Could not load templates"), reported);
            assertNotNull(onEdt(() -> findComponent(panel, JTable.class)), "the panel is still usable");
        }
        finally {
            TemplatePanel.failureSink = null;
        }
    }

    @Test
    void mcpTemplatesEditorShowsNoAlwaysOnGroupOrLockedLabel() throws Exception {
        McpTemplatePersistenceManager mcp = new McpTemplatePersistenceManager(tempDir);
        McpTemplatesPanel panel = onEdt(() -> new McpTemplatesPanel(mcp, () -> {
                                                            }));
        awaitLoaded(panel);

        assertFalse(onEdt(() -> containsText(panel, McpToolGroupEnum.ALWAYS_ON.label())),
                    "the always-on group must not be shown");
        assertFalse(onEdt(() -> containsText(panel, McpToolCategoryEnum.ALWAYS_ON.label())),
                    "the always-on category must not be shown");
        assertFalse(onEdt(() -> containsText(panel, McpToolGroupEnum.ALWAYS_ON.label() + " (locked on)")),
                    "the always-on group must not be shown as locked on");
    }

    private static boolean containsText(Component root, String text) {
        if (root instanceof AbstractButton button && text.equals(button.getText())) {
            return true;
        }
        if (root instanceof JLabel label && text.equals(label.getText())) {
            return true;
        }
        if (root instanceof JComponent component && component.getBorder() instanceof TitledBorder border
                && text.equals(border.getTitle())) {
            return true;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsText(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void awaitLoaded(TemplatePanel<?> panel) throws Exception {
        panel.whenLoaded().get(10, TimeUnit.SECONDS);
    }

    private static <T extends Component> T findComponent(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T result = findComponent(child, type);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Component root, String text) {
        if (root instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton result = findButton(child, text);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}
