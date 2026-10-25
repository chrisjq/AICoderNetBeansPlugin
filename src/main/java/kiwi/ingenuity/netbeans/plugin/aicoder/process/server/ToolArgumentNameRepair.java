package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Repairs tool-argument names that a model streamed with stray text attached, such as {@code isRegex=false},
 * {@code isRegext} or {@code filePattern»}. A key is matched to the longest accepted parameter name it starts with. It
 * is repaired only when that name is not already present and the value is usable; anything else stays rejected by
 * validation.
 */
final class ToolArgumentNameRepair {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * One change made to the arguments. {@code to} is null when the key was dropped as unusable. {@code from} is the
     * matched name followed by an ellipsis, so the attached text (which may be a secret) never reaches a log.
     */
    record Repair(String from, String to) {

    }

    private ToolArgumentNameRepair() {
    }

    /**
     * Rewrites {@code argsObj} in place. Each key that is not accepted is matched to the longest accepted name it
     * starts with (longest first, ties alphabetical), and is repaired only when that name is not already present. The
     * key is replaced by the name. A JSON null or blank value takes the text after a directly following {@code =} or
     * {@code :}, parsed as a boolean, number or string; if nothing usable remains, the key is dropped.
     *
     * @return one entry per key that was replaced or dropped, in encounter order; empty when nothing changed
     */
    static List<Repair> repairInPlace(JsonObject argsObj, Set<String> accepted) {
        List<Repair> repairs = new ArrayList<>();
        if (argsObj == null) {
            return repairs;
        }
        List<String> longestFirst = accepted.stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();
        for (String key : List.copyOf(argsObj.keySet())) {
            if (accepted.contains(key)) {
                continue;
            }
            String name = longestAcceptedPrefix(key, longestFirst);
            if (name == null || argsObj.has(name)) {
                continue;
            }
            String display = name + "…";
            JsonElement value = argsObj.get(key);
            String after = textAfterSeparator(key, name.length());
            if (isBlankValue(value) && !after.isEmpty()) {
                value = parseSuffix(after);
            }
            argsObj.remove(key);
            if (value == null || value.isJsonNull()) {
                repairs.add(new Repair(display, null));
                continue;
            }
            argsObj.add(name, value);
            repairs.add(new Repair(display, name));
        }
        return repairs;
    }

    /**
     * The first name in {@code longestFirst} that {@code key} starts with and extends, or null when none does.
     */
    private static String longestAcceptedPrefix(String key, List<String> longestFirst) {
        for (String name : longestFirst) {
            if (key.length() > name.length() && key.startsWith(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * The trimmed text after a {@code =} or {@code :} placed directly at {@code index}, or empty when there is no such
     * separator or nothing follows it.
     */
    private static String textAfterSeparator(String key, int index) {
        if (index >= key.length()) {
            return "";
        }
        char separator = key.charAt(index);
        if (separator != '=' && separator != ':') {
            return "";
        }
        return key.substring(index + 1).trim();
    }

    private static boolean isBlankValue(JsonElement value) {
        return value == null || value.isJsonNull()
                || (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && value.getAsString().isBlank());
    }

    private static JsonElement parseSuffix(String suffix) {
        if (suffix.length() >= 2 && suffix.startsWith("\"") && suffix.endsWith("\"")) {
            return new JsonPrimitive(suffix.substring(1, suffix.length() - 1));
        }
        if ("true".equals(suffix) || "false".equals(suffix)) {
            return new JsonPrimitive(Boolean.parseBoolean(suffix));
        }
        if (NUMBER.matcher(suffix).matches()) {
            return new JsonPrimitive(new BigDecimal(suffix));
        }
        return new JsonPrimitive(suffix);
    }
}
