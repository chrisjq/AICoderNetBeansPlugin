package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ToolJsonRescueTest {

    @Test
    @Timeout(5)
    void rescuesAToolCallObjectEmbeddedInProse() {
        String prose = "I'll check that.\n"
                + "{\"name\":\"get_current_weather\",\"arguments\":{\"city\":\"Toronto\"}}\n"
                + "Then I can answer.";

        List<ToolJsonRescue.RescuedCall> calls = ToolJsonRescue.rescue(prose, Set.of("get_current_weather"));

        assertEquals(1, calls.size());
        assertEquals("get_current_weather", calls.get(0).name());
        assertEquals("{\"city\":\"Toronto\"}", calls.get(0).argumentsJson());
    }

    @Test
    @Timeout(5)
    void rescuesAFencedCallAndDropsUnknownNames() {
        String fenced = "```json\n{\"name\":\"get_current_weather\",\"arguments\":{\"city\":\"Oslo\"}}\n```";

        List<ToolJsonRescue.RescuedCall> calls = ToolJsonRescue.rescue(fenced, Set.of("get_current_weather"));

        assertEquals(1, calls.size());
        assertEquals("get_current_weather", calls.get(0).name());
        assertTrue(ToolJsonRescue.rescue("{\"name\":\"rm_rf\",\"arguments\":{}}", Set.of("get_current_weather"))
                .isEmpty());
    }

    @Test
    @Timeout(5)
    void objectArgumentsKeepDuplicateCounts() {
        String json = "{\"name\":\"test_tool\",\"arguments\":{\"key\":\"a\",\"key\":\"b\"}}";

        List<ToolJsonRescue.RescuedCall> calls = ToolJsonRescue.rescue(json, Set.of("test_tool"));

        assertEquals(Map.of("key", 2), calls.get(0).duplicateCounts());
    }

    @Test
    @Timeout(5)
    void structuredCallsWin() {
        List<ToolJsonRescue.RescuedCall> calls = ToolJsonRescue.rescue(
                "{\"name\":\"ignored\",\"arguments\":{}}",
                List.of(new ChatToolCall("c1", "SaveFile", "{\"path\":\"/p\"}")),
                Set.of("ignored"));

        assertEquals(1, calls.size());
        assertEquals("SaveFile", calls.get(0).name());
        assertEquals("{\"path\":\"/p\"}", calls.get(0).argumentsJson());
        assertTrue(calls.get(0).duplicateCounts().isEmpty());
    }
}
