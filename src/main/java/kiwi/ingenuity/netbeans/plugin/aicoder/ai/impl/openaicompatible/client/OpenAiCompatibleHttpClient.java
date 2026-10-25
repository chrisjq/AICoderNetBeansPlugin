package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.BackendExecutors;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult.TimeoutKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;

/**
 * One streamed {@code /chat/completions} request that can never hang (spec §3.3, §3.5, §7.1–§7.3): HTTP/1.1 through the
 * NetBeans proxy settings, a watchdog task per request for first-byte, idle and total limits, one transport retry
 * before any output, compatibility resends for rejected optional fields, and a {@link RequestHandle} that cancels every
 * phase. Nothing here blocks a thread: the work is a chain of futures and the backoff wait runs on the shared
 * scheduler.
 */
public final class OpenAiCompatibleHttpClient {

    /**
     * Receives the traffic of one call for the backend debug log.
     */
    public interface DebugSink {

        DebugSink NONE = new DebugSink() {
            @Override
            public void sent(String json) {
            }

            @Override
            public void received(String line) {
            }

            @Override
            public void event(String text) {
            }
        };

        void sent(String json);

        void received(String line);

        void event(String text);
    }

    static final String BLIND_RETRY_MESSAGE = "This server rejected optional request fields; they are no longer sent.";

    private static final long ERROR_BODY_CAP = 64L * 1024;

    private final HttpClient http;

    /**
     * @param limits supplies the connect timeout (a per-client setting of the JDK client)
     */
    public OpenAiCompatibleHttpClient(OpenAiCompatibleLimits limits) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.getDefault())
                .connectTimeout(limits.connectTimeout())
                .build();
    }

    public OpenAiCompatibleHttpClient() {
        this(OpenAiCompatibleLimits.defaults());
    }

    /**
     * Sends one request; the key situation for error messages is derived from {@code apiKeyOrNull}.
     */
    public CompletableFuture<ClientResult> send(ChatRequestSpec spec, CompatibilityFlags flags, char[] apiKeyOrNull,
                                                List<ExtraHeader> headers, OpenAiCompatibleLimits limits,
                                                RequestHandle handle, Consumer<String> onTextDelta, DebugSink debug) {
        return send(spec, flags, apiKeyOrNull, headers, limits, handle, onTextDelta, debug,
                    apiKeyOrNull == null ? ErrorMapper.KeySituation.NONE : ErrorMapper.KeySituation.SENT);
    }

    /**
     * Sends one request. {@code keySituation} tells the error mapper why a 401/403 happened (for example a key that
     * could not be read from the Keyring).
     */
    public CompletableFuture<ClientResult> send(ChatRequestSpec spec, CompatibilityFlags flags, char[] apiKeyOrNull,
                                                List<ExtraHeader> headers, OpenAiCompatibleLimits limits,
                                                RequestHandle handle, Consumer<String> onTextDelta, DebugSink debug,
                                                ErrorMapper.KeySituation keySituation) {
        return send(spec, flags, apiKeyOrNull, headers, limits, handle, onTextDelta, null, debug, keySituation);
    }

    /**
     * As above, with {@code onReasoningDelta} receiving each streamed reasoning-field delta ({@code reasoning_content}
     * and the like) as it arrives; may be null.
     */
    public CompletableFuture<ClientResult> send(ChatRequestSpec spec, CompatibilityFlags flags, char[] apiKeyOrNull,
                                                List<ExtraHeader> headers, OpenAiCompatibleLimits limits,
                                                RequestHandle handle, Consumer<String> onTextDelta,
                                                Consumer<String> onReasoningDelta, DebugSink debug,
                                                ErrorMapper.KeySituation keySituation) {
        Call call = new Call(spec, flags, apiKeyOrNull, headers == null ? List.of() : headers, limits, handle,
                             onTextDelta == null ? text -> {
                                     } : onTextDelta,
                             onReasoningDelta == null ? text -> {
                                     } : onReasoningDelta,
                             debug == null ? DebugSink.NONE : debug, keySituation);
        return run(call, spec, flags, false);
    }

    private CompletableFuture<ClientResult> run(Call call, ChatRequestSpec spec, CompatibilityFlags flags,
                                                boolean blind) {
        if (call.handle.isCancelled()) {
            return CompletableFuture.completedFuture(
                    new ClientResult(null, ClientResult.End.CANCELLED, null, call.retriesUsed > 0));
        }
        Attempt attempt = new Attempt(call);
        if (!call.handle.attach(attempt)) {
            return CompletableFuture.completedFuture(
                    new ClientResult(null, ClientResult.End.CANCELLED, null, call.retriesUsed > 0));
        }
        attempt.start(spec, flags);
        return attempt.done.thenCompose(attempted -> decide(call, spec, flags, blind, attempted));
    }

    private CompletableFuture<ClientResult> decide(Call call, ChatRequestSpec spec, CompatibilityFlags flags,
                                                   boolean blind, Attempted attempted) {
        ClientResult result = attempted.result;
        boolean retried = call.retriesUsed > 0;
        switch (result.end()) {
            case CANCELLED, TIMEOUT, SIZE_LIMIT -> {
                return CompletableFuture.completedFuture(result.withRetried(retried));
            }
            case HTTP_ERROR -> {
                return decideHttpError(call, spec, flags, blind, attempted);
            }
            case ERROR -> {
                if (!result.reply().sawOutput() && retryableTransport(attempted.cause) && canRetry(call)) {
                    return retryAfterBackoff(call, spec, flags, blind, call.limits.retryBackoff(),
                                             "connection problem");
                }
                return CompletableFuture.completedFuture(result.withRetried(retried));
            }
            default -> {
                AssembledReply reply = result.reply();
                if (!reply.endedCleanly() && !reply.sawOutput() && canRetry(call)) {
                    return retryAfterBackoff(call, spec, flags, blind, call.limits.retryBackoff(),
                                             "the server ended the reply without output");
                }
                if (blind && reply.endedCleanly()) {
                    call.flags.dropAllOptional();
                    call.debug.event(BLIND_RETRY_MESSAGE);
                }
                return CompletableFuture.completedFuture(result.withRetried(retried));
            }
        }
    }

    private CompletableFuture<ClientResult> decideHttpError(Call call, ChatRequestSpec spec, CompatibilityFlags flags,
                                                            boolean blind, Attempted attempted) {
        ClientResult result = attempted.result;
        MappedError error = result.error();
        if (!blind && attempted.status == 400 && spec.reasoningEffort() != null
                && mentionsReasoningRejection(error.message())
                && call.compatFields.add(CompatibilityFlags.REASONING_EFFORT)) {
            call.flags.turnOff(CompatibilityFlags.REASONING_EFFORT);
            call.debug.event("The server rejected reasoning effort; it is no longer sent.");
            return run(call, call.spec, call.flags, false);
        }
        if (!blind && error.kind() == MappedError.Kind.REJECTED_FIELD && error.rejectedField() != null
                && call.compatFields.add(error.rejectedField())) {
            call.flags.turnOff(error.rejectedField());
            call.debug.event("The server rejected '" + error.rejectedField() + "'; it is no longer sent.");
            return run(call, call.spec, call.flags, false);
        }
        if (!blind && error.kind() == MappedError.Kind.UNNAMED_REJECTION && !call.blindDone
                && !call.flags.optionalFieldsPresent(call.spec).isEmpty()) {
            call.blindDone = true;
            call.debug.event("The server rejected the request without naming a field; retrying without optional "
                    + "fields.");
            CompatibilityFlags dropped = new CompatibilityFlags();
            dropped.dropAllOptional();
            ChatRequestSpec bare = new ChatRequestSpec(call.spec.apiBase(), call.spec.model(), call.spec.messages(),
                                                       call.spec.tools(), null, null, null, null);
            return run(call, bare, dropped, true);
        }
        int status = attempted.status;
        if ((status == 429 || status == 502 || status == 503) && canRetry(call)) {
            Duration delay = call.limits.retryBackoff();
            if (status == 429 && attempted.retryAfterSeconds >= 0) {
                long capped = Math.min(attempted.retryAfterSeconds, call.limits.retryAfterCap().toSeconds());
                delay = Duration.ofSeconds(capped);
            }
            return retryAfterBackoff(call, spec, flags, blind, delay,
                                     status == 429 ? "the server is rate-limiting requests" : "HTTP " + status);
        }
        if (error.kind() == MappedError.Kind.RATE_LIMITED) {
            MappedError tryLater = new MappedError(MappedError.Kind.RATE_LIMITED,
                                                   "The server is rate-limiting requests — try again shortly.",
                                                   null, null);
            return CompletableFuture.completedFuture(new ClientResult(result.reply(), ClientResult.End.HTTP_ERROR,
                                                                      tryLater, call.retriesUsed > 0));
        }
        return CompletableFuture.completedFuture(result.withRetried(call.retriesUsed > 0));
    }

    private static boolean mentionsReasoningRejection(String message) {
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(java.util.Locale.ROOT);
        return text.contains("reasoning") || text.contains("thinking") || text.contains("effort");
    }

    private CompletableFuture<ClientResult> retryAfterBackoff(Call call, ChatRequestSpec spec,
                                                              CompatibilityFlags flags, boolean blind,
                                                              Duration delay, String reason) {
        call.retriesUsed++;
        call.debug.event("Retry " + call.retriesUsed + "/" + call.limits.transportRetries() + " in "
                + delay.toSeconds() + " s (" + reason + ")");
        return call.handle.backoff(delay).thenCompose(proceed -> {
            if (!proceed) {
                return CompletableFuture.completedFuture(
                        new ClientResult(null, ClientResult.End.CANCELLED, null, true));
            }
            return run(call, spec, flags, blind);
        });
    }

    private static boolean canRetry(Call call) {
        return call.retriesUsed < call.limits.transportRetries();
    }

    private static boolean retryableTransport(Throwable cause) {
        if (cause == null || cause instanceof SSLException || cause instanceof CancellationException
                || cause instanceof HttpTimeoutException) {
            return false;
        }
        return cause instanceof IOException;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Everything that stays the same across the attempts of one {@code send} call.
     */
    private static final class Call {

        final ChatRequestSpec spec;
        final CompatibilityFlags flags;
        final char[] apiKey;
        final List<ExtraHeader> headers;
        final OpenAiCompatibleLimits limits;
        final RequestHandle handle;
        final Consumer<String> onTextDelta;
        final Consumer<String> onReasoningDelta;
        final DebugSink debug;
        final ErrorMapper.KeySituation keySituation;
        final Set<String> compatFields = new java.util.HashSet<>();
        final AtomicBoolean sentLogged = new AtomicBoolean();
        volatile int retriesUsed;
        volatile boolean blindDone;

        Call(ChatRequestSpec spec, CompatibilityFlags flags, char[] apiKey, List<ExtraHeader> headers,
             OpenAiCompatibleLimits limits, RequestHandle handle, Consumer<String> onTextDelta,
             Consumer<String> onReasoningDelta, DebugSink debug, ErrorMapper.KeySituation keySituation) {
            this.spec = spec;
            this.flags = flags;
            this.apiKey = apiKey;
            this.headers = headers;
            this.limits = limits;
            this.handle = handle;
            this.onTextDelta = onTextDelta;
            this.onReasoningDelta = onReasoningDelta;
            this.debug = debug;
            this.keySituation = keySituation;
        }
    }

    private record Attempted(ClientResult result, int status, long retryAfterSeconds, Throwable cause) {

    }

    /**
     * One HTTP exchange with its watchdog. {@code done} completes exactly once.
     */
    private final class Attempt implements RequestHandle.Cancellable {

        private final Call call;
        private final long startNanos = System.nanoTime();
        private final ChunkAssembler assembler = new ChunkAssembler();
        final CompletableFuture<Attempted> done = new CompletableFuture<>();
        private final AtomicReference<TimeoutKind> fired = new AtomicReference<>();
        private volatile CompletableFuture<?> httpFuture;
        private volatile Consumer<StreamingBody.End> stopper;
        private volatile long lastDataNanos = startNanos;
        private volatile boolean gotByte;
        private volatile boolean cancelled;
        private volatile boolean streamed;
        private volatile ScheduledFuture<?> watchdog;

        Attempt(Call call) {
            this.call = call;
            assembler.onTextDelta(call.onTextDelta);
            assembler.onReasoningDelta(call.onReasoningDelta);
        }

        void start(ChatRequestSpec spec, CompatibilityFlags flags) {
            done.whenComplete((attempted, error) -> {
                ScheduledFuture<?> timer = watchdog;
                if (timer != null) {
                    timer.cancel(false);
                }
                call.handle.detach(this);
            });
            if (done.isDone()) {
                return;
            }
            HttpRequest request;
            try {
                String json = RequestBuilder.build(spec, flags).toString();
                if (call.sentLogged.compareAndSet(false, true)) {
                    call.debug.sent(json);
                }
                request = buildRequest(spec, json);
            }
            catch (RuntimeException ex) {
                finishTransportError(ex);
                return;
            }
            startWatchdog();
            CompletableFuture<HttpResponse<StreamingBody.Outcome>> future = http.sendAsync(request, this::bodyFor);
            httpFuture = future;
            if (done.isDone()) {
                future.cancel(true);
            }
            future.whenComplete(this::onHttp);
        }

        private HttpRequest buildRequest(ChatRequestSpec spec, String json) {
            String base = spec.apiBase();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(call.limits.firstByteTimeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream");
            if (call.apiKey != null) {
                builder.header("Authorization", "Bearer " + new String(call.apiKey));
            }
            for (ExtraHeader header : call.headers) {
                try {
                    builder.header(header.name(), header.value());
                }
                catch (IllegalArgumentException ex) {
                    call.debug.event("Header " + header.name() + " was not sent: it is restricted.");
                }
            }
            return builder.POST(HttpRequest.BodyPublishers.ofString(json)).build();
        }

        private void startWatchdog() {
            long interval = Math.max(1L, call.limits.checkInterval().toMillis());
            try {
                watchdog = BackendExecutors.scheduler()
                        .scheduleWithFixedDelay(this::check, interval, interval, TimeUnit.MILLISECONDS);
                if (done.isDone()) {
                    watchdog.cancel(false);
                }
            }
            catch (RuntimeException ex) {
                call.debug.event("The timeout check could not be scheduled: " + ex.getMessage());
            }
        }

        private HttpResponse.BodySubscriber<StreamingBody.Outcome> bodyFor(HttpResponse.ResponseInfo info) {
            int status = info.statusCode();
            if (status >= 200 && status < 300) {
                boolean sse = info.headers().firstValue("Content-Type")
                        .map(value -> value.toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
                        .orElse(false);
                streamed = sse;
                StreamingBody body = new StreamingBody(sse, call.limits.toolArgumentCapBytes(),
                                                       call.limits.transcriptCapBytes(), this::onSseLine, this::touch);
                installStopper(body::stop);
                return body;
            }
            ErrorBody body = new ErrorBody(ERROR_BODY_CAP, this::touch);
            installStopper(body::stop);
            return body;
        }

        private void onSseLine(String line) {
            synchronized (assembler) {
                assembler.onSseLine(line);
            }
            call.debug.received(line);
        }

        private void touch() {
            gotByte = true;
            lastDataNanos = System.nanoTime();
        }

        private void installStopper(Consumer<StreamingBody.End> candidate) {
            stopper = candidate;
            if (cancelled) {
                candidate.accept(StreamingBody.End.CANCELLED);
            }
            else if (fired.get() != null) {
                candidate.accept(StreamingBody.End.TIMEOUT);
            }
        }

        private void check() {
            if (done.isDone()) {
                return;
            }
            long now = System.nanoTime();
            TimeoutKind kind = null;
            if (!gotByte) {
                if (now - startNanos > call.limits.firstByteTimeout().toNanos()) {
                    kind = TimeoutKind.FIRST_BYTE;
                }
            }
            else if (!call.limits.idleTimeoutOff() && now - lastDataNanos > call.limits.idleTimeout().toNanos()) {
                kind = TimeoutKind.IDLE;
            }
            if (kind == null && now - startNanos > call.limits.totalCap().toNanos()) {
                kind = TimeoutKind.TOTAL;
            }
            if (kind != null) {
                timeout(kind);
            }
        }

        private void timeout(TimeoutKind kind) {
            if (!fired.compareAndSet(null, kind)) {
                return;
            }
            call.debug.event("Timeout: " + kind.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
            Consumer<StreamingBody.End> current = stopper;
            if (current != null) {
                current.accept(StreamingBody.End.TIMEOUT);
            }
            finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.TIMEOUT, null, false, kind),
                                 0, -1, null));
            cancelHttp();
        }

        @Override
        public void cancelAttempt() {
            cancelled = true;
            Consumer<StreamingBody.End> current = stopper;
            if (current != null) {
                current.accept(StreamingBody.End.CANCELLED);
            }
            finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.CANCELLED, null, false),
                                 0, -1, null));
            cancelHttp();
        }

        private void cancelHttp() {
            CompletableFuture<?> future = httpFuture;
            if (future != null) {
                future.cancel(true);
            }
        }

        private AssembledReply partialReply() {
            synchronized (assembler) {
                return assembler.result();
            }
        }

        private void finish(Attempted attempted) {
            done.complete(attempted);
        }

        private void finishTransportError(Throwable failure) {
            MappedError mapped = ErrorMapper.mapException(failure, call.spec.apiBase());
            if (mapped.kind() == MappedError.Kind.SERVER_ERROR && failure instanceof IOException) {
                mapped = new MappedError(MappedError.Kind.CONNECTION_FAILED,
                                         "The server closed the connection without answering (" + call.spec.apiBase()
                                         + ").", null, null);
            }
            finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.ERROR, mapped, false),
                                 0, -1, failure));
        }

        private void onHttp(HttpResponse<StreamingBody.Outcome> response, Throwable failure) {
            if (done.isDone()) {
                return;
            }
            if (failure != null) {
                Throwable cause = unwrap(failure);
                TimeoutKind kind = fired.get();
                if (kind != null) {
                    finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.TIMEOUT, null, false,
                                                          kind), 0, -1, null));
                }
                else if (cancelled || cause instanceof CancellationException) {
                    finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.CANCELLED, null, false),
                                         0, -1, null));
                }
                else if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
                    fired.compareAndSet(null, TimeoutKind.FIRST_BYTE);
                    call.debug.event("Timeout: first byte.");
                    finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.TIMEOUT, null, false,
                                                          TimeoutKind.FIRST_BYTE), 0, -1, null));
                }
                else {
                    finishTransportError(cause);
                }
                return;
            }
            StreamingBody.Outcome outcome = response.body();
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                finishHttpStatus(response, outcome, status);
                return;
            }
            finishSuccessStatus(outcome, status);
        }

        private void finishHttpStatus(HttpResponse<StreamingBody.Outcome> response, StreamingBody.Outcome outcome,
                                      int status) {
            if (outcome.end() == StreamingBody.End.TIMEOUT || outcome.end() == StreamingBody.End.CANCELLED) {
                boolean timeout = outcome.end() == StreamingBody.End.TIMEOUT;
                finish(new Attempted(new ClientResult(null,
                                                      timeout ? ClientResult.End.TIMEOUT : ClientResult.End.CANCELLED,
                                                      null, false, timeout ? fired.get() : null), status, -1, null));
                return;
            }
            String body = outcome.nonStreamingBody() == null ? "" : outcome.nonStreamingBody();
            MappedError error = ErrorMapper.map(status, body, call.keySituation, call.spec.apiBase(),
                                                call.spec.model());
            long retryAfter = response.headers().firstValue("Retry-After").map(Attempt::parseSeconds).orElse(-1L);
            finish(new Attempted(new ClientResult(null, ClientResult.End.HTTP_ERROR, error, false), status,
                                 retryAfter, null));
        }

        private void finishSuccessStatus(StreamingBody.Outcome outcome, int status) {
            switch (outcome.end()) {
                case COMPLETE -> {
                    if (!streamed) {
                        synchronized (assembler) {
                            assembler.fromNonStreaming(outcome.nonStreamingBody());
                        }
                        call.debug.event("The server does not stream; the reply was read as one body.");
                        call.debug.received(outcome.nonStreamingBody() == null ? "" : outcome.nonStreamingBody());
                    }
                    finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.COMPLETE, null, false)
                            .withNonStreaming(!streamed), status, -1, null));
                }
                case SIZE_LIMIT ->
                    finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.SIZE_LIMIT, null, false),
                                         status, -1, null));
                case TIMEOUT ->
                    finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.TIMEOUT, null, false,
                                                          fired.get()), status, -1, null));
                case CANCELLED ->
                    finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.CANCELLED, null, false),
                                         status, -1, null));
                default -> {
                    Throwable cause = outcome.error() == null ? null : unwrap(outcome.error());
                    MappedError mapped = ErrorMapper.mapException(cause, call.spec.apiBase());
                    finish(new Attempted(new ClientResult(partialReply(), ClientResult.End.ERROR, mapped, false),
                                         status, -1, cause));
                }
            }
        }

        private static long parseSeconds(String value) {
            try {
                return Math.max(0L, Long.parseLong(value.trim()));
            }
            catch (NumberFormatException ex) {
                return -1L;
            }
        }
    }

    /**
     * Collects an error body up to a cap, then completes (truncated) and cancels the rest.
     */
    private static final class ErrorBody implements HttpResponse.BodySubscriber<StreamingBody.Outcome> {

        private final long cap;
        private final Runnable onBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<StreamingBody.Outcome> result = new CompletableFuture<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile Flow.Subscription subscription;

        ErrorBody(long cap, Runnable onBytes) {
            this.cap = cap;
            this.onBytes = onBytes;
        }

        void stop(StreamingBody.End end) {
            finish(new StreamingBody.Outcome(end, null, null), true);
        }

        @Override
        public java.util.concurrent.CompletionStage<StreamingBody.Outcome> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (done.get()) {
                subscription.cancel();
                return;
            }
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done.get()) {
                return;
            }
            onBytes.run();
            for (ByteBuffer item : items) {
                long room = cap - buffer.size();
                int take = (int) Math.min(room, item.remaining());
                byte[] bytes = new byte[take];
                item.get(bytes);
                buffer.write(bytes, 0, take);
                if (buffer.size() >= cap) {
                    finish(new StreamingBody.Outcome(StreamingBody.End.COMPLETE,
                                                     buffer.toString(StandardCharsets.UTF_8), null), true);
                    return;
                }
            }
            Flow.Subscription current = subscription;
            if (current != null && !done.get()) {
                current.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            finish(new StreamingBody.Outcome(StreamingBody.End.ERROR, null, throwable), false);
        }

        @Override
        public void onComplete() {
            finish(new StreamingBody.Outcome(StreamingBody.End.COMPLETE, buffer.toString(StandardCharsets.UTF_8),
                                             null), false);
        }

        private void finish(StreamingBody.Outcome outcome, boolean cancelSubscription) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            if (cancelSubscription) {
                Flow.Subscription current = subscription;
                if (current != null) {
                    current.cancel();
                }
            }
            result.complete(outcome);
        }
    }
}
