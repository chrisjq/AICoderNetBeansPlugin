package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

/**
 * An OpenAI-compatible endpoint answered a request with an HTTP 500 because the MODEL's tool call could not
 * be parsed server-side (e.g. Ollama's {@code error parsing tool call: ...}). That is a recoverable model
 * mistake, not a transport failure: unlike a generic 500, the parse error can be fed back to the model as a
 * tool result so it can correct itself on the next iteration. Callers that do not implement that recovery
 * treat this as an ordinary {@link OpenAiHttpStatusException}.
 */
public class OpenAiToolCallParseException extends OpenAiHttpStatusException {

    /**
     * The server's tool-call parse failure, extracted from the error body and bounded; secrets redacted.
     * Phrased to embed in a tool-call error result aimed at the model, not at the user.
     */
    private final String parseError;

    public OpenAiToolCallParseException(int statusCode, String message, String parseError) {
        super(statusCode, message);
        this.parseError = parseError;
    }

    public String parseError() {
        return parseError;
    }
}
