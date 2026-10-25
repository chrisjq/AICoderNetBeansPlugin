package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.time.Instant;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The session picker's MCP template list: the built-in Minimal is labelled "Minimal (built-in)", and the combo is
 * ordered Default first, Minimal second, the rest by name.
 */
class SessionPickerMinimalOrderTest {

    @Test
    @Timeout(10)
    void theBuiltInMinimalIsLabelledBuiltIn() {
        assertEquals("Minimal (built-in)", SessionPickerDialog.mcpTemplateLabel(McpTemplate.createMinimal()));
    }

    @Test
    @Timeout(10)
    void aUserTemplateNamedMinimalIsLabelledWithItsBareName() {
        McpTemplate user = new McpTemplate("user-minimal", McpTemplate.MINIMAL_NAME, McpToolConfig.ALL_ENABLED,
                                           Instant.now(), Instant.now());

        assertEquals("Minimal", SessionPickerDialog.mcpTemplateLabel(user));
    }

    @Test
    @Timeout(10)
    void aNonMinimalTemplateKeepsItsOwnName() {
        McpTemplate other = new McpTemplate("other", "Other", McpToolConfig.ALL_ENABLED, Instant.now(), Instant.now());

        assertEquals("Other", SessionPickerDialog.mcpTemplateLabel(other));
    }

    @Test
    @Timeout(10)
    void comboOrderIsDefaultThenMinimalThenTheRestByName() {
        McpTemplate zeta = new McpTemplate("zeta", "Zeta", McpToolConfig.ALL_ENABLED, Instant.now(), Instant.now());
        McpTemplate alpha = new McpTemplate("alpha", "Alpha", McpToolConfig.ALL_ENABLED, Instant.now(), Instant.now());
        McpTemplate minimal = McpTemplate.createMinimal();
        McpTemplate defaultTemplate = McpTemplate.createDefault(McpToolConfig.ALL_ENABLED);

        List<McpTemplate> ordered = SessionPickerDialog.ordered(
                List.of(zeta, alpha, minimal, defaultTemplate),
                McpTemplate::isDefault, McpTemplate::isMinimal, SessionPickerDialog::mcpTemplateLabel);

        assertTrue(ordered.get(0).isDefault(), "Default comes first and is what the picker preselects");
        assertTrue(ordered.get(1).isMinimal(), "Minimal comes directly after Default");
        assertEquals("Alpha", ordered.get(2).name());
        assertEquals("Zeta", ordered.get(3).name());
    }

    @Test
    @Timeout(10)
    void theRestAreOrderedCaseInsensitively() {
        McpTemplate lower = new McpTemplate("a", "alpha", McpToolConfig.ALL_ENABLED, Instant.now(), Instant.now());
        McpTemplate upper = new McpTemplate("b", "Beta", McpToolConfig.ALL_ENABLED, Instant.now(), Instant.now());

        List<McpTemplate> ordered = SessionPickerDialog.ordered(
                List.of(upper, lower), McpTemplate::isDefault, McpTemplate::isMinimal,
                SessionPickerDialog::mcpTemplateLabel);

        assertEquals("alpha", ordered.get(0).name());
        assertEquals("Beta", ordered.get(1).name());
    }
}
