package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
 * Pins the redaction of the "Skipping unparseable line" warning: the raw stream line can carry this session's secretKey
 * verbatim (Claude embeds it in tool-call payloads), so the log site must pass the line through
 * {@code McpHookServerUtil.redactAllSecrets} before formatting it into the record. Reverting that wrapper puts the live
 * session's secret straight into the captured warning and turns the first test red.
 */
class ClaudeStreamJsonParserRedactionTest {

    private static final String SESSION_ID = "redact-parser-ses";

    private String secret;
    private WarningCapture warnings;

    @BeforeEach
    void registerSessionAndAttachCapture() {
        secret = registerLiveSession(SESSION_ID);
        warnings = new WarningCapture();
        Logger.getLogger(ClaudeStreamJsonParser.class.getName()).addHandler(warnings);
    }

    @AfterEach
    void detachCaptureAndUnregister() {
        Logger.getLogger(ClaudeStreamJsonParser.class.getName()).removeHandler(warnings);
        SessionRegistry.unregister(SESSION_ID);
    }

    /**
     * Registers an {@code AbstractAiSession} whose backing {@link AiSession} carries the freshly generated secret the
     * value-based half of {@code redactAllSecrets} matches against, and returns that secret.
     */
    private static String registerLiveSession(String id) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
        AiSession session = new AiSession(id, "RedactionProbe", null, AiTypeEnum.CLAUDE, null, settings,
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

    private static class WarningCapture extends Handler {

        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        boolean anyContains(String fragment) {
            return records.stream().map(WarningCapture::render)
                    .anyMatch(text -> text.contains(fragment));
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

    @Test
    void unparseableLine_carryingSessionSecret_isLoggedRedacted() {
        assertTrue(secret != null && !secret.isBlank(), "probe session must have a real secret");

        String poisoned = "{\"type\":\"assistant\",\"message\":{\"content\":\""
                + secret + "\", \"broken";
        new ClaudeStreamJsonParser(event -> {
        }).parseLine(poisoned);

        assertTrue(warnings.anyContains("Skipping unparseable line"),
                "the malformed line must still be reported");
        assertFalse(warnings.anyContains(secret),
                "the raw session secret must never reach the log record");
    }

    @Test
    void wellFormedLine_producesNoWarning() {
        new ClaudeStreamJsonParser(event -> {
        }).parseLine("{}");

        assertTrue(warnings.records.isEmpty(),
                "a parseable line must not be treated as a parse failure");
    }
}
