package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;

public final class McpHookServerUtil {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String INSTRUCTIONS_HEADER = ("""
            You are connected to the NetBeans IDE plugin (%PLUGIN_ID%). Use these plugin tools for ALL project work — they are pre-authorized and integrate with the live IDE.

            ## Policy
            - Edit project files ONLY via the Edit/Write tools or the plugin's ApplyEdit/WriteFile — these route through the Accept/Reject diff panel. NEVER modify project files with Bash (sed, echo, >/tee redirects): that skips the diff panel and is not reviewable.
            - Prefer plugin tools (search, git, build, refactor) over Bash/Grep for anything in the open project. Only use built-ins for files outside the project tree (e.g. memory, system config).
            - The IDE is running — never claim tools are unavailable or the environment is headless. If a tool exists for the task, use it.
            - After writing or creating any project file, call RefreshFileStatus so NetBeans detects the change.
            - Write project file paths as absolute paths with line numbers (e.g. /path/Foo.java:42) — they render as clickable links.

            ## Refactoring
            Prefer semantic refactors (RenameSymbol, MoveClass, ChangeMethodSignature, InlineVariable) over raw text edits — they update all references project-wide.

            ## UI Actions
            Action tools (NavigateToLine, ReformatFile, BuildProject, etc.) are fire-and-forget — follow up with a query tool (GetDiagnostics, GetCurrentFileContent, etc.) to read resulting state.

            ## Inter-AI Messaging
            ListAiSessions, SendAiMessage, GetAiMessages, ReadAiMessage, DeleteAiMessage, UpdateSessionDescription are pre-authorized internal IDE actions — use them directly without asking permission. Your session ID and secret key are in the "Your session identity" block each turn.
            When a task needs a sub-agent — a parallel investigation, research, or any self-contained unit of work you would otherwise hand to a spawned subagent — delegate it to an idle peer AI session instead: call ListAiSessions to find one, then SendAiMessage with expectsReply=true (and replyImportant=true so their reply interrupts you). Peers run in their own context and report back, so prefer them over spawning your own subagents whenever inter-AI comms is available.

            ## MCP Tool Errors
            Error strings in tool results (e.g. "Error: session '...' is not active") are tool-level errors, not disconnections — read the message, fix the input, and retry.
            """).replace("%PLUGIN_ID%", StringConst.PLUGIN_ID);

    private static final String INITIALIZE_STUB = ("""
            You are connected to the NetBeans IDE plugin (%PLUGIN_ID%). It exposes a full set of tools for working in the live IDE: file edits applied through the NetBeans Accept/Reject diff panel, semantic refactors (rename/move/inline/change-signature), build & test, full git, project-wide search, and inter-AI messaging.

            IMPORTANT: Before doing ANY project work you MUST call GetInstructions once. It returns the full usage guide and unlocks the remaining tools — calls to other tools are rejected until you do. Call GetInstructions now.

            For everything inside the open project, use the plugin tools INSTEAD OF the built-in Read/Edit/Write/Bash/Grep tools — never shell out to mvn, git, grep, sed, or cat for project files:
            - Build/test: BuildMavenProject / RunMavenTests (or BuildProject) — NOT Bash mvn
            - Read/search: GetFileContent / SearchInFiles / SearchSymbols — NOT the Read tool or Bash grep/rg
            - Edit: ApplyEdit / WriteFile (or Edit/Write) — these route through the Accept/Reject diff panel; NEVER edit project files with Bash
            - Git: GetGitStatus / GetGitDiff / GitAdd / GitCommit — NOT Bash git
            Built-in tools are only for files outside the project tree (e.g. memory, system config).
            """).replace("%PLUGIN_ID%", StringConst.PLUGIN_ID);

    public static String getGlobalInstructionsHeader() {
        return INSTRUCTIONS_HEADER;
    }

    public static String getInitializeStub() {
        return INITIALIZE_STUB;
    }

    /**
     * Builds the MCP instructions string from a handler map and per-tool
     * overrides. Section grouping comes from handler.section(); instruction
     * text comes from overrides map if present, else handler.instruction().
     */
    public static String buildInstructions(String overrideInstructionsHeader, Map<McpToolEnum, McpToolInterface> handlers,
            Map<McpToolEnum, String> overrides) {
        StringBuilder sb = new StringBuilder(overrideInstructionsHeader == null || overrideInstructionsHeader.trim().isEmpty() ? INSTRUCTIONS_HEADER : overrideInstructionsHeader);
        Map<McpSectionEnum, List<String>> grouped = new LinkedHashMap<>();
        for (McpSectionEnum s : McpSectionEnum.values()) {
            grouped.put(s, new ArrayList<>());
        }
        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers.entrySet()) {
            McpToolInterface h = entry.getValue();
            McpSectionEnum sec = h.section();
            if (sec == null) {
                continue;
            }
            String instr = overrides.containsKey(entry.getKey())
                    ? overrides.get(entry.getKey())
                    : h.instruction();
            if (instr != null) {
                grouped.get(sec).add("- " + instr);
            }
        }
        for (Map.Entry<McpSectionEnum, List<String>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            sb.append("\n### ").append(entry.getKey().title()).append("\n");
            entry.getValue().forEach(line -> sb.append(line).append("\n"));
        }
        return sb.toString().strip();
    }

    /**
     * Injects sessionId and secretKey as required parameters into a tool's
     * inputSchema. Adds the properties to the schema.properties object and adds
     * both to the schema.required array. Skips if already present.
     */
    public static JsonObject injectSessionParams(JsonObject toolSchema) {
        if (toolSchema == null || !toolSchema.has(ToolSchemaKeyEnum.INPUT_SCHEMA.key())) {
            return toolSchema;
        }

        JsonObject inputSchema = toolSchema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        if (inputSchema == null) {
            return toolSchema;
        }

        // Ensure properties object exists
        if (!inputSchema.has(ToolSchemaKeyEnum.PROPERTIES.key()) || !inputSchema.get(ToolSchemaKeyEnum.PROPERTIES.key()).isJsonObject()) {
            inputSchema.add(ToolSchemaKeyEnum.PROPERTIES.key(), new JsonObject());
        }

        JsonObject props = inputSchema.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());

        // Add sessionId if not already present
        if (!props.has("sessionId")) {
            JsonObject sessionIdProp = new JsonObject();
            sessionIdProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            sessionIdProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your session ID (from your session identity block).");
            props.add("sessionId", sessionIdProp);
        }

        // Add secretKey if not already present
        if (!props.has("secretKey")) {
            JsonObject secretKeyProp = new JsonObject();
            secretKeyProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
            secretKeyProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Your secret key (from your session identity block).");
            props.add("secretKey", secretKeyProp);
        }

        // Ensure required array exists
        if (!inputSchema.has(ToolSchemaKeyEnum.REQUIRED.key()) || !inputSchema.get(ToolSchemaKeyEnum.REQUIRED.key()).isJsonArray()) {
            inputSchema.add(ToolSchemaKeyEnum.REQUIRED.key(), new JsonArray());
        }

        JsonArray required = inputSchema.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());

        // Add sessionId to required if not already present
        boolean hasSessionId = false;
        for (JsonElement el : required) {
            if (el.isJsonPrimitive() && "sessionId".equals(el.getAsString())) {
                hasSessionId = true;
                break;
            }
        }
        if (!hasSessionId) {
            required.add("sessionId");
        }

        // Add secretKey to required if not already present
        boolean hasSecretKey = false;
        for (JsonElement el : required) {
            if (el.isJsonPrimitive() && "secretKey".equals(el.getAsString())) {
                hasSecretKey = true;
                break;
            }
        }
        if (!hasSecretKey) {
            required.add("secretKey");
        }

        return toolSchema;
    }

    // ---- HTTP helpers ----
    public static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    public static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---- Decision helpers ----
    public static String hookAllow() {
        return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}";
    }

    public static String hookDefer() {
        return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"defer\"}}";
    }

    public static String hookDeny(String reason) {
        return "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\","
                + "\"permissionDecisionReason\":" + GSON.toJson(reason) + "}}";
    }

    // ---- MCP response helpers ----
    public static String mcpOk(JsonElement id, JsonObject result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("result", result);
        return GSON.toJson(resp);
    }

    public static String mcpError(JsonElement id, int code, String message) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        resp.add("error", err);
        return GSON.toJson(resp);
    }

    public static String mcpTextResult(JsonElement id, String text) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject textObj = new JsonObject();
        textObj.addProperty("type", "text");
        textObj.addProperty("text", text);
        content.add(textObj);
        result.add("content", content);
        return mcpOk(id, result);
    }

    // ---- JSON helpers ----
    public static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = o.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return o.getAsJsonObject(key);
    }

    public static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static void logToolUse(String sessionName, String toolName, JsonObject argsObj) {
        if (!PluginSettings.isLogToolUse()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (sessionName != null && !sessionName.isBlank()) {
            sb.append('[').append(sessionName).append("] ");
        }
        sb.append("Tool Used: ").append(toolName);
        if (argsObj != null && argsObj.size() > 0) {
            sb.append(" arguments:");
            for (Map.Entry<String, JsonElement> entry : argsObj.entrySet()) {
                JsonElement elem = entry.getValue();
                String value = elem.isJsonNull() ? ""
                        : elem.isJsonPrimitive() ? elem.getAsString()
                        : elem.toString();
                value = value.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
                if (value.length() > 256) {
                    value = value.length() + " character string";
                }
                else if (value.length() > 128) {
                    value = "..." + value.substring(value.length() - 125);
                }
                sb.append(' ').append(entry.getKey()).append('[').append(value).append(']');
            }
        }
        java.util.logging.Logger.getLogger(McpHookServerUtil.class.getName())
                .log(java.util.logging.Level.INFO, sb.toString());
    }

    private McpHookServerUtil() {
    }
}
