package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context;

/**
 * The pinned slots, declared in the order they are rendered into the leading
 * system message. The order is fixed so every run produces a byte-identical
 * prefix, which is what keeps server-side prefix caching effective.
 */
public enum PinSlotEnum {
    IDENTITY,
    BASELINE,
    INSTRUCTIONS,
    TOOLS
}
