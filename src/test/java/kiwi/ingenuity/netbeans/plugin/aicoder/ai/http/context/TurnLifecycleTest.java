package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class TurnLifecycleTest {

    private static ChatMessage user(String t) {
        return new ChatMessage(ChatRole.USER, t, List.of(), null);
    }

    private static ChatMessage assistantCall() {
        return new ChatMessage(ChatRole.ASSISTANT, null,
                List.of(new ChatToolCall("call_0", "GetFileContent", "{}")), null);
    }

    private static ChatMessage toolResult() {
        return new ChatMessage(ChatRole.TOOL, "result", List.of(), "call_0");
    }

    @Test
    void rollbackIsANoOpWhenNoTurnIsOpen() {
        TestBroker b = new TestBroker();
        assertDoesNotThrow(b::rollbackTurn);
        assertEquals(0, b.entryCount());
    }

    @Test
    void rollbackAfterCommitDoesNotRemoveTheCommittedTurn() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("kept"));
        b.commitTurn();

        b.rollbackTurn();

        assertEquals(1, b.entryCount(),
                "the unconditional finally rollback must not eat a committed turn");
    }

    @Test
    void rollbackDiscardsThePartialGroupIncludingToolCalls() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("do a thing"));
        b.append(assistantCall());
        b.append(toolResult());

        b.rollbackTurn();

        assertEquals(0, b.entryCount(),
                "a cancelled turn must leave no assistant tool_calls stranded");
    }

    @Test
    void rollbackLeavesEarlierTurnsIntact() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("turn one"));
        b.commitTurn();

        b.beginTurn();
        b.append(user("turn two"));
        b.append(assistantCall());
        b.rollbackTurn();

        assertEquals(1, b.entryCount());
        assertEquals("turn one", b.snapshot().get(0).content());
    }

    @Test
    void beginTurnTwiceThrows() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        assertThrows(IllegalStateException.class, b::beginTurn);
    }

    @Test
    void beginTurnSucceedsAfterAnAbnormalUnwind() {
        TestBroker b = new TestBroker();
        b.beginTurn();
        b.append(user("turn that failed"));
        b.rollbackTurn();

        assertDoesNotThrow(b::beginTurn,
                "an IOException mid-turn must not brick the session");
    }

    @Test
    void aRolledBackTurnLeavesAWellFormedNextRequest() {
        TestBroker b = new TestBroker();
        b.upsertPin(PinSlotEnum.IDENTITY, "ID");
        b.beginTurn();
        b.append(user("first"));
        b.append(assistantCall());
        b.rollbackTurn();

        b.beginTurn();
        b.append(user("second"));
        List<ChatMessage> snap = b.snapshot();

        assertEquals(2, snap.size());
        assertEquals(ChatRole.SYSTEM, snap.get(0).role());
        assertEquals(ChatRole.USER, snap.get(1).role());
    }

    static class TestBroker extends AbstractChatContextBroker {

        TestBroker() {
            super("lifecycle-session", ContextBrokerSettings.defaults());
        }

        @Override
        protected int contextLimit() {
            return 100000;
        }
    }
}
