package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.events.ClaudeUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui.ClaudeAiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ui.ClaudeInfoBarListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiModelSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.events.SessionLifecycleSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

/**
 * Thin adapter so the generic multi-AI system (AiSession, AiTopComponent, etc.)
 * can use the Claude implementation without any behavior change for Claude
 * users. All Claude-specific code stays in this package or the classes it owns.
 */
public class ClaudeAiImplementation extends AiImplementation {

    private static final Logger LOG = Logger.getLogger(ClaudeAiImplementation.class.getName());

    private static final Object MODEL_LOCK = new Object();
    private static volatile List<String> cachedModels = null;
    private static volatile boolean modelsFetched = false;
    private static volatile ClaudeUsageEvent cachedUsageEvent = null;

    private final ClaudeAiProcessManager delegate;

    public ClaudeAiImplementation(AiProcessEventListener listener) {
        super(AiTypeEnum.CLAUDE, listener);
        this.delegate = new ClaudeAiProcessManager(listener);
    }

    @Override
    protected ClaudeAiProcessManager delegate() {
        return delegate;
    }

    public String getCurrentModel() {
        if (currentSession != null && currentSession.settings() instanceof AbstractAiModelSessionSettings mc) {
            return mc.model();
        }
        return ClaudePluginSettings.getModel();
    }

    @Override
    public void startWithDiscovery(String model, Component parent) {
        String effectiveModel = (model != null && !model.isBlank()) ? model : getCurrentModel();
        String execPath = ClaudeExecutableLocator.locate();
        if (execPath != null) {
            // Executable was found: start exactly once. start() reports success or
            // failure via events. We must NOT fall through to a second start() here —
            // a failure unrelated to the executable (e.g. MCP port bind) would
            // otherwise trigger a duplicate MCP server start on the same port.
            delegate.start(execPath, effectiveModel);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            String chosen = promptForExecutable(parent);
            if (chosen != null) {
                delegate.start(chosen, effectiveModel);
            }
            else {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED, StatusMessageUtil.formatExecutableNotFound(null)));
            }
        });
    }

    private void applySessionPaths() {
        Path configPath = getSessionConfigPath();
        if (configPath != null) {
            delegate.setSessionConfigDir(configPath);
        }
    }

    /**
     * Run after every {@code delegate.start()}. Applies session paths, then —
     * if this session already exists in Claude's on-disk store — switches the
     * freshly started manager to RESUME it. start() always defaults to
     * create-via {@code --session-id}, which the Claude CLI rejects for an id
     * that already exists, so the process exits immediately. That is why an
     * in-place restart of a dead session "sends but dies again": every other
     * start path (componentOpened) reaches resumeSession() via loadHistory(),
     * but the resend-into-dead-session path did not. resumeSession() flips the
     * next turn to {@code --resume}, so a restart behaves like reopening the
     * tab.
     */
    @Override
    protected void afterStart() {
        applySessionPaths();
        if (currentSession != null && isStoredSessionValid(currentSession.id())) {
            delegate.resumeSession(currentSession.id());
        }
    }

    private String promptForExecutable(Component parent) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Locate claude executable");
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().equals("claude") || f.getName().startsWith("claude.");
            }

            @Override
            public String getDescription() {
                return "claude executable";
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
            ClaudePluginSettings.setExecutable(path);
            return path;
        }
        return null;
    }

    @Override
    public void setModel(String model) {
        ClaudePluginSettings.setModel(model);
        delegate.setModel(model);
    }

    @Override
    public boolean isStoredSessionValid(String sessionId) {
        Path projectsDir = Path.of(System.getProperty("user.home"), ".claude", "projects");
        if (!Files.isDirectory(projectsDir)) {
            return false;
        }
        String target = sessionId + ".jsonl";
        try (Stream<Path> dirs = Files.list(projectsDir)) {
            return dirs.filter(Files::isDirectory)
                    .anyMatch(dir -> Files.exists(dir.resolve(target)));
        }
        catch (IOException e) {
            return false;
        }
    }

    public List<String> getDefaultModels() {
        return Arrays.asList(ClaudePluginSettings.KNOWN_MODELS);
    }

    @Override
    public AiInfoBarExtension createInfoBarExtension(AiSession session, AiSessionHost host) {
        ClaudeAiInfoBarExtension provider = new ClaudeAiInfoBarExtension();
        provider.addListener(new ClaudeInfoBarListener() {
            @Override
            public void onCompactRequested() {
                compact(host);
            }

            @Override
            public void onModelChanged(String model) {
            }
        });
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
                ? modelCfg.model() : ClaudePluginSettings.getModel();
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
        return provider;
    }

    private void compact(AiSessionHost host) {
        if (!isRunning() || isProcessing()) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                    "Wait for Claude to finish before compacting"));
            return;
        }
        sendPrompt("/compact", host.resolveWorkDir(), List.of());
        if (isProcessing()) {
            host.suppressNextTurn("Compacting conversation...", null);
        }
    }

    @Override
    public void onTabActivated() {
        fetchUsageAsync();
    }

    @Override
    public void registerLifecycleListeners(SessionLifecycleSource source) {
        source.addListener(new SessionLifecycleListener() {
            @Override
            public void onSessionStarted() {
                fetchUsageAsync();
            }

            @Override
            public void onTurnComplete() {
                fetchUsageAsync();
            }
        });
        triggerModelDiscovery();
        triggerUsageReplay();
    }

    private void triggerModelDiscovery() {
        if (modelsFetched) {
            // Already fetched; fire from cache so this new session's dropdown is populated.
            List<String> cached;
            synchronized (MODEL_LOCK) {
                cached = cachedModels;
            }
            if (cached != null) {
                List<String> snapshot = cached;
                SwingUtilities.invokeLater(() -> AiTypePropertyBus.getInstance().fire(AiTypeEnum.CLAUDE, new ClaudeModelsEvent(snapshot)));
            }
            return;
        }
        // Not yet fetched. Coalesced by the "models" key so only one fetch runs at a
        // time across all Claude sessions; deferred if currently rate-limited.
        AnthropicApiClient.rateLimitManager().submitWhenClear("models", () -> {
            try {
                List<String> modelList = new AnthropicApiClient().fetchModels();
                if (modelList != null && !modelList.isEmpty()) {
                    synchronized (MODEL_LOCK) {
                        cachedModels = modelList;
                        modelsFetched = true;
                    }
                    ClaudePluginSettings.setDiscoveredModels(modelList.toArray(String[]::new));
                    ClaudeModelsEvent event = new ClaudeModelsEvent(modelList);
                    SwingUtilities.invokeLater(()
                            -> AiTypePropertyBus.getInstance().fire(AiTypeEnum.CLAUDE, event));
                }
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Model discovery failed: {0}", e.getMessage());
            }
        });
    }

    private void fetchUsageAsync() {
        triggerModelDiscovery();
        AnthropicApiClient.rateLimitManager().submitWhenClear("usage", () -> {
            try {
                AnthropicApiClient.UsageData data = new AnthropicApiClient().fetchUsage();
                ClaudeUsageEvent event = new ClaudeUsageEvent(data.fiveHourPct(), data.sevenDayPct());
                cachedUsageEvent = event;
                SwingUtilities.invokeLater(() -> AiTypePropertyBus.getInstance().fire(AiTypeEnum.CLAUDE, event));
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Usage fetch failed: {0}", e.getMessage());
            }
        });
    }

    private void triggerUsageReplay() {
        ClaudeUsageEvent cached = cachedUsageEvent;
        if (cached != null) {
            SwingUtilities.invokeLater(() -> AiTypePropertyBus.getInstance().fire(AiTypeEnum.CLAUDE, cached));
        }
    }

    @Override
    public void onStarted(AiSessionHost session) {
    }
}
