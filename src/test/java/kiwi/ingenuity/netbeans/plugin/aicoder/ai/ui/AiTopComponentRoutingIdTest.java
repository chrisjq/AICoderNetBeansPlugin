package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * A session about to start must hold an MCP routing id that no other live session of its type owns: the MCP server
 * resolves a session endpoint by that id, so a shared one would serve a session another session's tools.
 */
class AiTopComponentRoutingIdTest {

    private static AiSession session(String id, AiTypeEnum type, String routingId) {
        AiSessionSettings settings = new AiSessionSettings();
        if (routingId != null) {
            settings.setMcpRoutingId(routingId);
        }
        return new AiSession(id, id, null, type, null, settings, Instant.now(), Instant.now());
    }

    private static AbstractAiSession wrapper(AiSession session) {
        return new AbstractAiSession(session) {
            @Override
            public String getId() {
                return session.id();
            }

            @Override
            public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        };
    }

    @Test
    void aSessionWithNoRoutingIdGetsOneAndMustBeSaved() {
        AiSession mine = session("routing-a", AiTypeEnum.CLAUDE, null);

        boolean changed = AiTopComponent.claimUniqueMcpRoutingId(mine, List.of());

        assertTrue(changed, "a newly generated id has to be saved or it changes again on the next IDE start");
        assertNotNull(mine.settings().mcpRoutingId());
    }

    @Test
    void anExistingUniqueIdIsLeftAloneAndNeedsNoSave() {
        AiSession mine = session("routing-b", AiTypeEnum.CLAUDE, "route-mine");
        AiSession other = session("routing-b-other", AiTypeEnum.CLAUDE, "route-other");

        boolean changed = AiTopComponent.claimUniqueMcpRoutingId(mine, List.of(wrapper(other)));

        assertFalse(changed);
        assertEquals("route-mine", mine.settings().mcpRoutingId());
    }

    @Test
    void anIdAnotherLiveSessionOfTheSameTypeOwnsIsReplacedWithAFreshOne() {
        AiSession mine = session("routing-c", AiTypeEnum.CLAUDE, "route-shared");
        AiSession other = session("routing-c-other", AiTypeEnum.CLAUDE, "route-shared");

        boolean changed = AiTopComponent.claimUniqueMcpRoutingId(mine, List.of(wrapper(other)));

        assertTrue(changed);
        assertNotEquals("route-shared", mine.settings().mcpRoutingId());
        assertEquals("route-shared", other.settings().mcpRoutingId(), "the session already running keeps its address");
    }

    @Test
    void theSameIdOnADifferentTypeIsNotAClash() {
        AiSession mine = session("routing-d", AiTypeEnum.CLAUDE, "route-shared");
        AiSession other = session("routing-d-other", AiTypeEnum.CODEX, "route-shared");

        boolean changed = AiTopComponent.claimUniqueMcpRoutingId(mine, List.of(wrapper(other)));

        assertFalse(changed, "endpoints are /mcp/<type>/<id>, so different types never collide");
        assertEquals("route-shared", mine.settings().mcpRoutingId());
    }

    @Test
    void theSessionsOwnRegistryEntriesDoNotCountAsAnotherSession() {
        AiSession mine = session("routing-e", AiTypeEnum.CLAUDE, "route-mine");

        boolean changed = AiTopComponent.claimUniqueMcpRoutingId(mine, List.of(wrapper(mine), wrapper(mine)));

        assertFalse(changed, "a restart registers a new wrapper (and aliases) around the same session");
        assertEquals("route-mine", mine.settings().mcpRoutingId());
    }
}
