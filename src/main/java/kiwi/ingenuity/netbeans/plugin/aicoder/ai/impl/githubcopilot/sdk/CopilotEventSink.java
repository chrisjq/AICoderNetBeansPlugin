package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

/**
 * Receives normalized Copilot session events without exposing protocol event types.
 */
public interface CopilotEventSink {

    default void onAnyEvent(String type) {
    }

    default void onToolStart(String toolName, String mcpServerName, Object arguments) {
    }

    default void onDelta(String messageId, String text) {
    }

    default void onMessage(String messageId, String content) {
    }

    default void onReasoning(String content) {
    }

    default void onStreamingDelta(Object data) {
    }

    default void onTurnEnd() {
    }

    default void onIdle() {
    }

    default void onUsage(Long currentTokens, Long tokenLimit) {
    }

    default void onSessionError(String message) {
    }

    default void onModelCallFailure(String errorMessage, String failureKind, String errorCode) {
    }

    default void onHandlerError(String eventType, Throwable error) {
    }
}
