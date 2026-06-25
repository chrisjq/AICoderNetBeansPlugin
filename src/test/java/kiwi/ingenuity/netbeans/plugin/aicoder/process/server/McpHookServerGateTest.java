package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpHookServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpHookServerGateTest {

    @Test
    void getInstructionsAllowedWhenNotLoaded() {
        assertFalse(McpHookServer.isToolGated(false, McpToolEnum.GET_INSTRUCTIONS));
    }

    @Test
    void otherToolBlockedWhenNotLoaded() {
        assertTrue(McpHookServer.isToolGated(false, McpToolEnum.GET_DIAGNOSTICS));
    }

    @Test
    void unknownToolBlockedWhenNotLoaded() {
        assertTrue(McpHookServer.isToolGated(false, null));
    }

    @Test
    void everythingAllowedWhenLoaded() {
        assertFalse(McpHookServer.isToolGated(true, McpToolEnum.GET_DIAGNOSTICS));
        assertFalse(McpHookServer.isToolGated(true, null));
    }
}
