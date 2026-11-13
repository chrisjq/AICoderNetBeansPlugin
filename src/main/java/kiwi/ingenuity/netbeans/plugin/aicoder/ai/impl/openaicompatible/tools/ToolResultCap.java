package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;

/**
 * Truncates an over-long tool result and says, in a note suited to the tool, how the model can get the rest.
 */
public final class ToolResultCap {

    private static final Set<String> FILE_READS = Set.of(McpToolEnum.GET_FILE_CONTENT.toolName(),
                                                         McpToolEnum.GET_CURRENT_FILE_CONTENT.toolName(),
                                                         McpToolEnum.FILTER_FILE_CONTENT.toolName());
    private static final Set<String> SEARCHES = Set.of(McpToolEnum.SEARCH_IN_FILES.toolName(),
                                                       McpToolEnum.SEARCH_SYMBOLS.toolName(),
                                                       McpToolEnum.SEARCH_TYPES.toolName(),
                                                       McpToolEnum.FIND_USAGES.toolName(),
                                                       McpToolEnum.FIND_FILE.toolName());

    private ToolResultCap() {
    }

    public static String cap(String toolName, String result, int maxChars) {
        if (result == null || result.length() <= maxChars) {
            return result;
        }
        int kept = maxChars;
        if (kept > 0 && Character.isHighSurrogate(result.charAt(kept - 1))) {
            kept--;
        }
        return result.substring(0, kept) + note(toolName, kept);
    }

    private static String note(String toolName, int kept) {
        if (FILE_READS.contains(toolName)) {
            return "\n[truncated at " + kept + " characters — read the rest with startLine/endLine or FilterFileContent]";
        }
        if (SEARCHES.contains(toolName)) {
            return "\n[truncated at " + kept + " characters — narrow the search]";
        }
        return "\n[truncated at " + kept + " characters]";
    }
}
