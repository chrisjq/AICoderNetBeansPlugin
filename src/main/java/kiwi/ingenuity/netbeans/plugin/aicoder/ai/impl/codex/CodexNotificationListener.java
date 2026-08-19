package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.JsonObject;

/**
 * Callback for inbound {@code app-server} notifications (method, no id — no
 * reply expected). Invoked on {@link CodexJsonRpcClient}'s single-thread notify
 * executor, so implementations see notifications in arrival order and must
 * never block it (design doc §8: the same streaming-order trap that commit
 * {@code d363682} fixed for OpenCode applies here).
 */
public interface CodexNotificationListener {

    void onNotification(String method, JsonObject params);
}
