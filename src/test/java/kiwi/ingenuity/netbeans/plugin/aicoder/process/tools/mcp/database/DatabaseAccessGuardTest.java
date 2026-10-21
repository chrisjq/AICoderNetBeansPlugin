package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.database;

import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DatabaseAccessGuardTest {

    @Test
    void anMcpLayerRefusalNamesTheToolGroupNotTheHiddenConfigSetting() {
        AbstractAiSession session = newSession();
        session.getSettings().setAllowDatabaseAccess(Boolean.TRUE);
        session.getSettings().setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.DATABASE)));

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> DatabaseAccessGuard.requireAccess(session));
        assertTrue(ex.getMessage().contains("The 'Database' tool group is switched off for this session"),
                   ex.getMessage());
        assertFalse(ex.getMessage().contains("Allow database access"),
                    "that checkbox is hidden while the MCP group is off");
    }

    @Test
    void aConfigLayerRefusalKeepsTheConfigSpecificText() {
        AbstractAiSession session = newSession();
        session.getSettings().setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        session.getSettings().setAllowDatabaseAccess(Boolean.FALSE);

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> DatabaseAccessGuard.requireAccess(session));
        assertTrue(ex.getMessage().contains("Database access is disabled"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'Allow database access'"), ex.getMessage());
    }

    @Test
    void whenBothLayersRefuseTheMcpLayerIsReported() {
        AbstractAiSession session = newSession();
        session.getSettings().setAllowDatabaseAccess(Boolean.FALSE);
        session.getSettings().setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.DATABASE)));

        McpArgumentException ex = assertThrows(McpArgumentException.class,
                                               () -> DatabaseAccessGuard.requireAccess(session));
        assertTrue(ex.getMessage().contains("tool group is switched off"), ex.getMessage());
    }

    @Test
    void bothLayersOnPassesTheGuard() throws McpArgumentException {
        AbstractAiSession session = newSession();
        session.getSettings().setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        session.getSettings().setAllowDatabaseAccess(Boolean.TRUE);

        DatabaseAccessGuard.requireAccess(session);
    }

    private static AbstractAiSession newSession() {
        return new AbstractAiSession(AiSession.create(null, AiTypeEnum.CLAUDE)) {
            @Override
            public String getId() {
                return "database-guard-test";
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }

            @Override
            public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
                return Map.of();
            }
        };
    }
}
