package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Fired when a {@code model/list} probe completes during thread establishment, carrying the reasoning-effort capability
 * of the session's active model. The info bar drives its effort combo from this event: {@code supportedEfforts} are the
 * selectable levels, {@code currentEffort} is the live {@code reasoningEffort} read back from {@code thread/start}
 * (used to seed the combo's selection) and {@code defaultEffort} is the model's own default. An empty
 * {@code supportedEfforts} means the server exposed no capability information — the backend then never sends an
 * {@code effort} field and the combo offers only "model default".
 */
public final class CodexReasoningEffortEvent implements AiProcessImplEvent {

    private final String model;
    private final List<String> supportedEfforts;
    private final String defaultEffort;
    private final String currentEffort;

    public CodexReasoningEffortEvent(String model, List<String> supportedEfforts, String defaultEffort,
                                     String currentEffort) {
        this.model = model;
        this.supportedEfforts = supportedEfforts == null ? List.of() : List.copyOf(supportedEfforts);
        this.defaultEffort = defaultEffort;
        this.currentEffort = currentEffort;
    }

    /**
     * Model the capability data applies to.
     */
    public String model() {
        return model;
    }

    /**
     * Supported reasoning efforts for that model, unmodifiable.
     */
    public List<String> supportedEfforts() {
        return supportedEfforts;
    }

    /**
     * The model's own default reasoning effort, or {@code null} when unknown.
     */
    public String defaultEffort() {
        return defaultEffort;
    }

    /**
     * The reasoning effort currently in effect per {@code thread/start}'s read-back, or {@code null} when the server
     * did not echo one.
     */
    public String currentEffort() {
        return currentEffort;
    }
}
