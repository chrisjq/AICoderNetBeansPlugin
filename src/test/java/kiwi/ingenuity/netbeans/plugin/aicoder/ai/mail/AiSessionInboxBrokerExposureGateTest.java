package kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A session whose peer tools are hidden cannot read or answer mail, so the broker must not list it or let others
 * message it — whichever layer (the comms setting, or the Peer messaging MCP group) switched the tools off. Likewise
 * for idle watchers.
 */
class AiSessionInboxBrokerExposureGateTest {

    private AiSessionInboxBroker broker;
    private final List<String> registered = new java.util.ArrayList<>();

    private AiSession session(String id, McpToolConfig mcpConfig, boolean comms, boolean idleTimer) {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowInterAiComms(comms);
        settings.setAllowIdleWatcherTimer(idleTimer);
        if (mcpConfig != null) {
            settings.setMcpToolConfig(mcpConfig);
        }
        AiSession session = new AiSession(id, "Name-" + id, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(),
                                          Instant.now());
        SessionRegistry.register(new AbstractAiSession(session) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Map getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        });
        registered.add(id);
        broker.register(session);
        return session;
    }

    @BeforeEach
    void setUp() {
        broker = new AiSessionInboxBroker();
    }

    @AfterEach
    void tearDown() {
        registered.forEach(SessionRegistry::unregister);
    }

    @Test
    void aSessionWithEverythingOnIsListedAndMessageable() {
        AiSession caller = session("gate-caller-a", McpToolConfig.ALL_ENABLED, true, true);
        AiSession target = session("gate-target-a", McpToolConfig.ALL_ENABLED, true, true);

        assertTrue(target.allowsInterAiComms());
        assertTrue(broker.isInterAiCommsAllowed(target.id()));
        assertEquals(List.of(target), broker.listActive(caller.id()));
    }

    @Test
    void thePeerGroupSwitchedOffHidesTheSessionFromPeersEvenWithTheCommsSettingOn() {
        AiSession caller = session("gate-caller-b", McpToolConfig.ALL_ENABLED, true, true);
        AiSession target = session("gate-target-b",
                                   McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.PEER_MESSAGING)), true, true);
        assertTrue(target.settings().effectiveAllowInterAiComms(), "the behaviour setting itself is still on");

        assertFalse(target.allowsInterAiComms());
        assertFalse(broker.isInterAiCommsAllowed(target.id()));
        assertTrue(broker.listActive(caller.id()).isEmpty(), "it cannot read mail, so it must not be offered mail");
    }

    @Test
    void theCommsSettingOffStillHidesTheSessionWithTheGroupOn() {
        AiSession caller = session("gate-caller-c", McpToolConfig.ALL_ENABLED, true, true);
        AiSession target = session("gate-target-c", McpToolConfig.ALL_ENABLED, false, true);

        assertFalse(target.allowsInterAiComms());
        assertTrue(broker.listActive(caller.id()).isEmpty());
    }

    @Test
    void thePeerGroupDoesNotAffectTheIdleWatcherAnswerAndViceVersa() {
        AiSession peersOffIdleOn = session("gate-d1",
                                           McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.PEER_MESSAGING)), true, true);
        AiSession idleOffPeersOn = session("gate-d2",
                                           McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.IDLE_WATCHERS)), true, true);

        assertFalse(peersOffIdleOn.allowsInterAiComms());
        assertTrue(peersOffIdleOn.allowsIdleWatcherTimer());
        assertTrue(idleOffPeersOn.allowsInterAiComms());
        assertFalse(idleOffPeersOn.allowsIdleWatcherTimer());
    }

    @Test
    void theIdleWatcherTimerSettingOffAloneAlsoTurnsTheCombinedAnswerOff() {
        AiSession session = session("gate-e", McpToolConfig.ALL_ENABLED, true, false);

        assertFalse(session.allowsIdleWatcherTimer());
    }

    @Test
    void aSessionWithNoMcpConfigYetFollowsTheCommsSettingAlone() {
        AiSession on = session("gate-f1", null, true, true);
        AiSession off = session("gate-f2", null, false, true);

        assertTrue(on.allowsInterAiComms(), "no own MCP config: derived from the same settings, peers not disabled");
        assertFalse(off.allowsInterAiComms());
    }
}
