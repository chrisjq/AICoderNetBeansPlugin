package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ToolCallModeResolverTest {

    @Test
    @Timeout(5)
    void resolverTable() {
        assertMode(ToolCallingModeEnum.AUTO, null, 0, 2, false, ToolCallingModeEnum.NATIVE);
        assertMode(ToolCallingModeEnum.AUTO, Boolean.TRUE, 0, 2, false, ToolCallingModeEnum.NATIVE);
        assertMode(ToolCallingModeEnum.AUTO, Boolean.FALSE, 0, 2, false, ToolCallingModeEnum.SCHEMA);
        assertMode(ToolCallingModeEnum.AUTO, Boolean.TRUE, 1, 2, false, ToolCallingModeEnum.NATIVE);
        assertMode(ToolCallingModeEnum.AUTO, Boolean.TRUE, 2, 2, false, ToolCallingModeEnum.SCHEMA);
        assertMode(ToolCallingModeEnum.AUTO, null, 0, 2, true, ToolCallingModeEnum.SCHEMA);
        assertMode(null, null, 0, 2, false, ToolCallingModeEnum.NATIVE);
        assertMode(ToolCallingModeEnum.AUTO, Boolean.TRUE, 5, 0, false, ToolCallingModeEnum.NATIVE);
        assertMode(ToolCallingModeEnum.NATIVE, Boolean.FALSE, 9, 2, false, ToolCallingModeEnum.NATIVE);
        assertMode(ToolCallingModeEnum.NATIVE, Boolean.TRUE, 0, 2, true, ToolCallingModeEnum.SCHEMA);
        assertMode(ToolCallingModeEnum.SCHEMA, Boolean.TRUE, 0, 2, false, ToolCallingModeEnum.SCHEMA);
    }

    @Test
    @Timeout(5)
    void toolsUnsupportedIsTheImmediateSwitch() {
        assertTrue(ToolCallModeResolver.immediateSwitch(MappedError.Kind.TOOLS_UNSUPPORTED));
        assertFalse(ToolCallModeResolver.immediateSwitch(MappedError.Kind.SERVER_ERROR));
        assertFalse(ToolCallModeResolver.immediateSwitch(null));
    }

    private static void assertMode(ToolCallingModeEnum setting, Boolean supported, int failures, int limit,
                                   boolean switched, ToolCallingModeEnum expected) {
        assertEquals(expected, ToolCallModeResolver.mode(setting, supported, failures, limit, switched));
    }
}
