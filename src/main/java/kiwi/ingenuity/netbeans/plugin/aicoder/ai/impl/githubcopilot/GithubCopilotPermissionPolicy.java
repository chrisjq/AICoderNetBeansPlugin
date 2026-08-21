package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Pure classification/formatting rules for
 * {@link GithubCopilotPermissionHandler}, kept free of the SDK's
 * CompletableFuture plumbing so the routing decision can be unit tested
 * directly — mirrors how {@code PermissionDiffPolicy} is kept free of Swing.
 *
 * <p>
 * <b>Confirmed live.</b> {@code getKind()} returns the bare kind, and for an
 * MCP tool that kind is the literal string {@code "mcp"} — not the server name,
 * and not the {@code <mcp-server-name>(tool-name?)} pattern that
 * {@code copilot help permissions} documents for the CLI's own rule syntax. The
 * server name arrives separately, as
 * {@link GithubCopilotJsonKeyEnum#SERVER_NAME} in {@code extensionData}:
 *
 * <pre>
 * kind=mcp
 * extensionData={serverName=aicoder-nb-ki-plugin,
 *                toolName=aicoder-nb-ki-plugin-GetFileContent,
 *                toolTitle=GetFileContent, args={...}, readOnly=false}
 * </pre>
 *
 * The first version matched the server name against the kind string, which
 * could never hit, so every one of this plugin's own tool calls fell through to
 * {@link Category#UNKNOWN} and prompted. That was the intended failure
 * direction — fail closed, not fail silent — and the logged {@code kind}
 * identified the real shape in a single run.
 *
 * <p>
 * A tool from some <em>other</em> MCP server still prompts: this plugin only
 * gates its own tools, so it can only vouch for its own.
 *
 * <p>
 * Substring matching is kept for the remaining categories so they work whether
 * a build returns {@code "shell"} or {@code "shell(echo)"}. Anything
 * unrecognised falls through to {@link Category#UNKNOWN} and prompts.
 */
final class GithubCopilotPermissionPolicy {

    private static final int MAX_VALUE_CHARS = 300;

    static Category classify(String kind, String mcpServerName, Map<String, Object> extensionData) {
        if (kind == null || kind.isBlank()) {
            return Category.UNKNOWN;
        }
        String k = kind.toLowerCase(Locale.ROOT);
        if (k.contains("mcp")) {
            // The server name is in extensionData, never in the kind. A tool from
            // another MCP server is deliberately not auto-approved — this plugin
            // gates its own tools and can vouch for nothing else.
            Object server = extensionData == null ? null
                    : extensionData.get(GithubCopilotJsonKeyEnum.SERVER_NAME.key());
            return server != null && mcpServerName != null
                    && server.toString().equalsIgnoreCase(mcpServerName)
                    ? Category.MCP_OUR_SERVER : Category.UNKNOWN;
        }
        if (mcpServerName != null && !mcpServerName.isBlank() && k.contains(mcpServerName.toLowerCase(Locale.ROOT))) {
            return Category.MCP_OUR_SERVER;
        }
        // getKind() says "shell"; "commands" appears only INSIDE extensionData as a
        // sub-array alongside fullCommandText. Both are matched because the two
        // vocabularies were confused once already: a rewrite that matched only
        // "commands" made this branch unreachable, so every shell command fell
        // through to UNKNOWN and was auto-denied instead of prompting. Confirmed
        // from the handler's own log: getKind() yields only mcp, read and shell.
        if (k.contains("shell") || k.contains("commands")) {
            return Category.SHELL;
        }
        if (k.contains("read") || k.contains("path") || k.contains("url")) {
            return Category.INTERNAL;
        }
        return Category.UNKNOWN;
    }

    /**
     * Label for the confirm panel's tool-name slot. The category alone reads
     * badly — an MCP call showed up as literally "Unknown" even though its
     * {@link GithubCopilotJsonKeyEnum#TOOL_TITLE} was right there in
     * {@code extensionData}. Prefer the most specific thing actually known: the
     * tool's title, then the raw kind, and only "Unknown" when there is
     * genuinely nothing to show.
     */
    static String describeToolName(Category category, String rawKind, Map<String, Object> extensionData) {
        Object title = extensionData == null ? null
                : extensionData.get(GithubCopilotJsonKeyEnum.TOOL_TITLE.key());
        if (title != null && !title.toString().isBlank()) {
            return title.toString();
        }
        // A recognised category reads well on its own ("Shell", "Internal Command").
        // An unrecognised kind is NOT echoed into this slot: the SDK declares no
        // constants for the request-side kind (only the reply side,
        // PermissionRequestResultKind, is an enum), so an unfamiliar value is an
        // arbitrary server-supplied string, and putting something like
        // "some-future-kind(thing)" where a tool name belongs reads worse than
        // admitting we do not recognise it. The raw kind is still shown in full in
        // the display text, so nothing is hidden.
        return category == null || category == Category.UNKNOWN || category == Category.INTERNAL
                ? Category.UNKNOWN.label() : category.label();
    }

    /**
     * Builds the confirm-panel display text. The key names Copilot uses inside
     * {@code extensionData} for the human-relevant detail (e.g. the shell
     * command text) are only partly known, so rather than guess a key and risk
     * silently dropping the one piece of information the user needs, every
     * entry is rendered — generous, not exact.
     *
     * <p>
     * Two exceptions. Anything whose key looks like a credential is masked: the
     * inter-AI tools pass a session {@link McpToolPropertyEnum#SECRET_KEY} as a
     * normal argument, and a live run rendered one in full into both the
     * confirm panel and the log. Long values are truncated so one large
     * argument cannot push the rest of the request off the panel.
     */
    static String describeRequest(String rawKind, Map<String, Object> extensionData) {
        StringBuilder sb = new StringBuilder("GitHub Copilot requests permission: ")
                .append(rawKind == null || rawKind.isBlank() ? "(unknown kind)" : rawKind);
        if (extensionData != null && !extensionData.isEmpty()) {
            sb.append(" — ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : extensionData.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append('=').append(redact(entry.getKey(), entry.getValue()));
                first = false;
            }
        }
        return sb.toString();
    }

    static String rejectFeedbackFor(Category category, String rawKind) {
        String kind = rawKind == null ? "" : rawKind.toLowerCase(Locale.ROOT);
        if (category == Category.INTERNAL && kind.contains("read")) {
            return "declined — use the IDE's own tools instead: " + McpToolEnum.GET_FILE_CONTENT.toolName()
                    + " to read a file, or " + McpToolEnum.SEARCH_IN_FILES.toolName() + " / "
                    + McpToolEnum.SEARCH_TYPES.toolName() + " / "
                    + McpToolEnum.GET_PROJECT_STRUCTURE.toolName() + " to locate one.";
        }
        if (category == Category.INTERNAL && kind.contains("path")) {
            return "declined — use " + McpToolEnum.GET_PROJECT_STRUCTURE.toolName()
                    + " to inspect the project tree; read files with "
                    + McpToolEnum.GET_FILE_CONTENT.toolName() + ".";
        }
        if (category == Category.INTERNAL && kind.contains("url")) {
            return "declined — use the " + McpToolEnum.WEB_REQUEST.toolName()
                    + " tool instead; it also honours this session's web-access settings.";
        }
        return "declined — call " + McpToolEnum.GET_INSTRUCTIONS.toolName()
                + " for the list of tools this IDE provides, and use those instead.";
    }

    private static String redact(String key, Object value) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (k.contains("secret") || k.contains("token") || k.contains("password") || k.contains("apikey")) {
            return "***";
        }
        String v = String.valueOf(value);
        // Nested argument maps carry credentials too — args={..., secretKey=...}.
        // The parameter name comes from McpToolPropertyEnum so a rename there
        // cannot silently switch this redaction off.
        String secretKey = McpToolPropertyEnum.SECRET_KEY.key();
        if (v.contains(secretKey + "=")) {
            v = v.replaceAll(Pattern.quote(secretKey) + "=[^,}\\s]+",
                    Matcher.quoteReplacement(secretKey) + "=***");
        }
        return v.length() > MAX_VALUE_CHARS ? v.substring(0, MAX_VALUE_CHARS) + "…" : v;
    }

    private GithubCopilotPermissionPolicy() {
    }

    enum Category {
        /**
         * One of our own MCP server's tools — already gated by the plugin.
         */
        MCP_OUR_SERVER,
        SHELL,
        /**
         * Native Copilot actions that must use their MCP equivalents instead.
         */
        INTERNAL,
        /**
         * Matches none of the known kinds — always denied.
         */
        UNKNOWN;

        String label() {
            return switch (this) {
                case MCP_OUR_SERVER ->
                    "MCP";
                case SHELL ->
                    "Shell";
                case INTERNAL, UNKNOWN ->
                    "Internal Command";
            };
        }
    }
}
