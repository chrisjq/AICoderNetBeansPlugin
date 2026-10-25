package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-session memory of which optional request fields the server has accepted, so a rejected field is never sent again
 * (spec §3.5). Thread-safe: an HTTP retry on a background thread and a settings change on the EDT may both touch it, so
 * every read and write is synchronized — this is a session decision, one instance shared across every turn of a
 * session.
 */
public final class CompatibilityFlags {

    /**
     * Field name for {@code stream_options}/{@code include_usage}.
     */
    public static final String STREAM_OPTIONS = "stream_options";
    /**
     * Field name for {@code reasoning_effort}.
     */
    public static final String REASONING_EFFORT = "reasoning_effort";
    /**
     * Field name for the JSON-schema {@code response_format}.
     */
    public static final String RESPONSE_FORMAT = "response_format";
    /**
     * Field name for the max-token field; turning it off switches to {@code max_completion_tokens}.
     */
    public static final String MAX_TOKENS = "max_tokens";

    private boolean supportsStreamUsage = true;
    private boolean supportsReasoningEffort = true;
    private boolean supportsJsonSchemaResponseFormat = true;
    private boolean usesMaxCompletionTokens = false;

    /**
     * @return true while {@code stream_options: {include_usage: true}} may be sent.
     */
    public synchronized boolean supportsStreamUsage() {
        return supportsStreamUsage;
    }

    /**
     * @return true while {@code reasoning_effort} may be sent.
     */
    public synchronized boolean supportsReasoningEffort() {
        return supportsReasoningEffort;
    }

    /**
     * @return true while a JSON-schema {@code response_format} may be sent.
     */
    public synchronized boolean supportsJsonSchemaResponseFormat() {
        return supportsJsonSchemaResponseFormat;
    }

    /**
     * @return true when the max-token field must be spelled {@code max_completion_tokens}.
     */
    public synchronized boolean usesMaxCompletionTokens() {
        return usesMaxCompletionTokens;
    }

    /**
     * Remembers that the server rejected {@code field}. Unknown names are ignored. {@code max_tokens} does not disable
     * the field, it renames it to {@code max_completion_tokens} (the newer OpenAI spelling).
     */
    public synchronized void turnOff(String field) {
        if (field == null) {
            return;
        }
        switch (field) {
            case STREAM_OPTIONS ->
                supportsStreamUsage = false;
            case REASONING_EFFORT ->
                supportsReasoningEffort = false;
            case RESPONSE_FORMAT ->
                supportsJsonSchemaResponseFormat = false;
            case MAX_TOKENS ->
                usesMaxCompletionTokens = true;
            default -> {
            }
        }
    }

    /**
     * @return the names of the optional fields this spec asks for that are still enabled — i.e. the fields the server
     * would reject if it has a blanket objection to optional parameters. Empty when the spec carries no optional
     * fields.
     */
    public synchronized Set<String> optionalFieldsPresent(ChatRequestSpec spec) {
        Set<String> present = new LinkedHashSet<>();
        if (spec == null) {
            return present;
        }
        if (supportsStreamUsage) {
            present.add(STREAM_OPTIONS);
        }
        if (spec.reasoningEffort() != null && !spec.reasoningEffort().isBlank() && supportsReasoningEffort) {
            present.add(REASONING_EFFORT);
        }
        if (spec.responseFormat() != null && supportsJsonSchemaResponseFormat) {
            present.add(RESPONSE_FORMAT);
        }
        if (spec.maxTokens() != null) {
            present.add(MAX_TOKENS);
        }
        return present;
    }

    /**
     * Turns off every droppable optional field for the blind retry: a second attempt that carries only {@code model},
     * {@code stream}, {@code messages} and {@code tools}. The max-token field is kept — it is a caller request, not a
     * capability the server can be asked to ignore — but it is renamed to {@code max_completion_tokens} for good
     * measure.
     */
    public synchronized void dropAllOptional() {
        supportsStreamUsage = false;
        supportsReasoningEffort = false;
        supportsJsonSchemaResponseFormat = false;
        usesMaxCompletionTokens = true;
    }

    /**
     * Restores the initial state — every optional field on, max-token field spelled {@code max_tokens}.
     */
    public synchronized void reset() {
        supportsStreamUsage = true;
        supportsReasoningEffort = true;
        supportsJsonSchemaResponseFormat = true;
        usesMaxCompletionTokens = false;
    }
}
