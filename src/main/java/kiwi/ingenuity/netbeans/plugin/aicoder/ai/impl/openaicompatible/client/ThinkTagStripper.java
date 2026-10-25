package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

/**
 * Stream-aware {@code <think>} / {@code </think>} stripper. Tags may be split across {@link #feed} calls; unclosed
 * think text stays in {@link #reasoning()} after {@link #flush()}.
 */
public final class ThinkTagStripper {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private int depth;

    /**
     * @return the visible (non-think) portion of this delta; incomplete tag prefixes are held back
     */
    public String feed(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        StringBuilder visible = new StringBuilder();
        for (int i = 0; i < delta.length(); i++) {
            pending.append(delta.charAt(i));
            drain(visible);
        }
        return visible.toString();
    }

    /**
     * Emits any held visible characters. An unclosed {@code <think>} keeps its text as reasoning.
     */
    public String flush() {
        if (depth > 0) {
            reasoning.append(pending);
            pending.setLength(0);
            return "";
        }
        String leftover = pending.toString();
        pending.setLength(0);
        return leftover;
    }

    public String reasoning() {
        return reasoning.toString();
    }

    private void drain(StringBuilder visible) {
        while (pending.length() > 0) {
            String held = pending.toString();
            if (depth > 0) {
                if (held.equals(CLOSE)) {
                    depth--;
                    pending.setLength(0);
                    return;
                }
                if (held.equals(OPEN)) {
                    depth++;
                    pending.setLength(0);
                    return;
                }
                if (CLOSE.startsWith(held) || OPEN.startsWith(held)) {
                    return;
                }
                reasoning.append(pending.charAt(0));
                pending.deleteCharAt(0);
            }
            else {
                if (held.equals(OPEN)) {
                    depth = 1;
                    pending.setLength(0);
                    return;
                }
                if (OPEN.startsWith(held)) {
                    return;
                }
                visible.append(pending.charAt(0));
                pending.deleteCharAt(0);
            }
        }
    }
}
