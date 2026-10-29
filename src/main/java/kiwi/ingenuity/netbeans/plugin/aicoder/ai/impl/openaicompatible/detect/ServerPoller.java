package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;

/**
 * One detection cycle at a time for a single normalised root and API-key hash (spec §3.4). Ticks are scheduled; probe
 * I/O is the {@link Prober}'s job, which in production runs on {@code BackendExecutors.probePool()} via
 * {@link ProbeClient}.
 * <p>
 * The periodic tick is skipped while a cycle is in flight. Any other trigger marks the poller dirty and one more cycle
 * runs straight after. The last good probe body is kept through an outage, and listeners keep the last good
 * {@link DetectedFacts}.
 */
public final class ServerPoller {

    static final String V1_MODELS = "/v1/models";
    static final String OLLAMA_TAGS = "/api/tags";
    static final String OLLAMA_PS = "/api/ps";
    static final String OLLAMA_SHOW = "/api/show";
    static final String LM_V1 = "/api/v1/models";
    static final String LM_V0 = "/api/v0/models";
    static final String LLAMA_PROPS = "/props";
    static final int DEFAULT_MODEL_CAP = 8;

    private final String root;
    private final String keyHash;
    private final boolean keySet;
    private final Prober prober;
    private final ScheduledExecutorService scheduler;
    private final OpenAiCompatibleLimits limits;
    private final int modelCap;
    private final Object lock = new Object();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, JsonObject> lastGood = new LinkedHashMap<>();

    private ScheduledFuture<?> tick;
    private boolean cycleRunning;
    private boolean dirty;
    private boolean stopped;
    private boolean runFullSet;
    private ServerKind knownKind;
    private int cursor;

    ServerPoller(String root, String keyHash, boolean keySet, Prober prober, ScheduledExecutorService scheduler,
                 OpenAiCompatibleLimits limits, int modelCap) {
        this.root = root == null ? "" : root;
        this.keyHash = keyHash == null ? "" : keyHash;
        this.keySet = keySet;
        this.prober = prober;
        this.scheduler = scheduler;
        this.limits = limits == null ? OpenAiCompatibleLimits.defaults() : limits;
        this.modelCap = modelCap > 0 ? modelCap : DEFAULT_MODEL_CAP;
    }

    /**
     * A probe relative to the poller's root. {@code jsonBody} null is a GET; a body is a POST. Paths that already start
     * with a scheme are absolute and must not have the root prepended.
     */
    public interface Prober {

        CompletableFuture<ProbeClient.ProbeResult> probe(String path);

        default CompletableFuture<ProbeClient.ProbeResult> probe(String path, String jsonBody) {
            return probe(path);
        }

        default void close() {
        }
    }

    /**
     * Receives one session's snapshot. {@code reachable} is false when the cycle got no HTTP response; {@code facts} is
     * then the last good snapshot, or an empty one when detection has never succeeded.
     */
    public interface FactsListener {

        void onFacts(DetectedFacts facts, boolean reachable);
    }

    void add(String sessionId, ServerTypeEnum type, String model, String enteredUrl, FactsListener listener) {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            sessions.put(sessionId, new Session(sessionId, type == null ? ServerTypeEnum.AUTO : type,
                                                model == null ? "" : model, enteredUrl, listener));
            if (tick == null) {
                long millis = Math.max(1L, limits.redetectInterval().toMillis());
                try {
                    tick = scheduler.scheduleWithFixedDelay(this::periodicTick, millis, millis, TimeUnit.MILLISECONDS);
                }
                catch (RuntimeException ex) {
                    tick = null;
                }
            }
        }
        trigger(false);
    }

    /**
     * @return true when this was the last session and the poller has stopped
     */
    boolean remove(String sessionId) {
        synchronized (lock) {
            sessions.remove(sessionId);
            if (!sessions.isEmpty()) {
                return false;
            }
            stopped = true;
            if (tick != null) {
                tick.cancel(false);
            }
            prober.close();
            return true;
        }
    }

    void postReply(String sessionId) {
        synchronized (lock) {
            Session session = sessions.get(sessionId);
            if (session != null) {
                session.justReplied = true;
            }
        }
        trigger(false);
    }

    void settingsChanged(String sessionId, ServerTypeEnum type, String model) {
        synchronized (lock) {
            Session session = sessions.get(sessionId);
            if (session == null) {
                return;
            }
            session.type = type == null ? ServerTypeEnum.AUTO : type;
            session.model = model == null ? "" : model;
            session.windowConfirmed = false;
            session.typeMismatch = false;
        }
        trigger(false);
    }

    /**
     * The scheduled re-detect tick. Skipped, and not remembered, while a cycle is already running.
     */
    void periodicTick() {
        trigger(true);
    }

    boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    int sessionCount() {
        synchronized (lock) {
            return sessions.size();
        }
    }

    private void trigger(boolean periodic) {
        CyclePlan plan;
        synchronized (lock) {
            if (stopped || sessions.isEmpty()) {
                return;
            }
            if (cycleRunning) {
                if (!periodic) {
                    dirty = true;
                }
                return;
            }
            cycleRunning = true;
            plan = planLocked();
        }
        fire(plan, Map.of());
    }

    private void fire(CyclePlan plan, Map<String, ProbeClient.ProbeResult> already) {
        if (plan.pending.isEmpty()) {
            finish(plan, already);
            return;
        }
        List<CompletableFuture<ProbeClient.ProbeResult>> futures = new ArrayList<>();
        for (Call call : plan.pending) {
            futures.add(start(call));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            Map<String, ProbeClient.ProbeResult> results = new LinkedHashMap<>(already);
            for (int i = 0; i < plan.pending.size(); i++) {
                results.put(plan.pending.get(i).memoryKey(), join(futures.get(i)));
            }
            List<Call> extra = followUp(plan, results);
            if (extra.isEmpty()) {
                finish(plan, results);
            }
            else {
                fire(new CyclePlan(plan.sessions, extra, plan.showModels), results);
            }
        });
    }

    private CompletableFuture<ProbeClient.ProbeResult> start(Call call) {
        try {
            CompletableFuture<ProbeClient.ProbeResult> future = call.jsonBody() == null
                                                                ? prober.probe(call.path())
                                                                : prober.probe(call.path(), call.jsonBody());
            if (future == null) {
                return CompletableFuture.completedFuture(failed());
            }
            return future.exceptionally(ex -> failed());
        }
        catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(failed());
        }
    }

    private void finish(CyclePlan plan, Map<String, ProbeClient.ProbeResult> results) {
        List<Publication> publications = new ArrayList<>();
        CyclePlan next = null;
        synchronized (lock) {
            publications.addAll(publishLocked(plan, results));
        }
        for (Publication publication : publications) {
            try {
                publication.listener().onFacts(publication.facts(), publication.reachable());
            }
            catch (RuntimeException ex) {
                // A listener must not stop the cycle from finishing.
            }
        }
        synchronized (lock) {
            cycleRunning = false;
            if (dirty && !stopped && !sessions.isEmpty()) {
                dirty = false;
                cycleRunning = true;
                next = planLocked();
            }
            else {
                dirty = false;
            }
        }
        if (next != null) {
            fire(next, Map.of());
        }
    }

    private CyclePlan planLocked() {
        boolean fullSet = runFullSet;
        runFullSet = false;
        List<Session> snapshot = List.copyOf(sessions.values());
        boolean hosted = BaseUrls.isHosted(root, keySet);
        LinkedHashMap<String, Call> calls = new LinkedHashMap<>();
        boolean show = false;
        for (Session session : snapshot) {
            for (Slot slot : slotsFor(session, hosted, fullSet)) {
                if (slot == Slot.SHOW) {
                    show = true;
                    continue;
                }
                Call call = slot.call();
                calls.putIfAbsent(call.memoryKey(), call);
            }
        }
        List<String> shows = show ? selectModels(snapshot, hosted, fullSet) : List.of();
        for (String model : shows) {
            Call call = Call.show(model);
            calls.put(call.memoryKey(), call);
        }
        return new CyclePlan(snapshot, List.copyOf(calls.values()), shows);
    }

    private List<String> selectModels(List<Session> snapshot, boolean hosted, boolean fullSet) {
        LinkedHashSet<String> ring = new LinkedHashSet<>();
        for (Session session : snapshot) {
            if (slotsFor(session, hosted, fullSet).contains(Slot.SHOW) && !session.model.isBlank()) {
                ring.add(session.model);
            }
        }
        if (ring.isEmpty()) {
            return List.of();
        }
        List<String> models = new ArrayList<>(ring);
        int start = Math.floorMod(cursor, models.size());
        List<String> rotated = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            rotated.add(models.get((start + i) % models.size()));
        }
        boolean anyConfirmed = false;
        for (Session session : snapshot) {
            if (session.windowConfirmed) {
                anyConfirmed = true;
                break;
            }
        }
        List<String> ordered = new ArrayList<>();
        for (String model : rotated) {
            if (priority(model, snapshot, anyConfirmed)) {
                ordered.add(model);
            }
        }
        for (String model : rotated) {
            if (!ordered.contains(model)) {
                ordered.add(model);
            }
        }
        int count = Math.min(modelCap, ordered.size());
        List<String> selected = List.copyOf(ordered.subList(0, count));
        cursor = start + count;
        for (Session session : snapshot) {
            if (selected.contains(session.model)) {
                session.justReplied = false;
            }
        }
        return selected;
    }

    private boolean priority(String model, List<Session> snapshot, boolean anyConfirmed) {
        for (Session session : snapshot) {
            if (!model.equals(session.model)) {
                continue;
            }
            if (session.justReplied) {
                return true;
            }
            if (anyConfirmed && !session.windowConfirmed) {
                return true;
            }
        }
        return false;
    }

    private List<Slot> slotsFor(Session session, boolean hosted, boolean fullSet) {
        if (session.typeMismatch) {
            return hosted ? List.of(Slot.V1_MODELS)
                   : List.of(Slot.TAGS, Slot.PS, Slot.SHOW, Slot.LM_V1, Slot.PROPS, Slot.V1_MODELS);
        }
        if (session.type == ServerTypeEnum.AUTO && hosted) {
            return List.of(Slot.V1_MODELS);
        }
        if (session.type == ServerTypeEnum.AUTO && knownKind != null && !fullSet) {
            return slotsForKind(knownKind, true);
        }
        if (session.type == ServerTypeEnum.AUTO) {
            return List.of(Slot.TAGS, Slot.PS, Slot.SHOW, Slot.LM_V1, Slot.PROPS, Slot.V1_MODELS);
        }
        ServerKind chosen = chosenKind(session.type);
        if (chosen == null) {
            return List.of(Slot.V1_MODELS);
        }
        return slotsForKind(chosen, knownKind != null);
    }

    private static List<Slot> slotsForKind(ServerKind kind, boolean includeV1) {
        List<Slot> slots = new ArrayList<>();
        switch (kind) {
            case OLLAMA -> {
                slots.add(Slot.TAGS);
                slots.add(Slot.PS);
                slots.add(Slot.SHOW);
            }
            case LMSTUDIO ->
                slots.add(Slot.LM_V1);
            case LLAMACPP ->
                slots.add(Slot.PROPS);
            case VLLM, GENERIC ->
                slots.add(Slot.V1_MODELS);
            default ->
                slots.add(Slot.V1_MODELS);
        }
        if (includeV1 && !slots.contains(Slot.V1_MODELS)) {
            slots.add(Slot.V1_MODELS);
        }
        return slots;
    }

    private static ServerKind chosenKind(ServerTypeEnum type) {
        return switch (type) {
            case OLLAMA ->
                ServerKind.OLLAMA;
            case LM_STUDIO ->
                ServerKind.LMSTUDIO;
            case LLAMA_CPP ->
                ServerKind.LLAMACPP;
            case VLLM ->
                ServerKind.VLLM;
            case AUTO, OTHER ->
                null;
        };
    }

    private List<Call> followUp(CyclePlan plan, Map<String, ProbeClient.ProbeResult> results) {
        List<Call> extra = new ArrayList<>();
        boolean askedLm = plan.pending.stream().anyMatch(call -> LM_V1.equals(call.path()));
        ProbeClient.ProbeResult lm = results.get(LM_V1);
        if (askedLm && lm != null && lm.kind() == ProbeClient.Kind.NOT_FOUND && !results.containsKey(LM_V0)) {
            extra.add(Call.get(LM_V0));
        }
        ProbeClient.ProbeResult models = results.get(V1_MODELS);
        if (models != null && models.kind() == ProbeClient.Kind.NOT_FOUND) {
            for (Session session : plan.sessions) {
                for (String candidate : BaseUrls.apiBaseCandidates(session.enteredUrl)) {
                    String url = stripSlash(candidate) + "/models";
                    if (url.endsWith(V1_MODELS) || results.containsKey(url)) {
                        continue;
                    }
                    extra.add(Call.get(url));
                }
            }
        }
        return extra;
    }

    private List<Publication> publishLocked(CyclePlan plan, Map<String, ProbeClient.ProbeResult> results) {
        boolean reachable = false;
        for (ProbeClient.ProbeResult result : results.values()) {
            if (result != null && result.status() > 0) {
                reachable = true;
                break;
            }
        }
        if (!reachable && !results.isEmpty()) {
            runFullSet = true;
        }
        boolean unauthorized = !results.isEmpty();
        for (ProbeClient.ProbeResult result : results.values()) {
            if (result == null || result.kind() != ProbeClient.Kind.UNAUTHORIZED) {
                unauthorized = false;
                break;
            }
        }
        Map<String, JsonObject> bodies = new LinkedHashMap<>();
        for (Map.Entry<String, ProbeClient.ProbeResult> entry : results.entrySet()) {
            JsonObject body = resolveBody(entry.getKey(), entry.getValue());
            if (body != null) {
                bodies.put(entry.getKey(), body);
            }
        }
        if (reachable) {
            knownKind = detectKind(bodies);
        }
        List<Publication> publications = new ArrayList<>();
        for (Session planned : plan.sessions) {
            Session session = sessions.get(planned.id);
            if (session == null || session.listener == null) {
                continue;
            }
            if (!reachable) {
                DetectedFacts kept = session.lastGood == null
                                     ? DetectedFacts.of(generation(session)) : session.lastGood;
                publications.add(new Publication(session.listener, kept, false));
                continue;
            }
            DetectedFacts facts = parseSession(session, bodies).withProbesUnauthorized(unauthorized);
            ServerKind chosen = chosenKind(session.type);
            session.typeMismatch = !unauthorized && chosen != null && facts.kind() != chosen;
            session.lastGood = facts;
            session.windowConfirmed = !facts.windowReadings().isEmpty();
            publications.add(new Publication(session.listener, facts, true));
        }
        return publications;
    }

    private DetectedFacts parseSession(Session session, Map<String, JsonObject> bodies) {
        JsonObject v1Json = bodies.get(V1_MODELS);
        String apiBase = root + "/v1";
        if (v1Json == null) {
            for (Map.Entry<String, JsonObject> entry : bodies.entrySet()) {
                if (entry.getKey().startsWith("http") && entry.getKey().endsWith("/models")) {
                    v1Json = entry.getValue();
                    apiBase = entry.getKey().substring(0, entry.getKey().length() - "/models".length());
                    break;
                }
            }
        }
        Parsed parsed = readProbes(bodies.get(OLLAMA_TAGS), bodies.get(OLLAMA_PS),
                                   bodies.get(OLLAMA_SHOW + "\n" + session.model), bodies.get(LM_V1), bodies.get(LM_V0),
                                   bodies.get(LLAMA_PROPS), v1Json, session.model);
        ProbeParsers.OllamaTagsResult tags = parsed.tags();
        ProbeParsers.OllamaShowResult show = parsed.show();
        Integer ps = parsed.ps();
        ProbeParsers.LmStudioResult lm = parsed.lm();
        ProbeParsers.LlamaCppPropsResult props = parsed.props();
        ProbeParsers.V1ModelsResult v1 = parsed.v1();
        ServerKind kind = kindFor(session, parsed.ollama(), parsed.lmStudio(), parsed.llama(), parsed.vllm());

        List<WindowReading> readings = new ArrayList<>();
        if (ps != null && ps > 0) {
            readings.add(new WindowReading(ps, WindowReading.Source.OLLAMA_PS, true));
        }
        if (show != null && show.numCtx() != null && show.numCtx() > 0) {
            readings.add(new WindowReading(show.numCtx(), WindowReading.Source.OLLAMA_NUM_CTX, true));
        }
        if (lm != null && lm.loadedContextLength() != null && lm.loadedContextLength() > 0) {
            readings.add(new WindowReading(lm.loadedContextLength(), WindowReading.Source.LMSTUDIO_LOADED, true));
        }
        if (props != null && props.nCtx() != null && props.nCtx() > 0) {
            readings.add(new WindowReading(props.nCtx(), WindowReading.Source.LLAMACPP_N_CTX, true));
        }
        Integer vllmLen = v1 == null ? null : v1.maxModelLen();
        if (vllmLen != null && vllmLen > 0) {
            readings.add(new WindowReading(vllmLen, WindowReading.Source.VLLM_MAX_MODEL_LEN, true));
        }
        Integer v1Window = firstPositive(v1 == null ? null : v1.contextLength(),
                                         v1 == null ? null : v1.contextWindow(), vllmLen, v1 == null ? null : v1.metaNCtx());
        if (v1Window != null) {
            readings.add(new WindowReading(v1Window, WindowReading.Source.V1_MODELS, true));
        }
        boolean v1Field = v1 != null && (v1.contextLength() != null || v1.contextWindow() != null
                || v1.maxModelLen() != null || v1.metaNCtx() != null || v1.metaNCtxTrain() != null
                || v1.maxContextLength() != null);

        DetectedFacts.Capabilities capabilities = capabilities(show, lm);
        List<String> models = modelList(kind, tags, lm, v1);
        return DetectedFacts.of(generation(session))
                .withKind(kind)
                .withApiBase(apiBase)
                .withCapabilities(capabilities)
                .withWindowReadings(readings)
                .withV1WindowFieldPresent(v1Field)
                .withOllamaTrainingContextLength(show == null ? null : show.trainingContextLength())
                .withLmStudioMaxContextLength(lm == null ? null : lm.maxContextLength())
                .withLlamaCppTrainContextLength(v1 == null ? null : v1.metaNCtxTrain())
                .withModels(models)
                .withTimestamp(Instant.now());
    }

    private ServerKind kindFor(Session session, boolean ollama, boolean lmStudio, boolean llama, boolean vllm) {
        ServerKind chosen = chosenKind(session.type);
        if (chosen == null) {
            return firstKind(ollama, lmStudio, llama, vllm);
        }
        boolean matched = switch (chosen) {
            case OLLAMA ->
                ollama;
            case LMSTUDIO ->
                lmStudio;
            case LLAMACPP ->
                llama;
            case VLLM ->
                vllm;
            case GENERIC ->
                false;
        };
        return matched ? chosen : firstKind(ollama, lmStudio, llama, vllm);
    }

    /**
     * PARSE: every {@link ProbeParsers} call lives here. Kind detection and published snapshots both use it.
     */
    private static Parsed readProbes(JsonObject tagsJson, JsonObject psJson, JsonObject showJson, JsonObject lmV1Json,
                                     JsonObject lmV0Json, JsonObject propsJson, JsonObject v1Json, String model) {
        ProbeParsers.OllamaTagsResult tags = tagsJson == null ? null : ProbeParsers.ollamaTags(tagsJson);
        ProbeParsers.OllamaShowResult show = showJson == null ? null : ProbeParsers.ollamaShow(showJson);
        Integer ps = psJson == null ? null : ProbeParsers.ollamaPs(psJson, model);
        ProbeParsers.LmStudioResult lmV1 = lmV1Json == null ? null : ProbeParsers.lmStudioV1(lmV1Json, model);
        ProbeParsers.LmStudioResult lmV0 = lmV0Json == null ? null : ProbeParsers.lmStudioV0(lmV0Json, model);
        ProbeParsers.LmStudioResult lm = lmV1 != null && lmV1.signature() ? lmV1 : lmV0;
        ProbeParsers.LlamaCppPropsResult props = propsJson == null ? null : ProbeParsers.llamaCppProps(propsJson);
        ProbeParsers.V1ModelsResult v1 = v1Json == null ? null : ProbeParsers.v1Models(v1Json, model);
        return new Parsed(tags, show, ps, lm, props, v1,
                          tags != null && tags.signature(),
                          lm != null && lm.signature(),
                          props != null && props.signature(),
                          v1 != null && v1.vllmSignature());
    }

    private ServerKind detectKind(Map<String, JsonObject> bodies) {
        Parsed parsed = readProbes(bodies.get(OLLAMA_TAGS), null, null, bodies.get(LM_V1), bodies.get(LM_V0),
                                   bodies.get(LLAMA_PROPS), bodies.get(V1_MODELS), "");
        ServerKind detected = firstKind(parsed.ollama(), parsed.lmStudio(), parsed.llama(), parsed.vllm());
        return detected == ServerKind.GENERIC ? knownKind : detected;
    }

    private static ServerKind firstKind(boolean ollama, boolean lmStudio, boolean llama, boolean vllm) {
        if (ollama) {
            return ServerKind.OLLAMA;
        }
        if (lmStudio) {
            return ServerKind.LMSTUDIO;
        }
        if (llama) {
            return ServerKind.LLAMACPP;
        }
        if (vllm) {
            return ServerKind.VLLM;
        }
        return ServerKind.GENERIC;
    }

    private static DetectedFacts.Capabilities capabilities(ProbeParsers.OllamaShowResult show,
                                                           ProbeParsers.LmStudioResult lm) {
        Boolean tools = null;
        Boolean thinking = null;
        if (show != null) {
            tools = show.tools();
            thinking = show.thinking();
        }
        else if (lm != null && lm.tools() != null) {
            tools = lm.tools();
        }
        List<String> reasoningLevels = lm == null ? null : lm.reasoningLevels();
        return new DetectedFacts.Capabilities(tools, thinking, reasoningLevels);
    }

    private static List<String> modelList(ServerKind kind, ProbeParsers.OllamaTagsResult tags,
                                          ProbeParsers.LmStudioResult lm, ProbeParsers.V1ModelsResult v1) {
        if (kind == ServerKind.OLLAMA && tags != null && !tags.modelNames().isEmpty()) {
            return tags.modelNames();
        }
        if (kind == ServerKind.LMSTUDIO && lm != null && !lm.modelIds().isEmpty()) {
            return lm.modelIds();
        }
        if (v1 != null && !v1.ids().isEmpty()) {
            return v1.ids();
        }
        if (tags != null && !tags.modelNames().isEmpty()) {
            return tags.modelNames();
        }
        if (lm != null) {
            return lm.modelIds();
        }
        return List.of();
    }

    private JsonObject resolveBody(String key, ProbeClient.ProbeResult result) {
        if (result != null && result.kind() == ProbeClient.Kind.OK) {
            JsonObject body = asObject(result.json());
            if (body != null && !isEndpointError(body)) {
                lastGood.put(key, body);
                return body;
            }
        }
        if (result == null || result.kind() == ProbeClient.Kind.UNREACHABLE
                || result.kind() == ProbeClient.Kind.FAILED || result.kind() == ProbeClient.Kind.RATE_LIMITED) {
            return lastGood.get(key);
        }
        return null;
    }

    private String generation(Session session) {
        return DetectedFacts.generation(root, session.model, keyHash);
    }

    private static ProbeClient.ProbeResult join(CompletableFuture<ProbeClient.ProbeResult> future) {
        try {
            ProbeClient.ProbeResult result = future.join();
            return result == null ? failed() : result;
        }
        catch (RuntimeException ex) {
            return failed();
        }
    }

    private static ProbeClient.ProbeResult failed() {
        return new ProbeClient.ProbeResult(0, null, ProbeClient.Kind.FAILED);
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean isEndpointError(JsonObject body) {
        return body.size() == 1 && body.has("error") && body.get("error").isJsonPrimitive()
                && body.getAsJsonPrimitive("error").isString();
    }

    private static Integer firstPositive(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private static String stripSlash(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private enum Slot {
        TAGS,
        PS,
        SHOW,
        LM_V1,
        PROPS,
        V1_MODELS;

        Call call() {
            return switch (this) {
                case TAGS ->
                    Call.get(OLLAMA_TAGS);
                case PS ->
                    Call.get(OLLAMA_PS);
                case SHOW ->
                    Call.get(OLLAMA_SHOW);
                case LM_V1 ->
                    Call.get(ServerPoller.LM_V1);
                case PROPS ->
                    Call.get(LLAMA_PROPS);
                case V1_MODELS ->
                    Call.get(ServerPoller.V1_MODELS);
            };
        }
    }

    private record Call(String path, String jsonBody, String model) {

        static Call get(String path) {
            return new Call(path, null, null);
        }

        static Call show(String model) {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            return new Call(OLLAMA_SHOW, body.toString(), model);
        }

        String memoryKey() {
            return model == null ? path : path + "\n" + model;
        }
    }

    private record CyclePlan(List<Session> sessions, List<Call> pending, List<String> showModels) {

    }

    private record Publication(FactsListener listener, DetectedFacts facts, boolean reachable) {

    }

    private record Parsed(ProbeParsers.OllamaTagsResult tags, ProbeParsers.OllamaShowResult show, Integer ps,
                          ProbeParsers.LmStudioResult lm, ProbeParsers.LlamaCppPropsResult props, ProbeParsers.V1ModelsResult v1,
                          boolean ollama, boolean lmStudio, boolean llama, boolean vllm) {

    }

    private static final class Session {

        private final String id;
        private final String enteredUrl;
        private final FactsListener listener;
        private ServerTypeEnum type;
        private String model;
        private boolean justReplied;
        private boolean windowConfirmed;
        private boolean typeMismatch;
        private DetectedFacts lastGood;

        Session(String id, ServerTypeEnum type, String model, String enteredUrl, FactsListener listener) {
            this.id = id;
            this.type = type;
            this.model = model;
            this.enteredUrl = enteredUrl;
            this.listener = listener;
        }
    }
}
