package kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BigP_1's follow-up: {@link AcpConnection}'s raw wire logging (added this round, gated on {@link
 * PluginSettings#isDebugJson()}) must route every line through {@code McpHookServerUtil.redactAllSecrets}
 * before it reaches the logger — the same requirement every other debug-JSON log site in this plugin already
 * meets. A session's secret can appear verbatim in TWO places on the wire: inside a prompt's own
 * identity-block text ({@code "secretKey: <value>"}, sent every turn — see {@code ContextProvider
 * .buildIdentityBlock}), and inside a Grok {@code use_tool} call's {@code rawInput.tool_input} (the
 * credentials it hands the wrapped plugin tool). Both must be masked.
 */
class AcpConnectionRawLoggingRedactionTest {

    private static final String SESSION_ID = "redact-acpconn-ses";

    private String secret;
    private LogCapture capture;
    private boolean previousDebug;

    @BeforeEach
    void setUp() {
        previousDebug = PluginSettings.isDebugJson();
        PluginSettings.setDebugJson(true);
        secret = registerLiveSession(SESSION_ID);
        capture = new LogCapture();
        Logger.getLogger(AcpConnection.class.getName()).addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(AcpConnection.class.getName()).removeHandler(capture);
        SessionRegistry.unregister(SESSION_ID);
        PluginSettings.setDebugJson(previousDebug);
    }

    private static String registerLiveSession(String id) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
        AiSession session = new AiSession(id, "RedactionProbe", null, AiTypeEnum.GROK, null, settings,
                Instant.now(), Instant.now());
        SessionRegistry.register(new AbstractAiSession(session) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Map getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        });
        return session.secret();
    }

    private static final class LogCapture extends Handler {

        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        String renderAll() {
            StringBuilder sb = new StringBuilder();
            for (LogRecord record : records) {
                sb.append(render(record)).append('\n');
            }
            return sb.toString();
        }

        private static String render(LogRecord record) {
            String text = String.valueOf(record.getMessage());
            Object[] params = record.getParameters();
            if (params != null) {
                for (Object param : params) {
                    text += " " + param;
                }
            }
            return text;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopHandler implements AcpClientHandler {

        @Override
        public void onSessionUpdate(String sessionId, JsonObject update) {
        }

        @Override
        public CompletableFuture<JsonObject> onRequestPermission(JsonObject params) {
            return CompletableFuture.completedFuture(new JsonObject());
        }

        @Override
        public CompletableFuture<JsonObject> onWriteTextFile(JsonObject params) {
            return CompletableFuture.completedFuture(new JsonObject());
        }

        @Override
        public CompletableFuture<JsonObject> onReadTextFile(JsonObject params) {
            return CompletableFuture.completedFuture(new JsonObject());
        }

        @Override
        public void onDisconnected(Exception cause) {
        }
    }

    @Test
    void outboundLine_withSecretInPromptIdentityTextAndUseToolToolInput_isMaskedInBothPlaces() throws Exception {
        assertTrue(secret != null && secret.length() >= 20, "probe session must have a real, long-enough secret");

        AcpConnection conn = new AcpConnection(new ByteArrayOutputStream(), InputStream.nullInputStream(), new NoopHandler());
        try {
            // Mirrors a real turn's shape closely enough to exercise the same line: the prompt text carries
            // the identity block, and a use_tool wrapper's rawInput.tool_input carries the same credentials
            // for the wrapped plugin tool.
            JsonObject promptItem = new JsonObject();
            promptItem.addProperty("type", "text");
            promptItem.addProperty("text", "sessionId: " + SESSION_ID + "\nsecretKey: " + secret + "\n\nDo the thing.");
            JsonArray promptArray = new JsonArray();
            promptArray.add(promptItem);

            JsonObject toolInput = new JsonObject();
            toolInput.addProperty("sessionId", SESSION_ID);
            toolInput.addProperty("secretKey", secret);
            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("tool_name", "aicoder-nb-ki-plugin__GetInstructions");
            rawInput.add("tool_input", toolInput);

            JsonObject params = new JsonObject();
            params.addProperty("sessionId", "ses_fake");
            params.add("prompt", promptArray);
            params.add("rawInput", rawInput);

            conn.sendRequest(AcpMethodEnum.SESSION_PROMPT, params);

            String logged = capture.renderAll();
            assertFalse(logged.isBlank(), "the outbound line must have been logged");
            assertFalse(logged.contains(secret), "the raw secret must never reach the log record, in either shape: " + logged);
            assertTrue(logged.contains("***"), "the masked placeholder must be present where the secret was: " + logged);
        }
        finally {
            conn.close();
        }
    }
}
