package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Shared validation for the Maven/Gradle/Ant build-option parameters (#5 / F2), so a crafted value cannot smuggle an
 * extra CLI argument into a build the tool didn't ask for. Every option ends up as one element of a
 * {@link ProcessBuilder} argv list, never through a shell, so classic shell-metacharacter injection ({@code ; | & `})
 * is not itself exploitable here — the real risk this guards against is ARGUMENT injection: a value that starts with
 * {@code -} becomes an unintended extra FLAG (not the goal/property the caller thought they were naming) the moment it
 * lands in the argv list next to the real ones.
 * <p>
 * Rules, applied uniformly across Maven goals, Gradle tasks, Ant targets, module/profile names and property keys:
 * <ul>
 * <li>must not be blank;</li>
 * <li>must not start with {@code -} (the argument-injection guard above);</li>
 * <li>must not contain whitespace or control characters (a space would silently split into two argv elements relative
 * to what the caller intended; a control character has no legitimate place in any of these names);</li>
 * <li>restricted to a safe character set: letters, digits, and {@code . : _ - /}, which covers every legitimate
 * goal/task/target/module/profile name seen in practice (including Maven's {@code plugin:goal} and {@code :artifactId}
 * resume syntax) while excluding shell/argument metacharacters ({@code ; | & ` $ < > ' " \}).</li>
 * </ul>
 */
public final class BuildOptionValidator {

    /**
     * Allows a leading {@code :} (Maven's {@code -rf :artifactId} resume syntax) or an alphanumeric, then any run of
     * the same safe character set. Deliberately excludes a bare {@code -} as the first character — see the class
     * javadoc.
     */
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9:][A-Za-z0-9._:/-]*");

    /**
     * Property keys are held to the same safe-token rule as goal/task names — a key containing {@code =} or {@code -}
     * as a wire-level parameter name would be exactly the kind of ambiguity {@link McpToolPropertyEnum#PROPERTIES}
     * exists to avoid by using a real map instead of a raw {@code -Dk=v} string.
     */
    private static final Pattern SAFE_PROPERTY_KEY = SAFE_TOKEN;

    /**
     * Property VALUES are deliberately not restricted to the safe-token charset — a legitimate value (a file path, a
     * version string, free text) can contain spaces or punctuation the token rule would reject. Each value is still
     * exactly one argv element (never shell-interpreted), so the only thing worth refusing is a control character that
     * has no legitimate place in a single-line CLI argument.
     */
    private static final Pattern FORBIDDEN_VALUE_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern TEST_SELECTOR = Pattern.compile("[A-Za-z0-9_.$#*?,!+/]+");
    private static final Pattern MAVEN_THREADS = Pattern.compile("(?:[1-9]\\d*|(?:[1-9]\\d*(?:\\.\\d+)?|0\\.\\d+)C)");

    /**
     * Validates one goal/task/target/module/profile/resume-from/threads token.
     *
     * @return null if {@code value} is safe to pass to the build tool as one argv element, otherwise a caller-facing
     * error message naming the offending value and parameter
     */
    static String validateToken(String paramKey, String value) {
        if (value == null || value.isBlank()) {
            return paramKey + " must not contain a blank entry";
        }
        if (value.startsWith("-")) {
            return paramKey + " '" + value + "' must not start with '-' — that would be read as an extra command-line "
                    + "flag rather than a " + paramKey + " value";
        }
        if (!SAFE_TOKEN.matcher(value).matches()) {
            return paramKey + " '" + value + "' contains characters that are not allowed (letters, digits, "
                    + "'.', ':', '_', '-', '/' only, no whitespace)";
        }
        return null;
    }

    /**
     * Validates Maven's -T syntax: a positive integer, or a positive decimal factor followed by C.
     */
    static String validateMavenThreads(String value) {
        return MAVEN_THREADS.matcher(value).matches()
               ? null : McpToolPropertyEnum.THREADS.key()
                + " must be a positive integer or a positive number followed by C";
    }

    static String validateTestSelector(String paramKey, String value) {
        if (value == null || value.isBlank()) {
            return paramKey + " must not be blank";
        }
        if (value.startsWith("-")) {
            return paramKey + " '" + value + "' must not start with '-' — that would be read as an extra command-line flag";
        }
        if (!TEST_SELECTOR.matcher(value).matches()) {
            return paramKey + " '" + value + "' contains characters that are not allowed (letters, digits, "
                    + "selector punctuation only, no whitespace or control characters)";
        }
        return null;
    }

    /**
     * Validates every element of a token list (goals/tasks/targets/projectList/profiles), stopping at the first
     * problem.
     *
     * @return null if every entry is safe, otherwise the first entry's error message
     */
    static String validateTokens(String paramKey, List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String error = validateToken(paramKey, value);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    /**
     * Validates and converts a JSON object of build properties into an ordered key/value map — a map, not a raw string,
     * is the point: it removes the whole class of {@code -Dk1=v1 -Dk2=v2} string-smuggling problems a single free-text
     * property argument would have. {@code LinkedHashMap} preserves the caller's own ordering, in case a later property
     * is meant to override an earlier one.
     *
     * @return the validated map, or null with {@code errorOut[0]} set to the refusal message if any key or value is
     * invalid
     */
    static Map<String, String> validateProperties(String paramKey, JsonObject properties, String[] errorOut) {
        Map<String, String> result = new LinkedHashMap<>();
        if (properties == null) {
            return result;
        }
        for (String key : properties.keySet()) {
            if (!SAFE_PROPERTY_KEY.matcher(key).matches() || key.startsWith("-")) {
                errorOut[0] = paramKey + " key '" + key + "' contains characters that are not allowed (letters, "
                        + "digits, '.', ':', '_', '-', '/' only, no '=', no leading '-')";
                return null;
            }
            var element = properties.get(key);
            String value = element.isJsonPrimitive() ? element.getAsString() : String.valueOf(element);
            if (FORBIDDEN_VALUE_CHARS.matcher(value).find()) {
                errorOut[0] = paramKey + " value for '" + key + "' contains a control character, which is not allowed";
                return null;
            }
            result.put(key, value);
        }
        return result;
    }

    /**
     * Builds {@code -D<k>=<v>} style arguments (Maven system properties, Ant properties, Gradle system properties) from
     * an already-validated map, one argv element per entry.
     */
    static List<String> toDefineArgs(Map<String, String> properties) {
        List<String> args = new ArrayList<>();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            args.add("-D" + e.getKey() + "=" + e.getValue());
        }
        return args;
    }

    /**
     * Builds {@code -P<k>=<v>} style arguments (Gradle project properties) from an already-validated map.
     */
    static List<String> toDashPArgs(Map<String, String> properties) {
        List<String> args = new ArrayList<>();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            args.add("-P" + e.getKey() + "=" + e.getValue());
        }
        return args;
    }

    /**
     * Reads a JSON array of strings into a plain list, for a tool passing {@code goals}/{@code tasks}/
     * {@code targets}/{@code projectList}/{@code profiles} on to a provider's options record. The common build-option
     * shape validator rejects non-string elements before this conversion is reached. Null input (parameter omitted)
     * yields an empty list, never null, so callers never need a separate null check before iterating.
     */
    public static List<String> toStringList(JsonArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (var element : array) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                result.add(element.getAsString());
            }
        }
        return result;
    }

    private BuildOptionValidator() {
    }
}
