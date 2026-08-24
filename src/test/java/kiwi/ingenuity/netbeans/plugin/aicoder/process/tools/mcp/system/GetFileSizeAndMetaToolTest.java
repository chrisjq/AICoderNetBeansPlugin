package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum.CLAUDE;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the bug the user named specifically: GetFileSizeAndMetaTool previously checked only
 * {@code isFileAllowed}, so a session could read its own tool_results log via GetFileContent (which already OR'd in
 * isOwnSessionConfigFile) but not stat the same file via GetFileSizeAndMeta — exactly the half-implemented-rule failure
 * that motivated centralising the check into {@link McpHookServer#isFileAccessible}.
 */
class GetFileSizeAndMetaToolTest {

    private static ToolRequestArguments args(String filePath) {
        JsonObject o = new JsonObject();
        o.addProperty(GetFileSizeAndMetaParamEnum.FILE_PATH.key(), filePath);
        return new ToolRequestArguments(o);
    }

    @Test
    void handle_ownSessionConfigFile_notDeniedUnderRestrictToProjectWithNoProjectDirs() throws Exception {
        String sessionId = "gfsam-test-" + UUID.randomUUID();
        McpHookServer server = new McpHookServer(0);
        server.init();
        try {
            // Restrict on, no project dirs registered — the pre-fix bare isFileAllowed
            // check would have denied this outright.
            server.registerSession(sessionId, CLAUDE, List.of(), true);
            Path configDir = PluginUtil.getPluginAiSessionConfigDir(CLAUDE, sessionId);
            Path logFile = Files.createFile(configDir.resolve("build-maven-test.log"));
            Files.writeString(logFile, "BUILD SUCCESS\n");

            GetFileSizeAndMetaTool tool = new GetFileSizeAndMetaTool(server);
            String result = tool.handle(args(logFile.toString()), new FakeSession(sessionId));

            assertFalse(result.startsWith("Access denied"), "own config-dir file must not be denied: " + result);
            assertTrue(result.contains("bytes"), "must return the actual size/meta, not a denial: " + result);
        }
        finally {
            server.stop();
            PluginUtil.deleteAiSessionConfigDir(CLAUDE, sessionId);
        }
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;

        FakeSession(String id) {
            super(AiSession.create(null, AiTypeEnum.CLAUDE));
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
