package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

/**
 * Typed exception for {@code app-server} JSON-RPC error responses. Carries the
 * numeric JSON-RPC error code so callers can distinguish protocol-level
 * outcomes from generic failures.
 */
public class CodexJsonRpcException extends RuntimeException {

    private final int code;

    public CodexJsonRpcException(int code, String message) {
        super("Codex app-server error " + code + ": " + message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
