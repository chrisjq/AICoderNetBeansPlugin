package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.v2;

import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.rpc.CopilotClientMode;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.RuntimeConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotLaunch;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdk;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkException;

/**
 * Protocol-4 adapter for the Microsoft Copilot SDK.
 */
public final class V2CopilotSdk implements CopilotSdk {

    @Override
    public CopilotSdkClient start(CopilotLaunch launch) throws CopilotSdkException {
        CopilotClient client = null;
        try {
            RuntimeConnection runtime = RuntimeConnection.forSpawnedStdio(launch.executablePath())
                    .setArgs(spawnArgs(launch.extraArgs()));
            CopilotClientOptions options = new CopilotClientOptions()
                    .setMode(CopilotClientMode.CODING_AGENT)
                    .setRuntime(runtime);
            if (launch.executor() != null) {
                options.setExecutor(launch.executor());
            }
            client = new CopilotClient(options);
            client.start().get(launch.startTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return new V2Client(client);
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

    @Override
    public String label() {
        return "v2 (protocol 4)";
    }

    /**
     * The arguments handed to the spawned runtime: {@code --no-auto-update} first, then the caller's extra arguments.
     * The SDK adds {@code --server --stdio} itself.
     */
    static List<String> spawnArgs(List<String> extraArgs) {
        List<String> args = new ArrayList<>();
        args.add("--no-auto-update");
        if (extraArgs != null) {
            args.addAll(extraArgs);
        }
        return args;
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
