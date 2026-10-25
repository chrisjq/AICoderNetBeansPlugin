package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.turn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects repeated tool calls (same call + same result, or an A,B,A,B key pattern) and unproductive rounds.
 */
public final class RepetitionTracker {

    public enum Verdict {
        OK,
        REPEATED
    }

    private static final String LIST_BUILDS = "ListBuilds";
    private static final String REPEATED_TEXT
            = "You already called %s with these arguments and got the same result. Use that result or try something different.";
    private static final String LIST_BUILDS_TEXT
            = "The build is still running. Its result will arrive as a message when it finishes — stop polling and give your answer now.";

    private final int window;
    private final int unproductiveLimit;
    private final ArrayDeque<Call> recent = new ArrayDeque<>();
    private int unproductiveStreak;

    public RepetitionTracker(int window, int unproductiveLimit) {
        this.window = window;
        this.unproductiveLimit = unproductiveLimit;
    }

    /**
     * Clears recent-call memory after a mutation makes earlier observations stale.
     */
    public void clear() {
        recent.clear();
    }

    public Verdict record(String toolName, JsonObject coercedArgs, String result) {
        String key = callKey(toolName, coercedArgs);
        String normalisedResult = result == null ? "" : result;
        boolean sameCallSameResult = false;
        for (Call call : recent) {
            if (call.key.equals(key) && call.result.equals(normalisedResult)) {
                sameCallSameResult = true;
                break;
            }
        }
        boolean pingPong = isPingPong(key);
        recent.addLast(new Call(key, normalisedResult));
        while (recent.size() > window) {
            recent.removeFirst();
        }
        return sameCallSameResult || pingPong ? Verdict.REPEATED : Verdict.OK;
    }

    /**
     * Model-facing replacement text when {@link #record} returns {@link Verdict#REPEATED}.
     */
    public String repeatedMessage(String toolName) {
        if (LIST_BUILDS.equals(toolName)) {
            return LIST_BUILDS_TEXT;
        }
        return String.format(REPEATED_TEXT, toolName);
    }

    /**
     * @param anyNewResult true when this round produced a result not already seen this turn
     *
     * @return true when {@code unproductiveLimit} consecutive rounds were unproductive
     */
    public boolean endRound(boolean anyNewResult) {
        if (anyNewResult) {
            unproductiveStreak = 0;
            return false;
        }
        unproductiveStreak++;
        return unproductiveStreak >= unproductiveLimit;
    }

    private boolean isPingPong(String currentKey) {
        if (recent.size() < 3) {
            return false;
        }
        Call[] last = recent.toArray(Call[]::new);
        int n = last.length;
        String a = last[n - 3].key;
        String b = last[n - 2].key;
        String aAgain = last[n - 1].key;
        return a.equals(aAgain) && b.equals(currentKey) && !a.equals(b);
    }

    static String callKey(String toolName, JsonObject coercedArgs) {
        return (toolName == null ? "" : toolName) + canonical(coercedArgs == null ? new JsonObject() : coercedArgs);
    }

    private static String canonical(JsonElement element) {
        return sort(element).toString();
    }

    private static JsonElement sort(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray sorted = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                sorted.add(sort(child));
            }
            return sorted;
        }
        JsonObject object = element.getAsJsonObject();
        List<String> keys = new ArrayList<>(object.keySet());
        Collections.sort(keys);
        JsonObject sorted = new JsonObject();
        for (String key : keys) {
            sorted.add(key, sort(object.get(key)));
        }
        return sorted;
    }

    private record Call(String key, String result) {

    }
}
