package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import java.util.ArrayList;
import java.util.List;

public class PromptHistory {

    private final List<String> entries = new ArrayList<>();
    private int cursor = -1; // -1 = at "live" position (past newest)

    /**
     * Adds a prompt. Ignores blank input and consecutive duplicates.
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
    }

    /**
     * Returns the previous (older) entry, or "" if history is empty.
     * currentText is unused.
     */
    public String previous(String currentText) {
        if (entries.isEmpty()) {
            return "";
        }
        if (cursor == -1) {
            cursor = entries.size() - 1;
        }
        else if (cursor > 0) {
            cursor--;
        }
        return entries.get(cursor);
    }

    /**
     * Returns the next (newer) entry, or "" when past the newest entry (back at
     * live position).
     */
    public String next(String currentText) {
        if (cursor == -1) {
            return currentText;
        }
        cursor++;
        if (cursor >= entries.size()) {
            cursor = -1;
            return "";
        }
        return entries.get(cursor);
    }

    public void clear() {
        entries.clear();
        cursor = -1;
    }
}
