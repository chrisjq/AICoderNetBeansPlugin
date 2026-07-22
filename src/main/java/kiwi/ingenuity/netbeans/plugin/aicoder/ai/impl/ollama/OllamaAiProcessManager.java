package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TextDeltaEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ToolUseEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.TurnCompleteEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRequest;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatResult;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ExtractedToolCall;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.HttpAiClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.OpenAiCompatibleClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ToolCallExtractor;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.session.OllamaAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpInstructionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.StatusMessageUtil;

public class OllamaAiProcessManager extends AiProcessManager {

    private static final Logger LOG = Logger.getLogger(OllamaAiProcessManager.class.getName());
    static final int MAX_TOOL_ITERATIONS = 25;

    private volatile OllamaMcpRegistrar registrar;
    private volatile HttpAiClient httpClient;
    volatile OllamaAiSession ollamaSession;
    volatile OllamaMcpBridge bridge;
    volatile Thread activeTurnThread;

    public OllamaAiProcessManager(AiProcessEventListener listener) {
        super(listener);
    }

    @Override
    public synchronized void start(String ignored, String model) {
        stop();
        if (currentSession == null) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatSessionNotConfigured()));
            return;
        }
        if (!validateStart()) {
            return;
        }
        this.model = model;
        this.sessionId = currentSession.id();
        OllamaMcpRegistrar reg = new OllamaMcpRegistrar(sessionId, currentSession.aiType());
        boolean mcpReady;
        try {
            mcpReady = registerMcp(reg);
        }
        catch (Exception ex) {
            LOG.log(Level.WARNING, "MCP registration failed for Ollama session " + sessionId, ex);
            mcpReady = false;
        }
        if (!mcpReady) {
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                    StatusMessageUtil.formatMcpSetupFailed()));
            return;
        }
        registrar = reg;
        ollamaSession = new OllamaAiSession(currentSession, listener);
        bridge = createBridge(ollamaSession);
        bridge.setSessionCredentials(currentSession.id(), currentSession.secret());
        currentSession.setInstructionsLoaded(true);
        running = true;
        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.READY,
                StatusMessageUtil.formatReady(displayName())));
    }

    @Override
    public synchronized void sendPrompt(String text, File workingDir, List<File> projectDirs) {
        if (pendingDiff || !running || processing || currentSession == null || bridge == null) {
            return;
        }
        cancelledByUser = false;
        processing = true;
        if (sessionWorkingDir == null && workingDir != null && workingDir.isDirectory()) {
            sessionWorkingDir = workingDir;
        }
        Thread worker = new Thread(() -> runTurn(text), "ollama-turn-" + sessionId);
        worker.setDaemon(true);
        activeTurnThread = worker;
        worker.start();
    }

    private void runTurn(String text) {
        try {
            OllamaSessionSettings settings = currentSession.settings() instanceof OllamaSessionSettings os
                    ? os : new OllamaSessionSettings();
            String effectiveModel = model != null && !model.isBlank()
                    ? model
                    : settings.model() != null && !settings.model().isBlank()
                    ? settings.model()
                    : defaultModel();
            String effectiveBaseUrl = settings.baseUrl() != null && !settings.baseUrl().isBlank()
                    ? settings.baseUrl()
                    : defaultBaseUrl();

            Map<McpToolEnum, McpToolInterface> handlers = buildToolHandlers(ollamaSession);
            String instructions = McpInstructionRegistry.buildFullInstructions(
                    currentSession.aiType(), handlers, McpInstructionOptions.apiBackend());
            JsonArray toolSchemas = bridge.listToolsForModel(handlers.values());
            List<JsonObject> tools = new ArrayList<>();
            Set<String> knownToolNames = new java.util.LinkedHashSet<>();
            for (int i = 0; i < toolSchemas.size(); i++) {
                JsonObject tool = toolSchemas.get(i).getAsJsonObject();
                tools.add(tool);
                if (tool.has("name")) {
                    knownToolNames.add(tool.get("name").getAsString());
                }
            }

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage(ChatRole.SYSTEM, instructions, List.of(), null));
            messages.add(new ChatMessage(ChatRole.USER, text, List.of(), null));

            HttpAiClient client = httpClient;
            if (client == null) {
                client = createHttpAiClient();
                httpClient = client;
            }
            for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS && !cancelledByUser; iteration++) {
                String apiKey = resolveApiKey(settings);
                ChatRequest request = new ChatRequest(effectiveBaseUrl, apiKey, effectiveModel,
                        List.copyOf(messages), List.copyOf(tools));
                StringBuilder buf = new StringBuilder();
                boolean[] decided = {false};
                boolean[] streaming = {false};
                ChatResult result = client.chat(request, delta -> {
                    if (cancelledByUser) {
                        return;
                    }
                    buf.append(delta);
                    if (!decided[0]) {
                        String lead = buf.toString().stripLeading();
                        if (lead.isEmpty()) {
                            return;
                        }
                        char c = lead.charAt(0);
                        boolean looksJson = (c == '{' || c == '[' || lead.startsWith("```"));
                        decided[0] = true;
                        streaming[0] = !looksJson;
                        if (streaming[0]) {
                            listener.onAiProcessEvent(new TextDeltaEvent(buf.toString(), null));
                        }
                        return;
                    }
                    if (streaming[0]) {
                        listener.onAiProcessEvent(new TextDeltaEvent(delta, null));
                    }
                });
                List<ExtractedToolCall> calls = ToolCallExtractor.extract(result, knownToolNames);
                if (calls.isEmpty()) {
                    if (!streaming[0]) {
                        String finalText = result.assistantText();
                        if (finalText != null && !finalText.isBlank()) {
                            listener.onAiProcessEvent(new TextDeltaEvent(finalText, null));
                        }
                    }
                    listener.onAiProcessEvent(new TurnCompleteEvent());
                    return;
                }
                List<ChatToolCall> assistantToolCalls = new ArrayList<>();
                List<String> toolResults = new ArrayList<>();
                for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                    if (cancelledByUser) {
                        break;
                    }
                    ExtractedToolCall call = calls.get(callIndex);
                    String callId = "call_" + callIndex;
                    assistantToolCalls.add(new ChatToolCall(callId, call.name(), call.argumentsJson()));
                    listener.onAiProcessEvent(new ToolUseEvent(call.name(), null, "", null,
                            ToolUseEvent.Kind.OTHER));
                    JsonObject args;
                    try {
                        args = JsonParser.parseString(call.argumentsJson()).getAsJsonObject();
                    }
                    catch (RuntimeException ex) {
                        args = new JsonObject();
                    }
                    toolResults.add(bridge.invokeTool(call.name(), args));
                }
                messages.add(new ChatMessage(ChatRole.ASSISTANT, null,
                        List.copyOf(assistantToolCalls), null));
                for (int callIndex = 0; callIndex < assistantToolCalls.size(); callIndex++) {
                    ChatToolCall toolCall = assistantToolCalls.get(callIndex);
                    messages.add(new ChatMessage(ChatRole.TOOL, toolResults.get(callIndex),
                            List.of(), toolCall.id()));
                }
            }
            if (!cancelledByUser) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Stopped after 25 tool iterations"));
                listener.onAiProcessEvent(new TurnCompleteEvent());
            }
        }
        catch (IOException ex) {
            if (!cancelledByUser) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(ex.getMessage())));
            }
        }
        catch (RuntimeException ex) {
            if (!cancelledByUser) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.FAILED,
                        StatusMessageUtil.formatSendFailed(ex.getMessage())));
            }
        }
        finally {
            processing = false;
            activeTurnThread = null;
        }
    }

    boolean registerMcp(OllamaMcpRegistrar reg) throws Exception {
        return McpServerRegistry.register(reg).get(2, TimeUnit.MINUTES);
    }

    HttpAiClient createHttpAiClient() {
        return new OpenAiCompatibleClient();
    }

    OllamaMcpBridge createBridge(OllamaAiSession session) {
        return new OllamaMcpBridge(session);
    }

    Map<McpToolEnum, McpToolInterface> buildToolHandlers(OllamaAiSession session) {
        return session.getMcpToolHandlers();
    }

    protected String displayName() {
        return "Ollama (Local)";
    }

    protected String defaultModel() {
        return OllamaPluginSettings.getModel();
    }

    protected String defaultBaseUrl() {
        return OllamaPluginSettings.getBaseUrl();
    }

    protected String resolveApiKey(OllamaSessionSettings settings) {
        return null;
    }

    protected boolean validateStart() {
        return true;
    }

    @Override
    public void interrupt(InterruptTypeEnum type) {
        if (type == InterruptTypeEnum.Cancel) {
            cancelledByUser = true;
            processing = false;
            Thread turnThread = activeTurnThread;
            if (turnThread != null) {
                turnThread.interrupt();
            }
            listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.STOPPED,
                    StatusMessageUtil.formatStopped()));
        }
    }

    @Override
    public synchronized void stop() {
        cancelledByUser = true;
        running = false;
        processing = false;
        Thread turnThread = activeTurnThread;
        activeTurnThread = null;
        if (turnThread != null) {
            turnThread.interrupt();
        }
        if (ollamaSession != null) {
            ollamaSession.dispose();
        }
        ollamaSession = null;
        bridge = null;
        httpClient = null;
        if (registrar != null) {
            McpServerRegistry.deregister(registrar);
        }
        registrar = null;
        sessionId = null;
        sessionWorkingDir = null;
        pendingDiff = false;
        sessionConfigDir = null;
    }

    @Override
    public void resumeSession(String existingSessionId) {
        if (existingSessionId != null && !existingSessionId.isBlank()) {
            sessionId = existingSessionId;
        }
    }

    @Override
    public boolean isMcpActive() {
        return registrar != null;
    }
}
