package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/**
 * Callback interface for inbound ACP messages. Implementations handle
 * session/update notifications and serve agent-originated requests. All methods
 * are invoked on the AcpConnection dispatcher thread pool — never on the reader
 * thread.
 */
public interface AcpClientHandler {

    void onSessionUpdate(String sessionId, JsonObject update);

    CompletableFuture<JsonObject> onRequestPermission(JsonObject params);

    CompletableFuture<JsonObject> onWriteTextFile(JsonObject params);

    CompletableFuture<JsonObject> onReadTextFile(JsonObject params);

    void onDisconnected(Exception cause);
}
