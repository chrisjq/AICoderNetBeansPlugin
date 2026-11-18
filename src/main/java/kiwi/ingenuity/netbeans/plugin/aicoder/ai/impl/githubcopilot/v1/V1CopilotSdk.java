package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v1;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotLaunch;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdk;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkException;

/**
 * Protocol 3 adapter for the original Copilot Java SDK.
 */
public final class V1CopilotSdk implements CopilotSdk {

    public V1CopilotSdk() {
    }

    @Override
    public String label() {
        return "v1 (protocol 3)";
    }

    @Override
    public CopilotSdkClient start(CopilotLaunch launch) throws CopilotSdkException {
        CopilotClient client = null;
        try {
            List<String> args = new ArrayList<>();
            args.add("--no-auto-update");
            if (launch.extraArgs() != null) {
                args.addAll(launch.extraArgs());
            }
            CopilotClientOptions options = new CopilotClientOptions()
                    .setCliPath(launch.executablePath()).setCliArgs(args.toArray(String[]::new));
            if (launch.executor() != null) {
                options.setExecutor(launch.executor());
            }
            client = new CopilotClient(options);
            client.start().get(launch.startTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new V1Client(client);
        }
        catch (Exception | LinkageError failure) {
            if (client != null) {
                try {
                    client.close();
                }
                catch (Exception | LinkageError cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CopilotSdkException(messageOf(failure), failure);
        }
    }

    /**
     * The message of the deepest cause, or its {@code toString()} when that message is null, so a timeout never reports
     * a null message.
     */
    static String messageOf(Throwable failure) {
        Throwable detail = failure;
        while (detail.getCause() != null && detail.getCause() != detail) {
            detail = detail.getCause();
        }
        return detail.getMessage() != null ? detail.getMessage() : detail.toString();
    }
}
