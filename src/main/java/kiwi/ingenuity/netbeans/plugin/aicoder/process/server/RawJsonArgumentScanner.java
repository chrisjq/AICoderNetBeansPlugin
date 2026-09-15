package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads argument member names before Gson materialises an object, because Gson keeps only the last occurrence of a
 * duplicate name.
 */
public final class RawJsonArgumentScanner {

    /**
     * Counts names in one JSON object. Nested object keys are intentionally not included: only tool-argument members
     * are relevant to MCP validation.
     *
     * @return insertion-ordered duplicate names and their occurrence counts
     */
    public static Map<String, Integer> duplicateTopLevelKeys(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                counts.merge(name, 1, Integer::sum);
                reader.skipValue();
            }
            reader.endObject();
        }
        catch (IOException | IllegalStateException ex) {
            return Map.of();
        }
        counts.entrySet().removeIf(entry -> entry.getValue() < 2);
        return counts.isEmpty() ? Map.of()
               : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /**
     * Counts an object's members at a named object path inside a larger JSON document. The path is matched only through
     * object-member names.
     */
    public static Map<String, Integer> duplicateKeys(String json, String... objectPath) {
        if (json == null || json.isBlank() || objectPath == null || objectPath.length == 0) {
            return Map.of();
        }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            return duplicateKeys(reader, objectPath, 0);
        }
        catch (IOException | IllegalStateException ex) {
            return Map.of();
        }
    }

    private static Map<String, Integer> duplicateKeys(JsonReader reader, String[] path, int pathIndex)
            throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.equals(path[pathIndex])) {
                if (pathIndex == path.length - 1) {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String key = reader.nextName();
                        counts.merge(key, 1, Integer::sum);
                        reader.skipValue();
                    }
                    reader.endObject();
                    counts.entrySet().removeIf(entry -> entry.getValue() < 2);
                    return counts.isEmpty() ? Map.of()
                           : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
                }
                return duplicateKeys(reader, path, pathIndex + 1);
            }
            reader.skipValue();
        }
        reader.endObject();
        counts.entrySet().removeIf(entry -> entry.getValue() < 2);
        return counts.isEmpty() ? Map.of()
               : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private RawJsonArgumentScanner() {
    }
}
