package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ChatRequestSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ClientResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.ErrorMapper;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.OpenAiCompatibleHttpClient.DebugSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.RequestHandle;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;

/**
 * Testable seam over the HTTP client's send operation. The nine-argument form is the functional method (lambdas and
 * {@code ::send} keep working); the ten-argument form adds a consumer for streamed reasoning-field deltas and, unless
 * an implementation overrides it, ignores them (the reasoning then arrives only with the finished reply).
 */
@FunctionalInterface
public interface ChatClient {

    CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags, char[] apiKey,
                                         List<ExtraHeader> headers, OpenAiCompatibleLimits limits, RequestHandle handle,
                                         Consumer<String> onTextDelta, DebugSink debug, ErrorMapper.KeySituation keySituation);

    /**
     * As the nine-argument form, with {@code onReasoningDelta} receiving each streamed reasoning-field delta. The
     * default drops them.
     */
    default CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags, char[] apiKey,
                                                 List<ExtraHeader> headers, OpenAiCompatibleLimits limits,
                                                 RequestHandle handle, Consumer<String> onTextDelta,
                                                 Consumer<String> onReasoningDelta, DebugSink debug,
                                                 ErrorMapper.KeySituation keySituation) {
        return send(request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation);
    }

    /**
     * The real HTTP client as a ChatClient, with reasoning deltas streamed.
     */
    static ChatClient of(OpenAiCompatibleHttpClient http) {
        return new ChatClient() {
            @Override
            public CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags,
                                                        char[] apiKey, List<ExtraHeader> headers,
                                                        OpenAiCompatibleLimits limits, RequestHandle handle,
                                                        Consumer<String> onTextDelta, DebugSink debug,
                                                        ErrorMapper.KeySituation keySituation) {
                return http.send(request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation);
            }

            @Override
            public CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags,
                                                        char[] apiKey, List<ExtraHeader> headers,
                                                        OpenAiCompatibleLimits limits, RequestHandle handle,
                                                        Consumer<String> onTextDelta, Consumer<String> onReasoningDelta,
                                                        DebugSink debug, ErrorMapper.KeySituation keySituation) {
                return http.send(request, flags, apiKey, headers, limits, handle, onTextDelta, onReasoningDelta,
                                 debug, keySituation);
            }
        };
    }

    /**
     * {@code delegate} with {@code after} applied to every result, forwarding both send forms (so reasoning deltas
     * still stream through the wrapper).
     */
    static ChatClient mapped(ChatClient delegate, UnaryOperator<ClientResult> after) {
        return new ChatClient() {
            @Override
            public CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags,
                                                        char[] apiKey, List<ExtraHeader> headers,
                                                        OpenAiCompatibleLimits limits, RequestHandle handle,
                                                        Consumer<String> onTextDelta, DebugSink debug,
                                                        ErrorMapper.KeySituation keySituation) {
                return delegate.send(request, flags, apiKey, headers, limits, handle, onTextDelta, debug, keySituation)
                        .thenApply(after);
            }

            @Override
            public CompletableFuture<ClientResult> send(ChatRequestSpec request, CompatibilityFlags flags,
                                                        char[] apiKey, List<ExtraHeader> headers,
                                                        OpenAiCompatibleLimits limits, RequestHandle handle,
                                                        Consumer<String> onTextDelta, Consumer<String> onReasoningDelta,
                                                        DebugSink debug, ErrorMapper.KeySituation keySituation) {
                return delegate.send(request, flags, apiKey, headers, limits, handle, onTextDelta, onReasoningDelta,
                                     debug, keySituation).thenApply(after);
            }
        };
    }
}
