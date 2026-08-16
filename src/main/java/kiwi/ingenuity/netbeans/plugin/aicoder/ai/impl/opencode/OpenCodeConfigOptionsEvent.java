package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode;

import com.google.gson.JsonArray;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired once after the ACP {@code session/new} handshake completes, carrying
 * the initial {@code configOptions} snapshot. Consumed by
 * {@code OpenCodeAiInfoBarExtension} to populate the combo boxes.
 */
public final class OpenCodeConfigOptionsEvent implements AiProcessImplEvent {

    private final JsonArray configOptions;

    public OpenCodeConfigOptionsEvent(JsonArray configOptions) {
        this.configOptions = configOptions;
    }

    public JsonArray configOptions() {
        return configOptions;
    }
}
