package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;

public class HistoryPersistenceManager {

    private static final Logger LOG = Logger.getLogger(HistoryPersistenceManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static List<AiMessage> parseMessages(JsonArray arr) {
        List<AiMessage> result = new ArrayList<>();
        for (JsonElement el : arr) {
            try {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                JsonElement roleEl = o.get("role");
                JsonElement textEl = o.get("text");
                JsonElement tsEl = o.get("timestamp");
                if (roleEl == null || textEl == null || tsEl == null) {
                    continue;
                }
                String role = roleEl.getAsString();
                String text = textEl.getAsString();
                long ts = tsEl.getAsLong();
                if ("USER".equals(role)) {
                    result.add(AiMessage.restoredUser(text, ts));
                }
                else {
                    result.add(AiMessage.restoredAssistant(text, ts));
                }
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Skipping malformed message entry", e);
            }
        }
        return result;
    }

    private final Path filePath;

    public HistoryPersistenceManager(Path filePath) {
        this.filePath = filePath;
    }

    /**
     * Save messages, session ID, and pinned working directory so the session
     * can be resumed on next open with the same CWD.
     */
    public void save(List<AiMessage> messages, String sessionId, String workingDir) throws IOException {
        save(messages, sessionId, workingDir, false);
    }

    /**
     * Save messages, session ID, pinned working directory, and whether the full
     * instruction guide has been loaded this conversation, so the session can
     * be resumed on next open with the same CWD and gate state. Format:
     * {"sessionId":"<uuid>","workingDir":"/path","instructionsLoaded":bool,"messages":[...]}
     */
    public void save(List<AiMessage> messages, String sessionId, String workingDir,
            boolean instructionsLoaded) throws IOException {
        if (messages.isEmpty()) {
            return; // empty save is a no-op — callers must call delete() explicitly to clear
        }
        JsonArray arr = new JsonArray();
        for (AiMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role().name());
            o.addProperty("text", m.markdownText());
            o.addProperty("timestamp", m.timestamp());
            arr.add(o);
        }
        JsonObject wrapper = new JsonObject();
        if (sessionId != null) {
            wrapper.addProperty("sessionId", sessionId);
        }
        if (workingDir != null) {
            wrapper.addProperty("workingDir", workingDir);
        }
        wrapper.addProperty("instructionsLoaded", instructionsLoaded);
        wrapper.add("messages", arr);

        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = GSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8);
        Path tmp = filePath.resolveSibling(filePath.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                fc.write(ByteBuffer.wrap(bytes));
                fc.force(true); // fsync before rename
            }
            try {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            // fsync parent directory so the rename entry is durable (no-op on some platforms)
            if (parent != null) {
                try (FileChannel dirFc = FileChannel.open(parent, StandardOpenOption.READ)) {
                    dirFc.force(true);
                }
                catch (IOException ignored) {
                }
            }
        }
        finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Load messages and session ID. Handles both new format
     * {"sessionId":...,"messages":[...]} and old bare-array format.
     */
    public LoadedHistory load() throws IOException {
        if (!Files.exists(filePath)) {
            return new LoadedHistory(List.of(), null, null, false);
        }
        String json = Files.readString(filePath);
        try {
            JsonElement root = GSON.fromJson(json, JsonElement.class);
            if (root == null || root.isJsonNull()) {
                return new LoadedHistory(List.of(), null, null, false);
            }

            JsonArray arr;
            String sessionId = null;
            String workingDir = null;
            boolean instructionsLoaded = false;
            if (root.isJsonObject()) {
                JsonObject wrapper = root.getAsJsonObject();
                sessionId = wrapper.has("sessionId") ? wrapper.get("sessionId").getAsString() : null;
                workingDir = wrapper.has("workingDir") ? wrapper.get("workingDir").getAsString() : null;
                instructionsLoaded = wrapper.has("instructionsLoaded")
                        && wrapper.get("instructionsLoaded").isJsonPrimitive()
                        && wrapper.get("instructionsLoaded").getAsBoolean();
                arr = wrapper.has("messages") && wrapper.get("messages").isJsonArray()
                        ? wrapper.getAsJsonArray("messages") : new JsonArray();
            }
            else if (root.isJsonArray()) {
                // Old format — bare array, no session ID or workingDir
                arr = root.getAsJsonArray();
            }
            else {
                return new LoadedHistory(List.of(), null, null, false);
            }

            List<AiMessage> result = parseMessages(arr);
            return new LoadedHistory(result, sessionId, workingDir, instructionsLoaded);

        }
        catch (Exception e) {
            LOG.log(Level.WARNING, "History file corrupted, starting fresh: {0}", filePath);
            return new LoadedHistory(List.of(), null, null, false);
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(filePath);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete history file", e);
        }
    }

    /**
     * Result of loading history: messages, session ID to resume, the working
     * directory the session was pinned to, and whether the full instruction
     * guide was loaded this conversation (all but the flag may be null).
     */
    public record LoadedHistory(List<AiMessage> messages, String sessionId, String workingDir,
            boolean instructionsLoaded) {

    }
}
