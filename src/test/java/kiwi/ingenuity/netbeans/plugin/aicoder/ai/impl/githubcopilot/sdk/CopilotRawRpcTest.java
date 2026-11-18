package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class CopilotRawRpcTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPingWithAProtocolVersionReportsIt() throws Exception {
        assertEquals(3, CopilotRawRpc.protocol(json("{\"id\":1,\"result\":{\"protocolVersion\":3}}")));
        assertEquals(4, CopilotRawRpc.protocol(json("{\"id\":1,\"result\":{\"protocolVersion\":4}}")));
        assertEquals(5, CopilotRawRpc.protocol(json("{\"id\":1,\"result\":{\"protocolVersion\":\"5\"}}")));
    }

    @Test
    void aSuccessfulPingWithoutAProtocolVersionIsProtocolTwo() throws Exception {
        assertEquals(2, CopilotRawRpc.protocol(json("{\"id\":1,\"result\":{\"message\":\"pong\"}}")));
        assertEquals(2, CopilotRawRpc.protocol(json("{\"id\":1,\"result\":{\"protocolVersion\":null}}")));
    }

    @Test
    void aPingErrorIsAFailedProbeNotProtocolTwo() {
        IOException failure = assertThrows(IOException.class, () -> CopilotRawRpc.protocol(
                                           json("{\"id\":1,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}")));

        assertTrue(failure.getMessage().contains("Method not found"), failure.getMessage());
    }

    @Test
    void aPingWithNoResultIsAFailedProbe() {
        assertThrows(IOException.class, () -> CopilotRawRpc.protocol(json("{\"id\":1}")));
        assertThrows(IOException.class, () -> CopilotRawRpc.protocol(json("{\"id\":1,\"result\":null}")));
        assertThrows(IOException.class, () -> CopilotRawRpc.protocol(null));
    }

    @Test
    void aProtocolVersionThatIsNotAWholeNumberIsAFailedProbe() {
        for (String version : new String[]{"\"abc\"", "3.5", "{}", "[4]", "true", "\"\""}) {
            IOException failure = assertThrows(IOException.class, () -> CopilotRawRpc.protocol(
                                               json("{\"id\":1,\"result\":{\"protocolVersion\":" + version + "}}")), version);
            assertTrue(failure.getMessage().contains("not a whole number"), version + ": " + failure.getMessage());
        }
    }

    @Test
    void readFramedReadsTheBodyByContentLength() throws Exception {
        assertEquals("{\"a\":1}", CopilotRawRpc.readFramed(stream("Content-Length: 7\r\n\r\n{\"a\":1}")));
    }

    @Test
    void readFramedReadsTwoMessagesInSequence() throws Exception {
        ByteArrayInputStream in = stream("Content-Length: 2\r\n\r\n{}Content-Length: 7\r\n\r\n{\"b\":2}");

        assertEquals("{}", CopilotRawRpc.readFramed(in));
        assertEquals("{\"b\":2}", CopilotRawRpc.readFramed(in));
    }

    @Test
    void readFramedReturnsNullAtEndOfStream() throws Exception {
        assertNull(CopilotRawRpc.readFramed(stream("")));
    }

    @Test
    void readFramedReturnsNullForATruncatedBody() throws Exception {
        assertNull(CopilotRawRpc.readFramed(stream("Content-Length: 10\r\n\r\n{}")));
    }

    @Test
    void writeFramedPrefixesTheByteLength() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String accented = String.valueOf((char) 0xE9);
        String body = "{\"x\":\"" + accented + "\"}";

        CopilotRawRpc.writeFramed(out, body);

        assertEquals("Content-Length: 10\r\n\r\n" + body, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theServerCommandDefaultsToCopilotAndRunsJsFilesWithNode() {
        List<String> byDefault = CopilotRawRpc.serverCommand(null);
        List<String> script = CopilotRawRpc.serverCommand("/opt/copilot/index.js");

        assertTrue(byDefault.contains("copilot") || byDefault.contains("cmd"), byDefault.toString());
        assertEquals(List.of("node", "/opt/copilot/index.js"), script.subList(0, 2));
        assertTrue(script.containsAll(List.of("--server", "--no-auto-update", "--stdio")));
    }
}
