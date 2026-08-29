package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * MoveClass distinguishes "line omitted" from "line malformed".
 * <p>
 * {@code MoveClassTool} defaults the parameter to 0, so 0 legitimately means "move the whole file". A NEGATIVE line is
 * a different thing entirely — a caller sent a value and got it wrong — but it used to fall through the same
 * {@code line > 0} branch and be treated as omitted, silently performing a broader move than was asked for. In a file
 * declaring one top-level type that happened with no complaint at all, since the multi-type guard never fired.
 * <p>
 * Both cases are proven here because either assertion alone is worthless: the first on its own would also pass if the
 * guard rejected every line, which would break the documented whole-file mode.
 */
class MoveClassLineValidationTest {

    private static final String VALID_PACKAGE = "com.example.target";

    @Test
    void negativeLineIsRejectedAsMalformed() {
        String result = RefactoringProvider.moveClass("/tmp/does-not-matter.java", -1, VALID_PACKAGE, false);

        assertTrue(result.contains(McpToolPropertyEnum.LINE.key()),
                "the refusal must name the offending parameter: " + result);
        assertTrue(result.contains("-1"),
                "the refusal must quote the received value back so the caller can see what it sent: " + result);
        assertTrue(result.startsWith("Error:"),
                "a malformed argument must be reported as an error, not as a result: " + result);
    }

    @Test
    void omittedLineIsNotRejected() {
        // 0 is what the tool passes when the caller omits line, and it must still mean "move the whole file". The call
        // cannot complete headless, so it is only asserted NOT to fail the line check — it gets past validation and
        // stops at file resolution instead, which is the correct next failure.
        String result = RefactoringProvider.moveClass(null, 0, VALID_PACKAGE, false);

        assertFalse(result.contains("must be 1-based"),
                "an omitted line must not be treated as malformed: " + result);
        assertTrue(result.contains(McpToolPropertyEnum.FILE_PATH.key()),
                "validation should have moved on to the missing filePath: " + result);
    }
}
