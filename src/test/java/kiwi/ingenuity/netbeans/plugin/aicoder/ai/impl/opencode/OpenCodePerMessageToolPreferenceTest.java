package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ContextProvider;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpClientHandler;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpConnection;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpMethodEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The MCP tool-preference sentence is OpenCode management's own business, injected in {@code sendTurn} so it rides
 * EVERY turn by construction. These tests pin the user's verbatim wording and prove it precedes the user's own prompt
 * text on every turn, while never leaking into the shared preamble machinery other backends use.
 */
class OpenCodePerMessageToolPreferenceTest {

    private static final String EXPECTED = "Use the plugin's MCP tools over internal tools.";

    /**
     * Blocks forever on read until the connection is closed. An EOF-input would make AcpConnection's reader submit
     * {@code onDisconnected} and spawn an {@code acp-notify} thread that outlives this test — and
     * {@code AcpConnectionTest.closeShutsBothExecutors} asserts (via the global thread list) that no such thread is
     * alive anywhere in the JVM, so a leaked one would fail an unrelated suite.
     */
    private static final class BlockingInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            try {
                Thread.sleep(Long.MAX_VALUE);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    private static final class CapturingConnection extends AcpConnection {

        final List<JsonObject> sentParams = new ArrayList<>();

        CapturingConnection() {
            super(new ByteArrayOutputStream(), new BlockingInputStream(), new AcpClientHandler() {
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
            });
        }

        @Override
        public CompletableFuture<JsonObject> sendRequest(AcpMethodEnum method, JsonObject params) {
            sentParams.add(params);
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }

    private static String promptTextOf(JsonObject params) {
        JsonArray prompt = params.getAsJsonArray(AcpJsonKeyEnum.PROMPT.key());
        JsonObject item = prompt.get(0).getAsJsonObject();
        return item.get(AcpJsonKeyEnum.TEXT.key()).getAsString();
    }

    private static OpenCodeAiProcessManager managerOver(CapturingConnection conn) {
        return new OpenCodeAiProcessManager(e -> {
        }) {
            {
                connection = conn;
                acpSessionId = "ses_pref";
            }
        };
    }

    @Test
    void exactSentenceIsOnEveryTurnPrecedingTheUserText() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        try {
            OpenCodeAiProcessManager manager = managerOver(conn);

            manager.sendTurn("first turn");
            manager.sendTurn("second turn");

            assertEquals(2, conn.sentParams.size(), "both turns must go out on the wire");
            for (JsonObject params : conn.sentParams) {
                assertEquals("ses_pref", params.get(AcpJsonKeyEnum.SESSION_ID.key()).getAsString());
            }
            assertEquals(EXPECTED + "\n\n" + "first turn", promptTextOf(conn.sentParams.get(0)));
            assertEquals(EXPECTED + "\n\n" + "second turn", promptTextOf(conn.sentParams.get(1)),
                    "the standing guidance must ride EVERY turn, not just the first");
        }
        finally {
            conn.close();
        }
    }

    @Test
    void guidanceLeadsAndUsersWordsSurviveVerbatim() throws Exception {
        CapturingConnection conn = new CapturingConnection();
        try {
            managerOver(conn).sendTurn("read pom.xml");

            String sent = promptTextOf(conn.sentParams.get(0));
            assertTrue(sent.startsWith(EXPECTED + "\n\n"),
                    "the guidance must come before the user's own prompt, as a header: " + sent);
            assertEquals("read pom.xml", sent.substring(EXPECTED.length() + 2),
                    "the user's own words must follow unchanged");
        }
        finally {
            conn.close();
        }
    }

    @Test
    void sentenceNeverLeaksIntoTheSharedPreamble() {
        for (AiTypeEnum type : new AiTypeEnum[]{AiTypeEnum.CLAUDE, AiTypeEnum.GROK, AiTypeEnum.OLLAMA_LOCAL}) {
            AiSession session = AiSession.create(null, type);
            ContextProvider provider = new ContextProvider(fo -> {
            });
            provider.setSession(session);
            assertFalse(provider.buildPreamble("hi", null).contains(EXPECTED),
                    "the sentence belongs to OpenCode's own manager, not the shared preamble (" + type + ")");
        }
    }
}
