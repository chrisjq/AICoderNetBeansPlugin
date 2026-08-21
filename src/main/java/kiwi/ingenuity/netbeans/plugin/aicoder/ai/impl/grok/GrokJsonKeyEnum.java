package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

/**
 * Field names probed when parsing the grok CLI's {@code --output-format json}
 * response. Empirically confirmed against a live grok CLI (v0.2.93): the real
 * response shape is {@code {text, stopReason, sessionId, requestId, thought}}
 * with no usage/token data anywhere in it (usage is instead read from the
 * on-disk {@code signals.json} file — see {@link GrokUsageSignalsReader}).
 * RESULT/RESPONSE/CONTENT/MESSAGE are kept only as defensive fallbacks in case
 * a future grok CLI version renames the {@code text} field.
 * <p>
 * Also holds the field names of the per-session {@code signals.json} file the
 * grok CLI rewrites after every turn (see {@link GrokUsageSignalsReader}).
 */
public enum GrokJsonKeyEnum {
    // --output-format json response fields
    RESULT("result"),
    RESPONSE("response"),
    TEXT("text"),
    CONTENT("content"),
    MESSAGE("message"),
    ERROR("error"),
    SESSION_ID("sessionId"),
    STOP_REASON("stopReason"),
    // ~/.grok/sessions/<cwd>/<sessionId>/signals.json fields
    CONTEXT_TOKENS_USED("contextTokensUsed"),
    CONTEXT_WINDOW_TOKENS("contextWindowTokens"),
    PRIMARY_MODEL_ID("primaryModelId");

    private final String key;

    GrokJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
