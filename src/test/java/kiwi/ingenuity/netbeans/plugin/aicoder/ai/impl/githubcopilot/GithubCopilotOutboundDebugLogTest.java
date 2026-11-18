package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotCompaction;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotEventSink;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotMcpServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotQuotaSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkClient;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSdkSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk.CopilotSessionSpec;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.InterruptTypeEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The GitHub Copilot outbound calls reach the backend debug log: a prompt is a SENT "send", a Stop is a SENT "abort",
 * and a session create logs its model and effort but never the session secret. The session is detached, so each call on
 * it fails after its debug line is written; the tests tolerate that and check the log.
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
    @SuppressWarnings("unchecked")
    void theCreateLogNeverCarriesTheSessionSecret(@TempDir Path dir) throws Exception {
        AiSession session = newSession();
        OutboundManager manager = new OutboundManager(session);
        manager.setSessionConfigDir(dir);
        manager.setBackendDebugLog(true);
        manager.seedLiveSession();
        FakeClient client = new FakeClient();

        Map<String, CopilotMcpServer> mcpServers = (Map<String, CopilotMcpServer>) buildMcpServers(manager);
        try {
            invokeOpenSession(manager, client, "gpt-x");
        }
        catch (InvocationTargetException expected) {
            // The create fails on purpose: the create line is logged before the client is called.
        }
        manager.closeBackendDebugLog(false).get();

        String text = Files.readString(dir.resolve("backend-debug.log"));
        assertTrue(text.contains(" SENT session.create model=gpt-x"), text);
        assertFalse(text.contains(session.secret()), "the session secret must never reach the log: " + text);
        CopilotSessionSpec spec = client.createdSpecs.get(0);
        assertEquals("gpt-x", spec.model());
        assertEquals(mcpServers, spec.mcpServers());
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

    /**
     * The manager's private {@code buildMcpServers}, with its notices raised at once.
     */
    private static Object buildMcpServers(GithubCopilotProcessManager manager) throws Exception {
        Method method = GithubCopilotProcessManager.class.getDeclaredMethod("buildMcpServers", Consumer.class);
        method.setAccessible(true);
        Consumer<Runnable> immediately = Runnable::run;
        return method.invoke(manager, immediately);
    }

    /**
     * Plans a session for {@code model} and opens it on {@code client}, as {@code start()} does.
     */
    private static void invokeOpenSession(GithubCopilotProcessManager manager, CopilotSdkClient client, String model)
            throws Exception {
        Method plan = GithubCopilotProcessManager.class.getDeclaredMethod("planSession", String.class);
        plan.setAccessible(true);
        Object sessionPlan = plan.invoke(manager, model);
        Method open = GithubCopilotProcessManager.class.getDeclaredMethod("openSession", CopilotSdkClient.class,
                                                                          sessionPlan.getClass());
        open.setAccessible(true);
        open.invoke(manager, client, sessionPlan);
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

    /**
     * A session with no CLI behind it. Every call fails after the caller's debug line is written, so nothing is ever
     * sent anywhere.
     */
    private static final class DetachedSession implements CopilotSdkSession {

        @Override
        public String sessionId() {
            return "test-session";
        }

        @Override
        public CompletableFuture<String> send(String prompt) {
            return CompletableFuture.failedFuture(new IllegalStateException("no live CLI in tests"));
        }

        @Override
        public CompletableFuture<String> sendImmediate(String prompt) {
            return CompletableFuture.failedFuture(new IllegalStateException("no live CLI in tests"));
        }

        @Override
        public CompletableFuture<Void> abort() {
            return CompletableFuture.failedFuture(new IllegalStateException("no live CLI in tests"));
        }

        @Override
        public CompletableFuture<CopilotCompaction> compact(String customInstructions) {
            return CompletableFuture.failedFuture(new IllegalStateException("no live CLI in tests"));
        }

        @Override
        public void abortManualCompaction() {
        }

        @Override
        public AutoCloseable subscribe(CopilotEventSink sink) {
            return () -> {
            };
        }

        @Override
        public void close() {
        }
    }

    /**
     * A client with no stored sessions that fails every create, and records each spec it was asked to create so the
     * test can check what the create was given.
     */
    private static final class FakeClient implements CopilotSdkClient {

        final List<CopilotSessionSpec> createdSpecs = new ArrayList<>();

        @Override
        public CompletableFuture<List<String>> listSessionIds() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<CopilotSdkSession> create(CopilotSessionSpec spec) {
            createdSpecs.add(spec);
            return CompletableFuture.failedFuture(new IllegalStateException("no live CLI in tests"));
        }

        @Override
        public CompletableFuture<CopilotSdkSession> resume(String sessionId, CopilotSessionSpec spec) {
            return CompletableFuture.failedFuture(new IllegalStateException("no live CLI in tests"));
        }

        @Override
        public CompletableFuture<List<CopilotModel>> listModels() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Map<String, CopilotQuotaSnapshot>> quotaSnapshots() {
            return CompletableFuture.completedFuture(Map.of());
        }

        @Override
        public void close() {
        }
    }

    /**
     * Starts with a live (detached) session, so no CLI is ever contacted.
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
            set(this, "copilotSession", new DetachedSession());
        }
    }
}
