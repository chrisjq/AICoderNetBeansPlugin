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
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.SpecialInstructionTemplate;

/**
 * Durable, independently locked stores for reusable session templates.
 * <p>
 * The config store always carries the built-in Default template, seeded from the effective global settings when it is
 * absent and never overwritten once present. It recovers the same way {@link McpTemplatePersistenceManager} does: a
 * store that fails to parse is moved aside under a {@code .corrupt-}&lt;timestamp&gt; name and re-seeded as on a fresh
 * install (Default plus the shipped templates); a store that merely cannot be read is left untouched, served as an
 * in-memory Default on a read and refused on a write. Entries skipped while parsing are kept in a
 * {@code .dropped-}&lt;timestamp&gt; sidecar before the store is next written.
 */
public final class TemplatePersistenceManager {

    private static final Logger LOG = Logger.getLogger(TemplatePersistenceManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object CONFIG_JVM_LOCK = new Object();
    private static final Object INSTRUCTION_JVM_LOCK = new Object();
    private static final String CONFIG_FILENAME = "config-templates.json";
    private final Path baseDir;
    private final List<JsonElement> pendingDroppedConfigEntries = new ArrayList<>();

    public TemplatePersistenceManager() {
        this(SessionPersistenceManager.defaultBaseDir());
    }

    public TemplatePersistenceManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    public List<ConfigTemplate> loadConfigTemplates() throws IOException {
        synchronized (CONFIG_JVM_LOCK) {
            return withLock(CONFIG_FILENAME, () -> loadOrSeedConfigTemplates(false));
        }
    }

    /**
     * Returns the built-in Default config template, seeding it from the effective global settings if it is absent.
     */
    public ConfigTemplate getDefault() throws IOException {
        for (ConfigTemplate template : loadConfigTemplates()) {
            if (template.isDefault()) {
                return template;
            }
        }
        throw new IOException("Default config template was not available after seeding");
    }

    public void save(ConfigTemplate template) throws IOException {
        synchronized (CONFIG_JVM_LOCK) {
            withLock(CONFIG_FILENAME, () -> {
                 List<ConfigTemplate> all = loadOrSeedConfigTemplates(true);
                 all.removeIf(t -> t.id().equals(template.id()));
                 all.add(template);
                 persist(CONFIG_FILENAME, all.stream().map(ConfigTemplate::toJson).toList());
                 return null;
             });
        }
    }

    /**
     * Deletes a non-default config template.
     *
     * @return false when {@code id} is the built-in Default or no template has that id; true when an entry was deleted
     */
    public boolean deleteConfigTemplate(String id) throws IOException {
        synchronized (CONFIG_JVM_LOCK) {
            return withLock(CONFIG_FILENAME, () -> {
                        if (ConfigTemplate.DEFAULT_ID.equals(id)) {
                            return false;
                        }
                        List<ConfigTemplate> all = loadOrSeedConfigTemplates(true);
                        if (!all.removeIf(t -> t.id().equals(id))) {
                            return false;
                        }
                        persist(CONFIG_FILENAME, all.stream().map(ConfigTemplate::toJson).toList());
                        return true;
                    });
        }
    }

    /**
     * Reads the config store and guarantees a Default entry (spec §9 recovery applied, see the class javadoc).
     *
     * @param forWrite true when the caller intends to persist afterwards: a store that merely cannot be read is then
     * rethrown instead of being served as an in-memory Default, so a save or a delete never overwrites bytes this
     * process was unable to read
     */
    private List<ConfigTemplate> loadOrSeedConfigTemplates(boolean forWrite) throws IOException {
        return seedConfigStore(readConfigStore(forWrite), false);
    }

    /**
     * The config store as read from disk, with the two recovery cases applied: a corrupt store is quarantined under a
     * {@code .corrupt-}&lt;timestamp&gt; name and comes back empty and writable; an unreadable store comes back empty
     * and read-only on a read, and is a clear {@link IOException} naming the file on a write.
     */
    private ConfigStoreRead readConfigStore(boolean forWrite) throws IOException {
        Path file = baseDir.resolve(CONFIG_FILENAME);
        try {
            return new ConfigStoreRead(load(CONFIG_FILENAME, ConfigTemplate::fromJson), true, false);
        }
        catch (CorruptStoreException ex) {
            Path quarantined = quarantineCorruptStore(file);
            LOG.log(Level.WARNING, "Config template store " + file + " was corrupt; moved aside to " + quarantined
                    + " and re-seeding the built-in templates", ex);
            return new ConfigStoreRead(new ArrayList<>(), true, true);
        }
        catch (IOException ex) {
            if (forWrite) {
                throw new IOException("Config template store " + file + " could not be read; refusing to overwrite it ("
                        + ex.getMessage() + ")", ex);
            }
            // A read or permission failure is NOT corruption: leave the file untouched and serve an in-memory
            // Default so a session can still be created from it.
            LOG.log(Level.WARNING, "Config template store " + file + " is unreadable; serving an in-memory Default", ex);
            return new ConfigStoreRead(new ArrayList<>(), false, false);
        }
    }

    /**
     * Adds the built-in Default when it is missing and, on an empty store that was either just quarantined or whose
     * caller asked for it ({@code seedShippedWhenEmpty}), the shipped Coordinator/CoderPeer/ReviewerPeer templates —
     * what a fresh install gets. "Seeded once" is decided by the presence of the Default entry, not by a marker file.
     * The result is persisted only when the store is writable.
     */
    private List<ConfigTemplate> seedConfigStore(ConfigStoreRead store, boolean seedShippedWhenEmpty) throws IOException {
        List<ConfigTemplate> templates = store.templates();
        boolean wasEmpty = templates.isEmpty();
        boolean changed = false;
        if (templates.stream().noneMatch(ConfigTemplate::isDefault)) {
            templates.add(ConfigTemplate.createDefault(globalDefaults()));
            changed = true;
        }
        if (wasEmpty && (store.quarantined() || seedShippedWhenEmpty)) {
            templates.addAll(createDefaultConfigTemplates());
            changed = true;
        }
        if (changed && store.writable()) {
            persist(CONFIG_FILENAME, templates.stream().map(ConfigTemplate::toJson).toList());
        }
        return templates;
    }

    private static AiSessionSettings globalDefaults() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.applyDefaultSettingsFromGlobal();
        return settings;
    }

    /**
     * One read of the config store: its entries (a mutable list), whether it may be written back, and whether a corrupt
     * file was just moved aside so the shipped templates belong in the re-seed.
     */
    private record ConfigStoreRead(List<ConfigTemplate> templates, boolean writable, boolean quarantined) {

    }

    public List<SpecialInstructionTemplate> loadSpecialInstructionTemplates() throws IOException {
        synchronized (INSTRUCTION_JVM_LOCK) {
            return withLock("instruction-templates.json", () -> load("instruction-templates.json", SpecialInstructionTemplate::fromJson));
        }
    }

    public void save(SpecialInstructionTemplate template) throws IOException {
        synchronized (INSTRUCTION_JVM_LOCK) {
            withLock("instruction-templates.json", () -> {
                 List<SpecialInstructionTemplate> all = load("instruction-templates.json", SpecialInstructionTemplate::fromJson);
                 all.removeIf(t -> t.id().equals(template.id()));
                 all.add(template);
                 persist("instruction-templates.json", all.stream().map(SpecialInstructionTemplate::toJson).toList());
                 return null;
             });
        }
    }

    public void deleteSpecialInstructionTemplate(String id) throws IOException {
        synchronized (INSTRUCTION_JVM_LOCK) {
            withLock("instruction-templates.json", () -> {
                 List<SpecialInstructionTemplate> all = load("instruction-templates.json", SpecialInstructionTemplate::fromJson);
                 all.removeIf(t -> t.id().equals(id));
                 persist("instruction-templates.json", all.stream().map(SpecialInstructionTemplate::toJson).toList());
                 return null;
             });
        }
    }

    /**
     * Seeds the shipped Coordinator/CoderPeer/ReviewerPeer templates only when the config store is empty. The built-in
     * Default is maintained separately by {@link #loadConfigTemplates()}.
     */
    public List<ConfigTemplate> saveConfigDefaultsIfEmpty() throws IOException {
        synchronized (CONFIG_JVM_LOCK) {
            return withLock(CONFIG_FILENAME, () -> seedConfigStore(readConfigStore(false), true));
        }
    }

    /**
     * Seeds only an empty special-instruction store. Future defaults belong in the factory.
     */
    public List<SpecialInstructionTemplate> saveSpecialInstructionDefaultsIfEmpty() throws IOException {
        List<SpecialInstructionTemplate> current = loadSpecialInstructionTemplates();
        if (current.isEmpty()) {
            for (SpecialInstructionTemplate template : createDefaultSpecialInstructionTemplates()) {
                save(template);
            }
        }
        return loadSpecialInstructionTemplates();
    }

    private List<ConfigTemplate> createDefaultConfigTemplates() throws IOException {
        AiSessionSettings coordinator = new AiSessionSettings();
        coordinator.setMaxHistory(200);
        coordinator.setSaveHistory(true);
        coordinator.setRestrictToProjectFiles(true);
        coordinator.setAllowInterAiComms(true);
        coordinator.setAutoNotifyInbox(true);
        coordinator.setAllowImportantMessages(true);
        coordinator.setAutoAccept(false);
        coordinator.setAllowWebRequests(true);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.GET, true);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.POST, false);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.PUT, false);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.PATCH, false);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.DELETE, false);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEAD, false);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.OPTIONS, false);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS, false);
        coordinator.setAllowWebRequestAccess(WebRequestAccessOptionEnum.BODY, false);
        coordinator.setAllowDatabaseAccess(false);
        coordinator.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.READ_ONLY, true);
        coordinator.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.LIST_TABLES, false);
        coordinator.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SCHEMA, false);
        coordinator.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SELECT, false);
        coordinator.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.EXECUTE_SQL, false);
        coordinator.setAllowGitAccess(true);
        coordinator.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        coordinator.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, true);
        coordinator.setDatabaseRowLimit(25);
        coordinator.setEnableClipboardAccess(false);

        AiSessionSettings coderPeer = new AiSessionSettings();
        coderPeer.setMaxHistory(200);
        coderPeer.setSaveHistory(true);
        coderPeer.setRestrictToProjectFiles(true);
        coderPeer.setAllowInterAiComms(true);
        coderPeer.setAutoNotifyInbox(true);
        coderPeer.setAllowImportantMessages(true);
        coderPeer.setAutoAccept(false);
        coderPeer.setAllowWebRequests(false);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.GET, true);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.POST, false);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.PUT, false);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.PATCH, false);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.DELETE, false);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEAD, false);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.OPTIONS, false);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS, false);
        coderPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.BODY, false);
        coderPeer.setAllowDatabaseAccess(false);
        coderPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.READ_ONLY, true);
        coderPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.LIST_TABLES, false);
        coderPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SCHEMA, false);
        coderPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SELECT, false);
        coderPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.EXECUTE_SQL, false);
        coderPeer.setAllowGitAccess(true);
        coderPeer.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        coderPeer.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, true);
        coderPeer.setDatabaseRowLimit(25);
        coderPeer.setEnableClipboardAccess(false);

        AiSessionSettings reviewerPeer = new AiSessionSettings();
        reviewerPeer.setMaxHistory(200);
        reviewerPeer.setSaveHistory(true);
        reviewerPeer.setRestrictToProjectFiles(true);
        reviewerPeer.setAllowInterAiComms(true);
        reviewerPeer.setAutoNotifyInbox(true);
        reviewerPeer.setAllowImportantMessages(true);
        reviewerPeer.setAutoAccept(false);
        reviewerPeer.setAllowWebRequests(true);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.GET, true);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.POST, false);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.PUT, false);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.PATCH, false);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.DELETE, false);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEAD, false);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.OPTIONS, false);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.HEADERS, false);
        reviewerPeer.setAllowWebRequestAccess(WebRequestAccessOptionEnum.BODY, false);
        reviewerPeer.setAllowDatabaseAccess(false);
        reviewerPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.READ_ONLY, true);
        reviewerPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.LIST_TABLES, false);
        reviewerPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SCHEMA, false);
        reviewerPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SELECT, false);
        reviewerPeer.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.EXECUTE_SQL, false);
        // A reviewing session reads history, diffs and blame; it has no business committing,
        // resetting or pushing. This is the one place the READ/WRITE split earns its keep in a
        // shipped default.
        reviewerPeer.setAllowGitAccess(true);
        reviewerPeer.setAllowGitAccessOption(GitAccessOptionEnum.READ, true);
        reviewerPeer.setAllowGitAccessOption(GitAccessOptionEnum.WRITE, false);
        reviewerPeer.setDatabaseRowLimit(25);
        reviewerPeer.setEnableClipboardAccess(false);

        return List.of(ConfigTemplate.create("Coordinator", coordinator),
                       ConfigTemplate.create("CoderPeer", coderPeer),
                       ConfigTemplate.create("ReviewerPeer", reviewerPeer));
    }

    private List<SpecialInstructionTemplate> createDefaultSpecialInstructionTemplates() throws IOException {
        return List.of(
                SpecialInstructionTemplate.create("Coordinator", "You are a coordinator. Use PeerSessionList to discover available peers, then delegate discrete tasks with PeerMessageSend. Consolidate their results, validate completed work, and report concise outcomes. Never commit changes. Acknowledge this message and role only with \"I am a coordinator, what do you want me to do?\" return and wait for user input."),
                SpecialInstructionTemplate.create("CoderPeer", "You are a coding peer. Wait for tasks from a peer AI. Complete assigned work and always reply with the result. If requirements are unclear, ask the sending AI for clarification. Never commit changes. Acknowledge this message and role only with \"I am a coder, waiting for tasks.\" return and wait for user input or tasks."),
                SpecialInstructionTemplate.create("ReviewerPeer", "You are a review peer. Wait for tasks from a peer AI. Review the assigned work and always reply with verified findings. If requirements are unclear, ask the sending AI for clarification. Never commit changes. Acknowledge this message and role only with \"I am a reviewer, waiting for tasks.\" return and wait for user input or tasks."));
    }

    private <T> T withLock(String filename, IoAction<T> action) throws IOException {
        Files.createDirectories(baseDir);
        Path lock = baseDir.resolve(filename + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            return action.run();
        }
    }

    private <T> List<T> load(String filename, Function<JsonObject, T> parser) throws IOException {
        if (CONFIG_FILENAME.equals(filename)) {
            pendingDroppedConfigEntries.clear();
        }
        Path file = baseDir.resolve(filename);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            JsonArray array = GSON.fromJson(Files.readString(file), JsonArray.class);
            if (array == null) {
                return new ArrayList<>();
            }
            List<T> result = new ArrayList<>();
            for (JsonElement element : array) try {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("Entry is not an object");
                }
                result.add(parser.apply(element.getAsJsonObject()));
            }
            catch (Exception ex) {
                if (CONFIG_FILENAME.equals(filename)) {
                    pendingDroppedConfigEntries.add(element);
                }
                LOG.log(Level.WARNING, "Skipping malformed template entry", ex);
            }
            return result;
        }
        catch (JsonSyntaxException ex) {
            throw new CorruptStoreException(filename, ex);
        }
    }

    private void persist(String filename, List<JsonObject> entries) throws IOException {
        if (CONFIG_FILENAME.equals(filename)) {
            writeDroppedConfigEntries();
        }
        JsonArray array = new JsonArray();
        entries.forEach(array::add);
        Path file = baseDir.resolve(filename);
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

    private void writeDroppedConfigEntries() throws IOException {
        if (pendingDroppedConfigEntries.isEmpty()) {
            return;
        }
        JsonArray dropped = new JsonArray();
        pendingDroppedConfigEntries.forEach(dropped::add);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Path file = baseDir.resolve(CONFIG_FILENAME + ".dropped-" + stamp);
        for (int suffix = 1; Files.exists(file); suffix++) {
            file = baseDir.resolve(CONFIG_FILENAME + ".dropped-" + stamp + "-" + suffix);
        }
        byte[] bytes = GSON.toJson(dropped).getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        pendingDroppedConfigEntries.clear();
        LOG.log(Level.WARNING, "Skipped config-template entries were preserved in {0}", file);
    }

    /**
     * Moves a store that failed to parse out of the way under a timestamped name, so the corrupt bytes are preserved
     * for inspection instead of being silently overwritten by the freshly seeded store.
     */
    private static Path quarantineCorruptStore(Path source) throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Path target = source.resolveSibling(source.getFileName() + ".corrupt-" + stamp);
        for (int suffix = 1; Files.exists(target); suffix++) {
            target = source.resolveSibling(source.getFileName() + ".corrupt-" + stamp + "-" + suffix);
        }
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    @FunctionalInterface
    private interface IoAction<T> {

        T run() throws IOException;
    }

    /**
     * A store that exists but does not parse as JSON. Distinct from a plain {@link IOException} so the config-store
     * reader can quarantine the corrupt bytes rather than treating them like a transient read or permission failure.
     * The instruction store surfaces it unchanged, as the {@link IOException} it always was.
     */
    private static final class CorruptStoreException extends IOException {

        CorruptStoreException(String filename, Throwable cause) {
            super(filename + " is corrupted", cause);
        }
    }

}
