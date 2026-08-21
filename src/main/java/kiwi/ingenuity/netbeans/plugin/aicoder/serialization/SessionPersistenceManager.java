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
                    String descriptionKey = SessionPersistenceKeyEnum.DESCRIPTION.key();
                    String aiTypeKey = SessionPersistenceKeyEnum.AI_TYPE.key();
                    String idKey = SessionPersistenceKeyEnum.ID.key();
                    String description = o.has(descriptionKey) && !o.get(descriptionKey).isJsonNull()
                            ? o.get(descriptionKey).getAsString() : null;
                    AiTypeEnum aiType;
                    try {
                        aiType = o.has(aiTypeKey) ? AiTypeEnum.valueOf(o.get(aiTypeKey).getAsString()) : null;
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
                    String configKey = SessionPersistenceKeyEnum.CONFIG.key();
                    if (o.has(configKey) && o.get(configKey).isJsonObject()) {
                        JsonObject cfgObj = o.getAsJsonObject(configKey);
                        creator.update(settings, cfgObj);
                    }
                    if (!o.has(idKey) || !o.has(SessionPersistenceKeyEnum.NAME.key())
                            || !o.has(SessionPersistenceKeyEnum.CREATED_AT.key())
                            || !o.has(SessionPersistenceKeyEnum.LAST_USED_AT.key())) {
                        LOG.log(Level.WARNING, "Session missing required fields, skipping:\n{0}", o.toString());
                        continue;
                    }
                    Instant createdAt;
                    try {
                        createdAt = Instant.parse(o.get(SessionPersistenceKeyEnum.CREATED_AT.key()).getAsString());
                    }
                    catch (Exception e) {
                        LOG.log(Level.WARNING, "Malformed createdAt for session {0}, using epoch", o.get(idKey).getAsString());
                        createdAt = Instant.EPOCH;
                    }
                    Instant lastUsedAt;
                    try {
                        lastUsedAt = Instant.parse(o.get(SessionPersistenceKeyEnum.LAST_USED_AT.key()).getAsString());
                    }
                    catch (Exception e) {
                        LOG.log(Level.WARNING, "Malformed lastUsedAt for session {0}, using epoch", o.get(idKey).getAsString());
                        lastUsedAt = Instant.EPOCH;
                    }
                    String projectPathKey = SessionPersistenceKeyEnum.PROJECT_PATH.key();
                    AiSession session = new AiSession(
                            o.get(idKey).getAsString(),
                            o.get(SessionPersistenceKeyEnum.NAME.key()).getAsString(),
                            description,
                            aiType,
                            o.has(projectPathKey) ? o.get(projectPathKey).getAsString() : null,
                            settings,
                            createdAt,
                            lastUsedAt);
                    String deliveryKey = SessionPersistenceKeyEnum.SESSION_INSTRUCTIONS_DELIVERY.key();
                    if (o.has(deliveryKey)) {
                        try {
                            session.setSessionInstructionsDelivery(SessionInstructionsDeliveryEnum.valueOf(
                                    o.get(deliveryKey).getAsString()));
                        }
                        catch (IllegalArgumentException e) {
                            LOG.log(Level.WARNING, "Invalid session instruction delivery mode for session {0}; using first-request delivery",
                                    session.id());
                        }
                    }
                    String injectedKey = SessionPersistenceKeyEnum.STARTUP_INSTRUCTIONS_INJECTED.key();
                    if (o.has(injectedKey) && !o.get(injectedKey).isJsonNull()) {
                        session.setStartupInstructionsInjected(o.get(injectedKey).getAsBoolean());
                    }
                    String lastInjectedKey = SessionPersistenceKeyEnum.LAST_INJECTED_INSTRUCTIONS.key();
                    if (o.has(lastInjectedKey) && !o.get(lastInjectedKey).isJsonNull()) {
                        session.setLastInjectedInstructions(o.get(lastInjectedKey).getAsString());
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
            o.addProperty(SessionPersistenceKeyEnum.ID.key(), s.id());
            o.addProperty(SessionPersistenceKeyEnum.NAME.key(), s.name());
            if (s.description() != null) {
                o.addProperty(SessionPersistenceKeyEnum.DESCRIPTION.key(), s.description());
            }
            o.addProperty(SessionPersistenceKeyEnum.AI_TYPE.key(), s.aiType().name());
            if (s.projectPath() != null) {
                o.addProperty(SessionPersistenceKeyEnum.PROJECT_PATH.key(), s.projectPath());
            }
            JsonObject cfgObj = new JsonObject();
            AiSessionSettings cfg = s.settings();
            cfg.populateJsonObject(cfgObj);
            o.add(SessionPersistenceKeyEnum.CONFIG.key(), cfgObj);
            o.addProperty(SessionPersistenceKeyEnum.CREATED_AT.key(), s.createdAt().toString());
            o.addProperty(SessionPersistenceKeyEnum.LAST_USED_AT.key(), s.lastUsedAt().toString());
            o.addProperty(SessionPersistenceKeyEnum.SESSION_INSTRUCTIONS_DELIVERY.key(),
                    s.sessionInstructionsDelivery().name());
            o.addProperty(SessionPersistenceKeyEnum.STARTUP_INSTRUCTIONS_INJECTED.key(),
                    s.isStartupInstructionsInjected());
            if (s.lastInjectedInstructions() != null) {
                o.addProperty(SessionPersistenceKeyEnum.LAST_INJECTED_INSTRUCTIONS.key(),
                        s.lastInjectedInstructions());
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
