package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools;

import java.util.Arrays;

/**
 * <b>Every duration in the plugin belongs here</b>, unless it is domain-specific — that is, owned by one AI backend,
 * which keeps its own enum ({@code OpenCodeTimeoutEnum}, {@code GrokTimeoutEnum}, {@code CodexTimeoutEnum}). There is
 * no other exemption. UI delays, poll intervals and animation timings are durations like any other and live here too:
 * a literal in a {@code new Timer(...)} call is a magic number wherever it appears, and putting it here is what makes
 * every timing in the system findable and tunable from one place.
 * <p>
 * A UI timing does NOT need to be kept out to protect {@link #MUTATION_LOCK_WAIT_MILLIS} — that calculation filters on
 * {@link Kind#OPERATION_OR_WAIT} alone, so any other {@link Kind} is already excluded structurally. Choosing the right
 * Kind is the whole mechanism; keeping a value out of this enum is not.
 * <p>
 * Shared durations for user-facing tools and their supporting services. Lock lifetime and lock acquisition wait remain
 * distinct: lifetime releases abandoned locks, while a wait only bounds contention for a live holder. External I/O
 * timeouts (HTTP, database) are a separate {@link Kind#EXTERNAL_IO} category, deliberately excluded from
 * {@link #MUTATION_LOCK_WAIT_MILLIS} — they bound calls to other processes/machines, not mutation-lock handler
 * execution, so folding them into {@link Kind#OPERATION_OR_WAIT} would silently raise that ceiling.
 */
public enum TimeoutEnum {
    BUILD_PROCESS_MILLIS(600_000L, Kind.OPERATION_OR_WAIT),
    WEB_REQUEST_DEFAULT_MILLIS(30_000L, Kind.OPERATION_OR_WAIT),
    WEB_REQUEST_MAX_MILLIS(300_000L, Kind.OPERATION_OR_WAIT),
    USER_APPROVAL_WAIT_MILLIS(120_000L, Kind.OPERATION_OR_WAIT),
    USER_QUESTION_WAIT_MILLIS(300_000L, Kind.OPERATION_OR_WAIT),
    CLAUDE_CREDENTIAL_POLL_MILLIS(30_000L, Kind.BACKGROUND_INTERVAL),
    LOCK_CLEANUP_INTERVAL_MILLIS(30_000L, Kind.BACKGROUND_INTERVAL),
    GIT_LOCK_LIFETIME_MILLIS(300_000L, Kind.LOCK_LIFETIME),
    BUILD_LOCK_LIFETIME_MILLIS(600_000L, Kind.LOCK_LIFETIME),
    REFACTOR_LOCK_LIFETIME_MILLIS(180_000L, Kind.LOCK_LIFETIME),
    FILE_WRITE_LOCK_LIFETIME_MILLIS(120_000L, Kind.LOCK_LIFETIME),
    SESSION_LOCK_LIFETIME_MILLIS(60_000L, Kind.LOCK_LIFETIME),
    PROJECT_STRUCTURE_LOCK_LIFETIME_MILLIS(300_000L, Kind.LOCK_LIFETIME),
    GIT_LOCK_WAIT_MILLIS(5_000L, Kind.OPERATION_OR_WAIT),
    BUILD_LOCK_WAIT_MILLIS(120_000L, Kind.OPERATION_OR_WAIT),
    REFACTOR_LOCK_WAIT_MILLIS(5_000L, Kind.OPERATION_OR_WAIT),
    FILE_WRITE_LOCK_WAIT_MILLIS(0L, Kind.OPERATION_OR_WAIT),
    SESSION_LOCK_WAIT_MILLIS(5_000L, Kind.OPERATION_OR_WAIT),
    PROJECT_STRUCTURE_LOCK_WAIT_MILLIS(5_000L, Kind.OPERATION_OR_WAIT),
    LOCK_WAIT_POLL_MILLIS(50L, Kind.BACKGROUND_INTERVAL),
    MCP_REGISTRY_POLL_INTERVAL_MILLIS(60_000L, Kind.BACKGROUND_INTERVAL),
    /**
     * How old a plugin-created temp file (pasted images, tool-result logs) may get before the periodic age sweep
     * deletes it. Deliberately uncritical: session close, IDE shutdown and plugin uninstall each remove whole temp
     * directories regardless of age, so this value only bounds how much a very long-lived session can accumulate.
     * <p>
     * The trade is against live references, not disk: a spooled build log or a pasted image older than this is swept
     * while its session is still open, so an agent that revisits a tool-result log — or a {@code @tmp.} marker left
     * unsent in the input box — finds it gone. Both degrade safely (the log is re-creatable by re-running the tool, and
     * TmpMarkerExpander reports an unresolvable marker to the user rather than sending a dead path), which is what
     * makes four hours acceptable rather than merely tidy: long enough to outlast an ordinary working session, short
     * enough that an abandoned one does not hoard build logs until the IDE closes.
     */
    TEMP_FILE_MAX_AGE_MILLIS(14_400_000L, Kind.LOCK_LIFETIME),
    TEMP_FILE_SWEEP_INTERVAL_MILLIS(60_000L, Kind.BACKGROUND_INTERVAL),
    /**
     * Delay before re-stating a file the user has just accepted an AI diff for, so the IDE picks up the new bytes.
     * The write itself has already completed by then; this only defers the refresh briefly.
     * <p>
     * The original choice of 600 ms was an undocumented literal at the call site and no rationale was recorded, so
     * treat it as "short enough to be imperceptible, long enough to land after the accept settles" rather than as a
     * tuned value.
     */
    ACCEPTED_DIFF_REFRESH_DELAY_MILLIS(600L, Kind.UI_FEEDBACK),
    /**
     * Tick interval for the session clock in the info bar. One second because the clock renders whole seconds —
     * shorter repaints without changing the text, longer drops digits.
     */
    CLOCK_TICK_MILLIS(1_000L, Kind.UI_FEEDBACK),
    /**
     * How long a code block's copy button shows its confirmation tick before reverting to the copy glyph. Long enough
     * to register, short enough to be back to normal before a second copy.
     */
    COPY_FEEDBACK_RESET_MILLIS(1_200L, Kind.UI_FEEDBACK),
    /**
     * Duration of the tab status dot's flash each time AI output arrives mid-turn. Short enough that continuous
     * streaming reads as a steady pulse rather than a colour change.
     */
    THINKING_FLASH_MILLIS(250L, Kind.UI_FEEDBACK),
    /**
     * Poll interval while waiting for the conversation view's layout to stop changing after new content, before
     * scrolling to the end. Streaming resizes the panel repeatedly; this samples until height stabilises.
     */
    SCROLL_SETTLE_POLL_MILLIS(30L, Kind.UI_FEEDBACK),
    DATABASE_QUERY_TIMEOUT_MILLIS(300_000L, Kind.EXTERNAL_IO),
    OPENAI_HTTP_REQUEST_TIMEOUT_MILLIS(300_000L, Kind.EXTERNAL_IO),
    OPENAI_HTTP_CONNECT_TIMEOUT_MILLIS(10_000L, Kind.EXTERNAL_IO),
    MCP_HTTP_IDLE_INTERVAL_MILLIS(300_000L, Kind.EXTERNAL_IO),
    /**
     * Upper bound for waiting on one AI backend's MCP endpoint registration with its CLI (the {@code .get(...)} after
     * handing the registrar to {@code McpServerRegistry.register}). This blocks a local subprocess handshake — another
     * process, possibly a hung CLI — hence {@link Kind#EXTERNAL_IO} so it never silently raises the mutation-lock
     * ceiling. Replaces the former hardcoded two-minute waits and the dead per-backend {@code *_MCP_REGISTER_MILLIS}
     * constants.
     */
    MCP_REGISTRATION_WAIT_MILLIS(120_000L, Kind.EXTERNAL_IO),
    /**
     * Upper bound for {@code McpServerRegistry.stopAll()}'s join on the exiting supervisor thread. The supervisor's
     * shutdown path now also undoes each still-registered type's CLI hooks/endpoint (bounded subprocess calls that
     * self-cap at a few seconds each), so this must cover a couple of CLI invocations — while still guaranteeing a
     * wedged CLI can never make IDE exit hang unboundedly. {@link Kind#EXTERNAL_IO}: it waits on other processes and
     * must not raise the mutation-lock ceiling.
     */
    MCP_SHUTDOWN_TEARDOWN_WAIT_MILLIS(10_000L, Kind.EXTERNAL_IO),
    /**
     * How long plugin shutdown waits for the shared session-persist executor to finish already-queued work (history and
     * session-config saves) after {@code shutdown()}. Queued saves are user data and must be given time to land; a
     * wedged task must nonetheless not hang IDE exit, hence the bound.
     */
    PERSIST_EXECUTOR_SHUTDOWN_WAIT_MILLIS(10_000L, Kind.OPERATION_OR_WAIT),
    AI_MODEL_CATALOG_REFRESH_TIMEOUT_MILLIS(120_000L, Kind.EXTERNAL_IO);

    private static final long MUTATION_LOCK_SAFETY_MARGIN_MILLIS = 10000L;

    /**
     * The longest individual operation or lock-acquisition wait, plus a small scheduling margin. The calculation
     * deliberately ranges over every {@link Kind#OPERATION_OR_WAIT} value, including durations for operations that do
     * not take the mutation lock (such as the web-request cap). This intentional over-estimate is safe because a waiter
     * only waits longer than necessary; an under-estimate could make it give up while a legitimate lock holder is still
     * working. Consequently, increasing any operation-or-wait duration also increases this ceiling, even when that
     * operation does not itself hold the mutation lock. Lock lifetimes are excluded because they bound stale-lock
     * cleanup, not handler execution. {@link Kind#EXTERNAL_IO} and {@link Kind#BACKGROUND_INTERVAL} durations are
     * likewise excluded — they bound calls to other processes/machines or periodic polling, not mutation-lock handler
     * execution, so a long HTTP or database timeout must not silently raise this ceiling.
     */
    public static final long MUTATION_LOCK_WAIT_MILLIS = Arrays.stream(values())
            .filter(timeout -> timeout.kind == Kind.OPERATION_OR_WAIT)
            .mapToLong(TimeoutEnum::millis)
            .max()
            .orElseThrow() + MUTATION_LOCK_SAFETY_MARGIN_MILLIS;

    private final long millis;
    private final Kind kind;

    TimeoutEnum(long millis, Kind kind) {
        this.millis = millis;
        this.kind = kind;
    }

    public long millis() {
        return millis;
    }

    private enum Kind {
        OPERATION_OR_WAIT,
        LOCK_LIFETIME,
        BACKGROUND_INTERVAL,
        /**
         * Bounds a call to another process or machine (HTTP, database). Never a mutation lock.
         */
        EXTERNAL_IO,
        /**
         * A purely presentational delay on the EDT — an animation pulse, a label reverting, a clock tick, a settle
         * poll. Bounds nothing and blocks nothing; changing one can only alter how the UI looks. Excluded from
         * {@link #MUTATION_LOCK_WAIT_MILLIS} like every non-{@link #OPERATION_OR_WAIT} kind.
         */
        UI_FEEDBACK
    }
}
