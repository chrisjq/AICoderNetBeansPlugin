package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The picker's creation pipeline removes the saved key of every session that was not created: one whose save failed and
 * one dropped by createAll. These tests pin the rule that picks those sessions.
 */
@Timeout(10)
class SessionPickerDialogCleanupTest {

    @Test
    void aSessionDroppedByCreateAllIsTheOnlyOneToClean() {
        AiSession kept = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);
        AiSession dropped = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);

        List<AiSession> missing = SessionPickerDialog.sessionsNotCreated(List.of(kept, dropped), List.of(kept));

        assertEquals(1, missing.size());
        assertSame(dropped, missing.get(0), "the dropped session's saved key must be cleaned up");
    }

    @Test
    void nothingIsCleanedWhenEverySessionWasCreated() {
        AiSession first = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);
        AiSession second = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);

        assertEquals(List.of(), SessionPickerDialog.sessionsNotCreated(List.of(first, second), List.of(first, second)));
    }

    @Test
    void everySessionIsCleanedWhenNoneWasCreated() {
        AiSession first = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);
        AiSession second = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);

        assertEquals(List.of(first, second), SessionPickerDialog.sessionsNotCreated(List.of(first, second), List.of()));
    }
}
