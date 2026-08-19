package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

/**
 * Standard JSON-RPC 2.0 error codes. Codex's own error taxonomy
 * ({@code CodexErrorInfo} in the generated schemas) rides inside the JSON-RPC
 * {@code error.data} field, not the {@code error.code} — these are just the
 * protocol-level codes. These are integers, not strings.
 */
public enum CodexJsonRpcErrorCodeEnum {
    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603);

    /**
     * Resolve an error code to its enum constant. Returns null if the code is
     * not recognised — the protocol adds new codes over time and must not fail
     * on unknown codes.
     */
    public static CodexJsonRpcErrorCodeEnum fromCode(int code) {
        for (CodexJsonRpcErrorCodeEnum v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        return null;
    }

    private final int code;

    CodexJsonRpcErrorCodeEnum(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
