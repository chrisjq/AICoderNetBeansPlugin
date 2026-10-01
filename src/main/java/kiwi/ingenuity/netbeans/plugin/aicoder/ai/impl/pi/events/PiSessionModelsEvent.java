package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * The models this session's running pi process reported via {@code get_available_models}, each formatted
 * {@code "provider/id"}. Per-session: it comes from one process, so it reaches only that session's info bar.
 */
public record PiSessionModelsEvent(List<String> models) implements AiProcessImplEvent {

    public PiSessionModelsEvent {
        models = List.copyOf(models);
    }
}
