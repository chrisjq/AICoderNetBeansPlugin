package kiwi.ingenuity.netbeans.plugin.aicoder.process.limits;

/**
 *
 * @author chris
 */
public interface RateLimitListener {

    void onRateLimited(long retryAfterMs);

    void onRateLimitCleared();

}
