package kiwi.ingenuity.netbeans.plugin.aicoder.process;

public class McpArgumentException extends Exception {

    private final int code;

    public McpArgumentException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
