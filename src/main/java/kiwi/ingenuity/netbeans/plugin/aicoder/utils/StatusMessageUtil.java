/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.util.List;

/**
 *
 * @author chris
 */
public class StatusMessageUtil {

    // Status event messages
    public static String formatStartFailed(String reason) {
        return "Failed to start AI: " + reason;
    }

    public static String formatSessionNotConfigured() {
        return "No session configured — call setCurrentSession() before start()";
    }

    public static String formatMcpSetupFailed() {
        return "MCP server setup failed — hook registration unsuccessful";
    }

    public static String formatReady(String aiName) {
        return aiName + " Ready...";
    }

    public static String formatSendFailed(String reason) {
        return "Failed to send: " + reason;
    }

    public static String formatStopped() {
        return "Stopped at user's request";
    }

    public static String formatExecutableNotFound(String executablePath) {
        return "Executable not found — please set in plugin options.";
    }

    /**
     * Formats a notice for a turn that ended abnormally before completing (e.g.
     * the CLI runtime aborted the stream mid-response). The process is still
     * alive, so this is informational, not fatal — the user can simply retry.
     */
    public static String formatTurnInterrupted(String subtype) {
        String reason = (subtype == null || subtype.isBlank()) ? "an unexpected runtime error" : subtype;
        return "⚠ The response ended unexpectedly before finishing (" + reason
                + "). Any pending action from this turn was not completed — you can retry.";
    }

    /**
     * Formats an unexpected-process-exit status message, appending captured
     * stderr (if any) so the user has something to act on instead of a bare
     * exit code.
     */
    public static String formatExited(String aiName, int code, List<String> stderrLines) {
        String errText = stderrLines == null ? "" : String.join("\n", stderrLines).strip();
        return aiName + " exited (code " + code + ")" + (errText.isBlank() ? "" : ": " + errText);
    }

    private StatusMessageUtil() {
    }
}
