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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.LegacyToolGroups;

/**
 * Durable, independently locked store for MCP tool-exposure templates.
 * <p>
 * Stored separately from the config templates owned by {@link TemplatePersistenceManager}. The built-in Default
 * template is seeded from the plugin's effective global values on the first load of a store that has no Default entry,
 * and re-seeded if it is ever missing; afterwards it can be edited like any other template but never deleted. A store
 * that fails to parse is moved aside under a {@code .corrupt-}&lt;timestamp&gt; name before the fresh Default is
 * seeded, so the corrupt bytes are preserved; a merely unreadable store is left untouched. Entries skipped while
 * parsing are kept in a {@code .dropped-}&lt;timestamp&gt; sidecar before the store is next written.
 */
public final class McpTemplatePersistenceManager {

    private static final Logger LOG = Logger.getLogger(McpTemplatePersistenceManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object JVM_LOCK = new Object();
    private static final String FILENAME = "mcp-templates.json";
    private final Path baseDir;
    private final List<JsonElement> pendingDropped = new ArrayList<>();

    public McpTemplatePersistenceManager() {
        this(SessionPersistenceManager.defaultBaseDir());
    }

    public McpTemplatePersistenceManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    public List<McpTemplate> loadMcpTemplates() throws IOException {
        synchronized (JVM_LOCK) {
            return withLock(FILENAME, this::loadOrSeed);
        }
    }

    /**
     * Always returns the built-in Default template: the stored one (which a user may have edited) when present,
     * otherwise a freshly seeded one.
     */
    public McpTemplate getDefault() throws IOException {
        for (McpTemplate template : loadMcpTemplates()) {
            if (template.isDefault()) {
                return template;
            }
        }
        return seededDefault();
    }

    public void save(McpTemplate template) throws IOException {
        synchronized (JVM_LOCK) {
            withLock(FILENAME, () -> {
                 List<McpTemplate> all = loadOrSeedForWrite();
                 all.removeIf(t -> t.id().equals(template.id()));
                 all.add(template);
                 persist(all);
                 return null;
             });
        }
    }

    /**
     * Deletes a template by id. The built-in Default and Minimal templates are refused, and so is an id that is not
     * present: nothing is written and {@code false} is returned in every case.
     */
    public boolean deleteMcpTemplate(String id) throws IOException {
        synchronized (JVM_LOCK) {
            return withLock(FILENAME, () -> {
                        if (McpTemplate.DEFAULT_ID.equals(id) || McpTemplate.MINIMAL_ID.equals(id)) {
                            return false;
                        }
                        List<McpTemplate> all = loadOrSeedForWrite();
                        if (!all.removeIf(t -> t.id().equals(id))) {
                            return false;
                        }
                        persist(all);
                        return true;
                    });
        }
    }

    private List<McpTemplate> loadOrSeed() throws IOException {
        return loadOrSeed(false);
    }

    /**
     * The write side of {@link #loadOrSeed()}: a store that merely cannot be read is rethrown instead of being
     * replaced, so a save or a delete never overwrites bytes this process was unable to read.
     */
    private List<McpTemplate> loadOrSeedForWrite() throws IOException {
        return loadOrSeed(true);
    }

    private List<McpTemplate> loadOrSeed(boolean forWrite) throws IOException {
        List<McpTemplate> all = new ArrayList<>();
        boolean corrupt = false;
        try {
            all = load(FILENAME);
        }
        catch (CorruptStoreException ex) {
            Path quarantined = quarantineCorruptStore();
            LOG.log(Level.WARNING, "MCP template store was corrupt; moved aside to " + quarantined, ex);
            all = new ArrayList<>();
            corrupt = true;
        }
        catch (IOException ex) {
            if (forWrite) {
                throw ex;
            }
            // A read or permission failure is NOT corruption: leave the file untouched and serve an in-memory
            // Default so the plugin still has a template to fall back to.
            LOG.log(Level.WARNING, "MCP template store is unreadable; serving an in-memory Default", ex);
            return List.of(seededDefault());
        }
        // "Seeded once" is decided by the presence of the built-in entries themselves, not by a marker file: a Default
        // or Minimal that is present is never overwritten, and a missing one is re-seeded as recovery. The built-in
        // Minimal is seeded in every store (new and existing), so it always exists and can never be deleted.
        boolean seeded = false;
        if (corrupt || all.stream().noneMatch(McpTemplate::isDefault)) {
            all.add(seededDefault());
            seeded = true;
        }
        if (corrupt || all.stream().noneMatch(McpTemplate::isMinimal)) {
            all.add(seededMinimal());
            seeded = true;
        }
        if (seeded) {
            persist(all);
        }
        return all;
    }

    /**
     * Moves a store that failed to parse out of the way under a timestamped name, so the corrupt bytes are preserved
     * for inspection instead of being silently overwritten by the freshly seeded store.
     */
    private Path quarantineCorruptStore() throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Path source = baseDir.resolve(FILENAME);
        Path target = source.resolveSibling(source.getFileName() + ".corrupt-" + stamp);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private McpTemplate seededDefault() {
        return McpTemplate.createDefault(LegacyToolGroups.derive(null));
    }

    private McpTemplate seededMinimal() {
        return McpTemplate.createMinimal();
    }

    private <T> T withLock(String filename, IoAction<T> action) throws IOException {
        Files.createDirectories(baseDir);
        Path lock = baseDir.resolve(filename + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            return action.run();
        }
    }

    private List<McpTemplate> load(String filename) throws IOException {
        pendingDropped.clear();
        Path file = baseDir.resolve(filename);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            JsonArray array = GSON.fromJson(Files.readString(file), JsonArray.class);
            if (array == null) {
                return new ArrayList<>();
            }
            List<McpTemplate> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonElement element : array) try {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("Entry is not an object");
                }
                JsonObject json = element.getAsJsonObject();
                McpTemplate template = McpTemplate.fromJson(json);
                if (!ids.add(template.id())) {
                    LOG.log(Level.WARNING, "Skipping duplicate MCP template entry {0} ({1})",
                            new Object[]{template.id(), template.name()});
                    pendingDropped.add(element);
                    continue;
                }
                result.add(template);
            }
            catch (Exception ex) {
                LOG.log(Level.WARNING, "Skipping malformed MCP template entry " + templateDescription(element), ex);
                pendingDropped.add(element);
            }
            return result;
        }
        catch (JsonSyntaxException ex) {
            throw new CorruptStoreException(filename, ex);
        }
    }

    private static String templateDescription(JsonElement element) {
        if (!element.isJsonObject()) {
            return "(not an object)";
        }
        JsonObject value = element.getAsJsonObject();
        String id = value.has("id") ? value.get("id").toString() : "(missing id)";
        String name = value.has("name") ? value.get("name").toString() : "(missing name)";
        return id + " (" + name + ")";
    }

    private void persist(List<McpTemplate> entries) throws IOException {
        writeDroppedEntries();
        JsonArray array = new JsonArray();
        entries.stream().map(McpTemplate::toJson).forEach(array::add);
        Path file = baseDir.resolve(FILENAME);
        Path temp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = GSON.toJson(array).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Preserves the entries the last {@link #load(String)} skipped, side by side with the store, before anything is
     * written: a persist would otherwise silently drop them from the live file.
     */
    private void writeDroppedEntries() throws IOException {
        if (pendingDropped.isEmpty()) {
            return;
        }
        JsonArray dropped = new JsonArray();
        pendingDropped.forEach(dropped::add);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Path file = baseDir.resolve(FILENAME + ".dropped-" + stamp);
        for (int suffix = 1; Files.exists(file); suffix++) {
            file = baseDir.resolve(FILENAME + ".dropped-" + stamp + "-" + suffix);
        }
        byte[] bytes = GSON.toJson(dropped).getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        pendingDropped.clear();
        LOG.log(Level.WARNING, "Skipped MCP template entries were preserved in {0}", file);
    }

    @FunctionalInterface
    private interface IoAction<T> {

        T run() throws IOException;
    }

    /**
     * A store that exists but does not parse as JSON. Distinct from a plain {@link IOException} so the caller can
     * quarantine the corrupt bytes rather than treating them like a transient read or permission failure.
     */
    private static final class CorruptStoreException extends IOException {

        CorruptStoreException(String filename, Throwable cause) {
            super(filename + " is corrupted", cause);
        }
    }

}
