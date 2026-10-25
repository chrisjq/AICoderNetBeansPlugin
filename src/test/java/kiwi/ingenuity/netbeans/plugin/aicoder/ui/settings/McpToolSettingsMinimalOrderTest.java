package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.time.Instant;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The MCP tool settings template chooser: Default first (preselected), the built-in Minimal second and labelled
 * "Minimal (built-in)", then the rest by name.
 */
class McpToolSettingsMinimalOrderTest {

    @Test
    @Timeout(10)
    void choicesOrderDefaultThenMinimalThenTheRestByName() {
        McpTemplate zeta = new McpTemplate("zeta", "Zeta", McpToolConfig.ALL_ENABLED, Instant.now(), Instant.now());
        McpTemplate alpha = new McpTemplate("alpha", "Alpha", McpToolConfig.ALL_ENABLED, Instant.now(), Instant.now());
        McpTemplate minimal = McpTemplate.createMinimal();
        McpTemplate defaultTemplate = McpTemplate.createDefault(McpToolConfig.ALL_ENABLED);

        McpToolSettingsPanel.TemplateChoice[] choices = McpToolSettingsPanel.templateChoices(
                List.of(zeta, alpha, minimal, defaultTemplate));

        assertTrue(choices[0].template().isDefault(), "Default comes first and is what the chooser preselects");
        assertTrue(choices[1].template().isMinimal(), "Minimal comes directly after Default");
        assertEquals("Minimal (built-in)", choices[1].toString());
        assertEquals("Alpha", choices[2].toString());
        assertEquals("Zeta", choices[3].toString());
    }

    @Test
    @Timeout(10)
    void theBuiltInMinimalIsLabelledBuiltInEvenWhenAUserTemplateSharesItsName() {
        McpTemplate minimal = McpTemplate.createMinimal();
        McpTemplate user = new McpTemplate("user-minimal", McpTemplate.MINIMAL_NAME, McpToolConfig.ALL_ENABLED,
                                           Instant.now(), Instant.now());

        McpToolSettingsPanel.TemplateChoice[] choices = McpToolSettingsPanel.templateChoices(List.of(minimal, user));

        assertEquals("Minimal (built-in)", choices[0].toString());
        assertEquals("Minimal", choices[1].toString());
    }
}
