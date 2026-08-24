package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.McpConfigKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

/**
 * Grok-specific MCP registration strategy. Uses {@code grok mcp add/remove}
 * (https://docs.x.ai/build/features/mcp-servers) to manage the per-session HTTP MCP endpoint, and writes a dedicated
 * {@link McpConfigKeyEnum#PRE_TOOL_USE} HTTP hook file under {@code ~/.grok/hooks/}
 * (https://docs.x.ai/build/features/hooks) for the diff-intercept feature — Grok reads standalone hook JSON files, so
 * unlike Claude there is no need to merge into a shared settings.json.
 *
 * <p>
 * Optionally (behind {@link #PIN_TOOL_TIMEOUT}, off by default) after each successful {@code grok mcp add} this
 * registrar also pins {@code tool_timeout_sec} inside the plugin's own {@code [mcp_servers.<id>]} section of
 * {@code ~/.grok/config.toml}: Grok's CLI has no flag for it and no environment variable covers it (only the startup
 * handshake timeout does). The value deliberately tightens Grok's 6000 s default to the shared mutation-lock bound —
 * rationale on {@link GrokTimeoutEnum#MCP_TOOL_TIMEOUT_MILLIS}. {@code startup_timeout_sec} is left at its default.
 */
public class GrokAiMcpRegistrar extends AiMcpRegistrar {

    private static final Logger LOG = Logger.getLogger(GrokAiMcpRegistrar.class.getName());
    private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String HOOK_FILE_NAME = StringConst.PLUGIN_ID + ".json";
    /**
     * This plugin's own MCP server section header in {@code config.toml}, bare
     * ({@code [mcp_servers.aicoder-nb-ki-plugin]}) or quoted — the shape {@code grok mcp add} writes for a simple
     * lower-case id.
     */
    private static final Pattern MCP_SERVER_SECTION = Pattern.compile(
            "^\\[mcp_servers\\.(\"?)" + Pattern.quote(StringConst.PLUGIN_ID) + "\\1]\\s*$");
    /**
     * Existing per-tool timeout line inside our section (value replaced).
     */
    private static final Pattern TOOL_TIMEOUT_LINE
            = Pattern.compile("^\\s*tool_timeout_sec\\s*=.*$");
    /**
     * Master switch for pinning {@code tool_timeout_sec} into the user's own {@code ~/.grok/config.toml} after
     * {@code grok mcp add}. Default OFF.
     *
     * <p>
     * Why off: writing into the user's persistent config file is more invasive than the other backends' mechanisms,
     * which are all process-scoped and vanish with the process (Codex {@code -c} args, Copilot SDK call, Claude env
     * var) — this survives the IDE closing and lives entirely outside our control. And while off, nothing is broken:
     * Grok's default {@code tool_timeout_sec} of 6000&nbsp;s is already far above our shared bound, unlike Codex's
     * 60&nbsp;s default or Claude's 60&nbsp;s HTTP per-request limit, which genuinely needed fixing. The pin is
     * therefore optional hardening (tighter failure detection on a hung tool call), not a fix — off is a safe default.
     * Flip to {@code true} to pin the value.
     */
    static final boolean PIN_TOOL_TIMEOUT = false;

    private static Path hooksDir() {
        return Path.of(System.getProperty("user.home"), ".grok", "hooks");
    }

    private static boolean writePreToolHook(String baseUrl) {
        Path hookPath = hooksDir().resolve(HOOK_FILE_NAME);
        try {
            Files.createDirectories(hookPath.getParent());
            JsonObject entry = new JsonObject();
            entry.addProperty(McpConfigKeyEnum.MATCHER.key(), "Edit|Write");
            JsonArray innerHooks = new JsonArray();
            JsonObject httpHook = new JsonObject();
            httpHook.addProperty(McpConfigKeyEnum.TYPE.key(), "http");
            httpHook.addProperty(McpConfigKeyEnum.URL.key(), baseUrl + "/");
            innerHooks.add(httpHook);
            entry.add(McpConfigKeyEnum.HOOKS.key(), innerHooks);
            JsonArray preToolUse = new JsonArray();
            preToolUse.add(entry);
            JsonObject hooks = new JsonObject();
            hooks.add(McpConfigKeyEnum.PRE_TOOL_USE.key(), preToolUse);
            JsonObject root = new JsonObject();
            root.add(McpConfigKeyEnum.HOOKS.key(), hooks);

            Path tmp = hookPath.resolveSibling(hookPath.getFileName() + ".tmp");
            Files.writeString(tmp, PRETTY_GSON.toJson(root), java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(tmp, hookPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, hookPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.log(Level.INFO, "PreToolUse hook registered in {0}", hookPath);
            return true;
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write grok PreToolUse hook file", e);
            return false;
        }
    }

    private static void removePreToolHook() {
        Path hookPath = hooksDir().resolve(HOOK_FILE_NAME);
        try {
            Files.deleteIfExists(hookPath);
            LOG.log(Level.INFO, "PreToolUse hook removed: {0}", hookPath);
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not remove grok PreToolUse hook file", e);
        }
    }

    /**
     * Grok's {@code mcp_servers.<id>.tool_timeout_sec} setting is in SECONDS; our shared bound
     * ({@link GrokTimeoutEnum#MCP_TOOL_TIMEOUT_MILLIS}) is in MILLISECONDS. Converted explicitly — mirrors
     * {@code CodexAiProcessManager.buildMcpConfigArgs()}.
     */
    static long toolTimeoutSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(GrokTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis());
    }

    /**
     * Returns {@code lines} with {@code tool_timeout_sec = <timeoutSeconds>} inserted into (or replaced inside) the
     * plugin's own {@code [mcp_servers.<id>]} section, or {@code null} when that section is not present.
     *
     * <p>
     * Null means do nothing: appending a second definition of the same TOML table would invalidate the whole config,
     * and the section can legitimately live where this matcher does not look (project scope, unusual quoting). Only the
     * plugin's own section is ever touched; other servers' {@code tool_timeout_sec} values are left exactly as the user
     * set them.
     */
    static List<String> toolTimeoutLines(List<String> lines, long timeoutSeconds) {
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (MCP_SERVER_SECTION.matcher(lines.get(i)).matches()) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            return null;
        }
        // Exclusive end of our section: first line of the next table or EOF.
        int end = header + 1;
        while (end < lines.size() && !lines.get(end).startsWith("[")) {
            end++;
        }
        String replacement = "tool_timeout_sec = " + timeoutSeconds;
        List<String> out = new ArrayList<>(lines.size() + 1);
        out.addAll(lines.subList(0, header + 1));
        boolean found = false;
        for (int i = header + 1; i < end; i++) {
            String line = lines.get(i);
            if (TOOL_TIMEOUT_LINE.matcher(line).matches()) {
                out.add(replacement); // replace existing pin, preserving position
                found = true;
            }
            else {
                out.add(line);
            }
        }
        if (!found) {
            out.add(replacement);
        }
        out.addAll(lines.subList(end, lines.size()));
        return out;
    }

    /**
     * The grok user config: {@code $GROK_HOME/config.toml}, defaulting to {@code ~/.grok/config.toml} — the same file
     * {@code grok mcp add} writes.
     */
    private static Path configFile() {
        String grokHome = System.getenv("GROK_HOME");
        return grokHome == null || grokHome.isBlank()
                ? Path.of(System.getProperty("user.home"), ".grok", "config.toml")
                : Path.of(grokHome, "config.toml");
    }

    /**
     * Pins {@code tool_timeout_sec} after {@code grok mcp add} (re)created the server entry — the CLI writes a fresh
     * section without it on every registration cycle, so when enabled by {@link #PIN_TOOL_TIMEOUT} this runs on every
     * successful add. Best effort: failures are logged, never thrown — an unpinned timeout only degrades to Grok's
     * (much longer) default and must not fail MCP registration. Package-visible and flag-independent so tests exercise
     * it even while the gate is off.
     */
    static void applyToolTimeout(Path configPath) {
        try {
            if (!Files.isRegularFile(configPath)) {
                // No config left behind by grok mcp add; nothing to patch.
                return;
            }
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            String newline = raw.contains("\r\n") ? "\r\n" : "\n";
            List<String> lines = new ArrayList<>(Arrays.asList(raw.split("\\R", -1)));
            List<String> updated = toolTimeoutLines(lines, toolTimeoutSeconds());
            if (updated == null) {
                LOG.log(Level.WARNING,
                        "No [mcp_servers.{0}] section in {1}; tool_timeout_sec left at the Grok default",
                        new Object[]{StringConst.PLUGIN_ID, configPath});
                return;
            }
            if (updated.equals(lines)) {
                return; // already pinned with the current value
            }
            Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            Files.writeString(tmp, String.join(newline, updated), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, configPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.log(Level.INFO, "tool_timeout_sec={0} s written to {1}",
                    new Object[]{toolTimeoutSeconds(), configPath});
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to pin tool_timeout_sec in grok config.toml", e);
        }
    }

    private final String executablePath;

    public GrokAiMcpRegistrar(String sessionId, String executablePath) {
        super(sessionId, AiTypeEnum.GROK);
        this.executablePath = executablePath;
    }

    @Override
    public void addMcpEndpoint(String endpointUrl) {
        int exitCode = runCommand(GrokTimeoutEnum.GROK_CLI_CONFIG_WRITE_MILLIS.millis(),
                "mcp", "add", "--transport", "http", StringConst.PLUGIN_ID, endpointUrl);
        // Gated by PIN_TOOL_TIMEOUT (default off): when disabled, grok mcp add
        // behaves exactly as before this feature existed — config.toml is
        // neither read nor written and nothing about pinning is logged.
        if (exitCode == 0 && PIN_TOOL_TIMEOUT) {
            applyToolTimeout(configFile());
        }
    }

    @Override
    public void removeMcpEndpoint() {
        runCommand(GrokTimeoutEnum.GROK_CLI_CONFIG_REMOVE_MILLIS.millis(), "mcp", "remove", StringConst.PLUGIN_ID);
    }

    @Override
    public boolean registerHooks(String serverBaseUrl) {
        // Deliberately NO `grok mcp remove` here: McpServerRegistry.handleRegister runs
        // removeMcpEndpoint() + addMcpEndpoint() immediately after this returns, so a
        // pre-clean would just duplicate that ~3 s supervisor-thread subprocess call.
        return writePreToolHook(serverBaseUrl);
    }

    @Override
    public void unregisterHooks() {
        removePreToolHook();
    }

    private int runCommand(long timeoutMillis, String... args) {
        if (executablePath == null) {
            return -1;
        }
        try {
            List<String> cmd = GrokExecutableLocator.buildHostCommand(executablePath, args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean done = p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "grok MCP command error", e);
            return -1;
        }
    }
}
