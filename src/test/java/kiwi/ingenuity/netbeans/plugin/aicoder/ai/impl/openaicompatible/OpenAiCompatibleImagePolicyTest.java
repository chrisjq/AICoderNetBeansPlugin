package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.ImageInputModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn.TurnLoop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The effective vision decision: On and Off override everything; Auto follows detection and an earlier rejection, and
 * tries when nothing is known.
 */
class OpenAiCompatibleImagePolicyTest {

    private static DetectedFacts vision(Boolean value) {
        return DetectedFacts.of("g").withCapabilities(DetectedFacts.Capabilities.unknown().withVision(value));
    }

    @Test
    @Timeout(5)
    void onAndOffOverrideDetectionAndTheDecision() {
        assertEquals(TurnLoop.ImagePolicy.SEND,
                     OpenAiCompatibleAiProcessManager.imagePolicy(ImageInputModeEnum.ON, vision(false), true));
        assertEquals(TurnLoop.ImagePolicy.DROP_SETTING_OFF,
                     OpenAiCompatibleAiProcessManager.imagePolicy(ImageInputModeEnum.OFF, vision(true), false));
    }

    @Test
    @Timeout(5)
    void autoFollowsDetection() {
        assertEquals(TurnLoop.ImagePolicy.SEND,
                     OpenAiCompatibleAiProcessManager.imagePolicy(ImageInputModeEnum.AUTO, vision(true), false));
        assertEquals(TurnLoop.ImagePolicy.DROP_NO_VISION,
                     OpenAiCompatibleAiProcessManager.imagePolicy(ImageInputModeEnum.AUTO, vision(false), false));
    }

    @Test
    @Timeout(5)
    void autoTriesWhenUnknownUntilTheServerRejectsImages() {
        assertEquals(TurnLoop.ImagePolicy.SEND,
                     OpenAiCompatibleAiProcessManager.imagePolicy(ImageInputModeEnum.AUTO, null, false));
        assertEquals(TurnLoop.ImagePolicy.SEND,
                     OpenAiCompatibleAiProcessManager.imagePolicy(null, vision(null), false));
        assertEquals(TurnLoop.ImagePolicy.DROP_NO_VISION,
                     OpenAiCompatibleAiProcessManager.imagePolicy(ImageInputModeEnum.AUTO, vision(null), true));
        assertEquals(TurnLoop.ImagePolicy.DROP_NO_VISION,
                     OpenAiCompatibleAiProcessManager.imagePolicy(ImageInputModeEnum.AUTO, vision(true), true),
                     "a rejection outweighs the probe");
    }
}
