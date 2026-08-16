package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

/**
 * JSON-RPC error codes. These are integers, not strings.
 */
public enum AcpErrorCodeEnum {
    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603),
    REQUEST_CANCELLED(-32800),
    AUTH_REQUIRED(-32000),
    RESOURCE_NOT_FOUND(-32002);

    /**
     * Resolve an error code to its enum constant. Returns null if the code is
     * not recognised — the protocol adds new codes over time and must not fail
     * on unknown codes.
     */
    public static AcpErrorCodeEnum fromCode(int code) {
        for (AcpErrorCodeEnum v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        return null;
    }

    private final int code;

    AcpErrorCodeEnum(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

}
