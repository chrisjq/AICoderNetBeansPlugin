/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

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

    private StatusMessageUtil() {
    }
}
