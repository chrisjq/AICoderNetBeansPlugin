package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ErrorMapperTest {

    private static final String URL = "http://localhost:11434";
    private static final String MODEL = "llama3.2";

    @Test
    @Timeout(5)
    void rejectedKeyWhenAKeyWasSent() {
        String message = "The server rejected the API key. Check the API key in session settings.";
        for (int status : new int[]{401, 403}) {
            assertMapped(status, "{\"error\":{\"message\":\"Incorrect API key\",\"code\":\"invalid_api_key\"}}",
                         ErrorMapper.KeySituation.SENT, MappedError.Kind.KEY_REJECTED, message, null, null);
            assertMapped(status, "Incorrect API key",
                         ErrorMapper.KeySituation.SENT, MappedError.Kind.KEY_REJECTED, message, null, null);
        }
    }

    @Test
    @Timeout(5)
    void keyringUnavailableUsesTheKeyringMessage() {
        String message = "The API key could not be read from the system keyring (it may be locked).";
        for (int status : new int[]{401, 403}) {
            assertMapped(status, "{\"error\":{\"message\":\"unauthorized\"}}",
                         ErrorMapper.KeySituation.NOT_SENT_UNAVAILABLE, MappedError.Kind.NO_KEY_SENT, message, null, null);
            assertMapped(status, "unauthorized",
                         ErrorMapper.KeySituation.NOT_SENT_UNAVAILABLE, MappedError.Kind.NO_KEY_SENT, message, null, null);
        }
    }

    @Test
    @Timeout(5)
    void keyRequiredWhenNoneIsConfigured() {
        String message = "This server requires an API key — enter it in session settings.";
        for (int status : new int[]{401, 403}) {
            assertMapped(status, "{\"error\":{\"message\":\"missing key\"}}",
                         ErrorMapper.KeySituation.NONE, MappedError.Kind.KEY_REQUIRED, message, null, null);
            assertMapped(status, "missing key",
                         ErrorMapper.KeySituation.NONE, MappedError.Kind.KEY_REQUIRED, message, null, null);
        }
    }

    @Test
    @Timeout(5)
    void noKeyConfiguredAndDefaultForAnotherServerMapToNoKeySent() {
        String message = "No API key was sent — set a session key, or use a default key for this server.";
        for (ErrorMapper.KeySituation situation : new ErrorMapper.KeySituation[]{
            ErrorMapper.KeySituation.NO_KEY_CONFIGURED,
            ErrorMapper.KeySituation.DEFAULT_KEY_OTHER_SERVER}) {
            for (int status : new int[]{401, 403}) {
                assertMapped(status, "unauthorized", situation, MappedError.Kind.NO_KEY_SENT, message, null, null);
            }
        }
    }

    @Test
    @Timeout(5)
    void pathNotFoundWhenThe404DoesNotMentionTheModel() {
        String message = "The server has no /v1/chat/completions endpoint at " + URL + " — check the base URL.";
        assertMapped(404, "{\"error\":{\"message\":\"Not Found\"}}",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.PATH_NOT_FOUND, message, null, null);
        assertMapped(404, "404 page not found",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.PATH_NOT_FOUND, message, null, null);
    }

    @Test
    @Timeout(5)
    void modelNotFoundFromStatusCodeOrText() {
        String message = "Model " + MODEL + " is not available on the server — for Ollama run `ollama pull "
                + MODEL + "`, or load the model in LM Studio.";
        assertMapped(404, "{\"error\":{\"message\":\"model '" + MODEL + "' not found\"}}",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.MODEL_NOT_FOUND, message, null, null);
        assertMapped(404, "model '" + MODEL + "' not found",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.MODEL_NOT_FOUND, message, null, null);
        assertMapped(400, "{\"error\":{\"message\":\"The model does not exist\",\"code\":\"model_not_found\"}}",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.MODEL_NOT_FOUND, message, null, null);
        assertMapped(404, "no such model " + MODEL,
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.MODEL_NOT_FOUND, message, null, null);
    }

    @Test
    @Timeout(5)
    void rateLimitedLeavesTheRetryPlaceholder() {
        String message = "The server is rate-limiting requests (retrying in N s).";
        assertMapped(429, "{\"error\":{\"message\":\"slow down\"}}",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.RATE_LIMITED, message, null, null);
        assertMapped(429, "slow down",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.RATE_LIMITED, message, null, null);
    }

    @Test
    @Timeout(5)
    void quotaFrom402OrInsufficientQuota() {
        assertMapped(402, "{\"error\":{\"message\":\"payment required\"}}",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.QUOTA,
                     "Quota or billing issue: payment required", null, null);
        assertMapped(402, "payment required",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.QUOTA,
                     "Quota or billing issue: payment required", null, null);
        assertMapped(429, "{\"error\":{\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.QUOTA,
                     "Quota or billing issue: You exceeded your current quota", null, null);
    }

    @Test
    @Timeout(5)
    void rejectedFieldNamesTheOptionalField() {
        String[] fields = {"stream_options", "reasoning_effort", "response_format", "max_tokens"};
        for (String field : fields) {
            assertMapped(400, "{\"error\":{\"message\":\"bad\",\"param\":\"" + field + "\"}}",
                         ErrorMapper.KeySituation.NONE, MappedError.Kind.REJECTED_FIELD, "bad", field, null);
            assertMapped(422, "Unsupported parameter: '" + field + "'",
                         ErrorMapper.KeySituation.NONE, MappedError.Kind.REJECTED_FIELD,
                         "Unsupported parameter: '" + field + "'", field, null);
        }
        assertMapped(400, "unknown max_tokens_to_sample",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.UNNAMED_REJECTION,
                     "unknown max_tokens_to_sample", null, null);
    }

    @Test
    @Timeout(5)
    void unnamedRejectionWhenNoFieldIsNamed() {
        assertMapped(400, "{\"error\":{\"message\":\"something else went wrong\"}}",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.UNNAMED_REJECTION,
                     "something else went wrong", null, null);
        assertMapped(422, "something else went wrong",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.UNNAMED_REJECTION,
                     "something else went wrong", null, null);
    }

    @Test
    @Timeout(5)
    void toolsUnsupportedKeepsTheServerMessage() {
        String[] phrases = {
            "does not support tools",
            "tools param requires --jinja flag",
            "Unsupported param: tools"
        };
        for (String phrase : phrases) {
            assertMapped(400, "{\"error\":{\"message\":\"" + phrase + "\"}}",
                         ErrorMapper.KeySituation.NONE, MappedError.Kind.TOOLS_UNSUPPORTED, phrase, null, null);
            assertMapped(400, phrase,
                         ErrorMapper.KeySituation.NONE, MappedError.Kind.TOOLS_UNSUPPORTED, phrase, null, null);
        }
    }

    @Test
    @Timeout(5)
    void contextOverflowCapturesTheStatedLimit() {
        String json = "{\"error\":{\"message\":\"request exceeds the available context size\","
                + "\"type\":\"exceed_context_size_error\",\"n_ctx\":4096}}";
        assertMapped(400, json, ErrorMapper.KeySituation.NONE, MappedError.Kind.CONTEXT_OVERFLOW,
                     "request exceeds the available context size", null, 4096);

        String plain = "This model's maximum context length is 8192 tokens.";
        assertMapped(400, plain, ErrorMapper.KeySituation.NONE, MappedError.Kind.CONTEXT_OVERFLOW,
                     plain, null, 8192);

        assertMapped(413, "payload too large", ErrorMapper.KeySituation.NONE, MappedError.Kind.CONTEXT_OVERFLOW,
                     "payload too large", null, null);
    }

    @Test
    @Timeout(5)
    void serverErrorUsesTheServerMessageTrimmedTo500() {
        String longText = "e".repeat(600);
        MappedError fromJson = ErrorMapper.map(500, "{\"error\":{\"message\":\"" + longText + "\"}}",
                                               ErrorMapper.KeySituation.NONE, URL, MODEL);
        assertEquals(MappedError.Kind.SERVER_ERROR, fromJson.kind());
        assertEquals(longText.substring(0, 500), fromJson.message());
        assertNull(fromJson.rejectedField());
        assertNull(fromJson.statedContextLimit());

        MappedError fromPlain = ErrorMapper.map(503, "  " + longText + "  ",
                                                ErrorMapper.KeySituation.NONE, URL, MODEL);
        assertEquals(MappedError.Kind.SERVER_ERROR, fromPlain.kind());
        assertEquals(longText.substring(0, 500), fromPlain.message());

        assertMapped(502, "{\"error\":{\"message\":\"overloaded\"}}",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.SERVER_ERROR, "overloaded", null, null);
        assertMapped(500, "overloaded",
                     ErrorMapper.KeySituation.NONE, MappedError.Kind.SERVER_ERROR, "overloaded", null, null);
    }

    @Test
    @Timeout(5)
    void tlsFailureNamesTheHost() {
        String message = "The server's certificate is not trusted (llm.example.com). Check the URL, or add the "
                + "certificate to Java's trust store.";
        assertException(new SSLHandshakeException("bad"), message);
        assertException(new IOException("wrap", new SSLException("bad")), message);
    }

    @Test
    @Timeout(5)
    void connectionFailedNamesTheUrl() {
        String message = "Cannot reach the server at " + URL + " — is it running?";
        assertConnection(new ConnectException("refused"), message);
        assertConnection(new UnknownHostException("nope"), message);
        assertConnection(new HttpConnectTimeoutException("timeout"), message);
        assertConnection(new IOException("wrap", new ConnectException("refused")), message);
    }

    private static void assertMapped(int status, String body, ErrorMapper.KeySituation keys, MappedError.Kind kind,
                                     String message, String field, Integer limit) {
        MappedError error = ErrorMapper.map(status, body, keys, URL, MODEL);
        assertEquals(kind, error.kind());
        assertEquals(message, error.message());
        assertEquals(field, error.rejectedField());
        assertEquals(limit, error.statedContextLimit());
    }

    private static void assertException(Throwable failure, String message) {
        MappedError error = ErrorMapper.mapException(failure, "https://llm.example.com/v1");
        assertEquals(MappedError.Kind.TLS, error.kind());
        assertEquals(message, error.message());
        assertNull(error.rejectedField());
        assertNull(error.statedContextLimit());
    }

    private static void assertConnection(Throwable failure, String message) {
        MappedError error = ErrorMapper.mapException(failure, URL);
        assertEquals(MappedError.Kind.CONNECTION_FAILED, error.kind());
        assertEquals(message, error.message());
        assertNull(error.rejectedField());
        assertNull(error.statedContextLimit());
    }
}
