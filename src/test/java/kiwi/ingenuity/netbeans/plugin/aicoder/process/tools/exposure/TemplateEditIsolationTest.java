package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.Test;

class TemplateEditIsolationTest {

    @Test
    void editingATemplateLeavesAnExistingSessionsConfigUnchanged() {
        McpToolConfig readOnlyGit = McpToolConfig.withDisabled(List.of(McpToolGroupEnum.GIT_WRITE));
        McpTemplate template = McpTemplate.create("Read only", readOnlyGit);
        AiSessionSettings session = new AiSessionSettings();
        session.setMcpToolConfig(template.config());

        McpTemplate edited = template.withNameAndConfig("Read only", McpToolConfig.ALL_ENABLED);

        assertEquals(readOnlyGit, session.mcpToolConfig(), "a template edit must not reach a session that already uses it");
        assertNotEquals(session.mcpToolConfig(), edited.config());
    }
}
