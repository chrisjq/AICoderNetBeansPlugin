package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotReasoningEffortsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotLaunch;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotRawRpc;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSelector;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;

/**
 * Queries the real list of models available to the account, via the official GitHub Copilot SDK for Java's
 * {@code CopilotClient.listModels()}, and feeds it into the model dropdown. The {@code copilot -p} runtime is
 * unchanged; this only refreshes the model list.
 * <p>
 * Discovery is best-effort and fully isolated: any failure (CLI missing, RPC error, timeout) is swallowed and the
 * hardcoded fallback list remains in use.
 */
public final class GithubCopilotModelDiscovery {

    private static final Logger LOG = Logger.getLogger(GithubCopilotModelDiscovery.class.getName());
    private static final Gson GSON = new Gson();

    private static final int MAX_RETRIES = 20;
    private static final AtomicBoolean inProgress = new AtomicBoolean(false);
    private static volatile int retryCount = 0;

    /**
     * Builds the dropdown list from discovered model ids: "auto" first (it always works), then the discovered ids in
     * order, de-duplicated and trimmed, blanks dropped. Pure and side-effect free.
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
     * Starts a fresh discovery cycle: resets the retry counter and submits a background fetch. Does nothing if a
     * discovery is already in progress. On failure, retries up to {@value #MAX_RETRIES} times within the cycle.
     *
     * @param cliPath the located copilot CLI path, or null to use PATH
     */
    public static void discoverAsync(String cliPath, Consumer<String[]> onResult) {
        retryCount = 0;
        tryDiscover(cliPath, onResult);
    }

    private static void tryDiscover(String cliPath, Consumer<String[]> onResult) {
        if (!inProgress.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            boolean shouldRetry = false;
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
                    retryCount = 0;
                    LOG.log(Level.INFO, "Copilot model discovery succeeded via {0} ({1} models)",
                            new Object[]{method, models.length});
                    if (onResult != null) {
                        onResult.accept(models);
                    }
                }
                else {
                    int attempt = ++retryCount;
                    if (attempt < MAX_RETRIES) {
                        LOG.log(Level.INFO, "Copilot model discovery: no models returned (attempt {0}/{1}), retrying",
                                new Object[]{attempt, MAX_RETRIES});
                        shouldRetry = true;
                    }
                    else {
                        LOG.log(Level.INFO, "Copilot model discovery: using static fallback model list after {0} attempts", attempt);
                    }
                }
            }
            finally {
                inProgress.set(false);
            }
            if (shouldRetry) {
                tryDiscover(cliPath, onResult);
            }
        }, "copilot-model-discovery");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Tier 1: official Copilot SDK ({@code CopilotClient.listModels()}). Also populates
     * {@code GithubCopilotPluginSettings}'s per-model reasoning-effort cache from each {@code ModelInfo}'s
     * {@code getSupportedReasoningEfforts()}/{@code getDefaultReasoningEffort()} — the live discovery, so nothing about
     * effort levels is hardcoded. The direct-RPC fallback tier does not carry this (its response shape is not verified
     * to include these fields), so a model discovered only via that tier is treated as "no support" until SDK-tier
     * discovery succeeds — the fail-safe default.
     */
    private static String[] discoverViaSdk(String cliPath) throws Exception {
        long timeout = GithubCopilotTimeoutEnum.COPILOT_MODEL_DISCOVERY_MILLIS.millis();
        CopilotSdkClient client = CopilotSdkSelector.sdkFor(cliPath).start(
                new CopilotLaunch(cliPath, List.of(), null, Duration.ofMillis(timeout)));
        try {
            List<CopilotModel> models = client.listModels().get(timeout, TimeUnit.MILLISECONDS);
            List<String> ids = new ArrayList<>();
            Map<String, List<String>> supportedByModel = new LinkedHashMap<>();
            Map<String, String> defaultByModel = new LinkedHashMap<>();
            if (models != null) {
                for (CopilotModel model : models) {
                    if (model == null || model.id() == null) {
                        continue;
                    }
                    ids.add(model.id());
                    if (model.supportedEfforts() != null && !model.supportedEfforts().isEmpty()) {
                        supportedByModel.put(model.id(), List.copyOf(model.supportedEfforts()));
                    }
                    if (model.defaultEffort() != null && !model.defaultEffort().isBlank()) {
                        defaultByModel.put(model.id(), model.defaultEffort());
                    }
                }
            }
            GithubCopilotAiImplementation.publishReasoningEfforts(
                    new GithubCopilotReasoningEffortsEvent(supportedByModel, defaultByModel));
            return assembleModelList(ids);
        }
        finally {
            try {
                client.close();
            }
            catch (Throwable closeEx) {
                LOG.log(Level.FINE, "Ignoring benign error while closing Copilot client after model discovery", closeEx);
            }
        }
    }

    /**
     * Tier 2: drive the CLI's JSON-RPC server directly — spawn {@code copilot --server --stdio}, do a {@code ping}
     * handshake, then call {@code models.list}. Uses only gson; no SDK classes. Mirrors exactly what the SDK does on
     * the wire (LSP-framed JSON-RPC 2.0).
     */
    private static String[] discoverViaRpc(String cliPath) throws Exception {
        CopilotRawRpc.Response response = CopilotRawRpc.call(cliPath, "models.list",
                                                             new JsonObject(), GithubCopilotTimeoutEnum.COPILOT_MODEL_DISCOVERY_MILLIS.millis());
        return assembleModelList(parseModelIds("{\"result\":" + GSON.toJson(response.result()) + "}"));
    }

    /**
     * Extracts {@code result.models[].id} from a {@code models.list} response body.
     */
    static List<String> parseModelIds(String responseBody) {
        List<String> ids = new ArrayList<>();
        JsonObject msg = GSON.fromJson(responseBody, JsonObject.class);
        String resultKey = GithubCopilotJsonKeyEnum.RESULT.key();
        if (msg == null || !msg.has(resultKey) || !msg.get(resultKey).isJsonObject()) {
            return ids;
        }
        JsonObject result = msg.getAsJsonObject(resultKey);
        String modelsKey = GithubCopilotJsonKeyEnum.MODELS.key();
        if (!result.has(modelsKey) || !result.get(modelsKey).isJsonArray()) {
            return ids;
        }
        String modelIdKey = GithubCopilotJsonKeyEnum.MODEL_ID.key();
        for (JsonElement e : result.getAsJsonArray(modelsKey)) {
            if (e.isJsonObject() && e.getAsJsonObject().has(modelIdKey)) {
                ids.add(e.getAsJsonObject().get(modelIdKey).getAsString());
            }
        }
        return ids;
    }

    private GithubCopilotModelDiscovery() {
    }
}
