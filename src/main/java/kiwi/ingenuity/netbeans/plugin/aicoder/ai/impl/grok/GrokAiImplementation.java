package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.awt.Component;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.events.GrokModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.ui.GrokAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;
import org.openide.util.RequestProcessor;

/**
 * Thin adapter so the generic multi-AI system (AiSession, AiTopComponent, etc.)
 * can use the Grok (xAI) implementation. Drives the {@code grok} CLI
 * (https://docs.x.ai/build/cli) in headless mode via
 * {@link GrokAiProcessManager}, reusing the shared MCP tool server for IDE
 * introspection, edits, builds, git, etc. — the same architecture as
 * {@code ClaudeAiImplementation}.
 */
public class GrokAiImplementation extends AiImplementation {

    // start() blocks on MCP registration (up to a 2-minute future wait); run it
    // off the EDT so callers on the EDT never freeze the UI.
    private static final RequestProcessor START_RP = new RequestProcessor("grok-ai-start", 4);

    // The discovered model list is shared across all Grok sessions for the IDE
    // run (like Claude/Copilot): discover once via `grok models`, cache, and
    // broadcast to every open session's dropdown via AiTypePropertyBus.
    private static final Object MODEL_LOCK = new Object();
    private static volatile List<String> cachedModels = null;
    private static volatile boolean modelsFetched = false;

    private final GrokAiProcessManager delegate;

    public GrokAiImplementation(AiProcessEventListener listener) {
        super(AiTypeEnum.GROK, listener);
        this.delegate = new GrokAiProcessManager(listener);
    }

    @Override
    protected GrokAiProcessManager delegate() {
        return delegate;
    }

    public String getCurrentModel() {
        if (currentSession != null && currentSession.settings() instanceof AbstractAiModelSessionSettings mc) {
            return mc.model();
        }
        return GrokPluginSettings.getModel();
    }

    @Override
    public void startWithDiscovery(String model, Component parent) {
        String effectiveModel = (model != null && !model.isBlank()) ? model : getCurrentModel();
        String execPath = GrokExecutableLocator.locate();
        if (execPath != null) {
            START_RP.post(() -> delegate.start(execPath, effectiveModel));
            return;
        }
        SwingUtilities.invokeLater(() -> {
            String chosen = promptForExecutable(parent);
            if (chosen != null) {
                START_RP.post(() -> delegate.start(chosen, effectiveModel));
            }
            else {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, StatusMessageUtil.formatExecutableNotFound(null)));
            }
        });
    }

    private String promptForExecutable(Component parent) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Locate grok executable");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().equals("grok") || f.getName().startsWith("grok.");
            }

            @Override
            public String getDescription() {
                return "grok executable";
            }
        });
        fc.setAcceptAllFileFilterUsed(true);
        File startDir = new File("/usr/bin");
        if (!startDir.isDirectory()) {
            startDir = new File(System.getProperty("user.home"));
        }
        fc.setCurrentDirectory(startDir);
        int result = fc.showOpenDialog(parent);
        if (result == JFileChooser.APPROVE_OPTION) {
            String path = fc.getSelectedFile().getAbsolutePath();
            GrokPluginSettings.setExecutable(path);
            return path;
        }
        return null;
    }

    @Override
    public void setModel(String model) {
        GrokPluginSettings.setModel(model);
        delegate.setModel(model);
    }

    public List<String> getDefaultModels() {
        return Arrays.asList(GrokPluginSettings.KNOWN_MODELS);
    }

    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        GrokAiInfoBarExtension provider = new GrokAiInfoBarExtension();
        provider.addModelChangeListener(e -> {
            String model = provider.getSelectedModel();
            if (model == null) {
                return;
            }
            setModel(model);
            AbstractAiSessionSettings cfg = host.getSessionSettings() != null
                    ? host.getSessionSettings() : AbstractAiSessionSettings.defaults();
            String currentModel = cfg instanceof AbstractAiModelSessionSettings mc ? mc.model() : null;
            if (!model.equals(currentModel)) {
                AbstractAiModelSessionSettings newCfg = new AbstractAiModelSessionSettings(
                        cfg.maxHistory(), cfg.restrictToProjectFiles(), cfg.allowInterAiComms(),
                        cfg.autoNotifyInbox(), cfg.allowImportantMessages(), cfg.sessionInstructions(),
                        model, cfg.autoAccept());
                if (currentSession != null) {
                    currentSession.setSettings(newCfg);
                }
                delegate.setCurrentSession(currentSession);
                host.updateSessionSettings(newCfg);
            }
        });
        String initialModel = session.settings() instanceof AbstractAiModelSessionSettings modelCfg
                ? modelCfg.model() : GrokPluginSettings.getModel();
        provider.setSelectedModel(initialModel);
        if (initialModel != null && !(session.settings() instanceof AbstractAiModelSessionSettings)) {
            AbstractAiSessionSettings cfg = session.settings() != null ? session.settings() : AbstractAiSessionSettings.defaults();
            AbstractAiModelSessionSettings modelSettings = new AbstractAiModelSessionSettings(
                    cfg.maxHistory(), cfg.restrictToProjectFiles(), cfg.allowInterAiComms(),
                    cfg.autoNotifyInbox(), cfg.allowImportantMessages(), cfg.sessionInstructions(),
                    initialModel, cfg.autoAccept());
            session.setSettings(modelSettings);
            if (currentSession != null) {
                currentSession.setSettings(modelSettings);
            }
            host.updateSessionSettings(modelSettings);
        }

        // Discover the real available model list (best-effort; falls back
        // silently to the hardcoded list). Discovery runs once per IDE run and
        // the result is broadcast to EVERY open Grok session's dropdown.
        triggerModelDiscovery();
        return provider;
    }

    /**
     * Discover the Grok model list once per IDE run via {@code grok models},
     * then broadcast it to every open Grok session's info bar via
     * {@link AiTypePropertyBus} — mirroring the Claude/Copilot flow. A session
     * opened after discovery already completed replays the cached list
     * immediately.
     */
    private void triggerModelDiscovery() {
        if (modelsFetched) {
            List<String> cached;
            synchronized (MODEL_LOCK) {
                cached = cachedModels;
            }
            if (cached != null) {
                List<String> snapshot = cached;
                SwingUtilities.invokeLater(() -> AiTypePropertyBus.getInstance()
                        .fire(AiTypeEnum.GROK, new GrokModelsEvent(snapshot)));
            }
            return;
        }
        GrokModelDiscovery.discoverAsync(GrokExecutableLocator.locate(), models -> {
            synchronized (MODEL_LOCK) {
                cachedModels = models;
                modelsFetched = true;
            }
            SwingUtilities.invokeLater(() -> AiTypePropertyBus.getInstance()
                    .fire(AiTypeEnum.GROK, new GrokModelsEvent(models)));
        });
    }

    @Override
    public void onStarted(AiSessionHost session) {
    }

    /**
     * Run after every {@code delegate.start()}. grok's {@code -s} flag (create
     * a new headless session) is rejected by the CLI if the id already exists
     * on disk ({@code Error: Session ID <id> is already in
     * use.}, empirically confirmed) — but {@code start()} always defaults to
     * create mode. If this session id already exists in grok's on-disk store,
     * switch the freshly started manager to resume it instead, so an in-place
     * restart or reopen of an existing session (e.g. on IDE restart, or
     * reopening the chat tab) behaves like a resume rather than failing
     * outright on the next message.
     */
    @Override
    protected void afterStart() {
        if (currentSession != null && isStoredSessionValid(currentSession.id())) {
            delegate.resumeSession(currentSession.id());
        }
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        return GrokUsageSignalsReader.sessionExists(sessionId);
    }
}
