package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists a session's model-facing context to
 * &lt;baseDir&gt;/&lt;sessionId&gt;/context.json, beside the existing
 * history.json.
 *
 * Unlike sessions.json — which throws on corruption because it is irreplaceable
 * user data — a damaged context file is logged and ignored. This file is a
 * cache that can be rebuilt simply by talking.
 */
public class ContextPersistenceManager {

    private static final Logger LOG
            = Logger.getLogger(ContextPersistenceManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path baseDir;

    public ContextPersistenceManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    public Path contextPath(String sessionId) {
        return baseDir.resolve(sessionId).resolve("context.json");
    }

    /**
     * Written via a temporary file and an atomic move, so a crash mid-write
     * cannot leave a half-written file in place of a good one.
     */
    public void save(String sessionId, JsonObject payload) throws IOException {
        Path target = contextPath(sessionId);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("context.json.tmp");
        Files.writeString(tmp, GSON.toJson(payload), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * @return the stored payload, or null when absent or unreadable
     */
    public JsonObject load(String sessionId) {
        Path target = contextPath(sessionId);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        }
        catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "Ignoring unreadable context file: " + target, ex);
            return null;
        }
    }

    public void delete(String sessionId) {
        try {
            Files.deleteIfExists(contextPath(sessionId));
        }
        catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not delete context file", ex);
        }
    }
}
