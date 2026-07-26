package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import java.util.ArrayList;
import java.util.List;

public class PromptHistory {

    private final List<String> entries = new ArrayList<>();
    private int cursor = -1; // -1 = at "live" position (past newest)
    private String draft = ""; // Stores unsent draft when navigating history

    /**
     * Adds a prompt. Ignores blank input and consecutive duplicates. Clears the
     * saved draft when a new prompt is added.
     */
    public void add(String prompt) {
        if (prompt.isBlank()) {
            return;
        }
        if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(prompt)) {
            return;
        }
        entries.add(prompt);
        cursor = -1;
        draft = "";
    }

    /**
     * Returns the previous (older) entry, or currentText if history is empty.
     * When navigating from live position, saves currentText as draft before
     * moving.
     */
    public String previous(String currentText) {
        if (entries.isEmpty()) {
            return currentText;
        }
        if (cursor == -1) {
            draft = currentText;
            cursor = entries.size() - 1;
        }
        else if (cursor > 0) {
            cursor--;
        }
        return entries.get(cursor);
    }

    /**
     * Returns the next (newer) entry, or the saved draft when returning to live
     * position.
     */
    public String next(String currentText) {
        if (cursor == -1) {
            return currentText;
        }
        cursor++;
        if (cursor >= entries.size()) {
            cursor = -1;
            return draft;
        }
        return entries.get(cursor);
    }

    public void clear() {
        entries.clear();
        cursor = -1;
        draft = "";
    }
}
