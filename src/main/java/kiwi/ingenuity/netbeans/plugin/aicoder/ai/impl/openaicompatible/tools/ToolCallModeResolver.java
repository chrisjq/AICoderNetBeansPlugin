package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.MappedError;

/**
 * Chooses native or schema tool calling for one turn (spec §5). Never returns {@link ToolCallingModeEnum#AUTO}.
 * <p>
 * Auto is native when support is known-true or still unknown, and schema when support is known-false. After
 * {@code failureLimit} unrescued native failures the next turn is schema; pass the count from earlier turns, not a
 * count incremented in the turn being resolved. A limit of zero or less does not switch on the count (unlimited).
 * {@code switchedThisSession} is sticky, including an immediate switch. The immediate-switch trigger is
 * {@link MappedError.Kind#TOOLS_UNSUPPORTED}.
 */
public final class ToolCallModeResolver {

    private ToolCallModeResolver() {
    }

    /**
     * @param setting the session mode; null is treated as Auto
     * @param nativeSupported {@code true} supported, {@code false} not supported, null unknown
     * @param nativeFailures unrescued native failures already recorded for this model
     * @param failureLimit how many of those failures switch the next turn; zero or less never switches on the count
     * @param switchedThisSession sticky schema, including an immediate {@link #immediateSwitch} this turn
     *
     * @return {@link ToolCallingModeEnum#NATIVE} or {@link ToolCallingModeEnum#SCHEMA}
     */
    public static ToolCallingModeEnum mode(ToolCallingModeEnum setting, Boolean nativeSupported, int nativeFailures,
                                           int failureLimit, boolean switchedThisSession) {
        if (setting == ToolCallingModeEnum.SCHEMA) {
            return ToolCallingModeEnum.SCHEMA;
        }
        if (switchedThisSession) {
            return ToolCallingModeEnum.SCHEMA;
        }
        if (setting == ToolCallingModeEnum.NATIVE) {
            return ToolCallingModeEnum.NATIVE;
        }
        if (Boolean.FALSE.equals(nativeSupported)) {
            return ToolCallingModeEnum.SCHEMA;
        }
        if (failureLimit > 0 && nativeFailures >= failureLimit) {
            return ToolCallingModeEnum.SCHEMA;
        }
        return ToolCallingModeEnum.NATIVE;
    }

    /**
     * @return true when this mapped error must switch to schema at once and resend, and must not be counted as a native
     * failure
     */
    public static boolean immediateSwitch(MappedError.Kind kind) {
        return kind == MappedError.Kind.TOOLS_UNSUPPORTED;
    }
}
