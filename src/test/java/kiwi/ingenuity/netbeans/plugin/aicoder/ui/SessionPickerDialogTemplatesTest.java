package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.LegacyToolGroups;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.McpTemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionPickerDialogTemplatesTest {

    @TempDir
    Path temp;

    @Test
    void configTemplateOverridesGenericGlobals() {
        int previousMaxHistory = PluginSettings.getMaxHistory();
        boolean previousWeb = PluginSettings.isAllowWebRequests();
        try {
            PluginSettings.setMaxHistory(999);
            PluginSettings.setAllowWebRequests(false);
            AiSession session = AiSession.create(null, AiTypeEnum.CODEX);
            assertNull(session.settings().maxHistory());
            assertNull(session.settings().allowWebRequests());

            AiSessionSettings chosen = new AiSessionSettings();
            chosen.setMaxHistory(17);
            chosen.setAllowWebRequests(true);
            SessionPickerDialog.applyTemplates(session, ConfigTemplate.create("Chosen", chosen),
                                               McpTemplate.createDefault(McpToolConfig.ALL_ENABLED));

            assertEquals(17, session.settings().maxHistory());
            assertTrue(session.settings().allowWebRequests());
        }
        finally {
            PluginSettings.setMaxHistory(previousMaxHistory);
            PluginSettings.setAllowWebRequests(previousWeb);
        }
    }

    @Test
    void perTypeDefaultsStillApplyToNewSessions() {
        String previousEffort = CodexPluginSettings.getEffort();
        try {
            CodexPluginSettings.setEffort("high");
            AiSession session = AiSession.create(null, AiTypeEnum.CODEX);
            assertEquals("high", ((CodexSessionSettings) session.settings()).effort());
        }
        finally {
            CodexPluginSettings.setEffort(previousEffort);
        }
    }

    @Test
    void sessionsFromOneTemplateStayIndependentAndLaterGlobalsDoNotLeakIn() {
        int previousMaxHistory = PluginSettings.getMaxHistory();
        try {
            AiSessionSettings chosen = new AiSessionSettings();
            chosen.setMaxHistory(17);
            ConfigTemplate template = ConfigTemplate.create("Chosen", chosen);
            AiSession first = AiSession.create(null, AiTypeEnum.CODEX);
            AiSession second = AiSession.create(null, AiTypeEnum.CODEX);
            McpTemplate mcp = McpTemplate.createDefault(McpToolConfig.ALL_ENABLED);
            SessionPickerDialog.applyTemplates(first, template, mcp);
            SessionPickerDialog.applyTemplates(second, template, mcp);

            first.settings().setMaxHistory(33);
            assertEquals(17, second.settings().maxHistory());

            PluginSettings.setMaxHistory(555);
            AiSession third = AiSession.create(null, AiTypeEnum.CODEX);
            SessionPickerDialog.applyTemplates(third, template, mcp);
            assertEquals(17, third.settings().maxHistory());
        }
        finally {
            PluginSettings.setMaxHistory(previousMaxHistory);
        }
    }

    @Test
    void mcpTemplateIsAppliedAfterConfigTemplate() {
        AiSession session = AiSession.create(null, AiTypeEnum.CODEX);
        AiSessionSettings chosen = new AiSessionSettings();
        chosen.setAllowWebRequests(false);
        McpTemplate mcp = McpTemplate.create("Restricted", McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.WEB)));

        SessionPickerDialog.applyTemplates(session, ConfigTemplate.create("Chosen", chosen), mcp);

        assertFalse(session.settings().allowWebRequests());
        assertTrue(session.settings().mcpToolConfig().isDisabled(McpToolGroupEnum.WEB));
    }

    @Test
    void anMcpTemplateIsRequiredAndNothingIsAppliedWithoutOne() {
        AiSession session = AiSession.create(null, AiTypeEnum.CODEX);
        AiSessionSettings chosen = new AiSessionSettings();
        chosen.setMaxHistory(17);

        assertThrows(IllegalArgumentException.class,
                     () -> SessionPickerDialog.applyTemplates(session, ConfigTemplate.create("Chosen", chosen), null));

        assertNull(session.settings().mcpToolConfig());
        assertNull(session.settings().maxHistory(), "a rejected call leaves the session untouched");
    }

    @Test
    void loaderStillReturnsConfigAndMcpListsWhenTheInstructionStoreFails() throws Exception {
        Files.createDirectory(temp.resolve("instruction-templates.json"));

        SessionPickerDialog.TemplateData data = SessionPickerDialog.loadTemplateData(
                new TemplatePersistenceManager(temp), new McpTemplatePersistenceManager(temp));

        assertFalse(data.configs().isEmpty(), "a failing instruction store must not empty the config list");
        assertTrue(data.configs().stream().anyMatch(ConfigTemplate::isDefault));
        assertFalse(data.mcps().isEmpty(), "a failing instruction store must not empty the MCP list");
        assertTrue(data.mcps().stream().anyMatch(McpTemplate::isDefault));
        assertTrue(data.instructions().isEmpty());
        assertEquals(List.of(SessionPickerDialog.TemplateStoreEnum.INSTRUCTION),
                     data.failures().stream().map(SessionPickerDialog.StoreFailure::store).toList(),
                     "the failure names the instruction store, and only that store");
        assertFalse(data.failures().get(0).message().isBlank(), "the failure carries the reason it gave");
    }

    @Test
    void unreadableMcpStoreIsServedTheLegacyDerivedDefaultWithoutAFailure() throws Exception {
        Files.createDirectory(temp.resolve("mcp-templates.json"));

        SessionPickerDialog.TemplateData data = SessionPickerDialog.loadTemplateData(
                new TemplatePersistenceManager(temp), new McpTemplatePersistenceManager(temp));

        assertTrue(data.failures().isEmpty());
        assertEquals(1, data.mcps().size());
        assertTrue(data.mcps().get(0).isDefault());
        assertEquals(LegacyToolGroups.derive(null), data.mcps().get(0).config(),
                     "the MCP persistence layer serves its legacy-derived Default when its store is unreadable");
    }

    @Test
    void pickerFallbackMcpDefaultMatchesTheLegacyDerivedGroups() {
        McpTemplate fallback = SessionPickerDialog.inMemoryDefaultMcp();

        assertTrue(fallback.isDefault());
        assertEquals(LegacyToolGroups.derive(null), fallback.config());
    }

    @Test
    void loaderReportsNoFailuresWhenEveryStoreLoads() {
        SessionPickerDialog.TemplateData data = SessionPickerDialog.loadTemplateData(
                new TemplatePersistenceManager(temp), new McpTemplatePersistenceManager(temp));

        assertTrue(data.failures().isEmpty());
        assertFalse(data.configs().isEmpty());
        assertFalse(data.mcps().isEmpty());
        assertFalse(data.instructions().isEmpty());
    }

    @Test
    void duplicateTemplateNamesGainAOneBasedSuffixAndUniqueNamesAreLeftAlone() {
        assertEquals("Alpha", SessionPickerDialog.duplicateLabel("Alpha", List.of("Alpha", "Beta"), 1));
        assertEquals("Alpha (1)", SessionPickerDialog.duplicateLabel("Alpha", List.of("Alpha", "Alpha"), 1));
        assertEquals("Alpha (2)", SessionPickerDialog.duplicateLabel("Alpha", List.of("Alpha", "Alpha"), 2));
        assertEquals("Alpha (3)", SessionPickerDialog.duplicateLabel("Alpha", List.of("Beta", "Alpha", "Alpha", "Alpha"), 3));
    }
}
