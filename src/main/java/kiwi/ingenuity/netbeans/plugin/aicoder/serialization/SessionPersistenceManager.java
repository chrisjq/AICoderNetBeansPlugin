package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettingsCreator;

/**
 * Manages persistent storage and retrieval of AI session data. Handles JSON
 * serialization of sessions to disk with proper file locking and atomicity
 * guarantees.
 */
public class SessionPersistenceManager {

    /**
     * Logger for this class
     */
    private static final Logger LOG = Logger.getLogger(SessionPersistenceManager.class.getName());
    /**
     * Shared GSON instance for JSON serialization
     */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /**
     * Serializes all instances in this JVM before acquiring the cross-process
     * file lock. FileChannel.lock() throws OverlappingFileLockException if the
     * same JVM holds it twice, so we need intra-JVM serialization independently
     * of per-instance synchronized methods.
     */
    private static final Object JVM_LOCK = new Object();

    public static Path defaultBaseDir() {
        return Path.of(System.getProperty("user.home"), ".netbeans", ".aicoder");
    }

    /**
     * Base directory for all persisted data
     */
    private final Path baseDir;
    /**
     * File path for the sessions JSON file
     */
    private final Path sessionsFile;

    public SessionPersistenceManager() {
        this(defaultBaseDir());
    }

    public SessionPersistenceManager(Path baseDir) {
        this.baseDir = baseDir;
        this.sessionsFile = baseDir.resolve("sessions.json");
    }

    public Path historyPath(String sessionId) {
        return baseDir.resolve(sessionId).resolve("history.json");
    }

    public synchronized List<AiSession> loadAll() throws IOException {
        return withFileLock(this::loadAllLocked);
    }

    public synchronized void save(AiSession session) throws IOException {
        withFileLock(() -> {
            List<AiSession> all = new ArrayList<>(loadAllLocked());
            all.removeIf(s -> s.id().equals(session.id()));
            all.add(0, session.touched());
            persist(all);
            return null;
        });
    }

    public synchronized void delete(String sessionId) throws IOException {
        AiSession deleted = withFileLock(() -> {
            List<AiSession> all = new ArrayList<>(loadAllLocked());
            AiSession match = all.stream().filter(s -> s.id().equals(sessionId)).findFirst().orElse(null);
            all.removeIf(s -> s.id().equals(sessionId));
            persist(all);
            return match;
        });
        // History file lives in its own directory — no file lock needed
        Path hist = historyPath(sessionId);
        Files.deleteIfExists(hist);
        Path dir = hist.getParent();
        if (dir != null && Files.isDirectory(dir)) {
            boolean isEmpty;
            try (var stream = Files.list(dir)) {
                isEmpty = stream.findFirst().isEmpty();
            }
            if (isEmpty) {
                Files.deleteIfExists(dir);
            }
        }
        // Remove the per-session config dir (~/.ai-coder/{type}/{sessionId}):
        // logs, memory, and any other per-session data an AI impl stored there.
        if (deleted != null) {
            PluginUtil.deleteAiSessionConfigDir(deleted.aiType(), sessionId);
        }
    }

    private <T> T withFileLock(IoAction<T> action) throws IOException {
        synchronized (JVM_LOCK) {
            Files.createDirectories(baseDir);
            Path lockFile = sessionsFile.resolveSibling("sessions.lock");
            try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = ch.lock()) {
                return action.run();
            }
        }
    }

    private List<AiSession> loadAllLocked() throws IOException {
        if (!Files.exists(sessionsFile)) {
            return List.of();
        }
        String json = Files.readString(sessionsFile);
        List<AiSession> result = new ArrayList<>();
        try {
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null) {
                return List.of();
            }
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                try {
                    String description = o.has("description") && !o.get("description").isJsonNull()
                            ? o.get("description").getAsString() : null;
                    AiTypeEnum aiType;
                    try {
                        aiType = o.has("aiType") ? AiTypeEnum.valueOf(o.get("aiType").getAsString()) : null;
                    }
                    catch (IllegalArgumentException e) {
                        LOG.log(Level.WARNING, "Invalid aiType, skipping session:\n{0}", o.toString());
                        continue;
                    }

                    if (aiType == null) {
                        LOG.log(Level.WARNING, "Missing aiType, skipping session:\n{0}", o.toString());
                        continue;
                    }

                    AiSessionSettingsCreator creator = aiType.getSettingsCreator();
                    AiSessionSettings settings = creator.create();
                    if (o.has("config") && o.get("config").isJsonObject()) {
                        JsonObject cfgObj = o.getAsJsonObject("config");
                        creator.update(settings, cfgObj);
                    }
                    if (!o.has("id") || !o.has("name") || !o.has("createdAt") || !o.has("lastUsedAt")) {
                        LOG.log(Level.WARNING, "Session missing required fields, skipping:\n{0}", o.toString());
                        continue;
                    }
                    Instant createdAt;
                    try {
                        createdAt = Instant.parse(o.get("createdAt").getAsString());
                    }
                    catch (Exception e) {
                        LOG.log(Level.WARNING, "Malformed createdAt for session {0}, using epoch", o.get("id").getAsString());
                        createdAt = Instant.EPOCH;
                    }
                    Instant lastUsedAt;
                    try {
                        lastUsedAt = Instant.parse(o.get("lastUsedAt").getAsString());
                    }
                    catch (Exception e) {
                        LOG.log(Level.WARNING, "Malformed lastUsedAt for session {0}, using epoch", o.get("id").getAsString());
                        lastUsedAt = Instant.EPOCH;
                    }
                    AiSession session = new AiSession(
                            o.get("id").getAsString(),
                            o.get("name").getAsString(),
                            description,
                            aiType,
                            o.has("projectPath") ? o.get("projectPath").getAsString() : null,
                            settings,
                            createdAt,
                            lastUsedAt);
                    if (o.has("sessionInstructionsDelivery")) {
                        try {
                            session.setSessionInstructionsDelivery(SessionInstructionsDeliveryEnum.valueOf(
                                    o.get("sessionInstructionsDelivery").getAsString()));
                        }
                        catch (IllegalArgumentException e) {
                            LOG.log(Level.WARNING, "Invalid session instruction delivery mode for session {0}; using first-request delivery",
                                    session.id());
                        }
                    }
                    if (o.has("startupInstructionsInjected") && !o.get("startupInstructionsInjected").isJsonNull()) {
                        session.setStartupInstructionsInjected(o.get("startupInstructionsInjected").getAsBoolean());
                    }
                    if (o.has("lastInjectedInstructions") && !o.get("lastInjectedInstructions").isJsonNull()) {
                        session.setLastInjectedInstructions(o.get("lastInjectedInstructions").getAsString());
                    }
                    result.add(session);
                }
                catch (Exception e) {
                    LOG.log(Level.WARNING, "Skipping malformed session entry", e);
                }
            }
        }
        catch (JsonSyntaxException e) {
            LOG.log(Level.SEVERE, "sessions.json corrupted — refusing to load; manual recovery required: " + sessionsFile, e);
            throw new IOException("sessions.json corrupted — refusing to load to prevent data loss", e);
        }
        result.sort((a, b) -> b.lastUsedAt().compareTo(a.lastUsedAt()));
        return result;
    }

    private void persist(List<AiSession> sessions) throws IOException {
        JsonArray arr = new JsonArray();
        for (AiSession s : sessions) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.addProperty("name", s.name());
            if (s.description() != null) {
                o.addProperty("description", s.description());
            }
            o.addProperty("aiType", s.aiType().name());
            if (s.projectPath() != null) {
                o.addProperty("projectPath", s.projectPath());
            }
            JsonObject cfgObj = new JsonObject();
            AiSessionSettings cfg = s.settings();
            cfg.populateJsonObject(cfgObj);
            o.add("config", cfgObj);
            o.addProperty("createdAt", s.createdAt().toString());
            o.addProperty("lastUsedAt", s.lastUsedAt().toString());
            o.addProperty("sessionInstructionsDelivery", s.sessionInstructionsDelivery().name());
            o.addProperty("startupInstructionsInjected", s.isStartupInstructionsInjected());
            if (s.lastInjectedInstructions() != null) {
                o.addProperty("lastInjectedInstructions", s.lastInjectedInstructions());
            }
            arr.add(o);
        }
        byte[] bytes = GSON.toJson(arr).getBytes(StandardCharsets.UTF_8);
        Path tmp = sessionsFile.resolveSibling(sessionsFile.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel fc = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                fc.write(ByteBuffer.wrap(bytes));
                fc.force(true); // fsync before rename
            }
            try {
                Files.move(tmp, sessionsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, sessionsFile, StandardCopyOption.REPLACE_EXISTING);
            }
            // fsync directory so the rename entry is durable (no-op on some platforms)
            try (FileChannel dirFc = FileChannel.open(baseDir, StandardOpenOption.READ)) {
                dirFc.force(true);
            }
            catch (IOException ignored) {
            }
        }
        finally {
            Files.deleteIfExists(tmp);
        }
    }

    @FunctionalInterface
    private interface IoAction<T> {

        T run() throws IOException;
    }

}
