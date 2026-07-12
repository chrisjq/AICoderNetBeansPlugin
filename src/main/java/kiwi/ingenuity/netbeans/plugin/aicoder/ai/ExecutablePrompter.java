package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.concurrent.CompletableFuture;

/**
 * UI-side hook letting an {@link AiImplementation} ask the user to locate a
 * missing CLI executable, without needing to know about Swing/EDT itself.
 * Implemented by the AI chat tab; always shows the dialog on the EDT internally
 * regardless of which thread calls it. The returned future lets the calling
 * (typically background) thread block until the user responds.
 */
public interface ExecutablePrompter {

    /**
     * Shows a file-chooser dialog for the user to locate an executable.
     *
     * @param dialogTitle e.g. "Locate claude executable"
     * @param executableName e.g. "claude" — the file filter accepts a file
     * named exactly this, or starting with "{@code <executableName>.}"
     * @return a future completing with the chosen absolute path, or
     * {@code null} if the user cancelled
     */
    CompletableFuture<String> promptForExecutable(String dialogTitle, String executableName);
}
