package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;

/**
 * Removes the sentences of a text that name a tool the session cannot see, so instruction and description text never
 * points the model at a tool that is not exposed to it. Matching is on the text alone, so it also catches references
 * nobody declared.
 */
public final class HiddenToolTextFilter {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[.!?]+[)\"'\\]]*(\\s+)");
    private static final Pattern ABBREVIATION_TAIL = Pattern.compile("(?i)(?<![a-z])(?:e\\.g|i\\.e)\\.$");

    private HiddenToolTextFilter() {
    }

    /**
     * Whether {@code text} names {@code tool}, by exactly the rule the filter uses: a whole word on the tool's name,
     * where a letter or digit on either side disqualifies the match but an underscore before it does not, so a
     * qualified "mcp__server__Tool" counts. Exposed so tests that police references use the production boundary, not a
     * copy.
     */
    public static boolean mentions(String text, McpToolEnum tool) {
        return text != null && namePattern(Set.of(tool)).matcher(text).find();
    }

    private static Pattern namePattern(Set<McpToolEnum> tools) {
        return Pattern.compile("(?<![A-Za-z0-9])(?:"
                + tools.stream().map(t -> Pattern.quote(t.toolName())).collect(Collectors.joining("|"))
                + ")(?![A-Za-z0-9])");
    }

    /**
     * Drops every sentence that names (as a whole word) any tool in {@code hiddenTools} and keeps everything else
     * verbatim. Each line is its own unit and is split into sentences; a line left empty is dropped, and the lines that
     * remain are neither reflowed nor re-indented. Null or empty text and an empty set return the input unchanged.
     */
    public static String omitSentencesReferencing(String text, Set<McpToolEnum> hiddenTools) {
        if (text == null || text.isEmpty() || hiddenTools == null || hiddenTools.isEmpty()) {
            return text;
        }
        Pattern hidden = namePattern(hiddenTools);
        if (!hidden.matcher(text).find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int pos = 0;
        while (pos < text.length()) {
            int newline = text.indexOf('\n', pos);
            int contentEnd;
            int next;
            if (newline < 0) {
                contentEnd = text.length();
                next = text.length();
            }
            else {
                contentEnd = newline > pos && text.charAt(newline - 1) == '\r' ? newline - 1 : newline;
                next = newline + 1;
            }
            String content = text.substring(pos, contentEnd);
            String terminator = text.substring(contentEnd, next);
            String filtered = filterLine(content, hidden);
            if (filtered.isEmpty() && !content.isEmpty()) {
                if (terminator.isEmpty()) {
                    // The unterminated last line went: take the terminator it was hanging off with it.
                    stripTrailingTerminator(out);
                }
            }
            else {
                out.append(filtered).append(terminator);
            }
            pos = next;
        }
        return out.toString();
    }

    /**
     * The tools in {@code all} that are not in {@code visible}: the set whose names must not appear in text shown to a
     * session.
     */
    public static Set<McpToolEnum> hiddenFrom(Map<McpToolEnum, ?> all, Map<McpToolEnum, ?> visible) {
        Set<McpToolEnum> hidden = EnumSet.noneOf(McpToolEnum.class);
        for (McpToolEnum tool : all.keySet()) {
            if (!visible.containsKey(tool)) {
                hidden.add(tool);
            }
        }
        return hidden;
    }

    /**
     * Returns a deep copy of a tool schema in which every string-valued {@code description} — the tool's own and those
     * of its parameters, at any depth — has had its sentences naming a hidden tool removed. The input is not modified,
     * because handlers may hand out a schema they keep. A property that is itself called "description" is an object,
     * not a string, so it is descended into rather than filtered.
     */
    public static JsonObject omitHiddenReferencesInDescriptions(JsonObject schema, Set<McpToolEnum> hiddenTools) {
        JsonObject copy = schema.deepCopy();
        if (hiddenTools != null && !hiddenTools.isEmpty()) {
            filterDescriptions(copy, hiddenTools);
        }
        return copy;
    }

    private static void filterDescriptions(JsonElement node, Set<McpToolEnum> hiddenTools) {
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if ("description".equals(entry.getKey()) && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()) {
                    entry.setValue(new JsonPrimitive(omitSentencesReferencing(value.getAsString(), hiddenTools)));
                }
                else {
                    filterDescriptions(value, hiddenTools);
                }
            }
        }
        else if (node.isJsonArray()) {
            for (JsonElement element : node.getAsJsonArray()) {
                filterDescriptions(element, hiddenTools);
            }
        }
    }

    private static String filterLine(String line, Pattern hidden) {
        if (!hidden.matcher(line).find()) {
            return line;
        }
        StringBuilder kept = new StringBuilder(line.length());
        int start = 0;
        boolean lastDropped = false;
        Matcher boundary = SENTENCE_BOUNDARY.matcher(line);
        while (boundary.find()) {
            if (ABBREVIATION_TAIL.matcher(line.substring(0, boundary.start(1))).find()) {
                continue;
            }
            lastDropped = appendUnlessHidden(kept, line.substring(start, boundary.end()), hidden);
            start = boundary.end();
        }
        if (start < line.length()) {
            lastDropped = appendUnlessHidden(kept, line.substring(start), hidden);
        }
        if (lastDropped) {
            // The separator that followed the last kept sentence now trails the line.
            int end = kept.length();
            while (end > 0 && Character.isWhitespace(kept.charAt(end - 1))) {
                end--;
            }
            kept.setLength(end);
        }
        return kept.toString();
    }

    private static boolean appendUnlessHidden(StringBuilder kept, String sentenceWithSeparator, Pattern hidden) {
        if (hidden.matcher(sentenceWithSeparator).find()) {
            return true;
        }
        kept.append(sentenceWithSeparator);
        return false;
    }

    private static void stripTrailingTerminator(StringBuilder out) {
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == '\n') {
            length--;
            if (length > 0 && out.charAt(length - 1) == '\r') {
                length--;
            }
            out.setLength(length);
        }
    }
}
