package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.ui;

/**
 * Info bar callbacks for Ollama. Mirrors ClaudeInfoBarListener and
 * GithubCopilotInfoBarListener. Model changes keep their existing
 * addModelChangeListener(ActionListener) route — adding a typed method here
 * would give two paths to the same behaviour.
 */
public interface OllamaInfoBarListener {

    void onClearRequested();

    void onCompactRequested();
}
