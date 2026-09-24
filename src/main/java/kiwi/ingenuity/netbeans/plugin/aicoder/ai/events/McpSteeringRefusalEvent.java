package kiwi.ingenuity.netbeans.plugin.aicoder.ai.events;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;

/**
 * Records native tool calls that MCP steering denied automatically when the backend's refusal protocol cannot
 * carry the steering text back to the agent.
 *
 * <p>
 * The user sees none of this event. It is consumed by the UI after the turn ends and delivered only in an
 * agent-only follow-up turn, so the agent learns which MCP tool to use without presenting an automatic policy
 * decision as a user rejection.
 */
public record McpSteeringRefusalEvent(List<Refusal> refusals) implements AiProcessEvent {

    /**
     * One automatically denied native tool call and the steering text naming the replacement MCP tool(s).
     */
    public record Refusal(String toolLabel, String steeringText) {

    }

    public McpSteeringRefusalEvent {
        refusals = List.copyOf(refusals);
    }

    /**
     * Composes one line per refusal followed by a single continuation instruction for the agent-only
     * follow-up turn.
     *
     * @return null when refusals is null or empty
     */
    public static String compose(List<Refusal> refusals) {
        if (refusals == null || refusals.isEmpty()) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (Refusal refusal : refusals) {
            result.append("The native tool call for ")
                    .append(refusal.toolLabel())
                    .append(" was automatically denied: ")
                    .append(refusal.steeringText())
                    .append('\n');
        }
        return result.append("Continue the work using the named MCP tools provided by this IDE.").toString();
    }
}
