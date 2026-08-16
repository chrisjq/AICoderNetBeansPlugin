package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired when a {@code usage_update} session/update notification arrives from
 * OpenCode ACP. Carries the token counts that drive the context gauge.
 */
public final class OpenCodeUsageEvent implements AiProcessImplEvent {

    private final int used;
    private final int size;

    public OpenCodeUsageEvent(int used, int size) {
        this.used = used;
        this.size = size;
    }

    public int used() {
        return used;
    }

    public int size() {
        return size;
    }
}
