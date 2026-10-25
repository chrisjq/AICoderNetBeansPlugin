package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerRemedyEnum;

/**
 * The set of active hints of one session with the fixed priority of spec §7.4. The highest-priority hint is the one
 * shown; every active hint is listed in the tooltip. Thread-safe: detection and turn threads set and clear hints, the
 * EDT reads them.
 */
public final class HintState {

    /**
     * Hint slots in priority order (1 is shown first). A slot holds one hint at a time; its text says which of the
     * conditions in the slot applies.
     */
    public enum HintKind {
        /**
         * 1 — the server cannot be reached.
         */
        UNREACHABLE,
        /**
         * 2 — API key rejected, unavailable, needed or not found on this computer, or a secret header needs its value.
         */
        KEY,
        /**
         * 3 — window too small for tools, or tools too big for the window.
         */
        TOOLS_DO_NOT_FIT,
        /**
         * 4 — the server cuts the prompt.
         */
        SERVER_CUTTING_PROMPT,
        /**
         * 5 — small context window.
         */
        SMALL_WINDOW,
        /**
         * 6 — window unknown.
         */
        WINDOW_UNKNOWN,
        /**
         * 7 — server type mismatch.
         */
        TYPE_MISMATCH,
        /**
         * 8 — tools share of the window.
         */
        TOOLS_SHARE;

        /**
         * @return 1 for the most important hint up to 8
         */
        public int priority() {
            return ordinal() + 1;
        }
    }

    /**
     * One active hint.
     */
    public record Hint(HintKind kind, String text, String tooltip) {

    }

    /**
     * The tools-share hint (rank 8) appears once the tool definitions take at least this share of the window.
     */
    public static final double TOOLS_SHARE_THRESHOLD = 0.25;

    private static final String MINIMAL_TEMPLATE_ADVICE = "Apply the 'Minimal' MCP template (session settings → MCP "
            + "Tools → Apply MCP template…), switch off tool groups, or raise the context window.";

    /**
     * Rank 3 label when the tool definitions do not fit the window: "Tools use ~N of W — apply Minimal".
     */
    public static String toolsTooBigText(int toolTokens, int window) {
        return "Tools use ~" + toolTokens + " of " + window + " — apply Minimal";
    }

    /**
     * Rank 3 label when a confirmed window is below the minimum window for tools.
     */
    public static String windowTooSmallForToolsText(int window) {
        return "Window " + window + " too small for tools";
    }

    /**
     * Whether the tools-share hint (rank 8) applies: the tools fit, but take at least {@link #TOOLS_SHARE_THRESHOLD} of
     * the window.
     */
    public static boolean toolsShareApplies(int toolTokens, int window) {
        return window > 0 && toolTokens > 0 && toolTokens >= TOOLS_SHARE_THRESHOLD * window;
    }

    /**
     * Rank 8 label: "Tools use ~N of W".
     */
    public static String toolsShareText(int toolTokens, int window) {
        return "Tools use ~" + toolTokens + " of " + window;
    }

    /**
     * Rank 8 tooltip: the share in whole percent and how to free room.
     */
    public static String toolsShareTooltip(int toolTokens, int window) {
        long percent = window <= 0 ? 0 : Math.round(100.0 * toolTokens / window);
        return "Tool definitions take about " + percent + " % of the " + window + "-token context window, leaving less "
                + "room for the conversation. " + MINIMAL_TEMPLATE_ADVICE;
    }

    /**
     * Rank 5 label (spec §7.4): a real detected window below the small-window warning threshold.
     */
    public static String smallWindowText(int window) {
        return "Small context window (" + window + ") — older history will be trimmed rather than summarised. "
                + "Consider raising it on the server.";
    }

    /**
     * Rank 5 tooltip: the per-kind remedy for raising the window (spec §4.7).
     */
    public static String smallWindowTooltip(int window, String kindName) {
        return "The model's context window is " + window + " tokens. To raise it: "
                + ServerRemedyEnum.forKind(kindName).remedy() + ".";
    }

    /**
     * Rank 7 label (spec §3.1): the chosen server type does not match what the server looks like.
     */
    public static String typeMismatchText(String chosenTypeName) {
        return "Server doesn't look like " + chosenTypeName + " — try Auto-detect";
    }

    /**
     * Rank 7 tooltip: what the server looks like, and that kind's remedy for raising the window.
     *
     * @param detectedKindName the detected kind's enum name (e.g. LMSTUDIO), or null when unknown
     */
    public static String typeMismatchTooltip(String chosenTypeName, String detectedKindName) {
        String detected = detectedKindName == null || detectedKindName.isBlank() ? "another server" : detectedKindName;
        return "The session is set to " + chosenTypeName + ", but the server answers like " + detected
                + ". Choose Auto-detect (or the matching type) in session settings; chat is still attempted. To raise "
                + "this server's context window: " + ServerRemedyEnum.forKind(detectedKindName).remedy() + ".";
    }

    private final Map<HintKind, Hint> active = new EnumMap<>(HintKind.class);

    /**
     * Activates or replaces the hint of a slot.
     *
     * @param tooltip the longer explanation, or null
     */
    public synchronized void set(HintKind kind, String text, String tooltip) {
        if (text == null || text.isBlank()) {
            active.remove(kind);
            return;
        }
        active.put(kind, new Hint(kind, text, tooltip == null ? "" : tooltip));
    }

    /**
     * The unreachable-server hint: text "N/A", and always the tooltip "Cannot reach the server at &lt;URL&gt;".
     */
    public synchronized void setUnreachable(String url) {
        set(HintKind.UNREACHABLE, "N/A", "Cannot reach the server at " + (url == null ? "" : url));
    }

    public synchronized void clear(HintKind kind) {
        active.remove(kind);
    }

    public synchronized void clearAll() {
        active.clear();
    }

    public synchronized boolean isActive(HintKind kind) {
        return active.containsKey(kind);
    }

    /**
     * @return the highest-priority active hint, empty when none
     */
    public synchronized Optional<Hint> top() {
        return active.values().stream().findFirst();
    }

    /**
     * @return the text to show: the highest-priority hint, or an empty string
     */
    public String text() {
        return top().map(Hint::text).orElse("");
    }

    /**
     * @return every active hint in priority order
     */
    public synchronized List<Hint> all() {
        return new ArrayList<>(active.values());
    }

    /**
     * @return one line per active hint, highest priority first: the text, then " — " and the tooltip when it has one;
     * an empty string when no hint is active
     */
    public String tooltip() {
        StringBuilder builder = new StringBuilder();
        for (Hint hint : all()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(hint.text());
            if (!hint.tooltip().isBlank()) {
                builder.append(" — ").append(hint.tooltip());
            }
        }
        return builder.toString();
    }
}
