package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.extension;

import java.util.Set;

/**
 * Decides which plugin MCP tools a pi session's generated extension may see. Today excludes none; this is the single
 * place to exclude tools later, per the spec's *Architecture* unit table.
 */
public final class PiToolExposurePolicy {

    public static Set<String> excludedTools() {
        return Set.of();
    }

    private PiToolExposurePolicy() {
    }
}
