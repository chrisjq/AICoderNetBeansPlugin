package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
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
            // Destination options stay at their shipped default (off) so tests that use
            // loopback refusal as a signal keep working; grant them explicitly where needed.
            boolean destinationOption = option == WebRequestAccessOptionEnum.LOCALHOST
                    || option == WebRequestAccessOptionEnum.PRIVATE_NETWORKS;
            session.settings().setAllowWebRequestAccess(option, !destinationOption);
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

    /**
     * With the option granted, the same request the default policy refuses is fetched end-to-end.
     */
    @Test
    void handleAllowsLoopbackWhenLocalhostOptionGranted() throws Exception {
        String responseBody = "local-ok";
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[4096]);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "
                            + responseBody.length() + "\r\nConnection: close\r\n\r\n"
                            + responseBody).getBytes(StandardCharsets.UTF_8));
                }
                catch (IOException handled) {
                    // A client-side failure surfaces in the assertions below.
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            AiSession session = sessionAllowingWebRequests(true);
            session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST,
                                                        true);
            WebRequestTool tool = new WebRequestTool();
            JsonObject args = new JsonObject();
            args.addProperty(WebRequestParamEnum.URL.key(), "http://127.0.0.1:" + port + "/");
            args.addProperty(WebRequestParamEnum.MAX_CHARS.key(), 100);

            Object result = tool.handle(args(args), new FakeSession(session));
            assertTrue(result.toString().contains(responseBody), result.toString());
        }
    }

    /**
     * A public-looking entry URL must not launder a blocked redirect target past the policy: the hop that hands back a
     * 302 is allowed (loopback), the target it names is not.
     */
    @Test
    void redirectTargetIsRecheckedMidChainNotJustTheEntryUrl() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getInputStream().read(new byte[4096]);
                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\n"
                            + "Location: http://10.0.0.1/secret\r\nContent-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                }
                catch (IOException handled) {
                    // The refusal surfaces in the assertion below.
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            AiSession session = sessionAllowingWebRequests(true);
            session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST,
                                                        true);
            WebRequestTool tool = new WebRequestTool();
            JsonObject args = new JsonObject();
            args.addProperty(WebRequestParamEnum.URL.key(), "http://127.0.0.1:" + port + "/");

            Exception ex = assertThrows(Exception.class,
                                        () -> tool.handle(args(args), new FakeSession(session)));
            assertTrue(ex.getMessage().contains("private (site-local) address refused"),
                       ex.getMessage());
            assertTrue(ex.getMessage().contains("Allow private network destinations"),
                       ex.getMessage());
        }
    }

    /**
     * A malformed {@code Location} must surface as a tool-level failure, not an unchecked crash.
     * <p>
     * {@code URI.resolve} throws {@link IllegalArgumentException}, which is not among the exception types
     * {@code handle} catches — so before the fix a remote server could take the tool out with a header the caller never
     * chose and cannot correct. The assistant needs a message it can report, not a stack trace.
     */
    @Test
    void malformedRedirectLocationIsReportedNotThrownRaw() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            respondOnce(server, "HTTP/1.1 302 Found\r\n"
                        + "Location: http://[bad\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");

            AiSession session = sessionAllowingWebRequests(true);
            session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST, true);
            JsonObject args = new JsonObject();
            args.addProperty(WebRequestParamEnum.URL.key(), "http://127.0.0.1:" + port + "/");

            String result = new WebRequestTool().handle(args(args), new FakeSession(session));
            assertTrue(result.contains("redirect"), result);
        }
    }

    /**
     * Only 301/302/303/307/308 are redirects. A 304 carrying a stale {@code Location} must NOT be followed: doing so
     * issues a second request the caller never asked for, and — with a destination option granted — that request goes
     * somewhere the caller never named.
     */
    @Test
    void nonRedirectThreeHundredStatusIsNotFollowedEvenWithLocation() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            respondOnce(server, "HTTP/1.1 304 Not Modified\r\n"
                        + "Location: http://10.0.0.1/secret\r\nConnection: close\r\n\r\n");

            AiSession session = sessionAllowingWebRequests(true);
            session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST, true);
            JsonObject args = new JsonObject();
            args.addProperty(WebRequestParamEnum.URL.key(), "http://127.0.0.1:" + port + "/");

            String result = new WebRequestTool().handle(args(args), new FakeSession(session));
            // Assert on finalUrl, NOT on the absence of "10.0.0.1": the response echoes the Location header
            // verbatim, so that string is present either way and an absence check would pass for the wrong
            // reason. finalUrl is the only field that distinguishes "stopped here" from "followed the hop".
            assertTrue(result.contains("\"status\":304"), result);
            assertTrue(result.contains("\"finalUrl\":\"http://127.0.0.1:" + port + "/\""), result);
        }
    }

    /**
     * A failure carrying no message must never render as the bare word "null".
     * <p>
     * A refused TCP connection arrives as a ConnectException whose {@code getMessage()} is null, so
     * string-concatenating it produced "Request failed for http://...: null" — which reads as a bug in the tool rather
     * than a fact about the destination. Found by USING the feature, not by reading it: the case only became reachable
     * once localhost destinations could be granted, because before that the gate refused first.
     */
    @Test
    void connectionFailureWithNoMessageStillDescribesItself() throws Exception {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            deadPort = probe.getLocalPort();
        }
        // Closed above, so nothing is listening and the connect is refused deterministically.

        AiSession session = sessionAllowingWebRequests(true);
        session.settings().setAllowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST, true);
        JsonObject args = new JsonObject();
        args.addProperty(WebRequestParamEnum.URL.key(), "http://127.0.0.1:" + deadPort + "/");
        args.addProperty(WebRequestParamEnum.TIMEOUT_SECONDS.key(), 5);

        String result = new WebRequestTool().handle(args(args), new FakeSession(session));

        assertTrue(result.startsWith("Request failed for"), result);
        assertTrue(!result.contains(": null"), "must not render a null message: " + result);
    }

    /**
     * Serves one canned response on {@code server}, then lets the connection close.
     */
    private static void respondOnce(ServerSocket server, String response) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.getInputStream().read(new byte[4096]);
                socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException handled) {
                // The assertion in the calling test is what reports the outcome.
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * null on the session means "inherit the plugin default"; a non-null value overrides it. Both directions matter: a
     * user who turns localhost ON globally must still be able to turn it OFF for one session, and vice versa.
     */
    @Test
    void sessionOverrideBeatsPluginDefaultInBothDirections() {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        WebRequestAccessOptionEnum option = WebRequestAccessOptionEnum.LOCALHOST;

        session.settings().setAllowWebRequestAccess(option, Boolean.TRUE);
        assertTrue(session.settings().effectiveAllowWebRequestAccess(option),
                   "an explicit session TRUE must win regardless of the plugin default");

        session.settings().setAllowWebRequestAccess(option, Boolean.FALSE);
        assertFalse(session.settings().effectiveAllowWebRequestAccess(option),
                    "an explicit session FALSE must win regardless of the plugin default");

        session.settings().setAllowWebRequestAccess(option, null);
        assertFalse(session.settings().effectiveAllowWebRequestAccess(option),
                    "null must inherit the shipped plugin default, which is off");
    }

    @Test
    void boundedSubscriberCompletesTruncatedBodyBeforeCancelling() {
        RecordingSubscription subscription = new RecordingSubscription();
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(10);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("abcdef"), bytes("ghijklmnop")));

        assertTrue(subscription.cancelled);
        WebRequestTool.BoundedBody bounded = subscriber.getBody().toCompletableFuture().getNow(null);
        assertTrue(bounded != null && bounded.truncatedByBytes(),
                   "the body must already be complete when cancel() runs, or send() fails with 'Stream N cancelled'");
        assertEquals("abcdefghij", new String(bounded.bytes(), StandardCharsets.UTF_8));

        subscriber.onNext(List.of(bytes("late")));
        subscriber.onError(new IOException("Stream 1 cancelled"));
        assertEquals(bounded, subscriber.getBody().toCompletableFuture().getNow(null),
                     "the cancel-induced error must not replace the truncated body");
    }

    @Test
    void boundedSubscriberKeepsShortBodiesIntact() {
        RecordingSubscription subscription = new RecordingSubscription();
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(100);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("sh"), bytes("ort")));
        subscriber.onComplete();

        assertFalse(subscription.cancelled);
        assertEquals(Long.MAX_VALUE, subscription.requested);
        WebRequestTool.BoundedBody bounded = subscriber.getBody().toCompletableFuture().getNow(null);
        assertFalse(bounded.truncatedByBytes());
        assertEquals("short", new String(bounded.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void boundedSubscriberBodyOfExactlyTheLimitIsNotTruncated() {
        RecordingSubscription subscription = new RecordingSubscription();
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(5);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("abc"), bytes("de"), ByteBuffer.allocate(0)));
        subscriber.onComplete();

        assertFalse(subscription.cancelled);
        WebRequestTool.BoundedBody bounded = subscriber.getBody().toCompletableFuture().getNow(null);
        assertFalse(bounded.truncatedByBytes());
        assertEquals("abcde", new String(bounded.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void boundedSubscriberPropagatesTransportErrors() {
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(10);
        subscriber.onSubscribe(new RecordingSubscription());
        subscriber.onNext(List.of(bytes("abc")));
        subscriber.onError(new IOException("connection reset"));

        ExecutionException e = assertThrows(ExecutionException.class,
                                            () -> subscriber.getBody().toCompletableFuture().get());
        assertEquals("connection reset", e.getCause().getMessage());
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingSubscription implements Flow.Subscription {

        private long requested;
        private boolean cancelled;

        @Override
        public void request(long n) {
            requested = n;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    @Test
    void truncatedBodyIsSpooledCompletelyWhileOnlyTheHeadIsKeptInMemory() {
        RecordingSubscription subscription = new RecordingSubscription();
        FakeSpool spool = new FakeSpool("GET https://example.com/\nHTTP 200\n\n", false);
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(4, 1000, spool);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("abcdef"), bytes("ghij")));
        subscriber.onComplete();

        assertFalse(subscription.cancelled, "the rest of the body must still be downloaded into the spool");
        WebRequestTool.BoundedBody bounded = subscriber.getBody().toCompletableFuture().getNow(null);
        assertTrue(bounded.truncatedByBytes());
        assertEquals("abcd", new String(bounded.bytes(), StandardCharsets.UTF_8));
        assertEquals(FakeSpool.PATH, bounded.fullResponseFile());
        assertEquals("GET https://example.com/\nHTTP 200\n\nabcdefghij", spool.content());
        assertFalse(bounded.spoolTruncated());
        assertFalse(spool.discarded);
    }

    @Test
    void spoolStopsAtItsCapAndSaysSo() {
        RecordingSubscription subscription = new RecordingSubscription();
        FakeSpool spool = new FakeSpool("", false);
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(4, 6, spool);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("abcdef"), bytes("ghij")));

        assertTrue(subscription.cancelled);
        WebRequestTool.BoundedBody bounded = subscriber.getBody().toCompletableFuture().getNow(null);
        assertTrue(bounded.spoolTruncated());
        assertEquals("abcdef", spool.content());
        assertEquals(FakeSpool.PATH, bounded.fullResponseFile());
    }

    @Test
    void aBodyThatFitsNeverOpensTheSpool() {
        FakeSpool spool = new FakeSpool("preamble\n\n", false);
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(10, 1000, spool);
        subscriber.onSubscribe(new RecordingSubscription());
        subscriber.onNext(List.of(bytes("short")));
        subscriber.onComplete();

        WebRequestTool.BoundedBody bounded = subscriber.getBody().toCompletableFuture().getNow(null);
        assertFalse(bounded.truncatedByBytes());
        assertFalse(spool.opened(), "no file may be created for a response that is not truncated");

        // A body cut only by the character cap is complete in memory and is written out on demand.
        assertEquals(FakeSpool.PATH, bounded.fullResponseFile());
        assertEquals("preamble\n\nshort", spool.content());
    }

    @Test
    void aTransportErrorWhileSpoolingDiscardsTheFile() {
        FakeSpool spool = new FakeSpool("", false);
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(2, 1000, spool);
        subscriber.onSubscribe(new RecordingSubscription());
        subscriber.onNext(List.of(bytes("abcd")));
        subscriber.onError(new IOException("connection reset"));

        assertTrue(spool.discarded);
        assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get());
    }

    @Test
    void aSpoolThatCannotBeOpenedFallsBackToTheTruncatedHead() {
        RecordingSubscription subscription = new RecordingSubscription();
        FakeSpool spool = new FakeSpool("", true);
        WebRequestTool.BoundedBodySubscriber subscriber = new WebRequestTool.BoundedBodySubscriber(3, 1000, spool);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("abcdef")));

        assertTrue(subscription.cancelled);
        WebRequestTool.BoundedBody bounded = subscriber.getBody().toCompletableFuture().getNow(null);
        assertEquals("abc", new String(bounded.bytes(), StandardCharsets.UTF_8));
        assertEquals(null, bounded.fullResponseFile());
    }

    @Test
    void responsePreambleListsRequestStatusAndEveryHeaderValue() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("content-type", List.of("text/html"));
        headers.put("set-cookie", List.of("a=1", "b=2"));

        assertEquals("GET https://example.com/a\nHTTP 200\ncontent-type: text/html\nset-cookie: a=1\nset-cookie: b=2\n\n",
                     WebRequestTool.responsePreamble("GET", URI.create("https://example.com/a"), 200, headers));
    }

    @Test
    void descriptionNamesTheFullResponseFile() {
        String description = new WebRequestTool().schema(Set.of()).get(ToolSchemaKeyEnum.DESCRIPTION.key()).getAsString();
        assertTrue(description.contains("fullResponseFile"), description);
        assertTrue(description.contains("50 MB"), description);
    }

    private static final class FakeSpool implements WebRequestTool.ResponseSpool {

        static final Path PATH = Path.of("/tmp/web-request-test.txt");
        private final String preamble;
        private final boolean failOpen;
        private ByteArrayOutputStream written;
        private boolean discarded;

        FakeSpool(String preamble, boolean failOpen) {
            this.preamble = preamble;
            this.failOpen = failOpen;
        }

        @Override
        public OutputStream open() throws IOException {
            if (failOpen) {
                throw new IOException("no temp file support");
            }
            written = new ByteArrayOutputStream();
            written.write(preamble.getBytes(StandardCharsets.UTF_8));
            return written;
        }

        @Override
        public Path path() {
            return written != null ? PATH : null;
        }

        @Override
        public void discard() {
            discarded = true;
            written = null;
        }

        boolean opened() {
            return written != null;
        }

        String content() {
            return written.toString(StandardCharsets.UTF_8);
        }
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
        assertTrue(description.contains("unless"), description);
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
