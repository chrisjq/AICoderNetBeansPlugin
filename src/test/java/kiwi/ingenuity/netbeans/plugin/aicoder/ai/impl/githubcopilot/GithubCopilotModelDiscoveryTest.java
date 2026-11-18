package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.GithubCopilotModelDiscovery;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class GithubCopilotModelDiscoveryTest {

    @Test
    void assemble_putsAutoFirstAndKeepsDiscoveredOrder() {
        String[] out = GithubCopilotModelDiscovery.assembleModelList(
                List.of("claude-sonnet-4.5", "gpt-5-mini"));
        assertArrayEquals(new String[]{"auto", "claude-sonnet-4.5", "gpt-5-mini"}, out);
    }

    @Test
    void assemble_dedupesAndDropsBlanks_keepingSingleAuto() {
        String[] out = GithubCopilotModelDiscovery.assembleModelList(
                List.of("auto", "claude-sonnet-4.5", "claude-sonnet-4.5", "  ", ""));
        assertArrayEquals(new String[]{"auto", "claude-sonnet-4.5"}, out);
    }

    @Test
    void assemble_emptyDiscovery_returnsJustAuto() {
        assertArrayEquals(new String[]{"auto"},
                          GithubCopilotModelDiscovery.assembleModelList(List.of()));
    }

    @Test
    void assemble_trimsIds() {
        String[] out = GithubCopilotModelDiscovery.assembleModelList(List.of(" gpt-5-mini "));
        assertEquals("gpt-5-mini", out[1]);
    }

    @Test
    void parseModelIds_extractsIdsInOrder() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"models\":"
                + "[{\"id\":\"auto\"},{\"id\":\"gpt-5-mini\"},{\"id\":\"claude-haiku-4.5\"}]}}";
        assertEquals(List.of("auto", "gpt-5-mini", "claude-haiku-4.5"),
                     GithubCopilotModelDiscovery.parseModelIds(body));
    }

    @Test
    void parseModelIds_emptyOrMissing_returnsEmpty() {
        assertEquals(List.of(), GithubCopilotModelDiscovery.parseModelIds(
                     "{\"id\":2,\"result\":{\"models\":[]}}"));
        assertEquals(List.of(), GithubCopilotModelDiscovery.parseModelIds(
                     "{\"id\":2,\"result\":{}}"));
        assertEquals(List.of(), GithubCopilotModelDiscovery.parseModelIds("{\"id\":2}"));
    }

}
