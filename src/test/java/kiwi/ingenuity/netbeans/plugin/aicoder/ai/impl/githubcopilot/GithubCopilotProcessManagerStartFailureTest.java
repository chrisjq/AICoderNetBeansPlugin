package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.StatusEventTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.events.GithubCopilotFatalErrorEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Pins the failed-start teardown in {@code handleSessionStartFailure}: a start that dies mid-flight must release
 * everything {@code start()} had already created — dispose the AI session, deregister the MCP endpoint, cancel pending
 * permission dialogs, close client/session — before mapping the failure to events, with each step guarded so cleanup
 * cannot mask the original failure. Reverting the method to its pre-fix shape (which only mapped events) leaves the
 * stale registrar/handler in place and turns the first test red; SDK-typed fields stay null here since every teardown
 * step is individually null-guarded by design.
 */
class GithubCopilotProcessManagerStartFailureTest {

    private final List<AiProcessEvent> events = new ArrayList<>();

    private GithubCopilotProcessManager managerWithStaleState() throws Exception {
        GithubCopilotProcessManager manager = new GithubCopilotProcessManager(events::add);
        set(manager, "registrar", new GithubCopilotMcpRegistrar("ses-x"));
        set(manager, "permissionHandler", new GithubCopilotPermissionHandler(events::add, "ses-x"));
        set(manager, "running", true);
        return manager;
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        field(target.getClass(), fieldName).set(target, value);
    }

    private static Object get(Object target, String fieldName) throws Exception {
        return field(target.getClass(), fieldName).get(target);
    }

    private static Field field(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            }
            catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void startFailure(GithubCopilotProcessManager manager, String message)
            throws Exception {
        Method m = GithubCopilotProcessManager.class.getDeclaredMethod(
                "handleSessionStartFailure", Exception.class);
        m.setAccessible(true);
        m.invoke(manager, new Exception(message));
    }

    @Test
    void startFailure_releasesEverythingStartHadCreated() throws Exception {
        GithubCopilotProcessManager manager = managerWithStaleState();

        startFailure(manager, "boom");

        assertFalse((boolean) get(manager, "running"));
        assertNull(get(manager, "registrar"),
                "stale MCP registrar must be dropped (and deregistered), or it outlives the session");
        assertNull(get(manager, "permissionHandler"), "stale dialog handler must be dropped");
        assertNull(get(manager, "copilotAiSession"));
        assertNull(get(manager, "copilotSession"));
        assertNull(get(manager, "client"));
    }

    @Test
    void authenticationFailure_raisesAuthenticationRequiredFatalError() throws Exception {
        GithubCopilotProcessManager manager = managerWithStaleState();

        startFailure(manager, "Not authenticated — run copilot login");

        assertTrue(events.stream().anyMatch(e -> e instanceof GithubCopilotFatalErrorEvent
                && "AUTHENTICATION_REQUIRED".equals(((GithubCopilotFatalErrorEvent) e).errorType())),
                () -> "events were: " + events);
    }

    @Test
    void unavailableExplicitModel_fallsBackToAuto_andReportsTheFallback() throws Exception {
        GithubCopilotProcessManager manager = managerWithStaleState();
        List<String> fallbacks = new ArrayList<>();
        Consumer<String> recorder = fallbacks::add;
        set(manager, "onModelFallback", recorder);
        set(manager, "model", "grok-nonexistent");

        startFailure(manager, "model grok-nonexistent is not available for your account");

        assertEquals("auto", get(manager, "model"));
        assertEquals(List.of("auto"), fallbacks);
        assertTrue(events.stream().anyMatch(e -> e instanceof StatusEvent
                && ((StatusEvent) e).type() == StatusEventTypeEnum.INFO));
    }

    @Test
    void corruptedResume_marksSessionCorrupted_forFreshThreadIdNextStart() throws Exception {
        GithubCopilotProcessManager manager = managerWithStaleState();

        startFailure(manager, "session state could not be loaded");

        assertTrue((boolean) get(manager, "sessionCorrupted"));
    }
}
