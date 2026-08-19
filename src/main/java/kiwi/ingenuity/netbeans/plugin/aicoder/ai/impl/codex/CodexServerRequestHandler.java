package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

/**
 * Callback for inbound {@code app-server} requests — method plus id, a reply is
 * required. This is the genuinely new shape versus ACP (design doc §6): Codex's
 * server can call back into the client and block awaiting an answer (Guardian
 * approval review is the first consumer, added in a later slice). Invoked on
 * {@link CodexJsonRpcClient}'s dispatch executor, never on the reader thread,
 * so a slow or blocking implementation cannot stall inbound message processing.
 *
 * <p>
 * Unlike ACP's closed, enum-keyed set of inbound methods, Codex's
 * server-request surface is not yet pinned down (design doc §0a: exact
 * approval-request shape is still open) and the schema set is far larger, so
 * {@code method} is a raw wire string rather than an enum constant — narrowing
 * it to specific methods is deferred to the slice that actually consumes them.
 */
public interface CodexServerRequestHandler {

    CompletableFuture<JsonObject> onServerRequest(String method, JsonObject params);
}
