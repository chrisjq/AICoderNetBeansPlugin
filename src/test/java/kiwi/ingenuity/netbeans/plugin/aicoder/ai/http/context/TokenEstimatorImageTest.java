package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatImage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An image is estimated from its pixel count, never from the length of its base64.
 */
class TokenEstimatorImageTest {

    @Test
    @Timeout(5)
    void anImageCostsAboutOneTokenPer750Pixels() {
        assertEquals(3_279, TokenEstimator.imageTokens(new ChatImage("image/png", "x", 1568, 1568)));
        assertEquals(1_334, TokenEstimator.imageTokens(new ChatImage("image/png", "x", 1000, 1000)));
    }

    @Test
    @Timeout(5)
    void aTinyImageHasAFloorAndAnUnknownSizeAssumesTheLargest() {
        assertEquals(TokenEstimator.MIN_IMAGE_TOKENS, TokenEstimator.imageTokens(new ChatImage("image/png", "x", 4, 4)));
        assertEquals(TokenEstimator.UNKNOWN_IMAGE_TOKENS,
                     TokenEstimator.imageTokens(new ChatImage("image/png", "x", 0, 0)));
    }

    @Test
    @Timeout(5)
    void theBase64LengthDoesNotCount() {
        TokenEstimator estimator = new TokenEstimator();
        ChatMessage small = new ChatMessage(ChatRole.USER, "look", List.of(), null,
                                            List.of(new ChatImage("image/png", "A".repeat(10), 1000, 1000)));
        ChatMessage large = new ChatMessage(ChatRole.USER, "look", List.of(), null,
                                            List.of(new ChatImage("image/png", "A".repeat(2_000_000), 1000, 1000)));
        int textOnly = estimator.estimate(new ChatMessage(ChatRole.USER, "look", List.of(), null));

        assertEquals(estimator.estimate(small), estimator.estimate(large));
        assertEquals(textOnly + 1_334, estimator.estimate(large));
    }
}
