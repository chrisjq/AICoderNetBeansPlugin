package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;

/**
 * Best-effort discovery of models available to the current Codex CLI account.
 *
 * <p>
 * The discovery process is deliberately independent of a chat session: it starts a short-lived
 * {@code codex app-server}, initializes JSON-RPC, reads every {@code model/list} page, then closes the transport and
 * process. One budget covers a whole attempt, so a slow server cannot stretch it page by page. A failed or timed-out
 * attempt is retried; an empty but successful answer is final, and so is a process that will not launch. Callers'
 * editable model controls remain usable with an empty catalog.
 */
public final class CodexModelDiscovery {

    private static final Logger LOG = Logger.getLogger(CodexModelDiscovery.class.getName());
    private static final int MAX_RETRIES = 3;
    private static final int MAX_PAGES = 50;
    private static final long RETRY_BACKOFF_MILLIS = 500L;
    private static final long STOP_GRACE_MILLIS = 1_500L;
    private static final AtomicBoolean IN_PROGRESS = new AtomicBoolean();
    static volatile Long discoveryBudgetMillisForTests;

    /**
     * The process could not be started at all (a missing executable, say). Retrying cannot change that, so it is final.
     */
    private static final class LaunchFailure extends Exception {

        private static final long serialVersionUID = 1L;

        LaunchFailure(IOException cause) {
            super(cause);
        }
    }

    public static void discoverAsync(String executablePath, Consumer<List<String>> onResult) {
        if (!IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                List<String> models = null;
                for (int attempt = 1; attempt <= MAX_RETRIES && models == null; attempt++) {
                    try {
                        models = query(executablePath);
                    }
                    catch (LaunchFailure ex) {
                        LOG.log(Level.FINE, "Codex model discovery could not start the CLI", ex);
                        break;
                    }
                    catch (Exception ex) {
                        LOG.log(Level.FINE, "Codex model discovery attempt " + attempt + " failed", ex);
                        if (attempt < MAX_RETRIES) {
                            pauseBeforeRetry();
                        }
                    }
                }
                if (onResult != null) {
                    onResult.accept(models != null ? models : List.of());
                }
            }
            finally {
                IN_PROGRESS.set(false);
            }
        }, "codex-model-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Whether a discovery is running. Package-private so a test can wait for a previous run to finish.
     */
    static boolean discoveryInProgressForTests() {
        return IN_PROGRESS.get();
    }

    /**
     * One discovery attempt that reports any failure as an empty list.
     */
    static List<String> discover(String executablePath) {
        try {
            return query(executablePath);
        }
        catch (Exception ex) {
            LOG.log(Level.FINE, "Codex model discovery failed", ex);
            return List.of();
        }
    }

    /**
     * One attempt, sharing a single budget across the initialize and every page. Throws when the attempt fails or the
     * budget is spent before the list is complete, so the caller can tell a failure from a genuinely empty catalogue.
     */
    private static List<String> query(String executablePath) throws Exception {
        if (executablePath == null || executablePath.isBlank()) {
            throw new LaunchFailure(new IOException("no Codex executable was located"));
        }
        Process process;
        try {
            process = new ProcessBuilder(CodexExecutableLocator.buildHostCommand(executablePath, "app-server"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        }
        catch (IOException ex) {
            throw new LaunchFailure(ex);
        }
        try {
            CodexJsonRpcClient client = new CodexJsonRpcClient(process.getOutputStream(), process.getInputStream(),
                                                               (method, params) -> {
                                                               },
                                                               (method, params) -> CompletableFuture.completedFuture(new JsonObject()),
                                                               cause -> {
                                                               });
            try {
                long budget = discoveryBudgetMillisForTests != null
                              ? discoveryBudgetMillisForTests : CodexTimeoutEnum.CODEX_MODEL_DISCOVERY_MILLIS.millis();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
                client.sendRequest("initialize", CodexAiProcessManager.buildInitializeParams(
                                   "aicoder-netbeans", "AI Coder for NetBeans", Installer.VERSION))
                        .get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
                client.sendNotification("initialized", new JsonObject());
                return parseModels(readModelList(client, deadline));
            }
            finally {
                stop(process, client);
            }
        }
        finally {
            // Covers a failure while building the client, which stop() never reaches.
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Reads every {@code model/list} page on an initialized client, following the cursor until it ends, and merges them
     * into one page with no {@code nextCursor}. Throws when the list cannot be read whole: a repeated cursor, the page
     * cap, a failed page, or the deadline. So a caller never gets a partial list it could mistake for the complete one.
     *
     * @param deadline the {@link System#nanoTime()} after which no further request is sent
     */
    static JsonObject readModelList(CodexJsonRpcClient client, long deadline) throws Exception {
        JsonArray data = new JsonArray();
        Set<String> seenCursors = new LinkedHashSet<>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            JsonObject params = new JsonObject();
            if (cursor != null && !cursor.isBlank()) {
                params.addProperty(CodexJsonKeyEnum.CURSOR.key(), cursor);
            }
            JsonObject page = client.sendRequest("model/list", params).get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            if (!page.has(CodexJsonKeyEnum.DATA.key()) || !page.get(CodexJsonKeyEnum.DATA.key()).isJsonArray()) {
                throw new IllegalStateException("codex model/list page has no data array");
            }
            data.addAll(page.getAsJsonArray(CodexJsonKeyEnum.DATA.key()));
            cursor = nextCursor(page);
            if (cursor == null || cursor.isBlank()) {
                JsonObject merged = new JsonObject();
                merged.add(CodexJsonKeyEnum.DATA.key(), data);
                return merged;
            }
            if (!acceptCursor(seenCursors, cursor)) {
                throw new IllegalStateException("codex model/list repeated cursor " + cursor);
            }
        }
        throw new IllegalStateException("codex model/list exceeded " + MAX_PAGES + " pages");
    }

    /**
     * Time left in the attempt's budget. Throws once it is spent, so a page that would start after the deadline is
     * never sent.
     */
    private static long remainingNanos(long deadline) throws TimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new TimeoutException("codex model discovery budget spent");
        }
        return left;
    }

    /**
     * Ends the probe: closes the transport, asks the process to exit, and after a short grace period kills it. The
     * children are captured first, because the npm {@code codex} launcher is a Node wrapper whose native child would be
     * orphaned by an immediate SIGKILL of the wrapper alone.
     */
    private static void stop(Process process, CodexJsonRpcClient client) {
        List<ProcessHandle> children = process.descendants().toList();
        client.close();
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static List<String> parseModels(JsonObject page) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (page == null || !page.has(CodexJsonKeyEnum.DATA.key())
                || !page.get(CodexJsonKeyEnum.DATA.key()).isJsonArray()) {
            return List.of();
        }
        JsonArray data = page.getAsJsonArray(CodexJsonKeyEnum.DATA.key());
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject model = element.getAsJsonObject();
            if (isHidden(model) || !model.has(CodexJsonKeyEnum.ID.key())
                    || model.get(CodexJsonKeyEnum.ID.key()).isJsonNull()) {
                continue;
            }
            String id = model.get(CodexJsonKeyEnum.ID.key()).getAsString();
            if (id != null && !id.isBlank()) {
                models.add(id);
            }
        }
        return new ArrayList<>(models);
    }

    /**
     * Reads the generated codex-cli 0.167.1 Model schema's required {@code hidden} flag.
     */
    static boolean isHidden(JsonObject model) {
        return model.has(CodexJsonKeyEnum.HIDDEN.key())
                && !model.get(CodexJsonKeyEnum.HIDDEN.key()).isJsonNull()
                && model.get(CodexJsonKeyEnum.HIDDEN.key()).isJsonPrimitive()
                && model.get(CodexJsonKeyEnum.HIDDEN.key()).getAsBoolean();
    }

    static boolean acceptCursor(Set<String> seenCursors, String cursor) {
        return cursor != null && !cursor.isBlank() && seenCursors.add(cursor);
    }

    static String nextCursor(JsonObject page) {
        if (page == null) {
            return null;
        }
        if (page.has(CodexJsonKeyEnum.NEXT_CURSOR.key()) && !page.get(CodexJsonKeyEnum.NEXT_CURSOR.key()).isJsonNull()) {
            return page.get(CodexJsonKeyEnum.NEXT_CURSOR.key()).getAsString();
        }
        return null;
    }

    private CodexModelDiscovery() {
    }
}
