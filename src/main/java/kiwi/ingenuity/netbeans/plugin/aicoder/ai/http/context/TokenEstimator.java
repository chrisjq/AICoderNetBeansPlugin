package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;

/**
 * Character-based token estimate, corrected over time by the usage the backend
 * reports. No Java tokenizer exists for these models, so estimation plus
 * feedback is the accepted approach.
 */
public class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 3.5d;
    private static final int PER_MESSAGE_OVERHEAD = 4;
    private static final double MIN_RATIO = 0.5d;
    private static final double MAX_RATIO = 2.0d;
    private static final double SMOOTHING = 0.3d;

    private volatile double ratio = 1.0d;
    private volatile boolean seenReportedUsage = false;

    public int estimate(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        int chars = message.content() == null ? 0 : message.content().length();
        for (ChatToolCall call : message.toolCalls()) {
            if (call.name() != null) {
                chars += call.name().length();
            }
            if (call.argumentsJson() != null) {
                chars += call.argumentsJson().length();
            }
        }
        int raw = (int) Math.ceil(chars / CHARS_PER_TOKEN) + PER_MESSAGE_OVERHEAD;
        return (int) Math.ceil(raw * ratio);
    }

    /**
     * @param estimated what this estimator predicted for the request just sent
     * @param reported the backend's prompt_tokens, or null when it reported
     * none
     */
    public void calibrate(int estimated, Integer reported) {
        if (reported == null || reported <= 0 || estimated <= 0) {
            return;
        }
        seenReportedUsage = true;
        double observed = (double) reported / (double) estimated;
        double blended = (ratio * (1.0d - SMOOTHING)) + (observed * SMOOTHING);
        ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, blended));
    }

    public double calibrationRatio() {
        return ratio;
    }

    /**
     * True once the endpoint has actually returned a usage object. Until then
     * REPORTED_TOKENS has nothing to act on and the broker falls back to
     * ESTIMATED_TOKENS.
     */
    public boolean hasSeenReportedUsage() {
        return seenReportedUsage;
    }

    /**
     * Called on model change: the ratio is tokenizer-specific and cheaply
     * re-learned. Whether the endpoint reports usage is a property of the
     * endpoint, not the model, so that flag survives.
     */
    public void reset() {
        ratio = 1.0d;
    }

    /**
     * Restore a ratio earned in a previous run. Clamped like any other, so a
     * corrupt file cannot inject a nonsense multiplier.
     */
    public void restoreRatio(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return;
        }
        ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, value));
    }
}
