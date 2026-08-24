package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Opt-in writer for {@code experimental.mcp_timeout} into the user's global OpenCode config. OpenCode bounds every MCP
 * request with one shared timeout, and its default aborts legitimate long-running tool calls (issue
 * anomalyco/opencode#8212); the knob that actually governs execution is the experimental key in the user's own config
 * file — the per-server {@code timeout} field only bounds discovery, and the ACP transport this plugin hands the agent
 * carries no timeout field at all. When enabled, the value written is {@link TimeoutEnum#MUTATION_LOCK_WAIT_MILLIS} in
 * milliseconds, so the pin tracks the shared bound wherever it moves.
 *
 * <p>
 * The edit is surgical: the file is treated as JSONC, string interiors and comments are masked, and the new member is
 * spliced in at a single point so every other byte — comments included — survives verbatim. Unrecognisable structure is
 * refused (file untouched, reason logged) rather than rewritten. An existing {@code mcp_timeout} value is never
 * overridden, right or wrong ({@code putIfAbsent} semantics).
 *
 * <p>
 * Target selection mirrors OpenCode's own discovery: the global config directory (${@code XDG_CONFIG_HOME}/opencode,
 * defaulting to {@code ~/.config/opencode}), preferring an existing {@code opencode.jsonc} over {@code opencode.json};
 * with neither present a minimal {@code opencode.json} is created. Project-level configs and the
 * {@code OPENCODE_CONFIG} override layer are deliberately left alone — the global file loads in every session
 * regardless, and it cannot collide with a git-checked-out repository.
 */
final class OpenCodeMcpTimeoutPinner {

    private static final Logger LOG = Logger.getLogger(OpenCodeMcpTimeoutPinner.class.getName());

    static final String EXPERIMENTAL_KEY = "experimental";
    static final String MCP_TIMEOUT_KEY = "mcp_timeout";

    /**
     * Master switch for pinning {@code experimental.mcp_timeout} into the user's global OpenCode config when a plugin
     * session starts. Default OFF.
     *
     * <p>
     * Why off: unlike every other backend's mechanism, which is process-scoped (Codex {@code -c} args, Copilot SDK
     * call, Claude env var, OpenCode's own {@code OPENCODE_CONFIG_CONTENT}), this writes a file the user owns and it
     * persists after the IDE closes — and the setting is GLOBAL: the timeout applies to EVERY MCP server the user runs
     * under OpenCode, not just this plugin's endpoint (the Grok pin at least confines itself to the plugin's own
     * [mcp_servers] table). While off, the user's setup is exactly as they configured it.
     *
     * <p>
     * Unlike the Grok pin, enabling this fixes something real instead of only tightening failure detection: OpenCode's
     * default MCP timeout kills legitimate long tool calls (anomalyco/opencode#8212). The lower-risk alternative —
     * telling users how to set the key themselves — already exists: README.md documents it. Flip to {@code true} to
     * enable.
     */
    static final boolean PIN_MCP_TIMEOUT = false;

    /**
     * Splices {@code "mcp_timeout": <timeoutMillis>} into the given JSONC document's {@code experimental} object
     * (creating that object when absent) and returns the edited text. Everything outside one insertion point is
     * preserved byte-for-byte, comments included; an existing {@code mcp_timeout} member wins over the argument.
     * Malformed input is refused with the original text returned unchanged.
     */
    static McpTimeoutPinResult pinMcpTimeout(String jsonc, long timeoutMillis) {
        if (jsonc == null || jsonc.isBlank()) {
            return new McpTimeoutPinResult(jsonc, McpTimeoutPinOutcome.REFUSED_MALFORMED);
        }
        String mask = maskStringsAndComments(jsonc);
        int rootOpen = nextNonWs(mask, 0);
        if (rootOpen < 0 || mask.charAt(rootOpen) != '{') {
            return new McpTimeoutPinResult(jsonc, McpTimeoutPinOutcome.REFUSED_MALFORMED);
        }
        int rootClose = matchBrace(mask, rootOpen);
        if (rootClose < 0) {
            return new McpTimeoutPinResult(jsonc, McpTimeoutPinOutcome.REFUSED_MALFORMED);
        }
        String timeoutMember = "\"" + MCP_TIMEOUT_KEY + "\": " + timeoutMillis;
        int expKey = findChildKeyIndex(jsonc, mask, rootOpen, rootClose, EXPERIMENTAL_KEY);
        if (expKey < 0) {
            String experimentalMember = "\"" + EXPERIMENTAL_KEY + "\": {" + timeoutMember + "}";
            String edited = insertMemberBeforeClose(jsonc, mask, rootOpen, rootClose, experimentalMember);
            return new McpTimeoutPinResult(edited, McpTimeoutPinOutcome.ADDED_EXPERIMENTAL_BLOCK);
        }
        int valueStart = valueStartAfterKey(mask, expKey);
        if (valueStart < 0 || mask.charAt(valueStart) != '{') {
            return new McpTimeoutPinResult(jsonc, McpTimeoutPinOutcome.REFUSED_MALFORMED);
        }
        int expClose = matchBrace(mask, valueStart);
        if (expClose < 0) {
            return new McpTimeoutPinResult(jsonc, McpTimeoutPinOutcome.REFUSED_MALFORMED);
        }
        if (findChildKeyIndex(jsonc, mask, valueStart, expClose, MCP_TIMEOUT_KEY) >= 0) {
            return new McpTimeoutPinResult(jsonc, McpTimeoutPinOutcome.UNCHANGED_ALREADY_SET);
        }
        String edited = insertMemberBeforeClose(jsonc, mask, valueStart, expClose, timeoutMember);
        return new McpTimeoutPinResult(edited, McpTimeoutPinOutcome.INSERTED_INTO_EXPERIMENTAL);
    }

    /**
     * Replaces the interior of every string literal and every {@code //} or block comment with spaces — quotes,
     * newlines and all other characters stay in place — so the result has the input's exact length and line layout
     * while exposing only structural characters that sit outside strings and comments.
     */
    static String maskStringsAndComments(String jsonc) {
        char[] out = jsonc.toCharArray();
        final int CODE = 0, IN_STRING = 1, IN_LINE_COMMENT = 2, IN_BLOCK_COMMENT = 3;
        int state = CODE;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            switch (state) {
                case CODE -> {
                    if (c == '"') {
                        state = IN_STRING;
                    }
                    else if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        state = IN_LINE_COMMENT;
                        i++;
                    }
                    else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        state = IN_BLOCK_COMMENT;
                        i++;
                    }
                }
                case IN_STRING -> {
                    if (c == '\\') {
                        out[i] = ' ';
                        if (i + 1 < out.length) {
                            out[i + 1] = ' ';
                        }
                        i++;
                    }
                    else if (c == '"') {
                        state = CODE;
                    }
                    else {
                        out[i] = ' ';
                    }
                }
                case IN_LINE_COMMENT -> {
                    if (c == '\n') {
                        state = CODE;
                    }
                    else {
                        out[i] = ' ';
                    }
                }
                default -> {
                    // IN_BLOCK_COMMENT
                    if (c == '*' && i + 1 < out.length && out[i + 1] == '/') {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        state = CODE;
                        i++;
                    }
                    else if (c != '\n') {
                        out[i] = ' ';
                    }
                }
            }
        }
        return new String(out);
    }

    /**
     * Flag-gated entry point invoked from the OpenCode registration path. Never throws: a failed pin is logged and the
     * session starts regardless. Does nothing at all while {@link #PIN_MCP_TIMEOUT} is off.
     */
    static void applyMcpTimeoutPin() {
        if (!PIN_MCP_TIMEOUT) {
            return;
        }
        try {
            applyMcpTimeoutToFile(configFileIn(defaultConfigDir()));
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Could not pin experimental.mcp_timeout into the OpenCode config", e);
        }
    }

    /**
     * Reads the given global config file, pins the timeout into its text and writes it back atomically; a missing file
     * is created as clean JSON. The value is {@link TimeoutEnum#MUTATION_LOCK_WAIT_MILLIS} — the shared ceiling every
     * mutation-lock handler must finish inside — expressed in milliseconds, which is the unit OpenCode's schema
     * defines.
     */
    static void applyMcpTimeoutToFile(Path configFile) throws IOException {
        long timeoutMillis = TimeoutEnum.MUTATION_LOCK_WAIT_MILLIS;
        boolean existed = Files.isRegularFile(configFile);
        String original = existed ? Files.readString(configFile, StandardCharsets.UTF_8) : "{}";
        McpTimeoutPinResult result = pinMcpTimeout(original, timeoutMillis);
        switch (result.outcome()) {
            case UNCHANGED_ALREADY_SET ->
                LOG.log(Level.INFO, "{0} already sets experimental.{1}; left untouched",
                        new Object[]{configFile, MCP_TIMEOUT_KEY});
            case REFUSED_MALFORMED ->
                LOG.log(Level.WARNING, "{0}: not recognisable as JSONC with an object root; "
                        + "experimental.{1} NOT written", new Object[]{configFile, MCP_TIMEOUT_KEY});
            case INSERTED_INTO_EXPERIMENTAL -> {
                atomicWrite(configFile, result.jsonc());
                LOG.log(Level.INFO, "Pinned experimental.{1}={2} into {0}",
                        new Object[]{configFile, MCP_TIMEOUT_KEY, timeoutMillis});
            }
            case ADDED_EXPERIMENTAL_BLOCK -> {
                atomicWrite(configFile, result.jsonc());
                LOG.log(Level.INFO, existed
                        ? "Added an experimental block pinning {1}={2} into {0}"
                        : "Created {0} with experimental.{1}={2}",
                        new Object[]{configFile, MCP_TIMEOUT_KEY, timeoutMillis});
            }
        }
    }

    /**
     * OpenCode's global config directory: ${@code XDG_CONFIG_HOME}/opencode when that variable is set,
     * {@code ~/.config/opencode} otherwise (https://opencode.ai/docs/config — Locations/Global).
     */
    static Path defaultConfigDir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("opencode");
    }

    /**
     * The file to pin into within {@code dir}: an existing opencode.jsonc wins over opencode.json (whoever chose the
     * commented format keeps it); when neither exists, opencode.json is named as the creation target — the generated
     * content is plain JSON, so the extension stays honest.
     */
    static Path configFileIn(Path dir) {
        Path jsonc = dir.resolve("opencode.jsonc");
        if (Files.isRegularFile(jsonc)) {
            return jsonc;
        }
        return dir.resolve("opencode.json");
    }

    /**
     * Index of the opening quote of {@code key} among the object's direct children (relative depth 0 between objOpen
     * and objEnd), provided a colon follows it; -1 otherwise. The mask supplies the structure — depth, token
     * boundaries, colon position — but the key text itself is compared against the original document, because masking
     * blanks string interiors and JSON keys are strings. Keys nested in child objects or arrays are skipped via depth
     * tracking, and string values can never match since a match additionally requires the colon.
     */
    private static int findChildKeyIndex(String jsonc, String mask, int objOpen, int objEnd, String key) {
        String quoted = "\"" + key + "\"";
        int depth = 0;
        for (int i = objOpen + 1; i < objEnd; i++) {
            char c = mask.charAt(i);
            if (c == '{' || c == '[') {
                depth++;
            }
            else if (c == '}' || c == ']') {
                depth--;
            }
            else if (c == '"' && depth == 0) {
                int closeQuote = mask.indexOf('"', i + 1);
                if (closeQuote < 0 || closeQuote >= objEnd) {
                    return -1;
                }
                int colon = nextNonWs(mask, closeQuote + 1);
                if (jsonc.startsWith(quoted, i) && colon >= 0 && mask.charAt(colon) == ':') {
                    return i;
                }
                i = closeQuote;
            }
        }
        return -1;
    }

    /**
     * First character of the value belonging to the key whose opening quote is at keyQuote, or -1 when no colon
     * follows.
     */
    private static int valueStartAfterKey(String mask, int keyQuote) {
        int closeQuote = mask.indexOf('"', keyQuote + 1);
        if (closeQuote < 0) {
            return -1;
        }
        int colon = nextNonWs(mask, closeQuote + 1);
        if (colon < 0 || mask.charAt(colon) != ':') {
            return -1;
        }
        return nextNonWs(mask, colon + 1);
    }

    /**
     * Splices {@code memberText} into the object spanned by objOpen..objClose as its last member, reusing the
     * surrounding line layout: multi-line objects gain an indented line of their own, single-line objects stay
     * single-line. Existing commas are honoured, so strict-JSON input yields strict-JSON output.
     */
    private static String insertMemberBeforeClose(String text, String mask, int objOpen, int objClose, String memberText) {
        int lastNonWs = -1;
        for (int i = objClose - 1; i > objOpen; i--) {
            if (!isMaskWs(mask.charAt(i))) {
                lastNonWs = i;
                break;
            }
        }
        int closeLineStart = lineStartOf(text, objClose);
        boolean closeOnOwnLine = isAllWhitespace(text, closeLineStart, objClose);
        if (lastNonWs < 0) {
            if (closeOnOwnLine) {
                String indent = text.substring(closeLineStart, objClose);
                return text.substring(0, closeLineStart) + indent + memberText + "\n" + text.substring(closeLineStart);
            }
            return text.substring(0, objClose) + memberText + text.substring(objClose);
        }
        boolean needsComma = text.charAt(lastNonWs) != ',';
        int insertAt = lastNonWs + 1;
        if (closeOnOwnLine) {
            String memberIndent = leadingWhitespace(text, lineStartOf(text, lastNonWs), lastNonWs);
            return text.substring(0, insertAt)
                    + (needsComma ? "," : "") + "\n" + memberIndent + memberText
                    + text.substring(insertAt);
        }
        return text.substring(0, insertAt)
                + (needsComma ? ", " : " ") + memberText
                + text.substring(insertAt);
    }

    private static int matchBrace(String mask, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int nextNonWs(String s, int from) {
        for (int i = Math.max(from, 0); i < s.length(); i++) {
            if (!isMaskWs(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMaskWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF';
    }

    private static int lineStartOf(String text, int pos) {
        int start = pos;
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }

    private static boolean isAllWhitespace(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String leadingWhitespace(String text, int from, int to) {
        int end = from;
        while (end < to && Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(from, end);
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private OpenCodeMcpTimeoutPinner() {
    }

    /**
     * Why a config text was or was not modified by {@link #pinMcpTimeout}.
     */
    enum McpTimeoutPinOutcome {
        /**
         * {@code experimental.mcp_timeout} already present — original bytes returned untouched.
         */
        UNCHANGED_ALREADY_SET,
        /**
         * The member was spliced into an existing {@code experimental} object.
         */
        INSERTED_INTO_EXPERIMENTAL,
        /**
         * No {@code experimental} object existed — a new one wrapping the member was added at root level.
         */
        ADDED_EXPERIMENTAL_BLOCK,
        /**
         * Structure not recognisable as JSONC with an object root — file deliberately left untouched.
         */
        REFUSED_MALFORMED
    }

    record McpTimeoutPinResult(String jsonc, McpTimeoutPinOutcome outcome) {

    }
}
