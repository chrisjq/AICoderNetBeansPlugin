package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.SchemaToolCalls;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
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
                    currentSession.aiType(), handlers);
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

            // Populating the tools array makes this backend call something on
            // every turn regardless of the request, so under TOOL_CALLS_VIA_SCHEMA
            // the tools are described in the prompt and the reply is constrained
            // by a schema instead. See SchemaToolCalls.
            boolean schemaMode = currentSession.aiType().getMcpOptions()
                    .contains(McpInstructionOptionEnum.TOOL_CALLS_VIA_SCHEMA);
            JsonObject responseFormat = schemaMode ? SchemaToolCalls.responseFormat() : null;
            List<JsonObject> requestTools = schemaMode ? List.of() : tools;
            if (schemaMode) {
                instructions = instructions
                        + "\n\n## Calling a tool\n"
                        + "These are the tools you can call, with their parameters"
                        + " (those in [square brackets] are optional; use each name"
                        + " exactly as written):\n"
                        + SchemaToolCalls.renderToolList(tools)
                        + "\nReply as JSON. To call one tool, set tool_name and tool_arguments"
                        + " and leave message empty. To answer the user, put your reply in"
                        + " message and set tool_name to \"\". Never do both.";
            }

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage(ChatRole.SYSTEM, instructions, List.of(), null));
            messages.add(new ChatMessage(ChatRole.USER, text, List.of(), null));

            HttpAiClient client = httpClient;
            if (client == null) {
                client = createHttpAiClient();
                httpClient = client;
            }
            // A tool result like "Description updated." tells a model nothing about
            // whether the work is finished, so a weaker one can re-issue the same
            // call indefinitely. Track what has already run this turn: a repeat is
            // answered with a correction instead of being executed again, and two
            // consecutive all-repeat rounds end the turn rather than grinding out
            // the full iteration cap.
            Set<String> executedCalls = new java.util.LinkedHashSet<>();
            // Identical arguments are not the only way to make no progress: the
            // model varied the description on every UpdateSessionDescription call
            // and got "Description updated." back each time. A tool result already
            // seen this turn means the call told the model nothing new.
            Set<String> seenResults = new java.util.LinkedHashSet<>();
            int barrenRounds = 0;
            for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS && !cancelledByUser; iteration++) {
                String apiKey = resolveApiKey(settings);
                ChatRequest request = new ChatRequest(effectiveBaseUrl, apiKey, effectiveModel,
                        List.copyOf(messages), List.copyOf(requestTools), responseFormat);
                StringBuilder buf = new StringBuilder();
                boolean[] decided = {false};
                boolean[] streaming = {false};
                ChatResult result = client.chat(request, delta -> {
                    if (cancelledByUser) {
                        return;
                    }
                    buf.append(delta);
                    if (schemaMode) {
                        // Every reply is a JSON envelope; the message field is
                        // emitted once the turn resolves.
                        return;
                    }
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
                List<ExtractedToolCall> calls;
                String assistantText;
                if (schemaMode) {
                    // The answer is the schema's message field; the raw content is
                    // a JSON envelope the user must never see.
                    SchemaToolCalls.Reply reply = SchemaToolCalls.parse(result, knownToolNames);
                    calls = reply.calls();
                    assistantText = reply.message();
                }
                else {
                    calls = ToolCallExtractor.extract(result, knownToolNames);
                    assistantText = result.assistantText();
                }
                if (calls.isEmpty()) {
                    String finalText = assistantText;
                    if (!streaming[0] && isEmptyJson(finalText)) {
                        // A model that has run out of ideas emits "{}" rather than
                        // an answer. Streaming was suppressed because it opened
                        // like a tool call, and showing the user a bare "{}" as the
                        // reply is worse than telling them nothing came back.
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "The model returned an empty response instead of an answer"));
                        listener.onAiProcessEvent(new TurnCompleteEvent());
                        return;
                    }
                    if (!streaming[0] && finalText != null && !finalText.isBlank()) {
                        listener.onAiProcessEvent(new TextDeltaEvent(finalText, null));
                    }
                    listener.onAiProcessEvent(new TurnCompleteEvent());
                    return;
                }
                List<ChatToolCall> assistantToolCalls = new ArrayList<>();
                List<String> toolResults = new ArrayList<>();
                boolean madeProgress = false;
                for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                    if (cancelledByUser) {
                        break;
                    }
                    ExtractedToolCall call = calls.get(callIndex);
                    String callId = "call_" + callIndex;
                    assistantToolCalls.add(new ChatToolCall(callId, call.name(), call.argumentsJson()));
                    if (!executedCalls.add(call.name() + '(' + call.argumentsJson() + ')')) {
                        // Re-running it would repeat any side effect for no new
                        // information, so answer the model instead of the tool.
                        toolResults.add("Error: you already called " + call.name()
                                + " with these exact arguments during this turn and it succeeded. "
                                + "Calling it again changes nothing. Reply to the user in plain text now.");
                        continue;
                    }
                    listener.onAiProcessEvent(new ToolUseEvent(call.name(), null, "", null,
                            ToolUseEvent.Kind.OTHER));
                    JsonObject args;
                    try {
                        args = JsonParser.parseString(call.argumentsJson()).getAsJsonObject();
                    }
                    catch (RuntimeException ex) {
                        args = new JsonObject();
                    }
                    String toolResult = bridge.invokeTool(call.name(), args);
                    toolResults.add(toolResult);
                    if (seenResults.add(call.name() + " => " + toolResult)) {
                        madeProgress = true;
                    }
                }
                messages.add(new ChatMessage(ChatRole.ASSISTANT, null,
                        List.copyOf(assistantToolCalls), null));
                for (int callIndex = 0; callIndex < assistantToolCalls.size(); callIndex++) {
                    ChatToolCall toolCall = assistantToolCalls.get(callIndex);
                    messages.add(new ChatMessage(ChatRole.TOOL, toolResults.get(callIndex),
                            List.of(), toolCall.id()));
                }
                barrenRounds = madeProgress ? 0 : barrenRounds + 1;
                if (barrenRounds >= 2 && !cancelledByUser) {
                    // Ending here would leave the user with no reply at all, so
                    // ask once more with no tools offered — the model can only
                    // answer in prose.
                    if (!answerWithoutTools(client, effectiveBaseUrl, apiKey, effectiveModel, messages)) {
                        listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                                "Stopped: the model kept repeating the same tool call without making progress"));
                    }
                    listener.onAiProcessEvent(new TurnCompleteEvent());
                    return;
                }
            }
            if (!cancelledByUser) {
                listener.onAiProcessEvent(new StatusEvent(StatusEventTypeEnum.INFO,
                        "Stopped after " + MAX_TOOL_ITERATIONS + " tool iterations"));
                answerWithoutTools(client, effectiveBaseUrl, resolveApiKey(settings),
                        effectiveModel, messages);
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

    /**
     * Last resort when the tool loop ends without an answer: ask once more with
     * an empty tool list, so the model has nothing to call and must reply in
     * prose. Without this the user is left with only a status line.
     *
     * @return true if a non-empty answer was produced and emitted
     */
    private boolean answerWithoutTools(HttpAiClient client, String baseUrl, String apiKey,
            String model, List<ChatMessage> messages) throws IOException {
        List<ChatMessage> prompt = new ArrayList<>(messages);
        prompt.add(new ChatMessage(ChatRole.USER,
                "Stop calling tools. Answer my original message directly, in plain text.",
                List.of(), null));
        StringBuilder buf = new StringBuilder();
        ChatResult result = client.chat(
                new ChatRequest(baseUrl, apiKey, model, List.copyOf(prompt), List.of()),
                delta -> {
                    if (!cancelledByUser) {
                        buf.append(delta);
                    }
                });
        String text = buf.length() > 0 ? buf.toString() : result.assistantText();
        if (cancelledByUser || text == null || text.isBlank() || isEmptyJson(text)) {
            return false;
        }
        listener.onAiProcessEvent(new TextDeltaEvent(text, null));
        return true;
    }

    /**
     * True for text that is an empty JSON object or array — "{}" or "[]", with
     * or without a code fence. Anything with actual content is left alone: a
     * user can legitimately ask for JSON and must still receive it.
     */
    static boolean isEmptyJson(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String stripped = text.strip();
        if (stripped.startsWith("```")) {
            int close = stripped.lastIndexOf("```");
            stripped = (close > 2 ? stripped.substring(3, close) : stripped.substring(3)).strip();
            int nl = stripped.indexOf('\n');
            if (nl >= 0 && !stripped.startsWith("{") && !stripped.startsWith("[")) {
                stripped = stripped.substring(nl + 1).strip();
            }
        }
        try {
            JsonElement parsed = JsonParser.parseString(stripped);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject().size() == 0;
            }
            return parsed.isJsonArray() && parsed.getAsJsonArray().isEmpty();
        }
        catch (RuntimeException ex) {
            return false;
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
