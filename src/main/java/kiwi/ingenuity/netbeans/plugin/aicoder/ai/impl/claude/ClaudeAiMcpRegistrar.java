package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.StringConst;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

/**
 * Claude-specific MCP registration strategy. Uses {@code claude mcp add/remove}
 * to manage per-session endpoints, and writes the PreToolUse HTTP hook to
 * ~/.claude/settings.json for the diff-intercept feature.
 */
public class ClaudeAiMcpRegistrar extends AiMcpRegistrar {

    private static final Logger LOG = Logger.getLogger(ClaudeAiMcpRegistrar.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    // ---- PreToolUse hook in ~/.claude/settings.json ----
    private static boolean writePreToolHook(String baseUrl) {
        Path settingsPath = Path.of(System.getProperty("user.home"), ".claude", "settings.json");
        try {
            JsonObject settings = readSettings(settingsPath);
            if (!settings.has("hooks") || !settings.get("hooks").isJsonObject()) {
                settings.add("hooks", new JsonObject());
            }
            JsonObject hooks = settings.getAsJsonObject("hooks");
            JsonArray preToolUse = new JsonArray();
            if (hooks.has("PreToolUse") && hooks.get("PreToolUse").isJsonArray()) {
                for (JsonElement el : hooks.getAsJsonArray("PreToolUse")) {
                    if (!isNbPluginHookEntry(el)) {
                        preToolUse.add(el);
                    }
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("matcher", "Edit|Write");
            JsonArray innerHooks = new JsonArray();
            JsonObject httpHook = new JsonObject();
            httpHook.addProperty("type", "http");
            httpHook.addProperty("url", baseUrl + "/");
            innerHooks.add(httpHook);
            entry.add("hooks", innerHooks);
            preToolUse.add(entry);
            hooks.add("PreToolUse", preToolUse);
            writeSettings(settingsPath, settings);
            LOG.log(Level.INFO, "PreToolUse hook registered in {0}", settingsPath);
            return true;
        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to register PreToolUse hook in settings.json", e);
            return false;
        }
    }

    private static void removePreToolHook() {
        Path settingsPath = Path.of(System.getProperty("user.home"), ".claude", "settings.json");
        try {
            if (!Files.exists(settingsPath)) {
                return;
            }
            JsonObject settings = readSettings(settingsPath);
            if (!settings.has("hooks") || !settings.get("hooks").isJsonObject()) {
                return;
            }
            JsonObject hooks = settings.getAsJsonObject("hooks");
            if (!hooks.has("PreToolUse") || !hooks.get("PreToolUse").isJsonArray()) {
                return;
            }
            JsonArray filtered = new JsonArray();
            for (JsonElement el : hooks.getAsJsonArray("PreToolUse")) {
                if (!isNbPluginHookEntry(el)) {
                    filtered.add(el);
                }
            }
            hooks.add("PreToolUse", filtered);
            writeSettings(settingsPath, settings);
            LOG.log(Level.INFO, "PreToolUse hook unregistered from {0}", settingsPath);
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "Could not unregister PreToolUse hook", e);
        }
    }

    private static boolean isNbPluginHookEntry(JsonElement el) {
        if (!el.isJsonObject()) {
            return false;
        }
        try {
            JsonArray hks = el.getAsJsonObject().getAsJsonArray("hooks");
            if (hks == null) {
                return false;
            }
            for (JsonElement h : hks) {
                if (!h.isJsonObject()) {
                    continue;
                }
                JsonElement urlEl = h.getAsJsonObject().get("url");
                if (urlEl != null && !urlEl.isJsonNull()) {
                    String url = urlEl.getAsString();
                    if (url != null && (url.startsWith("http://127.0.0.1:")
                            || url.startsWith("http://[::1]:")
                            || url.startsWith("http://localhost:"))) {
                        return true;
                    }
                }
            }
        }
        catch (Exception ignored) {
        }
        return false;
    }

    private static JsonObject readSettings(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new JsonObject();
        }
        String content = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (content.isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonObject obj = GSON.fromJson(content, JsonObject.class);
            return obj != null ? obj : new JsonObject();
        }
        catch (com.google.gson.JsonSyntaxException e) {
            LOG.log(Level.WARNING, "settings.json is not valid JSON — starting fresh", e);
            return new JsonObject();
        }
    }

    private static void writeSettings(Path path, JsonObject settings) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, PRETTY_GSON.toJson(settings), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private final String executablePath;

    public ClaudeAiMcpRegistrar(String sessionId, String executablePath) {
        super(sessionId, AiTypeEnum.CLAUDE);
        this.executablePath = executablePath;
    }

    @Override
    public void addMcpEndpoint(String endpointUrl) {
        runCommand(5, "mcp", "add", "--transport", "http", "--scope", "user",
                StringConst.PLUGIN_ID, endpointUrl);
    }

    @Override
    public void removeMcpEndpoint() {
        runCommand(3, "mcp", "remove", "--scope", "user", StringConst.PLUGIN_ID);
    }

    @Override
    public boolean registerHooks(String serverBaseUrl) {
        runCommand(3, "mcp", "remove", "--scope", "user", StringConst.PLUGIN_ID);
        return writePreToolHook(serverBaseUrl);
    }

    @Override
    public void unregisterHooks() {
        removePreToolHook();
    }

    private int runCommand(int timeoutSeconds, String... args) {
        if (executablePath == null) {
            return -1;
        }
        try {
            List<String> cmd = ClaudeExecutableLocator.buildHostCommand(executablePath, args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        }
        catch (Exception e) {
            LOG.log(Level.FINE, "MCP command error", e);
            return -1;
        }
    }

}
