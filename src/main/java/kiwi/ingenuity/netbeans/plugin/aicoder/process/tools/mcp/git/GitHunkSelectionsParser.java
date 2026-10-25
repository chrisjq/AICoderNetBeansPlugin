package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.GitHunkPatch;

/**
 * Parses the shared hunk-selection argument used by GitAdd and GitReset.
 */
final class GitHunkSelectionsParser {

    private GitHunkSelectionsParser() {
    }

    static ParsedSelections parse(JsonArray hunks) throws McpArgumentException {
        List<String> wholeFiles = new ArrayList<>();
        Map<String, List<GitHunkPatch.Selection>> selectionsByFile = new LinkedHashMap<>();
        if (hunks == null) {
            return new ParsedSelections(wholeFiles, selectionsByFile);
        }
        for (int i = 0; i < hunks.size(); i++) {
            JsonElement element = hunks.get(i);
            if (!element.isJsonObject()) {
                throw new McpArgumentException(-32602,
                        "hunks entry " + i + " must be an object with a file (and optional hunk / fromLine / toLine), not a "
                        + jsonType(element));
            }
            parseEntry(element.getAsJsonObject(), i, wholeFiles, selectionsByFile);
        }
        return new ParsedSelections(wholeFiles, selectionsByFile);
    }

    private static void parseEntry(JsonObject object, int index, List<String> wholeFiles,
                                   Map<String, List<GitHunkPatch.Selection>> selectionsByFile)
            throws McpArgumentException {
        String file = optionalString(object, McpToolPropertyEnum.FILE.key());
        if (file == null || file.isBlank()) {
            throw new McpArgumentException(-32602, "hunks entry " + index + " is missing a non-blank \""
                    + McpToolPropertyEnum.FILE.key() + "\"");
        }
        Integer hunk = optionalInt(object, McpToolPropertyEnum.HUNK.key(), index);
        Integer fromLine = optionalInt(object, McpToolPropertyEnum.FROM_LINE.key(), index);
        Integer toLine = optionalInt(object, McpToolPropertyEnum.TO_LINE.key(), index);
        if ((fromLine == null) != (toLine == null)) {
            throw new McpArgumentException(-32602, "hunks entry " + index + " for " + file + ": "
                    + McpToolPropertyEnum.FROM_LINE.key() + " and " + McpToolPropertyEnum.TO_LINE.key()
                    + " must be given together or neither");
        }
        if (hunk == null && fromLine == null) {
            wholeFiles.add(file);
            return;
        }
        selectionsByFile.computeIfAbsent(file, ignored -> new ArrayList<>())
                .add(new GitHunkPatch.Selection(hunk, fromLine, toLine));
    }

    private static String optionalString(JsonObject object, String key) throws McpArgumentException {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new McpArgumentException(-32602, "\"" + key + "\" must be a string");
        }
        return value.getAsString();
    }

    private static Integer optionalInt(JsonObject object, String key, int index) throws McpArgumentException {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new McpArgumentException(-32602, "hunks entry " + index + " \"" + key + "\" must be an integer");
        }
        return value.getAsInt();
    }

    private static String jsonType(JsonElement element) {
        if (element.isJsonObject()) {
            return "object";
        }
        if (element.isJsonArray()) {
            return "array";
        }
        if (element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().isString() ? "string"
                    : element.getAsJsonPrimitive().isBoolean() ? "boolean" : "number";
        }
        return "a " + element.getClass().getSimpleName();
    }

    record ParsedSelections(List<String> wholeFiles, Map<String, List<GitHunkPatch.Selection>> selectionsByFile) {
    }
}
