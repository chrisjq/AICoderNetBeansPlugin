package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MemoryPromptSectionTest {

    /**
     * A simple stand-in for the session's estimator: one token per four characters.
     */
    private static final ToIntFunction<String> TOKENS = text -> (text.length() + 3) / 4;

    private static SessionMemoryStore.Note note(String name, String description) {
        return new SessionMemoryStore.Note(name, description, "content", Instant.EPOCH);
    }

    @Test
    @Timeout(10)
    void aSectionWithNotesListsThemInTheGivenOrderAfterTheGuidance() {
        String section = MemoryPromptSection.build(List.of(note("newest", "Newest one"), note("oldest", "Oldest one")),
                                                   TOKENS, MemoryPromptSection.INDEX_TOKEN_CAP);

        assertTrue(section.startsWith("## Memory\n"), section);
        assertTrue(section.contains("preferences, decisions and corrections"), section);
        assertTrue(section.contains("one fact per note"), section);
        assertTrue(section.indexOf("- newest: Newest one") < section.indexOf("- oldest: Oldest one"), section);
        assertFalse(section.contains("not shown"), section);
    }

    @Test
    @Timeout(10)
    void aSectionWithoutNotesSaysSo() {
        String section = MemoryPromptSection.build(List.of(), TOKENS, MemoryPromptSection.INDEX_TOKEN_CAP);

        assertTrue(section.endsWith("No notes saved yet."), section);
    }

    @Test
    @Timeout(10)
    void theIndexIsCappedAndTheOldestNotesAreCountedAsNotShown() {
        List<SessionMemoryStore.Note> notes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            notes.add(note("note-" + i, "d".repeat(SessionMemoryStore.MAX_DESCRIPTION_LENGTH)));
        }

        String section = MemoryPromptSection.build(notes, TOKENS, MemoryPromptSection.INDEX_TOKEN_CAP);

        int shownLines = section.split("\n- ", -1).length - 1;
        assertTrue(shownLines > 0 && shownLines < 200, "shown " + shownLines);
        int indexStart = section.indexOf("Saved notes, newest first:");
        String index = section.substring(indexStart, section.lastIndexOf("\n("));
        assertTrue(TOKENS.applyAsInt(index) <= MemoryPromptSection.INDEX_TOKEN_CAP + 10,
                   "index costs " + TOKENS.applyAsInt(index));
        assertTrue(section.contains("- note-0:"), "the newest note is kept");
        assertFalse(section.contains("- note-199:"), "the oldest note is dropped");
        assertTrue(section.endsWith("(" + (200 - shownLines) + " older notes not shown)"), section);
    }

    @Test
    @Timeout(10)
    void oneOmittedNoteIsWordedInTheSingular() {
        List<SessionMemoryStore.Note> notes = new ArrayList<>();
        int count = 0;
        // Add notes until exactly one more would not fit.
        String line = "\n- note-000: " + "d".repeat(SessionMemoryStore.MAX_DESCRIPTION_LENGTH);
        int fit = MemoryPromptSection.INDEX_TOKEN_CAP / TOKENS.applyAsInt(line);
        for (; count < fit + 1; count++) {
            notes.add(note(String.format("note-%03d", count), "d".repeat(SessionMemoryStore.MAX_DESCRIPTION_LENGTH)));
        }

        String section = MemoryPromptSection.build(notes, TOKENS, MemoryPromptSection.INDEX_TOKEN_CAP);

        assertTrue(section.endsWith("(1 older note not shown)"), section);
    }
}
