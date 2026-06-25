package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetInstructionsTool;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GetInstructionsToolTest {

    private static final class FakeSession extends AbstractAiSession {

        FakeSession(AiSession s) {
            super(s);
        }

        @Override
        public String getId() {
            return getAiSession().id();
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

    @Test
    void returnsGuideAndSetsFlag() {
        AiSession s = AiSession.create(null, AiTypeEnum.CLAUDE);
        FakeSession session = new FakeSession(s);
        assertFalse(s.isInstructionsLoaded());

        GetInstructionsTool tool = new GetInstructionsTool();
        String out = tool.handle(new ToolRequestArguments(new com.google.gson.JsonObject()), session);

        assertNotNull(out);
        assertFalse(out.isBlank());
        assertTrue(s.isInstructionsLoaded());
    }
}
