package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.generated.rpc.AccountGetQuotaResult;
import com.github.copilot.generated.rpc.AccountQuotaSnapshot;
import com.github.copilot.rpc.CopilotClientOptions;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GithubCopilotQuotaService {

    private static final Logger LOG = Logger.getLogger(GithubCopilotQuotaService.class.getName());
    private static final long TIMEOUT_SECONDS = 30;
    private static final Gson GSON = new Gson();

    public static void getQuotaAsync(String cliPath, Consumer<QuotaInfo> onResult) {
        Thread t = new Thread(() -> {
            try {
                QuotaInfo quota = null;
                String method = null;
                try {
                    quota = getQuotaViaSdk(cliPath);
                    method = "SDK";
                }
                catch (Throwable sdkEx) {
                    LOG.log(Level.INFO, "Copilot quota fetch via SDK failed; trying direct RPC", sdkEx);
                    try {
                        quota = getQuotaViaRpc(cliPath);
                        method = "direct RPC";
                    }
                    catch (Throwable rpcEx) {
                        LOG.log(Level.INFO, "Copilot quota fetch via direct RPC failed", rpcEx);
                    }
                }

                if (quota != null) {
                    LOG.log(Level.INFO, "Copilot quota fetch succeeded via {0}", method);
                    if (onResult != null) {
                        onResult.accept(quota);
                    }
                }
                else {
                    LOG.log(Level.INFO, "Copilot quota fetch failed");
                }
            }
            catch (Throwable t1) {
                LOG.log(Level.INFO, "Unexpected error in quota fetch", t1);
            }
        }, "copilot-quota-fetch");
        t.setDaemon(true);
        t.start();
    }

    private static QuotaInfo getQuotaViaSdk(String cliPath) throws Exception {
        CopilotClientOptions opts = new CopilotClientOptions();
        if (cliPath != null && !cliPath.isBlank()) {
            opts.setCliPath(cliPath);
        }
        // Note: CopilotClient is deliberately NOT closed via try-with-resources here.
        // The SDK's close() can race with in-flight RPC teardown and throw a benign
        // "Client closed" CompletionException; with try-with-resources that exception
        // would replace an already-successful return value. Instead we close in a
        // finally block and swallow any close-time failure so it can never clobber
        // a valid result or be mistaken for a fetch failure.
        CopilotClient client = new CopilotClient(opts);
        try {
            client.start().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            AccountGetQuotaResult result = client.getRpc().account.getQuota().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result != null) {
                Map<String, AccountQuotaSnapshot> snapshots = result.quotaSnapshots();
                if (snapshots != null) {
                    LOG.log(Level.INFO, "Copilot quota snapshots: {0}", snapshots);
                    AccountQuotaSnapshot premiumSnapshot = snapshots.get("premium_interactions");
                    if (premiumSnapshot != null) {
                        java.time.OffsetDateTime resetDateTime = premiumSnapshot.resetDate();
                        String resetDate = resetDateTime != null ? resetDateTime.toString() : null;
                        return new QuotaInfo(
                                premiumSnapshot.isUnlimitedEntitlement(),
                                premiumSnapshot.usedRequests(),
                                premiumSnapshot.entitlementRequests(),
                                premiumSnapshot.remainingPercentage(),
                                resetDate
                        );
                    }
                }
            }
        }
        finally {
            try {
                client.close();
            }
            catch (Throwable closeEx) {
                LOG.log(Level.FINE, "Ignoring benign error while closing Copilot client after quota fetch", closeEx);
            }
        }
        return null;
    }

    private static QuotaInfo getQuotaViaRpc(String cliPath) throws Exception {
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
            GithubCopilotModelDiscovery.writeFramed(out, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}");
            GithubCopilotModelDiscovery.writeFramed(out, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"account.getQuota\",\"params\":{}}");
            out.flush();
            String body;
            while ((body = GithubCopilotModelDiscovery.readFramed(in)) != null) {
                JsonObject msg = GSON.fromJson(body, JsonObject.class);
                if (msg != null && msg.has("id") && msg.get("id").isJsonPrimitive()
                        && msg.get("id").getAsInt() == 2) {
                    return parseQuotaResponse(body);
                }
            }
            throw new java.io.IOException("RPC stream closed before account.getQuota response");
        }
        finally {
            watchdog.interrupt();
            proc.destroyForcibly();
        }
    }

    private static QuotaInfo parseQuotaResponse(String responseBody) {
        JsonObject msg = GSON.fromJson(responseBody, JsonObject.class);
        if (msg == null || !msg.has("result") || !msg.get("result").isJsonObject()) {
            return null;
        }
        JsonObject result = msg.getAsJsonObject("result");
        if (!result.has("quotaSnapshots") || !result.get("quotaSnapshots").isJsonObject()) {
            return null;
        }
        JsonObject snapshots = result.getAsJsonObject("quotaSnapshots");
        LOG.log(Level.INFO, "Copilot quota snapshots: {0}", snapshots);
        if (!snapshots.has("premium_interactions") || !snapshots.get("premium_interactions").isJsonObject()) {
            return null;
        }
        JsonObject premiumSnapshot = snapshots.getAsJsonObject("premium_interactions");

        boolean unlimited = premiumSnapshot.has("isUnlimitedEntitlement") && premiumSnapshot.get("isUnlimitedEntitlement").getAsBoolean();
        long usedRequests = premiumSnapshot.has("usedRequests") ? premiumSnapshot.get("usedRequests").getAsLong() : 0;
        long entitlementRequests = premiumSnapshot.has("entitlementRequests") ? premiumSnapshot.get("entitlementRequests").getAsLong() : 0;
        double remainingPercentage = premiumSnapshot.has("remainingPercentage") ? premiumSnapshot.get("remainingPercentage").getAsDouble() : 0;
        String resetDate = premiumSnapshot.has("resetDate") ? premiumSnapshot.get("resetDate").getAsString() : null;
        return new QuotaInfo(unlimited, usedRequests, entitlementRequests, remainingPercentage, resetDate);
    }

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

    private GithubCopilotQuotaService() {
    }
}
