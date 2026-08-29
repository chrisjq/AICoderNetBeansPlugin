package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Source-level wiring test: asserts that all four "user is blocked" sites in {@code AiTopComponent} call
 * {@code enterAwaitingUserState()}, and that the {@code isPendingDiff()} branch of {@code refreshInputEnabled()} also
 * sets {@code TabStatus.AWAITING_USER}.
 * <p>
 * This class cannot instantiate {@code AiTopComponent} (it eagerly builds a real backend via {@code AiTypeRegistry}),
 * so it reads the source to verify the wiring is present. If any site drops the call, this test fails even though
 * nothing about the underlying behaviour would need to change.
 */
class AiTopComponentAwaitingUserWiringTest {

    private static final String SOURCE_PATH
            = "src/main/java/kiwi/ingenuity/netbeans/plugin/aicoder/ai/ui/AiTopComponent.java";

    private String readSource() throws IOException {
        return Files.readString(Path.of(SOURCE_PATH));
    }

    /**
     * Helper: extract a method body from source text. Finds the method signature then returns everything up to the next
     * method or end of class.
     */
    private String extractMethodBody(String source, String methodSignature) {
        int start = source.indexOf(methodSignature);
        assertTrue(start >= 0,
                "Could not find method signature '" + methodSignature + "' in AiTopComponent.java");
        // Find the opening brace
        int braceStart = source.indexOf('{', start);
        assertTrue(braceStart >= 0,
                "No opening brace after method signature '" + methodSignature + "'");
        // Walk braces to find the matching close
        int depth = 0;
        for (int i = braceStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
    }

    /**
     * Find a code block between two markers and verify it contains the expected call.
     */
    private void assertBlockContains(String source, String marker, String expectedCall, String description) {
        int markerIdx = source.indexOf(marker);
        assertTrue(markerIdx >= 0,
                "Could not find marker '" + marker + "' in AiTopComponent.java — line numbers may have shifted");
        // Look in a window after the marker (up to 2000 chars — covers early returns + prologue)
        int windowEnd = Math.min(markerIdx + 2000, source.length());
        String window = source.substring(markerIdx, windowEnd);
        assertTrue(window.contains(expectedCall),
                description + " — expected '" + expectedCall + "' near '" + marker + "' but it was not found. "
                + "Window:\n" + window);
    }

    @Test
    void askUserQuestionEventSiteCallsEnterAwaitingUserState() throws IOException {
        String source = readSource();
        assertBlockContains(source,
                "event instanceof AskUserQuestionEvent aqe",
                "enterAwaitingUserState()",
                "AskUserQuestionEvent handler must call enterAwaitingUserState()");
    }

    @Test
    void confirmEventSiteCallsEnterAwaitingUserState() throws IOException {
        String source = readSource();
        assertBlockContains(source,
                "event instanceof ConfirmEvent ce",
                "enterAwaitingUserState()",
                "ConfirmEvent handler must call enterAwaitingUserState()");
    }

    @Test
    void showDiffSiteCallsEnterAwaitingUserState() throws IOException {
        String source = readSource();
        assertBlockContains(source,
                "private void showDiff(ToolUseEvent tu)",
                "enterAwaitingUserState()",
                "showDiff() must call enterAwaitingUserState()");
    }

    @Test
    void showPermissionDiffSiteCallsEnterAwaitingUserState() throws IOException {
        String source = readSource();
        assertBlockContains(source,
                "private void showPermissionDiff(PermissionEvent pe)",
                "enterAwaitingUserState()",
                "showPermissionDiff() must call enterAwaitingUserState()");
    }

    /**
     * A batch awaiting per-file review is as much an "awaiting user" state as a single diff — the AI is blocked on it
     * the same way. Sibling of {@link #showPermissionDiffSiteCallsEnterAwaitingUserState()}, added when the multi-file
     * path arrived so the new site cannot silently drop the call the four older ones make.
     */
    @Test
    void showMultiPermissionReviewSiteCallsEnterAwaitingUserState() throws IOException {
        String source = readSource();
        assertBlockContains(source,
                "private void showMultiPermissionReview(MultiPermissionEvent mpe)",
                "enterAwaitingUserState()",
                "showMultiPermissionReview() must call enterAwaitingUserState()");
    }

    @Test
    void refreshInputEnabledIsPendingDiffBranchSetsAwaitingUser() throws IOException {
        String source = readSource();
        // The isPendingDiff() branch of refreshInputEnabled must set AWAITING_USER.
        // Find the isPendingDiff() check inside refreshInputEnabled, then look for
        // the setTabStatus(AWAITING_USER) within its true-branch.
        String refreshBody = extractMethodBody(source, "private void refreshInputEnabled()");
        int pendingDiffIdx = refreshBody.indexOf("isPendingDiff()");
        assertTrue(pendingDiffIdx >= 0,
                "refreshInputEnabled() must contain isPendingDiff() check");
        // Look within 500 chars after isPendingDiff() for the setTabStatus call
        int windowEnd = Math.min(pendingDiffIdx + 500, refreshBody.length());
        String window = refreshBody.substring(pendingDiffIdx, windowEnd);
        assertTrue(window.contains("setTabStatus(TabStatus.AWAITING_USER)"),
                "refreshInputEnabled() isPendingDiff() branch must set TabStatus.AWAITING_USER. "
                + "Window:\n" + window);
    }

    @Test
    void enterAwaitingUserStateExistsAsPrivateMethod() throws IOException {
        String source = readSource();
        assertTrue(source.contains("private void enterAwaitingUserState()"),
                "AiTopComponent must declare a private enterAwaitingUserState() method");
    }

    @Test
    void enterAwaitingUserStateSetsPendingDiffTrue() throws IOException {
        String source = readSource();
        String body = extractMethodBody(source, "private void enterAwaitingUserState()");
        assertTrue(body.contains("setPendingDiff(true)"),
                "enterAwaitingUserState() must call setPendingDiff(true)");
    }

    @Test
    void enterAwaitingUserStateDisablesInputField() throws IOException {
        String source = readSource();
        String body = extractMethodBody(source, "private void enterAwaitingUserState()");
        assertTrue(body.contains("inputField.setEnabled(false)"),
                "enterAwaitingUserState() must disable inputField");
    }

    @Test
    void enterAwaitingUserStateDisablesSendButton() throws IOException {
        String source = readSource();
        String body = extractMethodBody(source, "private void enterAwaitingUserState()");
        assertTrue(body.contains("sendButton.setEnabled(false)"),
                "enterAwaitingUserState() must disable sendButton");
    }

    @Test
    void enterAwaitingUserStateSetsAwaitingUserStatus() throws IOException {
        String source = readSource();
        String body = extractMethodBody(source, "private void enterAwaitingUserState()");
        assertTrue(body.contains("setTabStatus(TabStatus.AWAITING_USER)"),
                "enterAwaitingUserState() must set TabStatus.AWAITING_USER");
    }
}
