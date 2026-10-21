package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

/**
 * What an MCP request path addresses: the legacy per-type endpoint {@code /mcp/<type>}, or one session's own endpoint
 * {@code /mcp/<type>/<routingId>}. Anything else under {@code /mcp} is {@link Kind#INVALID}; it is never treated as the
 * legacy endpoint, so a malformed address cannot fall open to the full tool list.
 */
record McpEndpointRoute(Kind kind, String typeKey, String routingId) {

    enum Kind {
        LEGACY,
        SESSION,
        INVALID
    }

    private static final String PREFIX = "/mcp/";

    static McpEndpointRoute parse(String path) {
        if (path == null || !path.startsWith(PREFIX)) {
            // Includes the bare "/mcp" context path, which has always answered with no type.
            return new McpEndpointRoute(Kind.LEGACY, null, null);
        }
        String[] segments = path.substring(PREFIX.length()).split("/", -1);
        if (segments.length == 1 && !segments[0].isEmpty()) {
            return new McpEndpointRoute(Kind.LEGACY, segments[0], null);
        }
        if (segments.length == 2 && !segments[0].isEmpty() && !segments[1].isEmpty()) {
            return new McpEndpointRoute(Kind.SESSION, segments[0], segments[1]);
        }
        return new McpEndpointRoute(Kind.INVALID, null, null);
    }
}
