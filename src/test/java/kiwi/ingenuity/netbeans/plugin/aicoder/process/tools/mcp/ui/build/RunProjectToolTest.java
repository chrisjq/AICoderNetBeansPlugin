package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ProjectPathParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Confirms the handle()-to-ProjectActionProvider wiring (full IDE Run/Debug round trip needs a real open
 * project with an ActionProvider, which {@code ProjectActionProviderTest} already covers without one for the
 * paths that do not need it).
 */
class RunProjectToolTest {

    private static AbstractAiSession session() {
        AiSessionSettings settings = new AiSessionSettings();
        AiSession aiSession = new AiSession("run-project-tool-test", "RunProjectToolTestSession", null,
                AiTypeEnum.CLAUDE, null, settings, Instant.now(), Instant.now());
        return new AbstractAiSession(aiSession) {
            @Override
            public String getId() {
                return "run-project-tool-test";
            }

            @Override
            public String getSessionName() {
                return "RunProjectToolTestSession";
            }

            @Override
            public Map getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        };
    }

    private static ToolRequestArguments args(String projectPath, Boolean debug) {
        JsonObject o = new JsonObject();
        if (projectPath != null) {
            o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), projectPath);
        }
        if (debug != null) {
            o.addProperty(McpToolPropertyEnum.DEBUG.key(), debug);
        }
        return new ToolRequestArguments(o);
    }

    @Test
    void blankProjectPath_isRefused() {
        String result = new RunProjectTool().handle(args("  ", null), session());

        assertTrue(result.contains("projectPath is required"), result);
    }

    @Test
    void debugAsNonBoolean_isRefused() {
        JsonObject o = new JsonObject();
        o.addProperty(ProjectPathParamEnum.PROJECT_PATH.key(), "/some/path");
        o.addProperty(McpToolPropertyEnum.DEBUG.key(), "yes");

        String result = new RunProjectTool().handle(new ToolRequestArguments(o), session());

        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void nonDirectoryProjectPath_isRefused() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("run-project-tool-test-", ".txt");
        try {
            String result = new RunProjectTool().handle(args(tempFile.toString(), false), session());

            assertTrue(result.contains("is not a directory"), result);
        }
        finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }
}
