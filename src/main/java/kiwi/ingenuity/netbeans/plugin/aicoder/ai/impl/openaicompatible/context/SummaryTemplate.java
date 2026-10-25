package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context;

import java.util.List;
import java.util.Locale;

/**
 * The prompts for a summary of earlier conversation, and the check that a reply has the structure those prompts ask
 * for. A fresh summary is written from a transcript alone; an update folds a new transcript into the previous summary.
 */
public final class SummaryTemplate {

    /**
     * Section headings every summary must contain, in the order the prompts ask for them.
     */
    public static final List<String> HEADINGS = List.of(
            "Goal",
            "Constraints",
            "Progress",
            "Decisions",
            "Next steps",
            "Relevant files",
            "Critical context");

    private static final String STRUCTURE = String.join("\n",
                                                        HEADINGS.stream().map(h -> "## " + h).toList());

    /**
     * The prompt for summarising a transcript with no earlier summary to build on.
     */
    public String freshPrompt(String transcript) {
        return "Summarise the conversation below so an assistant can carry on the work. Use exactly these headings, "
                + "once each, in this order, with a short section under each ('none' if a section is empty):\n"
                + STRUCTURE + "\n"
                + "Do not call tools. Write nothing outside the headings.\n\n"
                + "Conversation:\n" + nonNull(transcript);
    }

    /**
     * The prompt for folding a new transcript into a previous summary. Keeps the same headings and the facts still
     * true, adds the new progress and decisions, and drops what no longer matters.
     */
    public String updatePrompt(String previousSummary, String transcript) {
        return "Update the summary below with the new conversation that follows it. Keep exactly these headings, once "
                + "each, in this order:\n"
                + STRUCTURE + "\n"
                + "Keep facts from the previous summary that are still true, add the progress and decisions from the new "
                + "conversation, and drop what is no longer relevant. Do not call tools. Write nothing outside the "
                + "headings.\n\n"
                + "Previous summary:\n" + nonNull(previousSummary) + "\n\n"
                + "New conversation:\n" + nonNull(transcript);
    }

    /**
     * Whether {@code summary} contains every required heading as a line of its own. A heading may be written with
     * leading {@code #}, {@code *} or {@code -} marks, or trailing {@code :} or {@code *}; the match ignores case.
     */
    public boolean isValid(String summary) {
        if (summary == null) {
            return false;
        }
        List<String> lines = summary.lines().map(SummaryTemplate::headingOf).toList();
        for (String heading : HEADINGS) {
            if (!lines.contains(heading.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The message sent once when a summary is rejected: it names the required headings so a second attempt can comply.
     */
    public String repairNudge() {
        return "Your summary did not contain every required heading. Reply again with the summary only, using exactly "
                + "these headings, once each, in this order:\n"
                + STRUCTURE;
    }

    private static String headingOf(String line) {
        String trimmed = line.strip();
        int start = 0;
        while (start < trimmed.length() && "#*- ".indexOf(trimmed.charAt(start)) >= 0) {
            start++;
        }
        int end = trimmed.length();
        while (end > start && ":* ".indexOf(trimmed.charAt(end - 1)) >= 0) {
            end--;
        }
        return trimmed.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }
}
