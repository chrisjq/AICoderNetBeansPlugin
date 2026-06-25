package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui;

public interface ClaudeInfoBarListener {

    void onCompactRequested();

    void onModelChanged(String model);
}
