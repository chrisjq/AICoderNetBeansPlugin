package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

/**
 * Callback fired when {@link CodexJsonRpcClient}'s reader loop ends (EOF or I/O
 * error), before {@link CodexJsonRpcClient#close()} has necessarily been
 * called. Invoked on the notify executor, same as
 * {@link CodexNotificationListener}.
 */
public interface CodexConnectionListener {

    void onDisconnected(Exception cause);
}
