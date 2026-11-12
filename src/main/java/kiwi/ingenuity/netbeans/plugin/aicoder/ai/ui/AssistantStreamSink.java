package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

/**
 * Receives the display operations produced by an assistant response stream.
 */
interface AssistantStreamSink {

    void beginAssistantMessage();

    void appendDelta(String text);

    /**
     * Finalises the current assistant message. Implementations remove it when it contains no visible text.
     */
    void finaliseAssistantMessage();

    void addSystemMessage(String text);
}
