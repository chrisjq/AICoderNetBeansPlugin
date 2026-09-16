package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * Told about every build that finishes, whatever its result, including builds cancelled before they started.
 */
@FunctionalInterface
public interface BuildCompletionListener {

    void onFinished(BuildJob job);
}
