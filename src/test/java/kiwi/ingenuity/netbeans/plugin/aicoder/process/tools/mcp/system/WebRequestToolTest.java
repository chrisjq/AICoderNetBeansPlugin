package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WebRequestToolTest {

    /**
     * Public URL used only for permission-gate tests that throw before any network I/O.
     */
    private static final String SAMPLE_PUBLIC_URL = "https://example.com/";

    private static AiSession sessionAllowingWebRequests(boolean allow) {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        session.settings().setAllowWebRequests(allow);
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            session.settings().setAllowWebRequestAccess(option, true);
        }
        return session;
    }

    private static ToolRequestArguments args(JsonObject object) {
        return new ToolRequestArguments(object);
    }

    @Test
    void rejectsNonHttpSchemes() {
        WebRequestTool tool = new WebRequestTool();
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), "file:///tmp/test.txt");

        Exception ex = assertThrows(Exception.class,
                () -> tool.handle(args(args), new FakeSession(sessionAllowingWebRequests(true))));
        assertTrue(ex.getMessage().contains("Only http:// and https:// URLs are supported"));
    }

    @Test
    void rejectsWhenWebRequestsDisabledForSession() {
        WebRequestTool tool = new WebRequestTool();
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), SAMPLE_PUBLIC_URL);

        Exception ex = assertThrows(Exception.class,
                () -> tool.handle(args(args), new FakeSession(sessionAllowingWebRequests(false))));
        assertTrue(ex.getMessage().contains("Web requests are disabled for this session"));
    }

    @Test
    void rejectsWhenMethodAccessIsDisabled() {
        WebRequestTool tool = new WebRequestTool();
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), SAMPLE_PUBLIC_URL);
        args.addProperty(WebRequestParamEnum.METHOD.key(), "POST");

        AiSession session = sessionAllowingWebRequests(true);
        session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.POST,
                false);

        Exception ex = assertThrows(Exception.class,
                () -> tool.handle(args(args), new FakeSession(session)));
        assertTrue(ex.getMessage().contains("Allow POST"));
    }

    @Test
    void rejectsWhenHeaderAccessIsDisabled() {
        WebRequestTool tool = new WebRequestTool();
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), SAMPLE_PUBLIC_URL);
        JsonObject headers = new JsonObject();
        headers.addProperty("X-Test", "demo");
        args.add(WebRequestParamEnum.HEADERS.key(), headers);

        AiSession session = sessionAllowingWebRequests(true);
        session.settings().setAllowWebRequestAccess(
                WebRequestAccessOptionEnum.HEADERS, false);

        Exception ex = assertThrows(Exception.class,
                () -> tool.handle(args(args), new FakeSession(session)));
        assertTrue(ex.getMessage().contains("Allow custom headers"));
    }

    @Test
    void rejectsWhenBodyAccessIsDisabled() {
        WebRequestTool tool = new WebRequestTool();
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), SAMPLE_PUBLIC_URL);
        args.addProperty(WebRequestParamEnum.METHOD.key(), "POST");
        args.addProperty(WebRequestParamEnum.BODY.key(), "hello world");

        AiSession session = sessionAllowingWebRequests(true);
        session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.BODY,
                false);

        Exception ex = assertThrows(Exception.class,
                () -> tool.handle(args(args), new FakeSession(session)));
        assertTrue(ex.getMessage().contains("Allow request bodies"));
    }

    @Test
    void requestOnlyChecksHeaderAndBodyPermissionsWhenTheyAreUsed() {
        // GET with no headers/body must not require those access bits. Destination
        // validation still rejects loopback, which proves we got past the access checks.
        WebRequestTool tool = new WebRequestTool();
        AiSession session = sessionAllowingWebRequests(true);
        session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS,
                false);
        session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.BODY,
                false);

        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), "http://127.0.0.1/");
        args.addProperty(WebRequestParamEnum.METHOD.key(), "GET");

        Exception ex = assertThrows(Exception.class,
                () -> tool.handle(args(args), new FakeSession(session)));
        assertTrue(ex.getMessage().contains("loopback"), ex.getMessage());
    }

    @Test
    void rejectsLoopbackDestination() {
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://127.0.0.1:8080/mcp")));
        assertTrue(ex.getMessage().contains("loopback"), ex.getMessage());
    }

    @Test
    void rejectsLocalhostByName() {
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://localhost/")));
        assertTrue(ex.getMessage().contains("loopback"), ex.getMessage());
    }

    @Test
    void rejectsPrivateAndLinkLocalAddresses() throws Exception {
        assertTrue(WebRequestTool.isBlockedAddress(InetAddress.getByName("10.0.0.1")));
        assertTrue(WebRequestTool.isBlockedAddress(InetAddress.getByName("192.168.1.1")));
        assertTrue(WebRequestTool.isBlockedAddress(InetAddress.getByName("172.16.5.5")));
        assertTrue(WebRequestTool.isBlockedAddress(InetAddress.getByName("169.254.169.254")));
        assertTrue(WebRequestTool.isBlockedAddress(InetAddress.getByName("100.64.1.1")));
        assertTrue(WebRequestTool.isBlockedAddress(InetAddress.getByName("::1")));
        assertTrue(WebRequestTool.isBlockedAddress(InetAddress.getByName("fc00::1")));
    }

    @Test
    void allowsPublicAddress() throws Exception {
        assertFalse(WebRequestTool.isBlockedAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void refusalMessageNamesTheBlockedAddressClass() {
        // The message must name which policy was violated (e.g. "loopback address"),
        // not just say "non-public" — otherwise the AI can't tell a policy refusal
        // from a network failure, and can't explain the refusal to the user.
        McpArgumentException loopback = assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://127.0.0.1/")));
        assertTrue(loopback.getMessage().contains("loopback address refused"), loopback.getMessage());

        McpArgumentException siteLocal = assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://10.0.0.1/")));
        assertTrue(siteLocal.getMessage().contains("private (site-local) address refused"), siteLocal.getMessage());

        McpArgumentException linkLocal = assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://169.254.169.254/")));
        assertTrue(linkLocal.getMessage().contains("link-local address refused"), linkLocal.getMessage());
    }

    @Test
    void handleRejectsLoopbackBeforeConnecting() {
        WebRequestTool tool = new WebRequestTool();
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), "http://127.0.0.1/");

        Exception ex = assertThrows(Exception.class,
                () -> tool.handle(args(args), new FakeSession(sessionAllowingWebRequests(true))));
        assertTrue(ex.getMessage().contains("loopback"), ex.getMessage());
    }

    @Test
    void readBoundedBodyStopsBeforeReadingTheWholeStream() throws IOException {
        byte[] payload = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        WebRequestTool.BoundedBody bounded = WebRequestTool.readBoundedBody(
                new ByteArrayInputStream(payload), 10);
        assertTrue(bounded.truncatedByBytes());
        assertEquals(10, bounded.bytes().length);
        assertEquals("abcdefghij", new String(bounded.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void readBoundedBodyKeepsShortBodiesIntact() throws IOException {
        byte[] payload = "short".getBytes(StandardCharsets.UTF_8);
        WebRequestTool.BoundedBody bounded = WebRequestTool.readBoundedBody(
                new ByteArrayInputStream(payload), 100);
        assertFalse(bounded.truncatedByBytes());
        assertEquals("short", new String(bounded.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void maxBytesForCharsUsesUtf8WorstCase() {
        assertEquals(40, WebRequestTool.maxBytesForChars(10));
        assertEquals(800_000, WebRequestTool.maxBytesForChars(200_000));
    }

    @Test
    void descriptionDisclosesTheAddressPolicyAndRedirectRecheck() {
        JsonObject tool = new WebRequestTool().schema(Set.of());
        String description = tool.get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString();
        assertTrue(description.contains("loopback"), description);
        assertTrue(description.contains("every redirect target is re-checked"), description);
    }

    @Test
    void maxCharsDescriptionDisclosesTheClampedCeiling() {
        JsonObject tool = new WebRequestTool().schema(Set.of());
        JsonObject props = tool.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key())
                .getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        String maxCharsDescription = props.getAsJsonObject(WebRequestParamEnum.MAX_CHARS.key())
                .get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString();
        assertTrue(maxCharsDescription.contains("200000"), maxCharsDescription);
    }

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
}
