package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolsDocumentation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanelMode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiManagerModelTest {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static AiSession session(String id, String name, String projectPath, Instant created, Instant used) {
        return new AiSession(id, name, null, AiTypeEnum.CLAUDE, projectPath,
                new AiSessionSettings(), created, used);
    }

    private static AiSessionSettings configuredSettings() {
        AiSessionSettings values = new AiSessionSettings();
        values.setMaxHistory(42);
        values.setSaveHistory(true);
        values.setRestrictToProjectFiles(true);
        values.setAllowWebRequests(true);
        values.setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS, true);
        values.setAllowDatabaseAccess(true);
        values.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.EXECUTE_SQL, true);
        values.setEnableClipboardAccess(true);
        return values;
    }

    private static TableModel newSessionTableModel() {
        return new SessionTableModel();
    }

    private static TableModel newTemplateTableModel() {
        return new SimpleTableModel<ConfigTemplate>(new String[]{"Name", "Updated", "Created"},
                ConfigTemplate::name,
                ConfigTemplate::updatedAt,
                ConfigTemplate::createdAt);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setRows(TableModel model, List<?> rows) {
        if (model instanceof SessionTableModel sessionModel) {
            sessionModel.setRows((List<AiSession>) rows);
        }
        else if (model instanceof SimpleTableModel simpleModel) {
            simpleModel.setRows(rows);
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
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
    void sessionTableModelRendersColumnsProjectLeafTimestampsAndSorts() throws Exception {
        TableModel model = newSessionTableModel();
        Instant created = Instant.parse("2026-01-02T03:04:05Z");
        Instant used = Instant.parse("2026-02-03T04:05:06Z");
        AiSession zulu = session("zulu", "Zulu", "/projects/zulu", created, used);
        AiSession alpha = session("alpha", "Alpha", null, created.plusSeconds(60), used.plusSeconds(60));
        setRows(model, List.of(zulu, alpha));

        assertEquals(2, model.getRowCount());
        assertEquals(5, model.getColumnCount());
        assertEquals(List.of("Name", "Type", "Project", "Last Use", "Created"),
                java.util.stream.IntStream.range(0, model.getColumnCount())
                        .mapToObj(model::getColumnName).toList());
        assertEquals("Zulu", model.getValueAt(0, 0));
        assertEquals(AiTypeEnum.CLAUDE.displayName(), model.getValueAt(0, 1));
        assertEquals("zulu", model.getValueAt(0, 2));
        assertEquals(DATE_FORMAT.format(used), model.getValueAt(0, 3));
        assertEquals(DATE_FORMAT.format(created), model.getValueAt(0, 4));
        assertEquals("—", model.getValueAt(1, 2));

        TableRowSorter<TableModel> sorter = new TableRowSorter<>(model);
        sorter.toggleSortOrder(0);
        assertEquals(1, sorter.convertRowIndexToModel(0));
        assertEquals(0, sorter.convertRowIndexToModel(1));
    }

    @Test
    void templateTableModelRendersTemplateColumnsAndSorts() throws Exception {
        TableModel model = newTemplateTableModel();
        AiSessionSettings settings = new AiSessionSettings();
        Instant created = Instant.parse("2026-03-04T05:06:07Z");
        ConfigTemplate zulu = new ConfigTemplate("z", "Zulu", settings, created, created.plusSeconds(30));
        ConfigTemplate alpha = new ConfigTemplate("a", "Alpha", settings, created.plusSeconds(60), created.plusSeconds(90));
        setRows(model, List.of(zulu, alpha));

        assertEquals(List.of("Name", "Updated", "Created"),
                java.util.stream.IntStream.range(0, model.getColumnCount())
                        .mapToObj(model::getColumnName).toList());
        assertEquals("Zulu", model.getValueAt(0, 0));
        assertEquals(DATE_FORMAT.format(zulu.updatedAt()), model.getValueAt(0, 1));
        assertEquals(DATE_FORMAT.format(zulu.createdAt()), model.getValueAt(0, 2));

        TableRowSorter<TableModel> sorter = new TableRowSorter<>(model);
        sorter.toggleSortOrder(0);
        assertEquals(1, sorter.convertRowIndexToModel(0));
    }

    @Test
    void sessionAndTemplateModesBindGenericSettingsButGlobalModeRejectsThem() throws Exception {
        AiSessionSettings values = configuredSettings();
        for (AiSessionConfigPanelMode mode : List.of(AiSessionConfigPanelMode.SESSION,
                AiSessionConfigPanelMode.TEMPLATE)) {
            AiSessionConfigPanel panel = onEdt(() -> new AiSessionConfigPanel(mode));
            onEdt(() -> {
                panel.loadSession(values);
                return null;
            });
            AiSessionSettings target = new AiSessionSettings();
            onEdt(() -> {
                panel.applySession(target);
                return null;
            });
            assertEquals(values.effectiveMaxHistory(), target.maxHistory());
            assertEquals(values.effectiveSaveHistory(), target.saveHistory());
            assertEquals(values.effectiveRestrictToProjectFiles(), target.restrictToProjectFiles());
            assertEquals(values.effectiveAllowWebRequests(), target.allowWebRequests());
            assertEquals(values.effectiveAllowDatabaseAccess(), target.allowDatabaseAccess());
            assertEquals(values.effectiveEnableClipboardAccess(), target.enableClipboardAccess());
        }

        AiSessionConfigPanel global = onEdt(() -> new AiSessionConfigPanel(AiSessionConfigPanelMode.GLOBAL));
        onEdt(() -> {
            assertThrows(IllegalStateException.class, () -> global.loadSession(values));
            assertThrows(IllegalStateException.class, () -> global.applySession(new AiSessionSettings()));
            return null;
        });
    }

    @Test
    void mcpToolsHelpIsGeneratedFromTheToolRegistryWithoutCredentials() {
        String html = McpToolsDocumentation.buildHtml();

        assertTrue(html.contains("BuildMavenProject"));
        assertTrue(html.contains("SendAiMessage"));
        assertFalse(html.contains("sessionId"));
        assertFalse(html.contains("secretKey"));
    }
}
