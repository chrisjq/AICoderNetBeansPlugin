package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class McpEndpointRouteTest {

    @Test
    void aTypeOnlyPathIsTheLegacyEndpoint() {
        McpEndpointRoute route = McpEndpointRoute.parse("/mcp/claude");

        assertEquals(McpEndpointRoute.Kind.LEGACY, route.kind());
        assertEquals("claude", route.typeKey());
        assertNull(route.routingId());
    }

    @Test
    void aTypeAndRoutingIdIsASessionEndpoint() {
        McpEndpointRoute route = McpEndpointRoute.parse("/mcp/claude/3f2a-routing-id");

        assertEquals(McpEndpointRoute.Kind.SESSION, route.kind());
        assertEquals("claude", route.typeKey());
        assertEquals("3f2a-routing-id", route.routingId());
    }

    @Test
    void theBareContextPathKeepsItsOldMeaningOfNoType() {
        for (String path : new String[]{"/mcp", null, "/other"}) {
            McpEndpointRoute route = McpEndpointRoute.parse(path);
            assertEquals(McpEndpointRoute.Kind.LEGACY, route.kind(), String.valueOf(path));
            assertNull(route.typeKey(), String.valueOf(path));
        }
    }

    @Test
    void malformedAddressesAreInvalidNotLegacy() {
        // None of these may fall back to the unfiltered per-type list.
        for (String path : new String[]{"/mcp/", "/mcp/claude/", "/mcp//abc", "/mcp/claude/abc/extra",
            "/mcp/claude/abc/"}) {
            McpEndpointRoute route = McpEndpointRoute.parse(path);
            assertEquals(McpEndpointRoute.Kind.INVALID, route.kind(), path);
            assertNull(route.routingId(), path);
        }
    }
}
