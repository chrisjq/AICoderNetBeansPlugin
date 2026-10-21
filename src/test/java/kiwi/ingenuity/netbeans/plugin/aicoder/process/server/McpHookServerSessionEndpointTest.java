package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives a real {@link McpHookServer} over HTTP to pin the session-addressed endpoint: {@code tools/list},
 * {@code initialize} and {@code tools/call} on {@code /mcp/<type>/<routingId>} answer and enforce per session, an
 * address that names no session fails closed, and the legacy {@code /mcp/<type>} path keeps its old, unfiltered
 * behaviour.
 */
class McpHookServerSessionEndpointTest {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static final String ROUTE_A = "route-a-for-endpoint-test";
    private static final String ROUTE_B = "route-b-for-endpoint-test";
    private static final String PLUGIN_VERSION_DESCRIPTION
            = "Returns the plugin version. Use GetClipboard to copy it. Cheap to call.";

    private McpHookServer server;
    private TestSession sessionA;
    private TestSession sessionB;
    private Map<McpToolEnum, McpToolInterface> priorClaudeRegistry;

    private static final class StubTool extends AbstractActionTool {

        StubTool(McpToolEnum tool, String description) {
            super(McpSectionEnum.PLUGIN, tool.toolName(), description, tool.toolName() + " -> stub");
        }

        @Override
        public boolean isMutating() {
            return false;
        }

        @Override
        public String handle(ToolRequestArguments args, AbstractAiSession session) {
            return "ran";
        }
    }

    private static final class TestSession extends AbstractAiSession {

        private final String id;
        private final Map<McpToolEnum, McpToolInterface> handlers;
        private final AiSession aiSession;

        TestSession(String id, AiSession aiSession, Map<McpToolEnum, McpToolInterface> handlers) {
            super(aiSession);
            this.id = id;
            this.aiSession = aiSession;
            this.handlers = handlers;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return handlers;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        String secret() {
            return aiSession.secret();
        }

        AiSessionSettings settings() {
            return aiSession.settings();
        }
    }

    private static Map<McpToolEnum, McpToolInterface> allHandlers() {
        Map<McpToolEnum, McpToolInterface> handlers = new EnumMap<>(McpToolEnum.class);
        handlers.put(McpToolEnum.GET_INSTRUCTIONS, new StubTool(McpToolEnum.GET_INSTRUCTIONS, "Returns the guide."));
        handlers.put(McpToolEnum.GET_PLUGIN_VERSION,
                     new StubTool(McpToolEnum.GET_PLUGIN_VERSION, PLUGIN_VERSION_DESCRIPTION));
        handlers.put(McpToolEnum.GET_CLIPBOARD, new StubTool(McpToolEnum.GET_CLIPBOARD, "Reads the clipboard."));
        handlers.put(McpToolEnum.SEARCH_IN_FILES, new StubTool(McpToolEnum.SEARCH_IN_FILES, "Searches files."));
        return handlers;
    }

    private static TestSession register(String id, String routingId, McpToolConfig config) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
        // The fixture controls clipboard exposure through the MCP config, so its config-layer setting must allow it.
        settings.setEnableClipboardAccess(true);
        settings.setMcpToolConfig(config);
        settings.setMcpRoutingId(routingId);
        AiSession aiSession = new AiSession(id, id, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(),
                                            Instant.now());
        aiSession.setInstructionsLoaded(true);
        TestSession session = new TestSession(id, aiSession, allHandlers());
        SessionRegistry.register(session);
        return session;
    }

    @BeforeEach
    void startServerAndSessions() throws Exception {
        priorClaudeRegistry = McpInstructionRegistry.getHandlers(AiTypeEnum.CLAUDE);
        server = new McpHookServer(0);
        server.init();
        server.start();
        // A hides the clipboard and text search; B has everything.
        sessionA = register("endpoint-test-session-a", ROUTE_A,
                            McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.CLIPBOARD, McpToolGroupEnum.TEXT_SEARCH)));
        sessionB = register("endpoint-test-session-b", ROUTE_B, McpToolConfig.ALL_ENABLED);
    }

    @AfterEach
    void stopServerAndSessions() {
        server.stop();
        SessionRegistry.unregister(sessionA);
        SessionRegistry.unregister(sessionB);
        if (!priorClaudeRegistry.isEmpty()) {
            McpInstructionRegistry.registerHandlers(AiTypeEnum.CLAUDE, priorClaudeRegistry);
        }
    }

    private JsonObject post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getBaseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return GSON.fromJson(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
    }

    private static String rpc(String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
    }

    private static String callParams(String tool, TestSession caller) {
        return "{\"name\":\"" + tool + "\",\"arguments\":{\"sessionId\":\"" + caller.getId()
                + "\",\"secretKey\":\"" + caller.secret() + "\"}}";
    }

    private static List<String> toolNames(JsonObject listResponse) {
        List<String> names = new ArrayList<>();
        for (JsonElement tool : listResponse.getAsJsonObject("result").getAsJsonArray("tools")) {
            names.add(tool.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    private static JsonObject toolNamed(JsonObject listResponse, String name) {
        JsonArray tools = listResponse.getAsJsonObject("result").getAsJsonArray("tools");
        for (JsonElement tool : tools) {
            if (name.equals(tool.getAsJsonObject().get("name").getAsString())) {
                return tool.getAsJsonObject();
            }
        }
        throw new AssertionError("no tool " + name + " in " + toolNames(listResponse));
    }

    private static int errorCode(JsonObject response) {
        return response.getAsJsonObject("error").get("code").getAsInt();
    }

    private static String errorMessage(JsonObject response) {
        return response.getAsJsonObject("error").get("message").getAsString();
    }

    private static String resultText(JsonObject response) {
        return response.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString();
    }

    // ---- tools/list ----
    @Test
    void toolsListOnASessionUrlAnswersFromThatSessionsOwnToolSet() throws Exception {
        List<String> forA = toolNames(post("/mcp/claude/" + ROUTE_A, rpc("tools/list", "{}")));
        List<String> forB = toolNames(post("/mcp/claude/" + ROUTE_B, rpc("tools/list", "{}")));

        assertTrue(forA.contains("GetInstructions"), "always on");
        assertTrue(forA.contains("GetPluginVersion"));
        assertFalse(forA.contains("GetClipboard"), "session A switched the clipboard off");
        assertFalse(forA.contains("SearchInFiles"), "session A switched text search off");
        assertEquals(4, forB.size(), "session B has every tool: " + forB);
        assertTrue(forB.contains("GetClipboard"));
    }

    @Test
    void toolsListDescriptionsOmitSentencesNamingAToolTheSessionCannotSee() throws Exception {
        JsonObject forA = post("/mcp/claude/" + ROUTE_A, rpc("tools/list", "{}"));
        JsonObject forB = post("/mcp/claude/" + ROUTE_B, rpc("tools/list", "{}"));

        assertEquals("Returns the plugin version. Cheap to call.",
                     toolNamed(forA, "GetPluginVersion").get("description").getAsString());
        assertEquals(PLUGIN_VERSION_DESCRIPTION,
                     toolNamed(forB, "GetPluginVersion").get("description").getAsString(),
                     "session B can see the clipboard tool, so nothing is omitted for it");
    }

    @Test
    void toolsListFailsClosedForAnUnknownRoutingId() throws Exception {
        JsonObject response = post("/mcp/claude/no-such-routing-id", rpc("tools/list", "{}"));

        assertFalse(response.has("result"), "a stale address must never be served the per-type list");
        assertEquals(-32600, errorCode(response));
        assertTrue(errorMessage(response).contains("Unknown MCP endpoint"), errorMessage(response));
    }

    @Test
    void aRoutingIdOfTheWrongTypeFailsClosed() throws Exception {
        JsonObject response = post("/mcp/grok/" + ROUTE_A, rpc("tools/list", "{}"));

        assertFalse(response.has("result"));
        assertEquals(-32600, errorCode(response));
    }

    @Test
    void aMalformedAddressFailsClosedRatherThanFallingBackToTheLegacyList() throws Exception {
        for (String path : new String[]{"/mcp/claude/", "/mcp/claude/" + ROUTE_A + "/extra"}) {
            JsonObject response = post(path, rpc("tools/list", "{}"));
            assertFalse(response.has("result"), path);
            assertEquals(-32600, errorCode(response), path);
        }
    }

    @Test
    void aDuplicateRoutingIdFailsClosedForDiscoveryAndCalls() throws Exception {
        sessionB.settings().setMcpRoutingId(ROUTE_A);
        String path = "/mcp/claude/" + ROUTE_A;

        JsonObject list = post(path, rpc("tools/list", "{}"));
        assertFalse(list.has("result"), "an ambiguous address must not disclose either session's tools");
        assertEquals(-32600, errorCode(list));
        assertTrue(errorMessage(list).contains("Unknown MCP endpoint"), errorMessage(list));

        JsonObject call = post(path, rpc("tools/call", callParams("GetPluginVersion", sessionA)));
        assertFalse(call.has("result"), "a valid credential cannot use an ambiguous address");
        assertEquals(-32600, errorCode(call));
        assertTrue(errorMessage(call).contains("Unknown MCP endpoint"), errorMessage(call));
    }

    @Test
    void wrappersForOneAiSessionDoNotMakeItsRoutingAddressAmbiguous() throws Exception {
        TestSession wrapper = new TestSession("endpoint-test-session-a-wrapper", sessionA.getAiSession(),
                                              sessionA.getMcpToolHandlers());
        SessionRegistry.register(wrapper);
        try {
            JsonObject list = post("/mcp/claude/" + ROUTE_A, rpc("tools/list", "{}"));
            assertTrue(list.has("result"), "aliases around one session still resolve the route");

            JsonObject call = post("/mcp/claude/" + ROUTE_A,
                                   rpc("tools/call", callParams("GetPluginVersion", sessionA)));
            assertEquals("ran", resultText(call), "the authenticated owner is the same underlying AiSession");
        }
        finally {
            SessionRegistry.unregister(wrapper);
        }
    }

    @Test
    void theLegacyTypeUrlStaysUnfilteredWhateverSessionsExist() throws Exception {
        McpInstructionRegistry.registerHandlers(AiTypeEnum.CLAUDE, allHandlers());

        List<String> names = toolNames(post("/mcp/claude", rpc("tools/list", "{}")));

        assertEquals(4, names.size(), "the transition path is not narrowed: " + names);
        assertTrue(names.contains("GetClipboard"));
    }

    // ---- initialize ----
    @Test
    void initializeOnASessionUrlOmitsInstructionLinesNamingHiddenTools() throws Exception {
        String forA = post("/mcp/claude/" + ROUTE_A, rpc("initialize", "{}"))
                .getAsJsonObject("result").get("instructions").getAsString();
        String forB = post("/mcp/claude/" + ROUTE_B, rpc("initialize", "{}"))
                .getAsJsonObject("result").get("instructions").getAsString();

        assertTrue(forB.contains("SearchInFiles"), "the stub's read/search line names SearchInFiles");
        assertFalse(forA.contains("SearchInFiles"), "session A cannot see SearchInFiles");
        assertTrue(forA.contains("GetInstructions"), "always-on guidance is kept");
    }

    @Test
    void initializeFailsClosedForAnUnknownRoutingId() throws Exception {
        JsonObject response = post("/mcp/claude/no-such-routing-id", rpc("initialize", "{}"));

        assertFalse(response.has("result"));
        assertEquals(-32600, errorCode(response));
    }

    // ---- tools/call ----
    @Test
    void aCallOnTheCallersOwnUrlToAnEnabledToolRuns() throws Exception {
        JsonObject response = post("/mcp/claude/" + ROUTE_A, rpc("tools/call", callParams("GetPluginVersion", sessionA)));

        assertEquals("ran", resultText(response));
    }

    @Test
    void aCallWithValidCredentialsOnAnotherSessionsUrlIsRejected() throws Exception {
        JsonObject response = post("/mcp/claude/" + ROUTE_B, rpc("tools/call", callParams("GetPluginVersion", sessionA)));

        assertFalse(response.has("result"), "session A must not act through session B's address");
        assertEquals(-32600, errorCode(response));
        assertTrue(errorMessage(response).contains("different session"), errorMessage(response));
    }

    @Test
    void aCallToAHiddenToolIsRejectedEvenThoughTheHandlerExists() throws Exception {
        JsonObject response = post("/mcp/claude/" + ROUTE_A, rpc("tools/call", callParams("GetClipboard", sessionA)));

        assertFalse(response.has("result"));
        assertEquals(-32601, errorCode(response));
        assertTrue(errorMessage(response).contains("GetClipboard"), errorMessage(response));
        assertTrue(errorMessage(response).contains("The 'Clipboard' tool group is switched off"),
                   "an MCP-layer refusal says which switch to flip: " + errorMessage(response));
    }

    @Test
    void aToolRefusedByItsConfigSettingNamesTheSettingNotTheMcpSwitch() throws Exception {
        sessionB.settings().setEnableClipboardAccess(false);

        JsonObject response = post("/mcp/claude/" + ROUTE_B, rpc("tools/call", callParams("GetClipboard", sessionB)));

        assertFalse(response.has("result"));
        assertEquals(-32601, errorCode(response));
        assertTrue(errorMessage(response).contains("Clipboard access is disabled"), errorMessage(response));
        assertFalse(errorMessage(response).contains("tool group is switched off"), errorMessage(response));
    }

    @Test
    void theSameToolRunsForASessionThatHasItEnabled() throws Exception {
        JsonObject response = post("/mcp/claude/" + ROUTE_B, rpc("tools/call", callParams("GetClipboard", sessionB)));

        assertEquals("ran", resultText(response));
    }

    @Test
    void switchingAGroupOffRefusesTheNextCallWithoutANewList() throws Exception {
        String path = "/mcp/claude/" + ROUTE_B;
        assertEquals("ran", resultText(post(path, rpc("tools/call", callParams("GetClipboard", sessionB)))));

        sessionB.settings().setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.CLIPBOARD)));

        JsonObject refused = post(path, rpc("tools/call", callParams("GetClipboard", sessionB)));
        assertFalse(refused.has("result"), "enforced at once from live settings");
        assertEquals(-32601, errorCode(refused));
    }

    @Test
    void aCallOnAnAddressNamingNoSessionIsRejected() throws Exception {
        JsonObject response = post("/mcp/claude/no-such-routing-id",
                                   rpc("tools/call", callParams("GetPluginVersion", sessionA)));

        assertFalse(response.has("result"));
        assertEquals(-32600, errorCode(response));
    }

    @Test
    void theLegacyPathAlsoRefusesACallToAToolDisabledForTheSession() throws Exception {
        JsonObject response = post("/mcp/claude", rpc("tools/call", callParams("GetClipboard", sessionA)));

        assertFalse(response.has("result"), "legacy discovery remains unfiltered, but calls must honour session access");
        assertEquals(-32601, errorCode(response));
        assertTrue(errorMessage(response).contains("GetClipboard"), errorMessage(response));
    }
}
