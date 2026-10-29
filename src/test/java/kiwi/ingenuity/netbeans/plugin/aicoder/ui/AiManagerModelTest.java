package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolsDocumentation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanelMode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        values.setAllowGitAccess(false);
        values.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        values.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);
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
    void createSessionProjectLabelsUseTableNamesUnlessTheyAreAmbiguous() {
        assertEquals("app-platform", SessionPickerDialog.projectDisplayLabel(
                     "/code/app-platform", List.of("/code/app-platform", "/code/utilities")));
        assertEquals("/other/app-platform", SessionPickerDialog.projectDisplayLabel(
                     "/other/app-platform", List.of("/code/app-platform", "/other/app-platform")));
    }

    @Test
    void sessionOpenProjectFlagIsPrecomputedFromSuppliedDirs() throws IOException {
        Path openDir = Files.createTempDirectory("aicoder-open");
        Path closedDir = Files.createTempDirectory("aicoder-closed");
        Instant now = Instant.now();
        AiSession open = session("o", "Open", openDir.toString(), now, now);
        AiSession closed = session("c", "Closed", closedDir.toString(), now, now);
        AiSession noProject = session("n", "NoProject", null, now, now);

        SessionTableModel model = new SessionTableModel();
        setRows(model, List.of(open, closed, noProject));
        assertEquals(3, model.getRowCount());

        model.setOpenProjectDirs(List.of(openDir.toFile()));
        assertTrue(model.isProjectOpen(0), "a session whose project is in the supplied set must read as open");
        assertFalse(model.isProjectOpen(1), "a session whose project is outside the supplied set must read as closed");
        assertTrue(model.isProjectOpen(2), "a session without a project must never read as closed");

        model.setOpenProjectDirs(List.of());
        assertFalse(model.isProjectOpen(0), "the flag must react to the supplied set changing");
        assertTrue(model.isProjectOpen(2), "no-project session stays open for an empty set too");
    }

    @Test
    void sessionProjectOpenFlagMatchesSymlinkedAliases() throws IOException {
        Path real = Files.createTempDirectory("aicoder-real");
        Path aliasParent = Files.createTempDirectory("aicoder-alias-parent");
        Path alias = aliasParent.resolve("alias");
        if (!aliasIsSupported(real, alias)) {
            return;
        }
        Instant now = Instant.now();
        AiSession viaAlias = session("a", "ViaAlias", alias.toAbsolutePath().toString(), now, now);
        AiSession viaReal = session("r", "ViaReal", real.toAbsolutePath().toString(), now, now);

        SessionTableModel model = new SessionTableModel();
        setRows(model, List.of(viaAlias, viaReal));

        model.setOpenProjectDirs(List.of(real.toFile()));
        assertTrue(model.isProjectOpen(0), "a session spelled through the alias must match the same open project");
        assertTrue(model.isProjectOpen(1), "the real spelling must also match");

        model.setOpenProjectDirs(List.of(alias.toFile()));
        assertTrue(model.isProjectOpen(0), "the reverse alias direction must match too");
        assertTrue(model.isProjectOpen(1), "the real spelling must match a project supplied through the alias");
    }

    private static boolean aliasIsSupported(Path real, Path alias) {
        try {
            Files.createSymbolicLink(alias, real);
            return true;
        }
        catch (UnsupportedOperationException | IOException e) {
            return false;
        }
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
            assertEquals(values.allowGitAccess(), target.allowGitAccess(), "allowGitAccess");
            for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
                assertEquals(values.allowGitAccessOption(option),
                             target.allowGitAccessOption(option), option.name());
            }
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
    void templateModeExposesEditableMcpSteering() throws Exception {
        AiSessionConfigPanel panel = onEdt(() -> new AiSessionConfigPanel(AiSessionConfigPanelMode.TEMPLATE));
        JCheckBox steering = onEdt(() -> findMcpSteeringCheckBox(panel));

        assertNotNull(steering);
        assertTrue(steering.isVisible());
        assertTrue(steering.isEnabled());

        onEdt(() -> {
            steering.setSelected(false);
            return null;
        });
        assertFalse(onEdt(() -> panel.snapshot().mcpSteering()));
    }

    /**
     * Reproduces exactly what {@link kiwi.ingenuity.netbeans.plugin.aicoder.ui.ConfigTemplatesPanel} does when editing
     * a template: it calls the single-argument {@code loadSession(AiSessionSettings)} both to populate the editor with
     * an existing template ({@code loadEditor}) and to reset it to a blank template ({@code clearEditor}). Construction
     * alone shows the MCP steering control in TEMPLATE mode, but a prior fix left
     * {@code loadSession(AiSessionSettings)} unconditionally re-hiding it afterwards, so the control vanished the
     * moment a template was selected or the editor cleared.
     */
    @Test
    void templateModeKeepsMcpSteeringVisibleAfterLoadSession() throws Exception {
        AiSessionConfigPanel panel = onEdt(() -> new AiSessionConfigPanel(AiSessionConfigPanelMode.TEMPLATE));

        AiSessionSettings settings = new AiSessionSettings();
        settings.setMcpSteering(true);
        onEdt(() -> {
            panel.loadSession(settings);
            return null;
        });

        JCheckBox steering = onEdt(() -> findMcpSteeringCheckBox(panel));
        assertNotNull(steering);
        assertTrue(steering.isVisible(),
                   "MCP steering checkbox must stay visible in TEMPLATE mode after loadSession(settings)");
        assertTrue(steering.isEnabled(),
                   "MCP steering checkbox must stay enabled in TEMPLATE mode after loadSession(settings)");

        onEdt(() -> {
            steering.setSelected(false);
            return null;
        });
        AiSessionSettings snapshot = onEdt(panel::snapshot);
        assertFalse(snapshot.mcpSteering(), "a value set in template mode must survive snapshot()");
        // clearEditor() calls loadSession(new AiSessionSettings()) — must not re-hide the control either.
        onEdt(() -> {
            panel.loadSession(new AiSessionSettings());
            return null;
        });
        assertTrue(steering.isVisible(),
                   "MCP steering checkbox must stay visible in TEMPLATE mode after loadSession(new AiSessionSettings())");
        assertTrue(steering.isEnabled(),
                   "MCP steering checkbox must stay enabled in TEMPLATE mode after loadSession(new AiSessionSettings())");
    }

    @Test
    void sessionModeShowsTheMcpSteeringSwitchForATypeThatSupportsItAndRoundTripsIt() throws Exception {
        AiSessionConfigPanel panel = onEdt(() -> new AiSessionConfigPanel(AiSessionConfigPanelMode.SESSION));
        JCheckBox steering = onEdt(() -> findMcpSteeringCheckBox(panel));
        JPanel steeringPanel = onEdt(() -> findTitledPanel(panel, "MCP steering"));
        assertNotNull(steering, "the switch is part of the SESSION General group again");
        assertFalse(steeringPanel.isVisible(), "hidden until an AI type that supports steering is loaded");

        ClaudeSessionSettings settings = new ClaudeSessionSettings();
        settings.setMcpSteering(true);
        assertTrue(AiTypeEnum.CLAUDE.mcpSteeringSupport().supported(), "premise: Claude supports steering");
        onEdt(() -> {
            panel.loadSession(settings, AiTypeEnum.CLAUDE);
            return null;
        });
        assertTrue(steeringPanel.isVisible());
        assertTrue(steeringPanel.isEnabled());
        assertTrue(steering.isSelected(), "the session's stored value is shown");

        onEdt(() -> {
            steering.setSelected(false);
            return null;
        });
        AiSessionSettings target = new AiSessionSettings();
        onEdt(() -> {
            panel.applySession(target);
            return null;
        });
        assertFalse(target.mcpSteering(), "a change made in the session dialog is saved");
    }

    @Test
    void sessionModeHidesTheMcpSteeringSwitchWhenNoAiTypeIsKnown() throws Exception {
        AiSessionConfigPanel panel = onEdt(() -> new AiSessionConfigPanel(AiSessionConfigPanelMode.SESSION));
        JPanel steeringPanel = onEdt(() -> findTitledPanel(panel, "MCP steering"));

        onEdt(() -> {
            panel.loadSession(new AiSessionSettings());
            return null;
        });

        assertFalse(steeringPanel.isVisible());
        assertFalse(steeringPanel.isEnabled());
    }

    @Test
    void globalModeHoldsNoSessionDefaultsAndNoDuplicateOfTheOptionsBannerNote() throws Exception {
        AiSessionConfigPanel global = onEdt(() -> new AiSessionConfigPanel(AiSessionConfigPanelMode.GLOBAL));

        assertTrue(onEdt(() -> labelTexts(global)).stream().noneMatch(text -> text.contains("Default config template")),
                   "the 'defaults moved' note is shown once, by the Options panel banner");
        assertFalse(onEdt(() -> containsCheckBoxText(global, "Automatically accept file changes")),
                    "auto-accept is a session default now and must not be editable in GLOBAL mode");
        assertFalse(onEdt(() -> containsCheckBoxText(global,
                                                     AccessControlLabelEnum.RESTRICT_TO_PROJECT_FILES.displayLabel())),
                    "restrict-to-project is a session default now and must not be editable in GLOBAL mode");
        assertNull(onEdt(() -> findMcpSteeringCheckBox(global)),
                   "MCP steering is a session default now and must not be editable in GLOBAL mode");

        for (AiSessionConfigPanelMode mode : List.of(AiSessionConfigPanelMode.SESSION,
                                                     AiSessionConfigPanelMode.TEMPLATE)) {
            AiSessionConfigPanel panel = onEdt(() -> new AiSessionConfigPanel(mode));
            assertTrue(onEdt(() -> containsCheckBoxText(panel, "Automatically accept file changes")),
                       mode + " must keep exposing the generic session settings GLOBAL mode dropped");
        }
    }

    @Test
    void applyGlobalStopsWritingTheSessionDefaultsThatMovedToTheDefaultTemplate() throws Exception {
        int previous = PluginSettings.getMaxHistory();
        AiSessionConfigPanel global = onEdt(() -> new AiSessionConfigPanel(AiSessionConfigPanelMode.GLOBAL));
        try {
            onEdt(() -> {
                global.loadGlobal();
                return null;
            });
            PluginSettings.setMaxHistory(previous + 1);
            onEdt(() -> {
                global.applyGlobal();
                return null;
            });
            assertEquals(previous + 1, PluginSettings.getMaxHistory(),
                         "applyGlobal must leave the stored session defaults alone; they belong to the Default config template now");
        }
        finally {
            PluginSettings.setMaxHistory(previous);
        }
    }

    @Test
    void mcpToolsPanelIsBuiltOnlyForSessionMode() throws Exception {
        for (AiSessionConfigPanelMode mode : List.of(AiSessionConfigPanelMode.GLOBAL,
                                                     AiSessionConfigPanelMode.TEMPLATE)) {
            AiSessionConfigPanel panel = onEdt(() -> new AiSessionConfigPanel(mode));
            assertNull(onEdt(() -> findTitledPanel(panel, "MCP Tools")),
                       mode + " must not build the MCP tools panel");
        }

        AiSessionConfigPanel session = onEdt(() -> new AiSessionConfigPanel(AiSessionConfigPanelMode.SESSION));
        assertNotNull(onEdt(() -> findTitledPanel(session, "MCP Tools")),
                      "SESSION mode owns the MCP tools panel");
    }

    private static List<String> labelTexts(Component root) {
        List<String> texts = new ArrayList<>();
        if (root instanceof AiSessionConfigPanel panel && panel.mode() == AiSessionConfigPanelMode.SESSION) {
            collectLabelTexts(panel.generalSection(), texts);
            collectLabelTexts(panel.typeSection(), texts);
            collectLabelTexts(panel.mcpSection(), texts);
        }
        else {
            collectLabelTexts(root, texts);
        }
        return texts;
    }

    private static void collectLabelTexts(Component component, List<String> texts) {
        if (component == null) {
            return;
        }
        if (component instanceof JLabel label && label.getText() != null) {
            texts.add(label.getText());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectLabelTexts(child, texts);
            }
        }
    }

    private static boolean containsCheckBoxText(Component root, String wanted) {
        if (root instanceof AiSessionConfigPanel panel && panel.mode() == AiSessionConfigPanelMode.SESSION) {
            return containsCheckBoxText(panel.generalSection(), wanted)
                    || containsCheckBoxText(panel.typeSection(), wanted)
                    || containsCheckBoxText(panel.mcpSection(), wanted);
        }
        if (root instanceof JCheckBox box && wanted.equals(box.getText())) {
            return true;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsCheckBoxText(child, wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JPanel findTitledPanel(Component root, String title) {
        if (root instanceof AiSessionConfigPanel panel && panel.mode() == AiSessionConfigPanelMode.SESSION) {
            JPanel result = findTitledPanel(panel.generalSection(), title);
            if (result == null) {
                result = findTitledPanel(panel.typeSection(), title);
            }
            if (result == null) {
                result = findTitledPanel(panel.mcpSection(), title);
            }
            return result;
        }
        if (root == null) {
            return null;
        }
        if (root instanceof JPanel panel
                && panel.getBorder() instanceof TitledBorder border
                && title.equals(border.getTitle())) {
            return panel;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JPanel result = findTitledPanel(child, title);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static JCheckBox findMcpSteeringCheckBox(Component root) {
        if (root instanceof AiSessionConfigPanel panel && panel.mode() == AiSessionConfigPanelMode.SESSION) {
            JCheckBox result = findMcpSteeringCheckBox(panel.generalSection());
            if (result == null) {
                result = findMcpSteeringCheckBox(panel.typeSection());
            }
            if (result == null) {
                result = findMcpSteeringCheckBox(panel.mcpSection());
            }
            return result;
        }
        if (root == null) {
            return null;
        }
        if (root instanceof JPanel panel
                && panel.getBorder() instanceof TitledBorder border
                && "MCP steering".equals(border.getTitle())) {
            return findCheckBox(panel);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JCheckBox result = findMcpSteeringCheckBox(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static JCheckBox findCheckBox(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JCheckBox checkBox) {
                return checkBox;
            }
            if (child instanceof Container nested) {
                JCheckBox result = findCheckBox(nested);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    @Test
    void mcpToolsHelpIsGeneratedFromTheToolRegistryWithoutCredentials() {
        String html = McpToolsDocumentation.buildHtml();

        assertTrue(html.contains("BuildMavenProject"));
        assertTrue(html.contains("PeerMessageSend"));
        assertFalse(html.contains("sessionId"));
        assertFalse(html.contains("secretKey"));
    }

}
