package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;

public record PiModelsEvent(List<String> models) implements AiPropertyEvent {

}
