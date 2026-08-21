package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Golden test for the version-1 on-disk context snapshot.
 *
 * <p>
 * Every key name in this file is a deliberate string LITERAL, not
 * {@code ContextJsonKeyEnum.X.key()}. A test that reads the spellings from the
 * enum follows any rename and proves nothing. The version gate in
 * {@code AbstractChatContextBroker.restoreFromJson} only fires on a deliberate
 * {@code FORMAT_VERSION} bump — an unbumped key rename passes it,
 * {@code ContextEntry.fromJson} then fails on the missing key and the loader
 * skips that entry, so a whole saved history silently vanishes while every
 * other test stays green. This file is the red light for that case: renaming a
 * {@code ContextJsonKeyEnum} value must either fail here or come with a version
 * bump and a migration.
 */
class ContextSnapshotFormatTest {

    /**
     * A snapshot exactly as version 1 writes it: one user-only group, then one
     * assistant tool call with its matching tool result (a group whose results
     * are missing is dropped on load, so the pair is needed for the entries to
     * survive). Entry 2 has no {@code content} and entries 2–3 have no
     * {@code cacheId}, because those keys are written only when present.
     */
    private static final String VERSION_1_SNAPSHOT = "{"
            + "\"version\":1,"
            + "\"sessionId\":\"golden-session\","
            + "\"savedAt\":1700000000000,"
            + "\"calibrationRatio\":1.25,"
            + "\"entries\":["
            + "{\"sequence\":1,\"groupId\":1,\"timestamp\":1000,\"retention\":\"EVICTABLE\",\"estimatedTokens\":7,"
            + "\"cacheId\":\"cache-1\",\"role\":\"USER\",\"content\":\"hello\",\"toolCalls\":[]},"
            + "{\"sequence\":2,\"groupId\":2,\"timestamp\":2000,\"retention\":\"EVICTABLE\",\"estimatedTokens\":9,"
            + "\"role\":\"ASSISTANT\",\"toolCalls\":[{\"id\":\"call_1\",\"name\":\"GetFileContent\","
            + "\"arguments\":\"{\\\"filePath\\\":\\\"/p/pom.xml\\\"}\"}]},"
            + "{\"sequence\":3,\"groupId\":2,\"timestamp\":3000,\"retention\":\"EVICTABLE\",\"estimatedTokens\":4,"
            + "\"role\":\"TOOL\",\"content\":\"<xml/>\",\"toolCallId\":\"call_1\",\"toolCalls\":[]}"
            + "]}";

    @Test
    void restoresAHandWrittenVersion1SnapshotFieldForField() {
        TestBroker b = new TestBroker();
        b.restoreFromJson(JsonParser.parseString(VERSION_1_SNAPSHOT).getAsJsonObject());

        List<ContextEntry> entries = b.entriesForTesting();
        assertEquals(3, entries.size(),
                "every entry must load — a skipped entry means the loader no longer recognises a key "
                + "that version-1 files on disk were written with");

        ContextEntry user = entries.get(0);
        assertEquals(1L, user.sequence());
        assertEquals(1L, user.groupId());
        assertEquals(1000L, user.timestamp());
        assertEquals(ContextRetentionEnum.EVICTABLE, user.retention());
        assertEquals(7, user.estimatedTokens());
        assertEquals("cache-1", user.cacheId());
        assertEquals(ChatRole.USER, user.message().role());
        assertEquals("hello", user.message().content());
        assertNull(user.message().toolCallId());
        assertTrue(user.message().toolCalls().isEmpty());

        ContextEntry assistant = entries.get(1);
        assertEquals(2L, assistant.sequence());
        assertEquals(2L, assistant.groupId());
        assertEquals(2000L, assistant.timestamp());
        assertEquals(9, assistant.estimatedTokens());
        assertNull(assistant.cacheId(), "cacheId is optional and was not written for this entry");
        assertEquals(ChatRole.ASSISTANT, assistant.message().role());
        assertNull(assistant.message().content(), "content is optional and was not written for this entry");
        assertEquals(1, assistant.message().toolCalls().size());
        ChatToolCall call = assistant.message().toolCalls().get(0);
        assertEquals("call_1", call.id());
        assertEquals("GetFileContent", call.name());
        assertEquals("{\"filePath\":\"/p/pom.xml\"}", call.argumentsJson());

        ContextEntry tool = entries.get(2);
        assertEquals(3L, tool.sequence());
        assertEquals(2L, tool.groupId());
        assertEquals(3000L, tool.timestamp());
        assertEquals(4, tool.estimatedTokens());
        assertEquals(ChatRole.TOOL, tool.message().role());
        assertEquals("<xml/>", tool.message().content());
        assertEquals("call_1", tool.message().toolCallId());

        assertEquals(1.25d, b.calibrationRatio(), 0.0001d, "calibrationRatio must restore from the envelope");
        assertEquals(3L, b.sequenceCounter, "sequence counter resumes from the highest loaded sequence");
        assertEquals(2L, b.groupCounter, "group counter resumes from the highest loaded group");
    }

    @Test
    void toJsonWritesExactlyTheVersion1KeyNames() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(new ChatMessage(ChatRole.USER, "hello", List.of(), null));
        b.append(new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall("call_1", "GetFileContent", "{}")), null));
        b.append(new ChatMessage(ChatRole.TOOL, "<xml/>", List.of(), "call_1"));
        b.commitTurn();
        b.entriesForTesting().get(0).setCacheId("cache-1");

        JsonObject root = b.toJson();

        assertEquals(Set.of("version", "sessionId", "savedAt", "calibrationRatio", "entries"), root.keySet(),
                "envelope keys are the version-1 on-disk contract");
        assertEquals(1, root.get("version").getAsInt());
        assertEquals("golden-session", root.get("sessionId").getAsString());

        JsonArray entries = root.getAsJsonArray("entries");
        assertEquals(3, entries.size());

        JsonObject user = entries.get(0).getAsJsonObject();
        assertEquals(Set.of("sequence", "groupId", "timestamp", "retention", "estimatedTokens",
                "cacheId", "role", "content", "toolCalls"), user.keySet(),
                "per-entry keys are the version-1 on-disk contract");
        assertEquals("EVICTABLE", user.get("retention").getAsString());
        assertEquals("USER", user.get("role").getAsString());

        JsonObject assistant = entries.get(1).getAsJsonObject();
        assertEquals(Set.of("sequence", "groupId", "timestamp", "retention", "estimatedTokens",
                "role", "toolCalls"), assistant.keySet(),
                "content, toolCallId and cacheId are written only when present");
        JsonObject call = assistant.getAsJsonArray("toolCalls").get(0).getAsJsonObject();
        assertEquals(Set.of("id", "name", "arguments"), call.keySet(),
                "tool-call keys are the version-1 on-disk contract");

        JsonObject tool = entries.get(2).getAsJsonObject();
        assertEquals(Set.of("sequence", "groupId", "timestamp", "retention", "estimatedTokens",
                "role", "content", "toolCallId", "toolCalls"), tool.keySet());
        assertEquals("call_1", tool.get("toolCallId").getAsString());
    }

    /**
     * Documents WHY the literals above are pinned: the version gate does not
     * catch a renamed key. The file still says version 1, so it is accepted,
     * and every entry is then skipped because a required field is missing —
     * zero entries, no error, green build. Only the golden tests above turn
     * that into a failure.
     */
    @Test
    void versionGateDoesNotProtectAgainstARenamedKey() {
        String renamed = VERSION_1_SNAPSHOT.replace("\"estimatedTokens\"", "\"estimated_tokens\"");
        TestBroker b = new TestBroker();
        b.restoreFromJson(JsonParser.parseString(renamed).getAsJsonObject());

        assertEquals(0, b.entryCount(),
                "a renamed key is not a version change, so the gate lets it through and the "
                + "history silently vanishes — this is the failure the golden tests exist to catch");
    }

    static class TestBroker extends AbstractChatContextBroker {

        TestBroker() {
            super("golden-session", ContextBrokerSettings.defaults());
        }

        @Override
        protected int contextLimit() {
            return 0;
        }
    }
}
