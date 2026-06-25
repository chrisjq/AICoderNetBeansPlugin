package kiwi.ingenuity.netbeans.plugin.aicoder.ai.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.notification.AbstractNotification;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;

/**
 * Mutable session state shared by reference across AiTopComponent,
 * ContextProvider, and the MCP session layer. All holders share the same
 * instance so mutations — rename, settings change — propagate immediately
 * without manual coordination.
 *
 * Immutable fields: id, aiType, projectPath, createdAt. Mutable fields: name,
 * description, settings, lastUsedAt (volatile). Extra data: arbitrary String
 * key-value pairs for future extensibility.
 */
public class AiSession {
    // ---- Factory ----

    public static AiSession create(String projectPath, AiTypeEnum aiType) {
        Path pp = projectPath != null ? Path.of(projectPath) : null;
        String folder = pp != null && pp.getFileName() != null
                ? pp.getFileName().toString()
                : (aiType != null ? aiType.displayName() : "AI");
        Instant now = Instant.now();
        return new AiSession(
                UUID.randomUUID().toString(),
                folder,
                null,
                aiType,
                projectPath,
                AbstractAiSessionSettings.defaults(),
                now,
                now);
    }

    private final String id;
    private final AiTypeEnum aiType;
    private final String projectPath;
    private final Instant createdAt;
    private final String secret = UUID.randomUUID().toString();

    private volatile String name;
    private volatile String description;
    private volatile AbstractAiSessionSettings settings;
    private volatile Instant lastUsedAt;
    private volatile boolean instructionsLoaded = false;

    private final ConcurrentHashMap<String, String> extraData = new ConcurrentHashMap<>();
    private volatile AiSessionCallback callback;

    public AiSession(String id, String name, String description, AiTypeEnum aiType,
            String projectPath, AbstractAiSessionSettings settings,
            Instant createdAt, Instant lastUsedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.aiType = aiType;
        this.projectPath = projectPath;
        this.settings = settings;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
    }

    // ---- Accessors (record-style names preserved for call-site compat) ----
    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public AiTypeEnum aiType() {
        return aiType;
    }

    public String projectPath() {
        return projectPath;
    }

    public AbstractAiSessionSettings settings() {
        return settings;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String secret() {
        return secret;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    // ---- Mutators ----
    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setSettings(AbstractAiSessionSettings settings) {
        this.settings = settings;
    }

    public void touchLastUsed() {
        this.lastUsedAt = Instant.now();
    }

    /**
     * True if this conversation's AI has fetched the full instruction guide via
     * the GetInstructions tool at least once. Tracked here (shared object) so
     * the MCP tool handler and the history save/load path see the same value.
     */
    public boolean isInstructionsLoaded() {
        return instructionsLoaded;
    }

    public void setInstructionsLoaded(boolean instructionsLoaded) {
        this.instructionsLoaded = instructionsLoaded;
    }

    // ---- Extra key-value data (for info map extensibility) ----
    public void putExtra(String key, String value) {
        if (value != null) {
            extraData.put(key, value);
        }
        else {
            extraData.remove(key);
        }
    }

    public String getExtra(String key) {
        return extraData.get(key);
    }

    // ---- Convenience ----
    /**
     * Snapshot for ListAiSessions info: name + model (if set) + any extra data.
     * The MCP layer reads this directly via AbstractAiSession.getInfo().
     */
    public Map<String, String> getSessionInfoMap() {
        Map<String, String> map = new LinkedHashMap<>(extraData);
        map.put("name", name);
        map.put("type", aiType.key());
        if (settings instanceof AbstractAiModelSessionSettings mc) {
            String model = mc.model();
            if (model != null && !model.isBlank()) {
                map.put("model", model);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public void setAiSessionCallback(AiSessionCallback callback) {
        this.callback = callback;
    }

    /**
     * True if the AI backend is actively processing a turn.
     */
    public boolean isRunning() {
        AiSessionCallback c = callback;
        return c != null && c.isRunning();
    }

    public void requestGracefulInterrupt(InterruptTypeEnum type) {
        AiSessionCallback c = callback;
        if (c != null) {
            c.requestGracefulInterrupt(type);
        }
    }

    public void deliverIncomingMessage(String fromSessionId, AbstractNotification notification) {
        AiSessionCallback c = callback;
        if (c != null) {
            c.deliverIncomingMessage(fromSessionId, notification);
        }
    }

    public void applyDescriptionUpdate(String description) {
        AiSessionCallback c = callback;
        if (c != null) {
            c.applyDescriptionUpdate(description);
        }
    }

    /**
     * Derived from settings — reads PluginSettings default when not overridden
     * per-session.
     */
    public boolean allowsInterAiComms() {
        AbstractAiSessionSettings s = settings;
        return s != null && s.effectiveAllowInterAiComms();
    }

    public boolean allowsImportantMessages() {
        AbstractAiSessionSettings s = settings;
        return s != null && s.effectiveAllowImportantMessages();
    }

    // ---- Legacy with*() API — mutate in place, return this ----
    // Callers that do  session = session.withName(x)  still compile unchanged.
    public AiSession withName(String newName) {
        this.name = newName;
        return this;
    }

    public AiSession withDescription(String newDescription) {
        this.description = newDescription;
        return this;
    }

    public AiSession withConfig(AbstractAiSessionSettings newConfig) {
        this.settings = newConfig;
        return this;
    }

    public AiSession touched() {
        touchLastUsed();
        return this;
    }

}
