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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;

public final class McpHookServerUtil {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String getGlobalInstructionsHeader(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.HEADER)) {
            return "";
        }
        boolean mcpOnly = options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS);
        boolean hasCreds = options.contains(McpInstructionOptionEnum.CREDENTIALS);
        StringBuilder sb = new StringBuilder();
        if (mcpOnly) {
            sb.append("You are connected to the NetBeans IDE plugin (").append(StringConst.PLUGIN_ID)
                    .append("). Use these plugin tools for ALL project work — they are the only tools available.");
        }
        else {
            sb.append("You are connected to the NetBeans IDE plugin (").append(StringConst.PLUGIN_ID)
                    .append("). Use these plugin tools for ALL project work — they are pre-authorized and integrate with the live IDE.");
        }
        sb.append("\n\n## Policy\n");
        if (!mcpOnly) {
            sb.append("- Edit project files ONLY via the Edit/Write tools or the plugin's ApplyEdit/WriteFile — these route through the Accept/Reject diff panel. NEVER modify project files with Bash (sed, echo, >/tee redirects): that skips the diff panel and is not reviewable.\n");
            sb.append("- Prefer plugin tools (search, git, build, refactor) over Bash/Grep for anything in the open project. Only use built-ins for files outside the project tree (e.g. memory, system config).\n");
        }
        else {
            sb.append("- Use plugin tools for ALL project work — they are the only tools available.\n");
        }
        sb.append("- The IDE is running — never claim tools are unavailable or the environment is headless. If a tool exists for the task, use it.\n");
        sb.append("- After writing or creating any project file, call RefreshFileStatus so NetBeans detects the change.\n");
        sb.append("- Write project file paths as absolute paths with line numbers (e.g. /path/Foo.java:42) — they render as clickable links.");
        if (hasCreds) {
            sb.append("\n- Your session ID and secret key are in the \"Your session identity\" block each turn.");
        }
        sb.append("\n\n## Refactoring\nPrefer semantic refactors (RenameSymbol, MoveClass, ChangeMethodSignature, InlineVariable) over raw text edits — they update all references project-wide.");
        sb.append("\n\n## UI Actions\nAction tools (" + McpToolEnum.NAVIGATE_TO_LINE.toolName() + ", " + McpToolEnum.REFORMAT_FILE.toolName() + ", " + McpToolEnum.BUILD_PROJECT.toolName() + ", etc.) are fire-and-forget — follow up with a query tool (" + McpToolEnum.GET_DIAGNOSTICS.toolName() + ", " + McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName() + ", etc.) to read resulting state.");
        sb.append("\n\n## Inter-AI Messaging\nListAiSessions, SendAiMessage, GetAiMessages, ReadAiMessage, DeleteAiMessage, UpdateSessionDescription are pre-authorized internal IDE actions — use them directly without asking permission.");
        if (!mcpOnly) {
            sb.append("\nWhen a task needs a sub-agent/background agent — a parallel investigation, research, or any self-contained unit of work you would otherwise hand to a spawned sub-agent/background agent — delegate it to an idle peer AI session instead: call ListAiSessions to find one, then SendAiMessage with expectsReply=true (and replyImportant=true so their reply interrupts you). Peers run in their own context and report back, so prefer them over spawning your own subagents whenever inter-AI comms is available.");
        }
        // Trailing newline so the first "### <section>" heading appended by
        // buildInstructions is preceded by a blank line, as every later one is.
        sb.append("\n\n## MCP Tool Errors\nError strings in tool results (e.g. \"Error: session '...' is not active\") are tool-level errors, not disconnections — read the message, fix the input, and retry.\n");
        return sb.toString();
    }

    public static String getInitializeStub(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.HEADER)) {
            return "";
        }
        boolean mcpOnly = options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS);
        StringBuilder sb = new StringBuilder();
        if (options.contains(McpInstructionOptionEnum.FORCE_MCP_TOOL_USE)) {
            // Copilot's own answer to "what would make you use these tools?", placed
            // first so it is read before any action. It otherwise fell back to its
            // native view/edit tools despite the guidance further down.
            sb.append("Use the IDE MCP tool server for all repository, editor, build, git, refactor, "
                    + "search, and UI actions; never read or write files directly with your own "
                    + "built-in tools.\n\n");
        }
        sb.append("You are connected to the NetBeans IDE plugin (").append(StringConst.PLUGIN_ID).append("). ");
        if (mcpOnly) {
            sb.append("It exposes a full set of tools for working in the live IDE: file edits, semantic refactors, build & test, full git, project-wide search, and inter-AI messaging.");
        }
        else {
            sb.append("It exposes a full set of tools for working in the live IDE: file edits applied through the NetBeans Accept/Reject diff panel, semantic refactors (rename/move/inline/change-signature), build & test, full git, project-wide search, and inter-AI messaging.");
        }
        sb.append("\n\nIMPORTANT: Call ").append(McpToolEnum.GET_INSTRUCTIONS.toolName()).append(" FIRST — before you read, open, search, or edit any file, run a build or any git command, or take any other action to do with the open project. This comes before your very first such action, not after.");
        if (!mcpOnly) {
            sb.append(" Do it even when you would normally reach for your own built-in Read/Edit/Search tools: this project has IDE-aware equivalents that ").append(McpToolEnum.GET_INSTRUCTIONS.toolName()).append(" explains, and using your built-in tools on project files bypasses the review panel and the IDE's own view of the code.");
        }
        sb.append(" ").append(McpToolEnum.GET_INSTRUCTIONS.toolName()).append(" returns the full usage guide and unlocks the remaining tools — the other plugin tools are rejected until you call it. Call ").append(McpToolEnum.GET_INSTRUCTIONS.toolName()).append(" now.");
        if (!mcpOnly) {
            sb.append("\n\nOnce you have, for everything inside the open project use the plugin tools INSTEAD OF the built-in Read/Edit/Write/Bash/Grep tools — never shell out to mvn, git, grep, sed, or cat for project files:\n");
            sb.append("- Build/test: ").append(McpToolEnum.BUILD_MAVEN_PROJECT.toolName()).append(" / ").append(McpToolEnum.RUN_MAVEN_TESTS.toolName()).append(" (or ").append(McpToolEnum.BUILD_PROJECT.toolName()).append(") — NOT Bash mvn\n");
            sb.append("- Read/search: ").append(McpToolEnum.GET_FILE_CONTENT.toolName()).append(" / ").append(McpToolEnum.SEARCH_IN_FILES.toolName()).append(" / ").append(McpToolEnum.SEARCH_SYMBOLS.toolName()).append(" — NOT the Read tool or Bash grep/rg\n");
            sb.append("- Edit: ").append(McpToolEnum.APPLY_EDIT.toolName()).append(" / ").append(McpToolEnum.WRITE_FILE.toolName()).append(" (or Edit/Write) — these route through the Accept/Reject diff panel; NEVER edit project files with Bash\n");
            sb.append("- Git: ").append(McpToolEnum.GET_GIT_STATUS.toolName()).append(" / ").append(McpToolEnum.GET_GIT_DIFF.toolName()).append(" / ").append(McpToolEnum.GIT_ADD.toolName()).append(" / ").append(McpToolEnum.GIT_COMMIT.toolName()).append(" — NOT Bash git\n");
            sb.append("Built-in tools are only for files outside the project tree (e.g. memory, system config).");
        }
        return sb.toString();
    }

    /**
     * Builds the MCP instructions string from a handler map and per-tool
     * overrides. Section grouping comes from handler.section(); instruction
     * text comes from overrides map if present, else handler.instruction().
     */
    public static String buildInstructions(AiTypeEnum type, String overrideInstructionsHeader, Map<McpToolEnum, McpToolInterface> handlers,
            Map<McpToolEnum, String> overrides) {
        Set<McpInstructionOptionEnum> opts = type.getMcpOptions();
        StringBuilder sb = new StringBuilder(overrideInstructionsHeader == null || overrideInstructionsHeader.trim().isEmpty()
                ? getGlobalInstructionsHeader(opts) : overrideInstructionsHeader);
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
                    : h.instruction(opts);
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
        return kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas.injectCredentials(toolSchema);
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
        try {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        catch (IOException e) {
            if (isPeerDisconnect(e)) {
                return;
            }
            throw e;
        }
    }

    static boolean isPeerDisconnect(IOException e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("broken pipe")
                    || normalized.contains("connection reset")
                    || normalized.contains("forcibly closed")) {
                return true;
            }
        }
        return false;
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
                if (McpToolPropertyEnum.SECRET_KEY.key().equals(entry.getKey())) {
                    // Never write the session secret to the IDE log. sessionId is
                    // kept: it is not secret and is needed to correlate entries.
                    // Matched through the enum so renaming the property cannot
                    // silently turn this redaction off.
                    sb.append(' ').append(McpToolPropertyEnum.SECRET_KEY.key()).append("[***]");
                    continue;
                }
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
        Logger.getLogger(McpHookServerUtil.class.getName())
                .log(java.util.logging.Level.INFO, sb.toString());
    }

    private McpHookServerUtil() {
    }
}
