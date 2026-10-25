package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.StreamingBody;

/**
 * One bounded probe of a server endpoint (spec §3.4). The exchange starts on {@link BackendExecutors#probePool()} (a
 * full pool counts as a failed probe for the cycle) and the WHOLE exchange, headers and body, is bounded by
 * {@link OpenAiCompatibleLimits#probeTimeout()} counted from the probe's start, not its queueing. The body is capped at
 * {@link OpenAiCompatibleLimits#probeBodyCapBytes()}. The API key is sent as a Bearer token only when given.
 *
 * <p>
 * Result kinds: any HTTP answer means the server is reachable. 2xx is {@code OK} (with the parsed JSON, or null when
 * the body is not JSON); 401/403 {@code UNAUTHORIZED}; 404, 405 and 501 {@code NOT_FOUND} ("not this kind of server");
 * 429 {@code RATE_LIMITED}; other statuses, an oversized body, a stall after the headers and a failed read are
 * {@code FAILED}. No connection, or no response at all before the timeout, is {@code UNREACHABLE}.
 */
public final class ProbeClient {

    public enum Kind {
        OK,
        NOT_FOUND,
        UNAUTHORIZED,
        RATE_LIMITED,
        UNREACHABLE,
        FAILED
    }

    /**
     * @param status the HTTP status, or 0 when no response arrived
     * @param json the parsed body of an {@code OK} answer, null when the body is empty or not JSON
     * @param kind how the probe ended
     */
    public record ProbeResult(int status, JsonElement json, Kind kind) {

        static ProbeResult unreachable() {
            return new ProbeResult(0, null, Kind.UNREACHABLE);
        }

        static ProbeResult failed(int status) {
            return new ProbeResult(status, null, Kind.FAILED);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.getDefault())
            .build();

    /**
     * Name of the probe-pool thread that last started a probe; visible to tests in this package.
     */
    volatile String lastProbeThreadName;

    public CompletableFuture<ProbeResult> get(String url, char[] keyOrNull, OpenAiCompatibleLimits limits) {
        return run("GET", url, null, keyOrNull, limits);
    }

    /**
     * POSTs {@code jsonBody} as {@code application/json}.
     */
    public CompletableFuture<ProbeResult> post(String url, String jsonBody, char[] keyOrNull,
                                               OpenAiCompatibleLimits limits) {
        return run("POST", url, jsonBody == null ? "" : jsonBody, keyOrNull, limits);
    }

    private CompletableFuture<ProbeResult> run(String method, String url, String body, char[] keyOrNull,
                                               OpenAiCompatibleLimits limits) {
        CompletableFuture<ProbeResult> result = new CompletableFuture<>();
        try {
            BackendExecutors.probePool().execute(() -> {
                lastProbeThreadName = Thread.currentThread().getName();
                new Probe(result, limits).start(method, url, body, keyOrNull);
            });
        }
        catch (RejectedExecutionException ex) {
            result.complete(ProbeResult.failed(0));
        }
        return result;
    }

    /**
     * The state of one running probe.
     */
    private static final class Probe {

        private final CompletableFuture<ProbeResult> result;
        private final OpenAiCompatibleLimits limits;
        private volatile boolean gotHeaders;
        private volatile StreamingBody body;
        private volatile CompletableFuture<?> http;
        private volatile ScheduledFuture<?> timer;

        Probe(CompletableFuture<ProbeResult> result, OpenAiCompatibleLimits limits) {
            this.result = result;
            this.limits = limits;
        }

        void start(String method, String url, String requestBody, char[] keyOrNull) {
            HttpRequest request;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(limits.probeTimeout())
                        .header("Accept", "application/json");
                if (keyOrNull != null && keyOrNull.length > 0) {
                    builder.header("Authorization", "Bearer " + new String(keyOrNull));
                }
                if (requestBody == null) {
                    builder.GET();
                }
                else {
                    builder.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                }
                request = builder.build();
            }
            catch (RuntimeException ex) {
                result.complete(ProbeResult.failed(0));
                return;
            }
            result.whenComplete((probeResult, error) -> cleanUp());
            try {
                timer = BackendExecutors.scheduler().schedule(this::timeout,
                                                              Math.max(1L, limits.probeTimeout().toMillis()), TimeUnit.MILLISECONDS);
                if (result.isDone()) {
                    timer.cancel(false);
                }
            }
            catch (RuntimeException ex) {
                // the request's own timeout still bounds the headers
            }
            CompletableFuture<HttpResponse<StreamingBody.Outcome>> future = HTTP.sendAsync(request, this::bodyFor);
            http = future;
            if (result.isDone()) {
                future.cancel(true);
            }
            future.whenComplete(this::onDone);
        }

        private HttpResponse.BodySubscriber<StreamingBody.Outcome> bodyFor(HttpResponse.ResponseInfo info) {
            gotHeaders = true;
            long cap = limits.probeBodyCapBytes();
            StreamingBody subscriber = new StreamingBody(false, cap, cap, line -> {
                                                 }, () -> {
                                                 });
            body = subscriber;
            if (result.isDone()) {
                subscriber.stop(StreamingBody.End.CANCELLED);
            }
            return subscriber;
        }

        /**
         * The whole-exchange limit: a stall after the headers is a failed probe, silence before them is unreachable.
         */
        private void timeout() {
            result.complete(gotHeaders ? ProbeResult.failed(0) : ProbeResult.unreachable());
        }

        private void cleanUp() {
            ScheduledFuture<?> scheduled = timer;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            StreamingBody current = body;
            if (current != null) {
                current.stop(StreamingBody.End.CANCELLED);
            }
            CompletableFuture<?> future = http;
            if (future != null) {
                future.cancel(true);
            }
        }

        private void onDone(HttpResponse<StreamingBody.Outcome> response, Throwable failure) {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                result.complete(isUnreachable(unwrap(failure)) ? ProbeResult.unreachable()
                                : ProbeResult.failed(0));
                return;
            }
            int status = response.statusCode();
            StreamingBody.Outcome outcome = response.body();
            if (status == 401 || status == 403) {
                result.complete(new ProbeResult(status, null, Kind.UNAUTHORIZED));
            }
            else if (status == 404 || status == 405 || status == 501) {
                result.complete(new ProbeResult(status, null, Kind.NOT_FOUND));
            }
            else if (status == 429) {
                result.complete(new ProbeResult(status, null, Kind.RATE_LIMITED));
            }
            else if (status >= 200 && status < 300 && outcome.end() == StreamingBody.End.COMPLETE) {
                result.complete(new ProbeResult(status, parseJson(outcome.nonStreamingBody()), Kind.OK));
            }
            else {
                result.complete(ProbeResult.failed(status));
            }
        }

        private static JsonElement parseJson(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return JsonParser.parseString(text);
            }
            catch (RuntimeException ex) {
                return null;
            }
        }

        private static Throwable unwrap(Throwable error) {
            Throwable current = error;
            while ((current instanceof CompletionException || current instanceof ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }

        private static boolean isUnreachable(Throwable failure) {
            Throwable current = failure;
            while (current != null) {
                if (current instanceof ConnectException || current instanceof UnknownHostException
                        || current instanceof HttpTimeoutException
                        || current instanceof ClosedChannelException) {
                    return true;
                }
                if (current.getCause() == current) {
                    return false;
                }
                current = current.getCause();
            }
            return failure instanceof IOException && failure.getMessage() != null
                    && failure.getMessage().contains("refused");
        }
    }
}
