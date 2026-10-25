package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.CAPABILITIES;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.CONFIG;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.CONTEXT_LENGTH;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.CONTEXT_WINDOW;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.DATA;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.DEFAULT_GENERATION_SETTINGS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.DETAILS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.ID;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.KEY;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.LOADED_CONTEXT_LENGTH;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.LOADED_INSTANCES;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.MAX_CONTEXT_LENGTH;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.MAX_MODEL_LEN;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.META;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.MODEL;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.MODELS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.MODEL_INFO;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.NAME;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.N_CTX;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.N_CTX_TRAIN;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.PARAMETERS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.STATE;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.THINKING;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.TOOLS;
import static kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerFieldEnum.TRAINED_FOR_TOOL_USE;

/**
 * Pure parsers for detection probe bodies. Never throws on malformed JSON shapes; missing data is null/empty/false.
 */
public final class ProbeParsers {

    private static final Pattern NUM_CTX = Pattern.compile("(?i)\\bnum_ctx\\s+(\\d+)");

    private ProbeParsers() {
    }

    public record OllamaTagsResult(boolean signature, List<String> modelNames) {

        public OllamaTagsResult {
            modelNames = modelNames == null ? List.of() : List.copyOf(modelNames);
        }
    }

    public record OllamaShowResult(boolean tools, boolean thinking, Integer trainingContextLength, Integer numCtx) {

    }

    public record LmStudioResult(boolean signature, List<String> modelIds, Integer loadedContextLength,
                                 Integer maxContextLength, Boolean tools, List<String> reasoningLevels) {

        public LmStudioResult(boolean signature, List<String> modelIds, Integer loadedContextLength,
                              Integer maxContextLength, Boolean tools) {
            this(signature, modelIds, loadedContextLength, maxContextLength, tools, null);
        }

        public LmStudioResult {
            modelIds = modelIds == null ? List.of() : List.copyOf(modelIds);
            reasoningLevels = reasoningLevels == null ? null : List.copyOf(reasoningLevels);
        }
    }

    public record LlamaCppPropsResult(boolean signature, Integer nCtx) {

    }

    public record V1ModelsResult(boolean vllmSignature, List<String> ids, Integer contextLength,
                                 Integer contextWindow, Integer maxModelLen, Integer metaNCtx,
                                 Integer metaNCtxTrain, Integer maxContextLength) {

        public V1ModelsResult {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }

    public static OllamaTagsResult ollamaTags(JsonObject json) {
        JsonArray models = array(json, MODELS);
        boolean signature = false;
        List<String> names = new ArrayList<>();
        if (models != null) {
            for (JsonElement element : models) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject model = element.getAsJsonObject();
                if (object(model, DETAILS) != null) {
                    signature = true;
                }
                String name = firstString(model, NAME, MODEL);
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return new OllamaTagsResult(signature, names);
    }

    public static OllamaShowResult ollamaShow(JsonObject json) {
        JsonArray capabilities = array(json, CAPABILITIES);
        boolean tools = containsString(capabilities, TOOLS.key());
        boolean thinking = containsString(capabilities, THINKING.key());
        Integer training = trainingContextLength(object(json, MODEL_INFO));
        Integer numCtx = parseNumCtx(string(json, PARAMETERS));
        return new OllamaShowResult(tools, thinking, training, numCtx);
    }

    public static Integer ollamaPs(JsonObject json, String model) {
        JsonArray models = array(json, MODELS);
        if (models == null) {
            return null;
        }
        for (JsonElement element : models) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String name = firstString(entry, NAME, MODEL);
            if (sameOllamaModel(model, name)) {
                return asInt(entry.get(CONTEXT_LENGTH.key()));
            }
        }
        return null;
    }

    public static LmStudioResult lmStudioV1(JsonObject json, String model) {
        JsonArray list = array(json, MODELS);
        boolean signature = false;
        List<String> ids = new ArrayList<>();
        JsonObject match = null;
        if (list != null) {
            for (JsonElement element : list) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (has(entry, KEY) && has(entry, MAX_CONTEXT_LENGTH)) {
                    signature = true;
                }
                String id = string(entry, KEY);
                if (id != null) {
                    ids.add(id);
                    if (id.equals(model)) {
                        match = entry;
                    }
                }
            }
        }
        return lmStudioFields(signature, ids, match, true);
    }

    public static LmStudioResult lmStudioV0(JsonObject json, String model) {
        JsonArray list = array(json, DATA);
        boolean signature = false;
        List<String> ids = new ArrayList<>();
        JsonObject match = null;
        if (list != null) {
            for (JsonElement element : list) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (has(entry, STATE) && has(entry, MAX_CONTEXT_LENGTH)) {
                    signature = true;
                }
                String id = string(entry, ID);
                if (id != null) {
                    ids.add(id);
                    if (id.equals(model)) {
                        match = entry;
                    }
                }
            }
        }
        return lmStudioFields(signature, ids, match, false);
    }

    public static LlamaCppPropsResult llamaCppProps(JsonObject json) {
        JsonObject settings = object(json, DEFAULT_GENERATION_SETTINGS);
        Integer nCtx = settings == null ? null : asInt(settings.get(N_CTX.key()));
        return new LlamaCppPropsResult(nCtx != null, nCtx);
    }

    public static V1ModelsResult v1Models(JsonObject json, String model) {
        JsonArray data = array(json, DATA);
        boolean vllmSignature = false;
        List<String> ids = new ArrayList<>();
        JsonObject match = null;
        if (data != null) {
            for (JsonElement element : data) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (has(entry, MAX_MODEL_LEN)) {
                    vllmSignature = true;
                }
                String id = string(entry, ID);
                if (id != null) {
                    ids.add(id);
                    if (id.equals(model)) {
                        match = entry;
                    }
                }
            }
        }
        Integer contextLength = null;
        Integer contextWindow = null;
        Integer maxModelLen = null;
        Integer metaNCtx = null;
        Integer metaNCtxTrain = null;
        Integer maxContextLength = null;
        if (match != null) {
            contextLength = asInt(match.get(CONTEXT_LENGTH.key()));
            contextWindow = asInt(match.get(CONTEXT_WINDOW.key()));
            maxModelLen = asInt(match.get(MAX_MODEL_LEN.key()));
            maxContextLength = asInt(match.get(MAX_CONTEXT_LENGTH.key()));
            JsonObject meta = object(match, META);
            if (meta != null) {
                metaNCtx = asInt(meta.get(N_CTX.key()));
                metaNCtxTrain = asInt(meta.get(N_CTX_TRAIN.key()));
            }
        }
        return new V1ModelsResult(vllmSignature, ids, contextLength, contextWindow, maxModelLen, metaNCtx,
                                  metaNCtxTrain, maxContextLength);
    }

    private static LmStudioResult lmStudioFields(boolean signature, List<String> ids, JsonObject match, boolean v1) {
        Integer loaded = null;
        Integer max = null;
        Boolean tools = null;
        List<String> reasoningLevels = null;
        if (match != null) {
            max = asInt(match.get(MAX_CONTEXT_LENGTH.key()));
            if (v1) {
                loaded = loadedInstanceContext(match);
                JsonObject capabilities = object(match, CAPABILITIES);
                if (capabilities != null && has(capabilities, TRAINED_FOR_TOOL_USE)) {
                    tools = bool(capabilities, TRAINED_FOR_TOOL_USE);
                }
                if (capabilities != null) {
                    JsonElement reasoningElement = capabilities.get("reasoning");
                    JsonObject reasoning = reasoningElement != null && reasoningElement.isJsonObject()
                                           ? reasoningElement.getAsJsonObject() : null;
                    JsonArray allowed = reasoning == null || !reasoning.has("allowed_options")
                            || !reasoning.get("allowed_options").isJsonArray()
                                        ? null : reasoning.getAsJsonArray("allowed_options");
                    if (allowed != null) {
                        reasoningLevels = new ArrayList<>();
                        for (JsonElement option : allowed) {
                            if (option != null && option.isJsonPrimitive()) {
                                reasoningLevels.add(option.getAsString());
                            }
                        }
                    }
                }
            }
            else {
                String state = string(match, STATE);
                if (state != null && "loaded".equalsIgnoreCase(state)) {
                    loaded = asInt(match.get(LOADED_CONTEXT_LENGTH.key()));
                }
            }
        }
        return new LmStudioResult(signature, ids, loaded, max, tools, reasoningLevels);
    }

    private static Integer loadedInstanceContext(JsonObject entry) {
        JsonArray instances = array(entry, LOADED_INSTANCES);
        if (instances == null) {
            return null;
        }
        for (JsonElement element : instances) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject config = object(element.getAsJsonObject(), CONFIG);
            if (config == null) {
                continue;
            }
            Integer ctx = asInt(config.get(CONTEXT_LENGTH.key()));
            if (ctx != null) {
                return ctx;
            }
        }
        return null;
    }

    private static Integer trainingContextLength(JsonObject modelInfo) {
        if (modelInfo == null) {
            return null;
        }
        Integer found = null;
        String suffix = "." + CONTEXT_LENGTH.key();
        for (Map.Entry<String, JsonElement> entry : modelInfo.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (key.equals(CONTEXT_LENGTH.key()) || key.endsWith(suffix)) {
                Integer value = asInt(entry.getValue());
                if (value != null && (found == null || value > found)) {
                    found = value;
                }
            }
        }
        return found;
    }

    private static Integer parseNumCtx(String parameters) {
        if (parameters == null || parameters.isBlank()) {
            return null;
        }
        Matcher matcher = NUM_CTX.matcher(parameters);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    static boolean sameOllamaModel(String requested, String listed) {
        if (requested == null || listed == null) {
            return false;
        }
        return normaliseOllamaName(requested).equals(normaliseOllamaName(listed));
    }

    static String normaliseOllamaName(String name) {
        return name.indexOf(':') >= 0 ? name : name + ":latest";
    }

    private static JsonArray array(JsonObject parent, ServerFieldEnum field) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(field.key());
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static JsonObject object(JsonObject parent, ServerFieldEnum field) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(field.key());
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, ServerFieldEnum field) {
        if (parent == null) {
            return null;
        }
        return asString(parent.get(field.key()));
    }

    private static String firstString(JsonObject parent, ServerFieldEnum first, ServerFieldEnum second) {
        String value = string(parent, first);
        return value != null ? value : string(parent, second);
    }

    private static boolean has(JsonObject parent, ServerFieldEnum field) {
        if (parent == null || !parent.has(field.key())) {
            return false;
        }
        JsonElement element = parent.get(field.key());
        return element != null && !element.isJsonNull();
    }

    private static boolean containsString(JsonArray array, String value) {
        if (array == null) {
            return false;
        }
        for (JsonElement element : array) {
            String item = asString(element);
            if (value.equals(item)) {
                return true;
            }
        }
        return false;
    }

    private static Boolean bool(JsonObject parent, ServerFieldEnum field) {
        JsonElement element = parent.get(field.key());
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsBoolean();
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    private static String asString(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static Integer asInt(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
            String text = element.getAsString().trim();
            return text.isEmpty() ? null : Integer.valueOf(text);
        }
        catch (RuntimeException ex) {
            return null;
        }
    }
}
