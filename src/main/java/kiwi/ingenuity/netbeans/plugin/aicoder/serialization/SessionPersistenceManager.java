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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;

public class SessionPersistenceManager {

    private static final Logger LOG = Logger.getLogger(SessionPersistenceManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // Serializes all instances in this JVM before acquiring the cross-process file lock.
    // FileChannel.lock() throws OverlappingFileLockException if the same JVM holds it twice,
    // so we need intra-JVM serialization independently of per-instance synchronized methods.
    private static final Object JVM_LOCK = new Object();

    public static Path defaultBaseDir() {
        return Path.of(System.getProperty("user.home"), ".netbeans", ".aicoder");
    }

    private final Path baseDir;
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

                    AbstractAiSessionSettings settings = AbstractAiSessionSettings.defaults();
                    if (o.has("config") && o.get("config").isJsonObject()) {
                        JsonObject cfgObj = o.getAsJsonObject("config");
                        Integer maxHistory = cfgObj.has("maxHistory") && cfgObj.get("maxHistory").isJsonPrimitive() ? cfgObj.get("maxHistory").getAsInt() : null;
                        Boolean restrictToProjectFiles = cfgObj.has("restrictToProjectFiles") && cfgObj.get("restrictToProjectFiles").isJsonPrimitive() ? cfgObj.get("restrictToProjectFiles").getAsBoolean() : null;
                        Boolean allowInterAiComms = cfgObj.has("allowInterAiComms") && cfgObj.get("allowInterAiComms").isJsonPrimitive() ? cfgObj.get("allowInterAiComms").getAsBoolean() : null;
                        Boolean autoNotifyInbox = cfgObj.has("autoNotifyInbox") && cfgObj.get("autoNotifyInbox").isJsonPrimitive() ? cfgObj.get("autoNotifyInbox").getAsBoolean() : null;
                        Boolean allowImportantMessages = cfgObj.has("allowImportantMessages") && cfgObj.get("allowImportantMessages").isJsonPrimitive() ? cfgObj.get("allowImportantMessages").getAsBoolean() : null;
                        String sessionInstructions = cfgObj.has("sessionInstructions") && cfgObj.get("sessionInstructions").isJsonPrimitive()
                                ? cfgObj.get("sessionInstructions").getAsString()
                                : cfgObj.has("toolInstructions") && cfgObj.get("toolInstructions").isJsonPrimitive() ? cfgObj.get("toolInstructions").getAsString() : null;
                        Boolean autoAccept = cfgObj.has("autoAccept") && cfgObj.get("autoAccept").isJsonPrimitive() ? cfgObj.get("autoAccept").getAsBoolean() : null;
                        String model = cfgObj.has("model") && cfgObj.get("model").isJsonPrimitive() ? cfgObj.get("model").getAsString() : null;
                        if (model != null) {
                            settings = new AbstractAiModelSessionSettings(maxHistory, restrictToProjectFiles,
                                    allowInterAiComms, autoNotifyInbox, allowImportantMessages, sessionInstructions,
                                    model, autoAccept);
                        }
                        else {
                            settings = new AbstractAiSessionSettings(maxHistory, restrictToProjectFiles,
                                    allowInterAiComms, autoNotifyInbox, allowImportantMessages, sessionInstructions, autoAccept);
                        }
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
                    result.add(new AiSession(
                            o.get("id").getAsString(),
                            o.get("name").getAsString(),
                            description,
                            aiType,
                            o.has("projectPath") ? o.get("projectPath").getAsString() : null,
                            settings,
                            createdAt,
                            lastUsedAt));
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
            AbstractAiSessionSettings cfg = s.settings() != null ? s.settings() : AbstractAiSessionSettings.defaults();
            if (cfg.maxHistory() != null) {
                cfgObj.addProperty("maxHistory", cfg.maxHistory());
            }
            if (cfg.restrictToProjectFiles() != null) {
                cfgObj.addProperty("restrictToProjectFiles", cfg.restrictToProjectFiles());
            }
            if (cfg.allowInterAiComms() != null) {
                cfgObj.addProperty("allowInterAiComms", cfg.allowInterAiComms());
            }
            if (cfg.autoNotifyInbox() != null) {
                cfgObj.addProperty("autoNotifyInbox", cfg.autoNotifyInbox());
            }
            if (cfg.allowImportantMessages() != null) {
                cfgObj.addProperty("allowImportantMessages", cfg.allowImportantMessages());
            }
            if (cfg.sessionInstructions() != null) {
                cfgObj.addProperty("sessionInstructions", cfg.sessionInstructions());
            }
            if (cfg.autoAccept() != null) {
                cfgObj.addProperty("autoAccept", cfg.autoAccept());
            }
            if (cfg instanceof AbstractAiModelSessionSettings modelCfg
                    && modelCfg.model() != null) {
                cfgObj.addProperty("model", modelCfg.model());
            }
            o.add("config", cfgObj);
            o.addProperty("createdAt", s.createdAt().toString());
            o.addProperty("lastUsedAt", s.lastUsedAt().toString());
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
