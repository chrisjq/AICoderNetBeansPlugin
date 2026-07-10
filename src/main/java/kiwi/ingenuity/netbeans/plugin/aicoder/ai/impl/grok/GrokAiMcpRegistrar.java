package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
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
 * Grok-specific MCP registration strategy. Uses {@code grok mcp add/remove}
 * (https://docs.x.ai/build/features/mcp-servers) to manage the per-session HTTP
 * MCP endpoint, and writes a dedicated PreToolUse HTTP hook file under
 * {@code ~/.grok/hooks/} (https://docs.x.ai/build/features/hooks) for the
 * diff-intercept feature — Grok reads standalone hook JSON files, so unlike
 * Claude there is no need to merge into a shared settings.json.
 */
public class GrokAiMcpRegistrar extends AiMcpRegistrar {

    private static final Logger LOG = Logger.getLogger(GrokAiMcpRegistrar.class.getName());
    private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String HOOK_FILE_NAME = StringConst.PLUGIN_ID + ".json";

    private static Path hooksDir() {
        return Path.of(System.getProperty("user.home"), ".grok", "hooks");
    }

    private static boolean writePreToolHook(String baseUrl) {
        Path hookPath = hooksDir().resolve(HOOK_FILE_NAME);
        try {
            Files.createDirectories(hookPath.getParent());
            JsonObject entry = new JsonObject();
            entry.addProperty("matcher", "Edit|Write");
            JsonArray innerHooks = new JsonArray();
            JsonObject httpHook = new JsonObject();
            httpHook.addProperty("type", "http");
            httpHook.addProperty("url", baseUrl + "/");
            innerHooks.add(httpHook);
            entry.add("hooks", innerHooks);
            JsonArray preToolUse = new JsonArray();
            preToolUse.add(entry);
            JsonObject hooks = new JsonObject();
            hooks.add("PreToolUse", preToolUse);
            JsonObject root = new JsonObject();
            root.add("hooks", hooks);

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

    private final String executablePath;

    public GrokAiMcpRegistrar(String sessionId, String executablePath) {
        super(sessionId, AiTypeEnum.GROK);
        this.executablePath = executablePath;
    }

    @Override
    public void addMcpEndpoint(String endpointUrl) {
        runCommand(5, "mcp", "add", "--transport", "http", StringConst.PLUGIN_ID, endpointUrl);
    }

    @Override
    public void removeMcpEndpoint() {
        runCommand(3, "mcp", "remove", StringConst.PLUGIN_ID);
    }

    @Override
    public boolean registerHooks(String serverBaseUrl) {
        runCommand(3, "mcp", "remove", StringConst.PLUGIN_ID);
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
            List<String> cmd = GrokExecutableLocator.buildHostCommand(executablePath, args);
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
            LOG.log(Level.FINE, "grok MCP command error", e);
            return -1;
        }
    }
}
