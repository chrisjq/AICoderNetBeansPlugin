package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import java.util.List;
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

    @Test
    void readFramed_readsBodyByContentLength() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] msg = ("Content-Length: " + body.length + "\r\n\r\n{\"a\":1}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var in = new java.io.ByteArrayInputStream(msg);
        assertEquals("{\"a\":1}", GithubCopilotModelDiscovery.readFramed(in));
    }

    @Test
    void readFramed_readsTwoMessagesSequentially() throws Exception {
        String framed = "Content-Length: 2\r\n\r\n{}Content-Length: 7\r\n\r\n{\"b\":2}";
        var in = new java.io.ByteArrayInputStream(
                framed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("{}", GithubCopilotModelDiscovery.readFramed(in));
        assertEquals("{\"b\":2}", GithubCopilotModelDiscovery.readFramed(in));
    }

    @Test
    void readFramed_returnsNullOnEof() throws Exception {
        var in = new java.io.ByteArrayInputStream(new byte[0]);
        assertEquals(null, GithubCopilotModelDiscovery.readFramed(in));
    }
}
