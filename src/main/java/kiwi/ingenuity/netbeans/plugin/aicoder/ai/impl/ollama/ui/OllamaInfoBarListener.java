package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui;

/**
 * Info bar callbacks for Ollama. Mirrors ClaudeInfoBarListener and GithubCopilotInfoBarListener. Model
 * changes keep their existing addModelChangeListener(ActionListener) route — adding a typed method here would
 * give two paths to the same behaviour.
 */
public interface OllamaInfoBarListener {

    void onClearRequested();

    /**
     * @return true if the compaction actually started (posted BUSY) — false for every refusal (not running,
     *         already busy, or {@code runWork} declining because another compaction is already in flight). The bar
     *         uses this to decide whether its optimistic gauge flip is warranted, since a refusal never triggers
     *         {@code onBusyChanged} to clear it otherwise.
     */
    boolean onCompactRequested();
}
