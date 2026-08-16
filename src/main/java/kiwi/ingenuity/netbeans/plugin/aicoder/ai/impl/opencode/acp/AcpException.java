package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

/**
 * Typed exception for ACP (Agent Client Protocol) error responses. Carries the
 * numeric JSON-RPC error code so callers can distinguish protocol-level outcomes
 * (e.g. REQUEST_CANCELLED -32800, AUTH_REQUIRED -32000) from generic failures.
 */
public class AcpException extends RuntimeException {

    private final int code;

    public AcpException(int code, String message) {
        super("ACP error " + code + ": " + message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
