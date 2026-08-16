package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ExecutablePrompter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui.OpenCodeAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

public class OpenCodeAiImplementation extends AiImplementation {

    private static final AiModelCatalog modelCatalog = new AiModelCatalog();

    public static AiModelCatalog modelCatalog() {
        return modelCatalog;
    }

    /**
     * OpenCode's model list only becomes available after a successful ACP
     * {@code session/new} handshake, via the {@code configOptions} array. There
     * is nothing to discover before a session exists; models are cached in
     * {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings#setDiscoveredModels}
     * when the first {@code session/new} response arrives.
     */
    public static void triggerModelDiscovery() {
        // intentional no-op: models arrive via configOptions after session/new
    }
    private final OpenCodeAiProcessManager delegate;
    private volatile AiSessionHost sessionHost;

    public OpenCodeAiImplementation(AiProcessEventListener listener, ExecutablePrompter prompter) {
        super(AiTypeEnum.OPENCODE, listener, prompter);
        this.delegate = new OpenCodeAiProcessManager(listener);
    }

    @Override
    protected OpenCodeAiProcessManager delegate() {
        return delegate;
    }

    @Override
    public void startWithDiscovery(String model) {
        String effectiveModel = (model != null && !model.isBlank()) ? model : OpenCodePluginSettings.getModel();
        String execPath = OpenCodeExecutableLocator.locate();
        if (execPath != null) {
            start(execPath, effectiveModel);
            return;
        }
        String chosen;
        try {
            chosen = prompter.promptForExecutable("Locate opencode executable", "opencode").get();
        }
        catch (Exception ex) {
            chosen = null;
        }
        if (chosen != null) {
            OpenCodePluginSettings.setExecutable(chosen);
            start(chosen, effectiveModel);
        }
        else {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatExecutableNotFound(null)));
        }
    }

    @Override
    public void setModel(String model) {
        // Session-scoped change — deliberately does not write the global default.
        // The global default (Tools → Options) is owned solely by the settings panel.
        delegate().setModel(model);
        if (currentSession != null && currentSession.settings() instanceof OpenCodeSessionSettings settings) {
            settings.setModel(model);
        }
        if (delegate().isSessionLive()) {
            String current = currentConfigValue("model");
            if (model != null && !model.equals(current)) {
                delegate().setConfigOption("model", model);
            }
        }
    }

    private String currentConfigValue(String id) {
        JsonArray opts = delegate().configOptions();
        if (opts == null) {
            return null;
        }
        for (JsonElement el : opts) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (id.equals(o.has("id") ? o.get("id").getAsString() : null)) {
                return o.has("currentValue") ? o.get("currentValue").getAsString() : null;
            }
        }
        return null;
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        // session/resume is attempted first with fallback to session/new, so a stored
        // session is always safe to load — a stale ACP id never blocks the user.
        return true;
    }

    @Override
    protected void afterStart() {
        Path configPath = getSessionConfigPath();
        if (configPath != null) {
            delegate.setSessionConfigDir(configPath);
        }
        if (currentSession != null && currentSession.settings() instanceof OpenCodeSessionSettings) {
            String storedAcpId = ((OpenCodeSessionSettings) currentSession.settings()).acpSessionId();
            if (storedAcpId != null) {
                delegate.resumeSession(storedAcpId);
            }
        }
    }

    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        OpenCodeSessionSettings s = (session != null && session.settings() instanceof OpenCodeSessionSettings)
                ? (OpenCodeSessionSettings) session.settings() : null;
        return new OpenCodeAiInfoBarExtension(delegate, s, host);
    }

    @Override
    public void onStarted(AiSessionHost host) {
        this.sessionHost = host;
        delegate.setOnSessionEstablished(() -> {
            AiSessionHost h = sessionHost;
            AiSession s = currentSession;
            if (h != null && s != null) {
                h.updateSessionSettings(s.settings());
            }
        });
    }
}
