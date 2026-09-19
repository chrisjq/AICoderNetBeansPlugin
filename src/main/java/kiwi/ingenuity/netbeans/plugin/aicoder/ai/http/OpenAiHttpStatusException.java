package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

import java.io.IOException;

/**
 * An OpenAI-compatible endpoint returned a non-2xx HTTP status. A plain {@link IOException} carries no structured
 * status code, so callers that need to react to a specific range (e.g. Ollama retrying once without
 * {@code reasoning_effort} on a 4xx) could not tell the failure apart from a network error or a malformed stream.
 */
public class OpenAiHttpStatusException extends IOException {

    private final int statusCode;

    public OpenAiHttpStatusException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
