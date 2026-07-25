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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.SpecialInstructionTemplate;

/**
 * Durable, independently locked stores for reusable session templates.
 */
public final class TemplatePersistenceManager {

    private static final Logger LOG = Logger.getLogger(TemplatePersistenceManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object CONFIG_JVM_LOCK = new Object();
    private static final Object INSTRUCTION_JVM_LOCK = new Object();
    private final Path baseDir;

    public TemplatePersistenceManager() {
        this(SessionPersistenceManager.defaultBaseDir());
    }

    public TemplatePersistenceManager(Path baseDir) {
        this.baseDir = baseDir;
    }

    public List<ConfigTemplate> loadConfigTemplates() throws IOException {
        synchronized (CONFIG_JVM_LOCK) {
            return withLock("config-templates.json", () -> load("config-templates.json", ConfigTemplate::fromJson));
        }
    }

    public void save(ConfigTemplate template) throws IOException {
        synchronized (CONFIG_JVM_LOCK) {
            withLock("config-templates.json", () -> {
                List<ConfigTemplate> all = load("config-templates.json", ConfigTemplate::fromJson);
                all.removeIf(t -> t.id().equals(template.id()));
                all.add(template);
                persist("config-templates.json", all.stream().map(ConfigTemplate::toJson).toList());
                return null;
            });
        }
    }

    public void deleteConfigTemplate(String id) throws IOException {
        synchronized (CONFIG_JVM_LOCK) {
            withLock("config-templates.json", () -> {
                List<ConfigTemplate> all = load("config-templates.json", ConfigTemplate::fromJson);
                all.removeIf(t -> t.id().equals(id));
                persist("config-templates.json", all.stream().map(ConfigTemplate::toJson).toList());
                return null;
            });
        }
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
     * Seeds only an empty config store. Future defaults belong in the factory.
     */
    public List<ConfigTemplate> saveConfigDefaultsIfEmpty() throws IOException {
        List<ConfigTemplate> current = loadConfigTemplates();
        if (current.isEmpty()) {
            for (ConfigTemplate template : createDefaultConfigTemplates()) {
                save(template);
            }
        }
        return loadConfigTemplates();
    }

    /**
     * Seeds only an empty special-instruction store. Future defaults belong in
     * the factory.
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
        reviewerPeer.setDatabaseRowLimit(25);
        reviewerPeer.setEnableClipboardAccess(false);

        return List.of(ConfigTemplate.create("Coordinator", coordinator),
                ConfigTemplate.create("CoderPeer", coderPeer),
                ConfigTemplate.create("ReviewerPeer", reviewerPeer));
    }

    private List<SpecialInstructionTemplate> createDefaultSpecialInstructionTemplates() throws IOException {
        return List.of(
                SpecialInstructionTemplate.create("Coordinator", "You are a coordinator. Use ListAiSessions to discover available peers, then delegate discrete tasks with SendAiMessage. Consolidate their results, validate completed work, and report concise outcomes. Never commit changes."),
                SpecialInstructionTemplate.create("CoderPeer", "You are a coding peer. Wait for tasks from a peer AI. Complete assigned work and always reply with the result. If requirements are unclear, ask the sending AI for clarification. Never commit changes."),
                SpecialInstructionTemplate.create("ReviewerPeer", "You are a review peer. Wait for tasks from a peer AI. Review the assigned work and always reply with verified findings. If requirements are unclear, ask the sending AI for clarification. Never commit changes."));
    }

    private <T> T withLock(String filename, IoAction<T> action) throws IOException {
        Files.createDirectories(baseDir);
        Path lock = baseDir.resolve(filename + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            return action.run();
        }
    }

    private <T> List<T> load(String filename, Function<JsonObject, T> parser) throws IOException {
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
                LOG.log(Level.WARNING, "Skipping malformed template entry", ex);
            }
            return result;
        }
        catch (JsonSyntaxException ex) {
            throw new IOException(filename + " is corrupted; refusing to overwrite it", ex);
        }
    }

    private void persist(String filename, List<JsonObject> entries) throws IOException {
        JsonArray array = new JsonArray();
        entries.forEach(array::add);
        Path file = baseDir.resolve(filename);
        Path temp = file.resolveSibling(file.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
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

    @FunctionalInterface
    private interface IoAction<T> {

        T run() throws IOException;
    }
}
