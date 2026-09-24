package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpSteeringRefusalEventTest {

    @Test
    void composesOneLinePerRefusalAndOneClosingInstruction() {
        List<McpSteeringRefusalEvent.Refusal> refusals = List.of(
                new McpSteeringRefusalEvent.Refusal("shell", "use BuildMavenProject"),
                new McpSteeringRefusalEvent.Refusal("read", "use GetFileContent"));
        String[] lines = McpSteeringRefusalEvent.compose(refusals).split("\\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].contains("shell") && lines[0].contains("use BuildMavenProject"));
        assertTrue(lines[1].contains("read") && lines[1].contains("use GetFileContent"));
        assertTrue(lines[2].contains("Continue") && lines[2].contains("MCP"));
    }

    @Test
    void emptyInputComposesNothing() {
        assertNull(McpSteeringRefusalEvent.compose(null));
        assertNull(McpSteeringRefusalEvent.compose(List.of()));
    }

    @Test
    void eventCopiesItsRefusalList() {
        List<McpSteeringRefusalEvent.Refusal> source = new ArrayList<>(List.of(
                new McpSteeringRefusalEvent.Refusal("write", "use ApplyEdit")));
        McpSteeringRefusalEvent event = new McpSteeringRefusalEvent(source);
        source.clear();
        assertEquals(1, event.refusals().size());
    }
}
