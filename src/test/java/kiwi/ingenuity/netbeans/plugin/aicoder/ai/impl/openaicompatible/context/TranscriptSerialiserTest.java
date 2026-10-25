package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.EnumSet;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class TranscriptSerialiserTest {

    private static final TranscriptSerialiser PLAIN = new TranscriptSerialiser(EnumSet.noneOf(McpToolEnum.class));

    private static ChatMessage user(String text) {
        return new ChatMessage(ChatRole.USER, text, null, null);
    }

    @Test
    void rendersUserAssistantToolCallsAndToolResults() {
        List<ChatMessage> messages = List.of(
                user("list files"),
                new ChatMessage(ChatRole.ASSISTANT, "Looking.",
                                List.of(new ChatToolCall("c1", "SearchInFiles", "{\"query\":\"foo\",\"caseSensitive\":true}")),
                                null),
                new ChatMessage(ChatRole.TOOL, "found 3", null, "c1"));

        assertEquals("[User] list files\n"
                + "[Assistant] Looking.\n"
                + "[Assistant tool calls] SearchInFiles(query=foo, caseSensitive=true)\n"
                + "[Tool result] found 3", PLAIN.render(messages));
    }

    @Test
    void systemMessagesAreLeftOut() {
        List<ChatMessage> messages = List.of(
                new ChatMessage(ChatRole.SYSTEM, "secret system prompt", null, null),
                user("hi"));

        String text = PLAIN.render(messages);

        assertFalse(text.contains("secret system prompt"), text);
        assertEquals("[User] hi", text);
    }

    @Test
    void toolResultsAreCutToTheLimitWithAMarker() {
        String longResult = "x".repeat(2500);
        List<ChatMessage> messages = List.of(new ChatMessage(ChatRole.TOOL, longResult, null, "c1"));

        String text = PLAIN.render(messages);

        assertTrue(text.contains("x".repeat(TranscriptSerialiser.TOOL_RESULT_MAX_CHARS)), text);
        assertFalse(text.contains("x".repeat(TranscriptSerialiser.TOOL_RESULT_MAX_CHARS + 1)), text);
        assertTrue(text.contains("[truncated 500 chars]"), text);
    }

    @Test
    void sentencesNamingAHiddenToolAreRemoved() {
        TranscriptSerialiser hiding = new TranscriptSerialiser(EnumSet.of(McpToolEnum.SEARCH_IN_FILES));
        List<ChatMessage> messages = List.of(user("Use SearchInFiles to find text. Then edit the file."));

        String text = hiding.render(messages);

        assertFalse(text.contains("SearchInFiles"), text);
        assertEquals("Then edit the file.", text);
    }

    @Test
    void chunksAreCutOnlyAtMessageBoundariesWithinTheBudget() {
        List<ChatMessage> messages = List.of(user("aaaa"), user("aaaa"), user("aaaa"));

        List<String> chunks = PLAIN.chunks(messages, 25);

        assertEquals(List.of("[User] aaaa\n[User] aaaa", "[User] aaaa"), chunks);
    }

    @Test
    void anOversizedEntryIsNeverSplit() {
        String body = "y".repeat(50);

        List<String> chunks = PLAIN.chunks(List.of(user(body)), 10);

        assertEquals(List.of("[User] " + body), chunks);
    }

    @Test
    void aNonPositiveBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PLAIN.chunks(List.of(), 0));
    }

    @Test
    void aConfiguredToolResultCapIsApplied() {
        TranscriptSerialiser capped = new TranscriptSerialiser(EnumSet.noneOf(McpToolEnum.class), 100);
        List<ChatMessage> messages = List.of(new ChatMessage(ChatRole.TOOL, "x".repeat(150), null, "c1"));

        String text = capped.render(messages);

        assertTrue(text.contains("x".repeat(100)), text);
        assertFalse(text.contains("x".repeat(101)), text);
        assertTrue(text.contains("[truncated 50 chars]"), text);
    }

    @Test
    void aNonPositiveToolResultCapFallsBackToTheDefault() {
        TranscriptSerialiser fallback = new TranscriptSerialiser(EnumSet.noneOf(McpToolEnum.class), 0);
        List<ChatMessage> messages = List.of(new ChatMessage(ChatRole.TOOL, "y".repeat(2_500), null, "c1"));

        String text = fallback.render(messages);

        assertTrue(text.contains("y".repeat(TranscriptSerialiser.TOOL_RESULT_MAX_CHARS)), "the 2000-char default applies");
        assertFalse(text.contains("y".repeat(TranscriptSerialiser.TOOL_RESULT_MAX_CHARS + 1)));
    }
}
