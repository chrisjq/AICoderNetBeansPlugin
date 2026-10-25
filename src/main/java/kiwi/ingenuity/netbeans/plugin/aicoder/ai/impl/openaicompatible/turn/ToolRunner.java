package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;

/**
 * Runs a reply's tool calls one at a time on the session's tool executor (spec §4.2, §7.2). The turn thread waits on
 * each call's future; a Cancel interrupts that wait, never the tool: the tool keeps running in the background, its
 * result is discarded, and the next turn waits for it ({@link #awaitIdle}). One instance per session, so the abandoned
 * tool of one turn is visible to the next.
 */
public final class ToolRunner {

    /**
     * Runs one tool synchronously and returns its result text (the MCP bridge's {@code invokeTool}).
     */
    @FunctionalInterface
    public interface Invoker {

        String invoke(String toolName, JsonObject arguments);
    }

    /**
     * Told when each call starts, for the {@code ToolUseEvent}.
     */
    @FunctionalInterface
    public interface Listener {

        void toolStarted(ChatToolCall call);
    }

    /**
     * The result of one call. {@code completed} is false when the turn stopped waiting for it (Cancel) or never ran it;
     * {@code invalidArguments} is true when the call was not run because its arguments failed validation.
     */
    public record ToolOutcome(ChatToolCall call, String result, boolean completed, boolean invalidArguments) {

        public ToolOutcome(ChatToolCall call, String result, boolean completed) {
            this(call, result, completed, false);
        }
    }

    /**
     * What one {@link #run} produced: the outcome of every call, in order, and whether a Cancel cut it short.
     */
    public record RunResult(List<ToolOutcome> outcomes, boolean cancelled, String cancelledTool) {

    }

    static final String NOT_RUN_NOTE = "Not run: the user cancelled before this call started.";

    private final Invoker invoker;
    private final Supplier<ExecutorService> executor;
    private final Listener listener;

    private final Object lock = new Object();
    private Future<String> inFlight;
    private String inFlightName;

    public ToolRunner(Invoker invoker, Supplier<ExecutorService> executor, Listener listener) {
        this.invoker = invoker;
        this.executor = executor;
        this.listener = listener == null ? call -> {
        } : listener;
    }

    /**
     * The model's calls with every missing or duplicate id replaced by {@code call_<turn>_<round>_<n>} (n counts from 1
     * within the reply), so each tool result can be matched to its call.
     */
    public static List<ChatToolCall> withIds(List<ChatToolCall> calls, int turnNo, int roundNo) {
        List<ChatToolCall> out = new ArrayList<>(calls.size());
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (ChatToolCall call : calls) {
            n++;
            String id = call.id();
            if (id == null || id.isBlank() || !seen.add(id)) {
                id = "call_" + turnNo + "_" + roundNo + "_" + n;
                seen.add(id);
            }
            out.add(new ChatToolCall(id, call.name(), call.argumentsJson()));
        }
        return out;
    }

    /**
     * The user-facing note for a call whose outcome is unknown because the user cancelled while it ran.
     */
    public static String cancelledNote(String toolName) {
        return "The user cancelled; " + toolName + " was running and its outcome is unknown.";
    }

    /**
     * Runs {@code calls} (ids already assigned) one after another. If the waiting thread is interrupted (Cancel), the
     * running call gets {@link #cancelledNote}, the calls after it are not started, and the interrupt flag is kept.
     *
     * @param onStart told as each call starts (the turn's status line), after the runner's own listener; may be null
     */
    public RunResult run(List<ChatToolCall> calls, Listener onStart) {
        List<ToolOutcome> outcomes = new ArrayList<>(calls.size());
        String cancelledTool = null;
        for (ChatToolCall call : calls) {
            if (cancelledTool != null) {
                outcomes.add(new ToolOutcome(call, NOT_RUN_NOTE, false));
                continue;
            }
            if (Thread.currentThread().isInterrupted()) {
                cancelledTool = "";
                outcomes.add(new ToolOutcome(call, NOT_RUN_NOTE, false));
                continue;
            }
            JsonObject arguments;
            try {
                arguments = parseArguments(call.argumentsJson());
            }
            catch (RuntimeException ex) {
                outcomes.add(new ToolOutcome(call, "Invalid arguments: " + ex.getMessage(), true, true));
                continue;
            }
            listener.toolStarted(call);
            if (onStart != null) {
                onStart.toolStarted(call);
            }
            Future<String> future;
            try {
                future = executor.get().submit(() -> invoker.invoke(call.name(), arguments));
            }
            catch (RejectedExecutionException ex) {
                outcomes.add(new ToolOutcome(call, "Error: the tool could not be started (the session is stopping).",
                                             true));
                continue;
            }
            synchronized (lock) {
                inFlight = future;
                inFlightName = call.name();
            }
            try {
                outcomes.add(new ToolOutcome(call, future.get(), true));
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                cancelledTool = call.name();
                outcomes.add(new ToolOutcome(call, cancelledNote(call.name()), false));
            }
            catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                outcomes.add(new ToolOutcome(call, "Error: " + describe(cause), true));
            }
            catch (CancellationException ex) {
                outcomes.add(new ToolOutcome(call, "Error: the tool was stopped.", true));
            }
        }
        return new RunResult(outcomes, cancelledTool != null,
                             cancelledTool == null || cancelledTool.isEmpty() ? null : cancelledTool);
    }

    /**
     * True while a tool started by this runner is still running (including one a cancelled turn abandoned).
     */
    public boolean isBusy() {
        synchronized (lock) {
            return inFlight != null && !inFlight.isDone();
        }
    }

    /**
     * The name of the tool that is running, or null when none is.
     */
    public String busyToolName() {
        synchronized (lock) {
            return inFlight != null && !inFlight.isDone() ? inFlightName : null;
        }
    }

    /**
     * Waits until the running tool (if any) finishes.
     *
     * @return true when no tool is running any more, false when {@code max} passed first
     *
     * @throws InterruptedException when the waiting thread is interrupted (Cancel)
     */
    public boolean awaitIdle(Duration max) throws InterruptedException {
        Future<String> running;
        synchronized (lock) {
            running = inFlight;
        }
        if (running == null || running.isDone()) {
            return true;
        }
        try {
            running.get(Math.max(0L, max.toNanos()), TimeUnit.NANOSECONDS);
            return true;
        }
        catch (TimeoutException ex) {
            return false;
        }
        catch (ExecutionException | CancellationException ex) {
            return true;
        }
    }

    private static JsonObject parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (parsed == null || parsed.isJsonNull()) {
            return new JsonObject();
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("expected a JSON object, got " + json.strip());
        }
        return parsed.getAsJsonObject();
    }

    private static String describe(Throwable failure) {
        String detail = failure.getMessage();
        return detail == null || detail.isBlank() ? failure.getClass().getSimpleName() : detail;
    }
}
