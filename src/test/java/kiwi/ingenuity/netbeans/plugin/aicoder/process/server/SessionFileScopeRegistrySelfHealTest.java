package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #15/#21: a session whose scope was lost (health-tick server replacement, or any other reason) must recover it from
 * the currently open projects on the very call that would otherwise be refused, rather than waiting for its next submit
 * or a project open/close. {@link SessionFileScopeRegistry#selfHealScope} is the recovery; {@code isFileAllowed} and
 * {@code isWithinProjectDirs} both attempt it before consulting their maps.
 * <p>
 * Uses a session with {@code restrictToProjectFiles=false} so the recovered scope is provably in effect without
 * depending on a real open project — {@code OpenProjects.getDefault().getOpenProjects()} is empty in this headless test
 * harness, so an unrestricted session is the only shape that can be asserted "allowed" here; a restricted one would
 * still (correctly) deny with zero project dirs, which would prove nothing about self-heal specifically.
 */
class SessionFileScopeRegistrySelfHealTest {

    private SessionFileScopeRegistry registry;
    private String sessionId;
    private FakeSession session;

    @BeforeEach
    void setUp() {
        registry = new SessionFileScopeRegistry();
        sessionId = "selfheal-" + UUID.randomUUID();
        session = new FakeSession(sessionId, AiTypeEnum.CLAUDE);
        SessionRegistry.register(session);
    }

    @AfterEach
    void tearDown() {
        SessionRegistry.unregister(sessionId);
    }

    @Test
    void selfHealRegistersScopeForKnownSession() {
        session.getAiSession().settings().setRestrictToProjectFiles(false);
        assertFalse(registry.hasScope(sessionId));

        assertTrue(registry.selfHealScope(sessionId), "a live, registered session must be recoverable");

        assertTrue(registry.hasScope(sessionId), "self-heal must leave a real scope entry behind");
        assertTrue(registry.isUnrestrictedFileAccess(sessionId),
                   "the recovered scope must match the session's own live settings");
    }

    @Test
    void isFileAllowedSelfHealsAndRetriesOnceInTheSameCall() {
        session.getAiSession().settings().setRestrictToProjectFiles(false);
        assertFalse(registry.hasScope(sessionId), "fixture sanity: no scope registered yet");

        boolean allowed = registry.isFileAllowed(sessionId, "/some/arbitrary/path.txt");

        assertTrue(allowed,
                   "an unrestricted known session must be allowed on the FIRST call, healed and retried inline");
        assertTrue(registry.hasScope(sessionId), "the inline retry must have registered a scope entry");
    }

    @Test
    void isWithinProjectDirsAlsoSelfHeals() {
        // The native Claude Edit/Write hook calls isWithinProjectDirs directly rather than through isFileAllowed
        // (see McpHookServer's hook dispatch), so it needs its own self-heal attempt.
        session.getAiSession().settings().setRestrictToProjectFiles(false);
        assertFalse(registry.hasScope(sessionId));

        registry.isWithinProjectDirs(sessionId, "/some/arbitrary/path.txt");

        assertTrue(registry.hasScope(sessionId), "isWithinProjectDirs must also trigger self-heal");
    }

    @Test
    void selfHealDoesNothingForUnknownSession() {
        String unknown = "no-such-session-" + UUID.randomUUID();
        assertFalse(registry.hasScope(unknown));

        assertFalse(registry.selfHealScope(unknown), "an id with no live AbstractAiSession must not be healed");

        assertFalse(registry.hasScope(unknown), "nothing must be invented for an unknown id");
    }

    @Test
    void selfHealIsANoOpWhenScopeAlreadyPresent() {
        registry.registerScope(sessionId, AiTypeEnum.CLAUDE, List.of(), true);

        assertFalse(registry.selfHealScope(sessionId), "must not re-run once a scope entry already exists");
    }

    private static final class FakeSession extends AbstractAiSession {

        private final String id;

        FakeSession(String id, AiTypeEnum type) {
            super(AiSession.create(null, type));
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }
}
