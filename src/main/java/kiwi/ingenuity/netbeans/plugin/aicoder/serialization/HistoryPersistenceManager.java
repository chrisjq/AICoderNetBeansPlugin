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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiMessage;

public class HistoryPersistenceManager {

    private static final Logger LOG = Logger.getLogger(HistoryPersistenceManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Independent read attempts before deciding a history file is stably corrupt rather than suffering a transient or
     * partial read. Watchlist 13 forbids quarantining on a single failed parse.
     */
    private static final int LOAD_ATTEMPTS = 3;

    private static List<AiMessage> parseMessages(JsonArray arr) {
        List<AiMessage> result = new ArrayList<>();
        for (JsonElement el : arr) {
            try {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                JsonElement roleEl = o.get(HistoryPersistenceKeyEnum.ROLE.key());
                JsonElement textEl = o.get(HistoryPersistenceKeyEnum.TEXT.key());
                JsonElement tsEl = o.get(HistoryPersistenceKeyEnum.TIMESTAMP.key());
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

    private static String readViaFiles(Path path) throws IOException {
        return Files.readString(path);
    }

    private final Path filePath;

    /**
     * Set when reads disagree about an unparseable file: the on-disk bytes are retained and the next non-empty save
     * copies them aside before overwriting (see {@link #load()}).
     */
    private volatile boolean unreadableFileRetained;

    /**
     * Seam for tests to inject flaky or partial reads; production reads via Files.readString.
     */
    private ContentReader contentReader = HistoryPersistenceManager::readViaFiles;

    public HistoryPersistenceManager(Path filePath) {
        this.filePath = filePath;
    }

    void setContentReader(ContentReader reader) {
        this.contentReader = reader;
    }

    /**
     * Save messages, session ID, and pinned working directory so the session can be resumed on next open with the same
     * CWD.
     */
    public void save(List<AiMessage> messages, String sessionId, String workingDir) throws IOException {
        save(messages, sessionId, workingDir, false);
    }

    /**
     * Save messages, session ID, pinned working directory, and whether the full instruction guide has been loaded this
     * conversation, so the session can be resumed on next open with the same CWD and gate state. Format:
     * {"sessionId":"<uuid>","workingDir":"/path","instructionsLoaded":bool,"messages":[...]}
     */
    public void save(List<AiMessage> messages, String sessionId, String workingDir,
            boolean instructionsLoaded) throws IOException {
        if (messages.isEmpty()) {
            return; // empty save is a no-op — callers must call delete() explicitly to clear
        }
        retainUnreadableOriginalBeforeOverwrite();
        JsonArray arr = new JsonArray();
        for (AiMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty(HistoryPersistenceKeyEnum.ROLE.key(), m.role().name());
            o.addProperty(HistoryPersistenceKeyEnum.TEXT.key(), m.markdownText());
            o.addProperty(HistoryPersistenceKeyEnum.TIMESTAMP.key(), m.timestamp());
            arr.add(o);
        }
        JsonObject wrapper = new JsonObject();
        if (sessionId != null) {
            wrapper.addProperty(HistoryPersistenceKeyEnum.SESSION_ID.key(), sessionId);
        }
        if (workingDir != null) {
            wrapper.addProperty(HistoryPersistenceKeyEnum.WORKING_DIR.key(), workingDir);
        }
        wrapper.addProperty(HistoryPersistenceKeyEnum.INSTRUCTIONS_LOADED.key(), instructionsLoaded);
        wrapper.add(HistoryPersistenceKeyEnum.MESSAGES.key(), arr);

        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = GSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8);
        Path tmp = filePath.resolveSibling(filePath.getFileName() + "." + UUID.randomUUID() + ".tmp");
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
     * Load messages and session ID. Handles both new format {"sessionId":...,"messages":[...]} and old bare-array
     * format.
     *
     * <p>
     * Corruption recovery policy: a parse failure alone proves nothing — it may be a transient or partial read — so the
     * file is re-read before anything is touched. Only when repeated independent reads yield byte-identical content
     * that all fail to parse is the stored file itself considered stably corrupt, and only then is it quarantined via
     * atomic move to a sibling {@code *.corrupt-*.json} name, preserving the original bytes while the next save starts
     * fresh (watchlist 13). Reads that fail outright with {@link IOException} keep the existing contract and are
     * rethrown; reads that produce differing unparseable snapshots leave the file untouched, mark it retained, and the
     * next non-empty save copies those unreadable bytes aside before overwriting.</p>
     */
    public LoadedHistory load() throws IOException {
        if (!Files.exists(filePath)) {
            return new LoadedHistory(List.of(), null, null, false);
        }
        List<String> unparseableReads = new ArrayList<>(LOAD_ATTEMPTS);
        IOException lastReadFailure = null;
        for (int attempt = 0; attempt < LOAD_ATTEMPTS; attempt++) {
            String json;
            try {
                json = contentReader.read(filePath);
            }
            catch (IOException e) {
                lastReadFailure = e;
                continue;
            }
            try {
                LoadedHistory loaded = parseLoadedHistory(json);
                if (attempt > 0) {
                    LOG.log(Level.WARNING, "Transient read/parse failure of {0} resolved by retry;"
                            + " file left untouched", filePath);
                }
                unreadableFileRetained = false;
                return loaded;
            }
            catch (Exception e) {
                unparseableReads.add(json);
            }
        }
        if (unparseableReads.isEmpty()) {
            // Never obtained any bytes to judge — pure I/O trouble. Surface it unchanged.
            throw lastReadFailure;
        }
        boolean stableCorruption = unparseableReads.size() > 1
                && unparseableReads.stream().allMatch(read -> read.equals(unparseableReads.get(0)));
        if (stableCorruption) {
            unreadableFileRetained = false;
            quarantineCorruptFile();
            return new LoadedHistory(List.of(), null, null, false);
        }
        // The reads disagree, so the persisted bytes cannot be proven bad. Retention policy:
        // leave the file exactly as it is and remember it — the next non-empty save keeps a
        // recoverable copy instead of silently destroying whatever is on disk.
        unreadableFileRetained = true;
        LOG.log(Level.SEVERE, "History file {0} yielded {1} differing unparseable snapshots across"
                + " {2} attempts; retaining file untouched until a successful load or explicit delete",
                new Object[]{filePath, unparseableReads.size(), LOAD_ATTEMPTS});
        return new LoadedHistory(List.of(), null, null, false);
    }

    private LoadedHistory parseLoadedHistory(String json) {
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
            String sessionIdKey = HistoryPersistenceKeyEnum.SESSION_ID.key();
            String workingDirKey = HistoryPersistenceKeyEnum.WORKING_DIR.key();
            String loadedKey = HistoryPersistenceKeyEnum.INSTRUCTIONS_LOADED.key();
            String messagesKey = HistoryPersistenceKeyEnum.MESSAGES.key();
            sessionId = wrapper.has(sessionIdKey) ? wrapper.get(sessionIdKey).getAsString() : null;
            workingDir = wrapper.has(workingDirKey) ? wrapper.get(workingDirKey).getAsString() : null;
            instructionsLoaded = wrapper.has(loadedKey)
                    && wrapper.get(loadedKey).isJsonPrimitive()
                    && wrapper.get(loadedKey).getAsBoolean();
            arr = wrapper.has(messagesKey) && wrapper.get(messagesKey).isJsonArray()
                    ? wrapper.getAsJsonArray(messagesKey) : new JsonArray();
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

    /**
     * Atomically move the confirmed-corrupt file to a sibling quarantine name so its bytes survive for manual recovery
     * while the next save starts fresh. Falls back to a plain move when the filesystem cannot move atomically, and
     * never destroys the original if even that fails.
     */
    private void quarantineCorruptFile() {
        long stamp = System.currentTimeMillis();
        Path target;
        do {
            target = filePath.resolveSibling(filePath.getFileName() + ".corrupt-" + stamp + ".json");
            stamp++;
        }
        while (Files.exists(target));
        try {
            try {
                Files.move(filePath, target, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(filePath, target);
            }
            LOG.log(Level.SEVERE, "Stable corruption confirmed for {0}; original quarantined to {1},"
                    + " starting fresh", new Object[]{filePath, target});
        }
        catch (IOException e) {
            LOG.log(Level.SEVERE, "Stable corruption confirmed for " + filePath
                    + " but quarantining failed; file retained untouched for manual recovery", e);
        }
    }

    /**
     * Retention half of the recovery policy: before the first non-empty save after a load whose reads disagreed about
     * an unparseable file, copy those unreadable bytes aside so the overwrite can never silently destroy potentially
     * recoverable conversation content.
     */
    private void retainUnreadableOriginalBeforeOverwrite() {
        if (!unreadableFileRetained || !Files.exists(filePath)) {
            return;
        }
        Path copyTarget = filePath.resolveSibling(filePath.getFileName()
                + ".unreadable-" + System.currentTimeMillis() + ".json");
        try {
            Files.copy(filePath, copyTarget, StandardCopyOption.REPLACE_EXISTING);
            LOG.log(Level.WARNING, "Retained unreadable history bytes at {0} before overwriting {1}",
                    new Object[]{copyTarget, filePath});
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not retain unreadable history bytes before overwriting "
                    + filePath, e);
        }
        unreadableFileRetained = false;
    }

    public void delete() {
        // Explicit deletion is user intent — the retention state has nothing left to protect.
        unreadableFileRetained = false;
        try {
            Files.deleteIfExists(filePath);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete history file", e);
        }
    }

    @FunctionalInterface
    interface ContentReader {

        String read(Path path) throws IOException;
    }

    /**
     * Result of loading history: messages, session ID to resume, the working directory the session was pinned to, and
     * whether the full instruction guide was loaded this conversation (all but the flag may be null).
     */
    public record LoadedHistory(List<AiMessage> messages, String sessionId, String workingDir,
            boolean instructionsLoaded) {

    }
}
