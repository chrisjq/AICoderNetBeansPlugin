package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

/**
 * Fired when the list of available OpenCode models becomes known from the ACP
 * {@code session/new} or {@code session/resume} response. Consumers (e.g. the
 * create-dialog model picker) may register on
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus} for type
 * {@code OPENCODE} to receive this event and refresh their combo.
 */
public class OpenCodeModelsEvent implements AiPropertyEvent {

    private final List<String> models;

    public OpenCodeModelsEvent(List<String> models) {
        this.models = models;
    }

    public List<String> models() {
        return models;
    }
}
