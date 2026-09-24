package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpSteeringSupportEnumTest {

    @Test
    void everyAiTypeEnumHasMcpSteeringSupport() {
        int denyWithMessageCount = 0;
        int denyNeedsFollowUpCount = 0;
        int notApplicableCount = 0;
        int notInterceptableCount = 0;

        for (AiTypeEnum type : AiTypeEnum.values()) {
            assertNotNull(type.mcpSteeringSupport(),
                    "AI type " + type.name() + " must have a non-null mcpSteeringSupport value");

            if (type.mcpSteeringSupport() == McpSteeringSupportEnum.DENY_WITH_MESSAGE) {
                denyWithMessageCount++;
                assertTrue(type.mcpSteeringSupport().supported(),
                        "DENY_WITH_MESSAGE must be supported for " + type.name());
                assertTrue(!type.mcpSteeringSupport().needsFollowUp(),
                        "DENY_WITH_MESSAGE must not need follow-up for " + type.name());
            } else if (type.mcpSteeringSupport() == McpSteeringSupportEnum.DENY_NEEDS_FOLLOW_UP) {
                denyNeedsFollowUpCount++;
                assertTrue(type.mcpSteeringSupport().supported(),
                        "DENY_NEEDS_FOLLOW_UP must be supported for " + type.name());
                assertTrue(type.mcpSteeringSupport().needsFollowUp(),
                        "DENY_NEEDS_FOLLOW_UP must need follow-up for " + type.name());
            } else if (type.mcpSteeringSupport() == McpSteeringSupportEnum.NOT_APPLICABLE) {
                notApplicableCount++;
                assertTrue(!type.mcpSteeringSupport().supported(),
                        "NOT_APPLICABLE must not be supported for " + type.name());
                assertTrue(!type.mcpSteeringSupport().needsFollowUp(),
                        "NOT_APPLICABLE must not need follow-up for " + type.name());
            } else if (type.mcpSteeringSupport() == McpSteeringSupportEnum.NOT_INTERCEPTABLE) {
                notInterceptableCount++;
                assertTrue(!type.mcpSteeringSupport().supported(),
                        "NOT_INTERCEPTABLE must not be supported for " + type.name());
                assertTrue(!type.mcpSteeringSupport().needsFollowUp(),
                        "NOT_INTERCEPTABLE must not need follow-up for " + type.name());
            }
        }

        assertTrue(denyWithMessageCount > 0, "at least one backend must use DENY_WITH_MESSAGE");
        assertTrue(denyNeedsFollowUpCount > 0, "at least one backend must use DENY_NEEDS_FOLLOW_UP");
        assertTrue(notApplicableCount + notInterceptableCount > 0, "at least one backend must not be steerable");
    }

    @Test
    void supportedOnlyForDenyCategories() {
        for (AiTypeEnum type : AiTypeEnum.values()) {
            boolean isSupported = type.mcpSteeringSupport().supported();
            boolean isDenyWithMessage = type.mcpSteeringSupport() == McpSteeringSupportEnum.DENY_WITH_MESSAGE;
            boolean isDenyNeedsFollowUp = type.mcpSteeringSupport() == McpSteeringSupportEnum.DENY_NEEDS_FOLLOW_UP;

            assertEquals(isDenyWithMessage || isDenyNeedsFollowUp, isSupported,
                    "supported() must be true only for DENY_WITH_MESSAGE and DENY_NEEDS_FOLLOW_UP, got "
                    + type.mcpSteeringSupport() + " for " + type.name());
        }
    }

    @Test
    void needsFollowUpOnlyForDenyNeedsFollowUp() {
        for (AiTypeEnum type : AiTypeEnum.values()) {
            boolean needsFollowUp = type.mcpSteeringSupport().needsFollowUp();
            boolean isDenyNeedsFollowUp = type.mcpSteeringSupport() == McpSteeringSupportEnum.DENY_NEEDS_FOLLOW_UP;

            assertEquals(isDenyNeedsFollowUp, needsFollowUp,
                    "needsFollowUp() must be true only for DENY_NEEDS_FOLLOW_UP, got "
                    + type.mcpSteeringSupport() + " for " + type.name());
        }
    }
}
