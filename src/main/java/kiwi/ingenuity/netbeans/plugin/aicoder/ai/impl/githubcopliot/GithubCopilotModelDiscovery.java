package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.ModelInfo;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopliot.settings.GithubCopilotPluginSettings;

/**
 * Phase 1 of adopting the official GitHub Copilot SDK for Java: query the real
 * list of models available to the account via
 * {@code CopilotClient.listModels()} and feed it into the model dropdown. The
 * {@code copilot -p} runtime is unchanged; this only refreshes the model list.
 * <p>
 * Discovery is best-effort and fully isolated: any failure (CLI missing, RPC
 * error, timeout) is swallowed and the hardcoded fallback list remains in use.
 */
public final class GithubCopilotModelDiscovery {

    private static final Logger LOG = Logger.getLogger(GithubCopilotModelDiscovery.class.getName());
    private static final long TIMEOUT_SECONDS = 30;
    private static final Gson GSON = new Gson();

    private static final AtomicBoolean inProgress = new AtomicBoolean(false);
    private static volatile boolean succeeded = false;

    /**
     * Builds the dropdown list from discovered model ids: "auto" first (it
     * always works), then the discovered ids in order, de-duplicated and
     * trimmed, blanks dropped. Pure and side-effect free.
     */
    static String[] assembleModelList(List<String> discoveredIds) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("auto");
        if (discoveredIds != null) {
            for (String id : discoveredIds) {
                if (id != null && !id.isBlank()) {
                    out.add(id.trim());
                }
            }
        }
        return out.toArray(String[]::new);
    }

    /**
     * Runs model discovery once per IDE run on a background daemon thread. On
     * success it stores the list in {@link GithubCopilotPluginSettings} and
     * invokes {@code onResult} (off the EDT — the caller must marshal to the
     * EDT before touching Swing). Does nothing if a discovery is already
     * running or has already succeeded.
     *
     * @param cliPath the located copilot CLI path, or null to use PATH
     */
    public static void discoverAsync(String cliPath, Consumer<String[]> onResult) {
        if (succeeded || !inProgress.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                // Tier 1: official SDK. Tier 2: direct stdio JSON-RPC. Tier 3:
                // static fallback (do nothing — the combo already shows it).
                // Catch Throwable so even a classloader/linkage failure of the
                // SDK inside the NetBeans module falls through to the RPC tier.
                String[] models = null;
                String method = null;
                try {
                    models = discoverViaSdk(cliPath);
                    method = "SDK";
                }
                catch (Throwable sdkEx) {
                    LOG.log(Level.INFO, "Copilot model discovery via SDK failed; trying direct RPC", sdkEx);
                    try {
                        models = discoverViaRpc(cliPath);
                        method = "direct RPC";
                    }
                    catch (Throwable rpcEx) {
                        LOG.log(Level.INFO, "Copilot model discovery via direct RPC failed", rpcEx);
                    }
                }

                if (models != null && models.length > 1) {
                    GithubCopilotPluginSettings.setDiscoveredModels(models);
                    succeeded = true;
                    LOG.log(Level.INFO, "Copilot model discovery succeeded via {0} ({1} models)",
                            new Object[]{method, models.length});
                    if (onResult != null) {
                        onResult.accept(models);
                    }
                }
                else {
                    LOG.log(Level.INFO, "Copilot model discovery: using static fallback model list");
                }
            }
            finally {
                inProgress.set(false);
            }
        }, "copilot-model-discovery");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Tier 1: official Copilot SDK ({@code CopilotClient.listModels()}).
     */
    private static String[] discoverViaSdk(String cliPath) throws Exception {
        CopilotClientOptions opts = new CopilotClientOptions();
        if (cliPath != null && !cliPath.isBlank()) {
            opts.setCliPath(cliPath);
        }
        try (CopilotClient client = new CopilotClient(opts)) {
            client.start().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<ModelInfo> models = client.listModels().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            List<String> ids = new ArrayList<>();
            if (models != null) {
                for (ModelInfo m : models) {
                    if (m != null && m.getId() != null) {
                        ids.add(m.getId());
                    }
                }
            }
            return assembleModelList(ids);
        }
    }

    /**
     * Tier 2: drive the CLI's JSON-RPC server directly — spawn
     * {@code copilot --server --stdio}, do a {@code ping} handshake, then call
     * {@code models.list}. Uses only gson; no SDK classes. Mirrors exactly what
     * the SDK does on the wire (LSP-framed JSON-RPC 2.0).
     */
    private static String[] discoverViaRpc(String cliPath) throws Exception {
        Process proc = new ProcessBuilder(buildServerCommand(cliPath)).start();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT_SECONDS * 1000);
                proc.destroyForcibly();
            }
            catch (InterruptedException ignored) {
            }
        }, "copilot-rpc-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            OutputStream out = proc.getOutputStream();
            InputStream in = proc.getInputStream();
            writeFramed(out, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}");
            writeFramed(out, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"models.list\",\"params\":{}}");
            out.flush();
            String body;
            while ((body = readFramed(in)) != null) {
                JsonObject msg = GSON.fromJson(body, JsonObject.class);
                if (msg != null && msg.has("id") && msg.get("id").isJsonPrimitive()
                        && msg.get("id").getAsInt() == 2) {
                    return assembleModelList(parseModelIds(body));
                }
            }
            throw new java.io.IOException("RPC stream closed before models.list response");
        }
        finally {
            watchdog.interrupt();
            proc.destroyForcibly();
        }
    }

    /**
     * Per-OS command to start the CLI as a stdio JSON-RPC server (mirrors the
     * SDK).
     */
    private static List<String> buildServerCommand(String cliPath) {
        String path = (cliPath != null && !cliPath.isBlank()) ? cliPath : "copilot";
        List<String> cmd = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (path.endsWith(".js")) {
            cmd.add("node");
            cmd.add(path);
        }
        else if (os.contains("win")) {
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add(path);
        }
        else {
            cmd.add(path);
        }
        cmd.add("--server");
        cmd.add("--no-auto-update");
        cmd.add("--log-level");
        cmd.add("error");
        cmd.add("--stdio");
        return cmd;
    }

    /**
     * Extracts {@code result.models[].id} from a {@code models.list} response
     * body.
     */
    static List<String> parseModelIds(String responseBody) {
        List<String> ids = new ArrayList<>();
        JsonObject msg = GSON.fromJson(responseBody, JsonObject.class);
        if (msg == null || !msg.has("result") || !msg.get("result").isJsonObject()) {
            return ids;
        }
        JsonObject result = msg.getAsJsonObject("result");
        if (!result.has("models") || !result.get("models").isJsonArray()) {
            return ids;
        }
        for (JsonElement e : result.getAsJsonArray("models")) {
            if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                ids.add(e.getAsJsonObject().get("id").getAsString());
            }
        }
        return ids;
    }

    private static void writeFramed(OutputStream out, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }

    /**
     * Reads one LSP-framed message (Content-Length header + body). Returns the
     * body as a UTF-8 string, or null at end of stream.
     */
    static String readFramed(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            header.write(b);
            byte[] h = header.toByteArray();
            int s = h.length;
            if (s >= 4 && h[s - 4] == '\r' && h[s - 3] == '\n' && h[s - 2] == '\r' && h[s - 1] == '\n') {
                break;
            }
        }
        if (header.size() == 0) {
            return null; // clean EOF, no message
        }
        int contentLength = -1;
        for (String line : header.toString(StandardCharsets.UTF_8).split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        if (contentLength < 0) {
            return null;
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n == -1) {
                return null;
            }
            read += n;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private GithubCopilotModelDiscovery() {
    }
}
