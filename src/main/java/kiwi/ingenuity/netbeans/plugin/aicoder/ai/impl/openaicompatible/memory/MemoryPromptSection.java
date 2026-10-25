package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The "Memory" section of the system prompt: short guidance on what to save, then an index of the session's notes,
 * newest first, kept to about {@value #INDEX_TOKEN_CAP} tokens so a large memory cannot eat the context window.
 */
public final class MemoryPromptSection {

    /**
     * The most the index lines may cost, by the session's token estimate.
     */
    public static final int INDEX_TOKEN_CAP = 1000;

    static final String HEADING = "## Memory";

    static final String GUIDANCE = "You have a long-term memory that survives restarts and resets of this conversation. "
            + "Save what will matter in later conversations: the user's preferences, decisions and corrections. "
            + "Do not save what the code, the git history or this conversation already holds. "
            + "Keep one fact per note. Saving under an existing name replaces that note, so update a note that has "
            + "changed and delete one that turned out wrong. Read a note in full by its name.";

    private MemoryPromptSection() {
    }

    /**
     * @param notesNewestFirst the session's notes, most recently written first
     * @param tokens estimates the tokens of a piece of text
     * @param indexTokenCap the most the index lines may cost
     *
     * @return the section, ready to put in the system prompt
     */
    public static String build(List<SessionMemoryStore.Note> notesNewestFirst, ToIntFunction<String> tokens,
                               int indexTokenCap) {
        StringBuilder section = new StringBuilder(HEADING).append('\n').append(GUIDANCE).append("\n\n");
        if (notesNewestFirst.isEmpty()) {
            return section.append("No notes saved yet.").toString();
        }
        section.append("Saved notes, newest first:");
        int used = 0;
        int shown = 0;
        for (SessionMemoryStore.Note note : notesNewestFirst) {
            String line = "\n- " + note.name() + ": " + note.description();
            int cost = tokens.applyAsInt(line);
            if (used + cost > indexTokenCap) {
                break;
            }
            used += cost;
            shown++;
            section.append(line);
        }
        int omitted = notesNewestFirst.size() - shown;
        if (omitted > 0) {
            section.append("\n(").append(omitted).append(omitted == 1 ? " older note" : " older notes")
                    .append(" not shown)");
        }
        return section.toString();
    }
}
