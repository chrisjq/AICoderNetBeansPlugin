package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

/**
 * Field names probed when parsing the grok CLI's {@code --output-format json}
 * response. Empirically confirmed against a live grok CLI (v0.2.93): the real
 * response shape is {@code {text, stopReason, sessionId, requestId, thought}}
 * with no usage/token data anywhere in it (usage is instead read from the
 * on-disk {@code signals.json} file — see {@link GrokUsageSignalsReader}).
 * RESULT/RESPONSE/CONTENT/MESSAGE are kept only as defensive fallbacks in case
 * a future grok CLI version renames the {@code text} field.
 */
public enum GrokJsonKeyEnum {
    RESULT("result"),
    RESPONSE("response"),
    TEXT("text"),
    CONTENT("content"),
    MESSAGE("message"),
    ERROR("error"),
    SESSION_ID("sessionId"),
    STOP_REASON("stopReason");

    private final String key;

    GrokJsonKeyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
