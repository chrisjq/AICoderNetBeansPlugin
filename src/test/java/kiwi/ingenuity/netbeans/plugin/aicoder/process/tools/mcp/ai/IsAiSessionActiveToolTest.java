package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.SessionRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * D4 (Stage 2 follow-up): PeerSessionIsActive must resolve a session NAME the same way PeerMessageSend and
 * PeerIdleWatcherCreate do, instead of rejecting it with "is not open" because it is not an id — reuses
 * {@link IdleWatcherTools#resolveTargetSessionId}.
 */
class IsAiSessionActiveToolTest {

    private final List<String> registeredSessionIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String id : registeredSessionIds) {
            AiSessionInboxBroker.getInstance().unregister(id);
            SessionRegistry.unregister(id);
        }
    }

    private AbstractAiSession session(String id, String name) {
        AiSessionSettings settings = new AiSessionSettings();
        AiSession aiSession = new AiSession(id, name, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(), Instant.now());
        AbstractAiSession wrapper = new AbstractAiSession(aiSession) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getSessionName() {
                return name;
            }

            @Override
            public Map getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        };
        SessionRegistry.register(wrapper);
        AiSessionInboxBroker.getInstance().register(aiSession);
        registeredSessionIds.add(id);
        return wrapper;
    }

    private static ToolRequestArguments args(String targetSessionId) {
        JsonObject o = new JsonObject();
        o.addProperty(IsAiSessionActiveParamEnum.TARGET_SESSION_ID.key(), targetSessionId);
        return new ToolRequestArguments(o);
    }

    @Test
    void byIdStillWorks() {
        AbstractAiSession target = session("peer-active-tool-byid", "ById");

        String result = new IsAiSessionActiveTool().handle(args(target.getId()), target);

        assertFalse(result.startsWith("Session") && result.contains("is not open"), result);
        assertTrue(result.contains("is open"), result);
    }

    @Test
    void byUniqueSessionName_resolvesToTheTargetId() {
        AbstractAiSession target = session("peer-active-tool-byname-target", "BigP_2");

        String result = new IsAiSessionActiveTool().handle(args("BigP_2"), target);

        assertTrue(result.contains("peer-active-tool-byname-target"),
                "must report on the resolved id, not the literal name: " + result);
        assertTrue(result.contains("is open"), result);
    }

    @Test
    void byAmbiguousSessionName_isRefusedListingTheMatches() {
        AiSessionSettings settings = new AiSessionSettings();
        AiSession dup1 = new AiSession("peer-active-tool-ambiguous-1", "SameName", null, AiTypeEnum.CLAUDE, null,
                settings, Instant.now(), Instant.now());
        AiSession dup2 = new AiSession("peer-active-tool-ambiguous-2", "SameName", null, AiTypeEnum.CLAUDE, null,
                settings, Instant.now(), Instant.now());
        registerDuplicate(dup1);
        registerDuplicate(dup2);

        String result = new IsAiSessionActiveTool().handle(args("SameName"), null);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("ambiguous"), result);
        assertTrue(result.contains(dup1.id()) && result.contains(dup2.id()), result);
    }

    private void registerDuplicate(AiSession aiSession) {
        AbstractAiSession wrapper = new AbstractAiSession(aiSession) {
            @Override
            public String getId() {
                return aiSession.id();
            }

            @Override
            public String getSessionName() {
                return aiSession.name();
            }

            @Override
            public Map getMcpToolHandlers() {
                return Map.of();
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }
        };
        SessionRegistry.register(wrapper);
        registeredSessionIds.add(aiSession.id());
        AiSessionInboxBroker.getInstance().register(aiSession);
    }

    @Test
    void unknownNameIsRefusedAsBeforeTheFix() {
        String result = new IsAiSessionActiveTool().handle(args("NoSuchSessionAtAll"), null);

        assertTrue(result.contains("is not open"), result);
    }
}
