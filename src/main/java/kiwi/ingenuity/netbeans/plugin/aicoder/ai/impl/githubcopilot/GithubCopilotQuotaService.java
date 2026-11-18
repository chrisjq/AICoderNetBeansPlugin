package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotLaunch;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotQuotaSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotRawRpc;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSelector;

public final class GithubCopilotQuotaService {

    private static final Logger LOG = Logger.getLogger(GithubCopilotQuotaService.class.getName());
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
        long timeout = GithubCopilotTimeoutEnum.COPILOT_QUOTA_SERVICE_MILLIS.millis();
        CopilotSdkClient client = CopilotSdkSelector.sdkFor(cliPath).start(
                new CopilotLaunch(cliPath, List.of(), null, Duration.ofMillis(timeout)));
        try {
            Map<String, CopilotQuotaSnapshot> snapshots = client.quotaSnapshots().get(timeout, TimeUnit.MILLISECONDS);
            if (snapshots == null) {
                return null;
            }
            LOG.log(Level.INFO, "Copilot quota snapshots: {0}", snapshots);
            CopilotQuotaSnapshot premium = snapshots.get(GithubCopilotJsonKeyEnum.PREMIUM_INTERACTIONS.key());
            if (premium == null) {
                return null;
            }
            if (premium.usedRequests() == null || premium.entitlementRequests() == null
                    || premium.remainingPercentage() == null) {
                // A partial snapshot is unusable. Failing here sends the caller to the raw RPC tier rather than showing 0 of 0.
                throw new IllegalStateException("SDK quota snapshot is missing its usage or entitlement fields");
            }
            return new QuotaInfo(
                    Boolean.TRUE.equals(premium.unlimited()),
                    premium.usedRequests(),
                    premium.entitlementRequests(),
                    premium.remainingPercentage(),
                    premium.resetDate());
        }
        finally {
            try {
                client.close();
            }
            catch (Throwable closeEx) {
                LOG.log(Level.FINE, "Ignoring benign error while closing Copilot client after quota fetch", closeEx);
            }
        }
    }

    private static QuotaInfo getQuotaViaRpc(String cliPath) throws Exception {
        CopilotRawRpc.Response response = CopilotRawRpc.call(cliPath, "account.getQuota",
                                                             new JsonObject(), GithubCopilotTimeoutEnum.COPILOT_QUOTA_SERVICE_MILLIS.millis());
        return parseQuotaResponse("{\"result\":" + GSON.toJson(response.result()) + "}");
    }

    private static QuotaInfo parseQuotaResponse(String responseBody) {
        JsonObject msg = GSON.fromJson(responseBody, JsonObject.class);
        String resultKey = GithubCopilotJsonKeyEnum.RESULT.key();
        if (msg == null || !msg.has(resultKey) || !msg.get(resultKey).isJsonObject()) {
            return null;
        }
        JsonObject result = msg.getAsJsonObject(resultKey);
        String snapshotsKey = GithubCopilotJsonKeyEnum.QUOTA_SNAPSHOTS.key();
        if (!result.has(snapshotsKey) || !result.get(snapshotsKey).isJsonObject()) {
            return null;
        }
        JsonObject snapshots = result.getAsJsonObject(snapshotsKey);
        LOG.log(Level.INFO, "Copilot quota snapshots: {0}", snapshots);
        String premiumKey = GithubCopilotJsonKeyEnum.PREMIUM_INTERACTIONS.key();
        if (!snapshots.has(premiumKey) || !snapshots.get(premiumKey).isJsonObject()) {
            return null;
        }
        JsonObject premiumSnapshot = snapshots.getAsJsonObject(premiumKey);

        String unlimitedKey = GithubCopilotJsonKeyEnum.IS_UNLIMITED_ENTITLEMENT.key();
        String usedKey = GithubCopilotJsonKeyEnum.USED_REQUESTS.key();
        String entitlementKey = GithubCopilotJsonKeyEnum.ENTITLEMENT_REQUESTS.key();
        String remainingKey = GithubCopilotJsonKeyEnum.REMAINING_PERCENTAGE.key();
        String resetKey = GithubCopilotJsonKeyEnum.RESET_DATE.key();
        boolean unlimited = premiumSnapshot.has(unlimitedKey) && premiumSnapshot.get(unlimitedKey).getAsBoolean();
        long usedRequests = premiumSnapshot.has(usedKey) ? premiumSnapshot.get(usedKey).getAsLong() : 0;
        long entitlementRequests = premiumSnapshot.has(entitlementKey) ? premiumSnapshot.get(entitlementKey).getAsLong() : 0;
        double remainingPercentage = premiumSnapshot.has(remainingKey) ? premiumSnapshot.get(remainingKey).getAsDouble() : 0;
        String resetDate = premiumSnapshot.has(resetKey) ? premiumSnapshot.get(resetKey).getAsString() : null;
        return new QuotaInfo(unlimited, usedRequests, entitlementRequests, remainingPercentage, resetDate);
    }

    private GithubCopilotQuotaService() {
    }
}
