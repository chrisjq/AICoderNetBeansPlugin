package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.rpc.SessionConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The GitHub Copilot outbound calls reach the backend debug log: a prompt is a SENT "send", a Stop is a SENT "abort",
 * and a session create logs its model and effort but never the session secret. The SDK session is detached, so each
 * call on it throws after its debug line is written; the tests tolerate that and check the log.
 */
@ResourceLock("aicoder-debuglog")
class GithubCopilotOutboundDebugLogTest {

    @Test
    @Timeout(10)
    void aPromptAndAStopAreLoggedAsSentLines(@TempDir Path dir) throws Exception {
        OutboundManager manager = new OutboundManager(newSession());
        manager.setSessionConfigDir(dir);
        manager.setBackendDebugLog(true);
        manager.seedLiveSession();

        quietly(() -> manager.sendPrompt("hello", null, List.of()));
        quietly(() -> manager.interrupt(InterruptTypeEnum.Cancel));
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains(" SENT send hello"), text);
        assertTrue(text.contains(" SENT abort session.abort"), text);
    }

    @Test
    @Timeout(10)
    void theCreateLogNeverCarriesTheSessionSecret(@TempDir Path dir) throws Exception {
        AiSession session = newSession();
        OutboundManager manager = new OutboundManager(session);
        manager.setSessionConfigDir(dir);
        manager.setBackendDebugLog(true);
        manager.seedLiveSession();

        Object mcpServers = invoke(manager, "buildMcpServers");
        try {
            invokeCreate(manager, mcpServers);
        }
        catch (InvocationTargetException expected) {
            // The create hook fails on purpose: the create line is logged before it is called.
        }
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains(" SENT session.create model=gpt-x"), text);
        assertFalse(text.contains(session.secret()), "the session secret must never reach the log: " + text);
    }

    private static AiSession newSession() {
        return new AiSession("gh-outbound", "Test", null, AiTypeEnum.GitHubCoPilot, null,
                             new GithubCopilotSessionSettings(), Instant.now(), Instant.now());
    }

    private static void quietly(Runnable call) {
        try {
            call.run();
        }
        catch (RuntimeException expected) {
            // The detached session has no CLI behind it; the debug line was written first.
        }
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = GithubCopilotProcessManager.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static void invokeCreate(GithubCopilotProcessManager manager, Object mcpServers) throws Exception {
        Method create = GithubCopilotProcessManager.class.getDeclaredMethod("createSession", CopilotClient.class,
                                                                            String.class, java.util.Map.class,
                                                                            String.class, String.class);
        create.setAccessible(true);
        create.invoke(manager, null, "gpt-x", mcpServers, "target-1", null);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            }
            catch (NoSuchFieldException notHere) {
                // Keep looking up the hierarchy.
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static CopilotSession newDetachedSession() throws Exception {
        @SuppressWarnings("unchecked")
        Constructor<CopilotSession> ctor = (Constructor<CopilotSession>) Arrays
                .stream(CopilotSession.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 3 && c.getParameterTypes()[0] == String.class)
                .findFirst().orElseThrow();
        ctor.setAccessible(true);
        return ctor.newInstance("test-session", null, null);
    }

    /**
     * Starts with a live (detached) session and a failing create hook, so no CLI is ever contacted.
     */
    private static final class OutboundManager extends GithubCopilotProcessManager {

        OutboundManager(AiSession session) {
            super(event -> {
            });
            currentSession = session;
        }

        void seedLiveSession() throws Exception {
            running = true;
            model = "gpt-x";
            set(this, "sessionId", currentSession.id());
            set(this, "copilotSession", newDetachedSession());
        }

        @Override
        CompletableFuture<CopilotSession> createSessionHook(CopilotClient client, SessionConfig config) {
            return CompletableFuture.failedFuture(new IllegalStateException("no live CLI in tests"));
        }
    }
}
