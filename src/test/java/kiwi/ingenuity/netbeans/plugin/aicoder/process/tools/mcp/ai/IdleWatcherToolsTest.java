package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcherRegistry;
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
 * Drives CreateIdleWatcherTool/CancelIdleWatcherTool/ListIdleWatchersTool end to end against the real {@link
 * IdleWatcherRegistry#getInstance()} singleton and a real {@link SessionRegistry} registration, the way {@code
 * BuildSubmitterTest} drives the real {@code BuildQueue}. Every session this test registers and every watcher it
 * creates is torn down in {@link #tearDown()} so no clock is left running on the singleton's real scheduler thread
 * after the suite moves on.
 */
class IdleWatcherToolsTest {

    private static final String DISABLED
            = "Error: Idle AI watcher timers are disabled for this session. Enable 'Allow Idle AI Watcher Timer?' in"
            + " this session's configuration.";

    private final List<String> registeredSessionIds = new ArrayList<>();
    private final List<String[]> createdWatchers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String[] watcher : createdWatchers) {
            IdleWatcherRegistry.getInstance().cancel(watcher[0], watcher[1]);
        }
        for (String id : registeredSessionIds) {
            SessionRegistry.unregister(id);
        }
    }

    private AbstractAiSession session(String id, boolean allowIdleWatcherTimer) {
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowIdleWatcherTimer(allowIdleWatcherTimer);
        AiSession aiSession = new AiSession(id, "Name-" + id, null, AiTypeEnum.CLAUDE, null, settings, Instant.now(),
                                            Instant.now());
        AbstractAiSession wrapper = new AbstractAiSession(aiSession) {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getSessionName() {
                return "Name-" + id;
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
        registeredSessionIds.add(id);
        return wrapper;
    }

    private void rememberLatestWatcher(String watcherSessionId) {
        List<kiwi.ingenuity.netbeans.plugin.aicoder.ai.idlewatch.IdleWatcherStatus> statuses
                = IdleWatcherRegistry.getInstance().list(watcherSessionId);
        String id = statuses.get(statuses.size() - 1).watcher().id();
        createdWatchers.add(new String[]{watcherSessionId, id});
    }

    private static ToolRequestArguments args(String... values) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            object.addProperty(values[i], values[i + 1]);
        }
        return new ToolRequestArguments(object);
    }

    private static String createWithTimeout(AbstractAiSession watcher, AbstractAiSession target, Object timeoutValue) {
        JsonObject o = new JsonObject();
        o.addProperty(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId());
        if (timeoutValue instanceof Number n) {
            o.addProperty(CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key(), n);
        }
        else if (timeoutValue instanceof Boolean b) {
            o.addProperty(CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key(), b);
        }
        else {
            o.addProperty(CreateIdleWatcherParamEnum.TIMEOUT_MINUTES.key(), String.valueOf(timeoutValue));
        }
        return new CreateIdleWatcherTool().handle(new ToolRequestArguments(o), watcher);
    }

    @Test
    void disabledSettingRefusesAllThreeTools() {
        AbstractAiSession watcher = session("idlewatch-tool-disabled-watcher", false);
        AbstractAiSession target = session("idlewatch-tool-disabled-target", true);

        assertEquals(DISABLED, new CreateIdleWatcherTool().handle(
                     args(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId()), watcher));
        assertEquals(DISABLED, new CancelIdleWatcherTool().handle(
                     args(CancelIdleWatcherParamEnum.WATCHER_ID.key(), "idle-watch-1"), watcher));
        assertEquals(DISABLED, new ListIdleWatchersTool().handle(args(), watcher));
    }

    @Test
    void createWithDefaultsIsOneshotFiveMinutes() {
        AbstractAiSession watcher = session("idlewatch-tool-defaults-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-defaults-target", true);

        String result = new CreateIdleWatcherTool().handle(
                args(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId()), watcher);

        assertFalse(result.startsWith("Error:"), result);
        assertTrue(result.contains("oneshot, 5 minutes"), result);
        rememberLatestWatcher(watcher.getId());
    }

    @Test
    void timeoutMinutesBelowTheTwoMinuteMinimumIsRefusedNamingTheMinimum() {
        AbstractAiSession watcher = session("idlewatch-tool-min-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-min-target", true);

        String result = createWithTimeout(watcher, target, 1);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("2"), result);
        assertTrue(result.toLowerCase(Locale.ROOT).contains("minute"), result);
    }

    /**
     * Explicit JSON-type validation for timeoutMinutes: a non-integer number, a string, a boolean, and overflow must
     * all be refused cleanly rather than silently coerced or thrown as an uncaught exception.
     */
    @Test
    void timeoutMinutesWithTheWrongJsonShapeIsRefused() {
        AbstractAiSession watcher = session("idlewatch-tool-shape-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-shape-target", true);

        assertTrue(createWithTimeout(watcher, target, 2.5).startsWith("Error:"), "2.5 (non-integer number)");
        assertTrue(createWithTimeout(watcher, target, "abc").startsWith("Error:"), "\"abc\" (string)");
        assertTrue(createWithTimeout(watcher, target, true).startsWith("Error:"), "true (boolean)");
        assertTrue(createWithTimeout(watcher, target, 99_999_999_999L).startsWith("Error:"), "overflow");
    }

    @Test
    void recurringAsAStringIsRefused() {
        AbstractAiSession watcher = session("idlewatch-tool-recurring-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-recurring-target", true);
        JsonObject o = new JsonObject();
        o.addProperty(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId());
        o.addProperty(CreateIdleWatcherParamEnum.RECURRING.key(), "yes");

        String result = new CreateIdleWatcherTool().handle(new ToolRequestArguments(o), watcher);

        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void selfWatchIsRefused() {
        AbstractAiSession watcher = session("idlewatch-tool-self-watcher", true);

        String result = new CreateIdleWatcherTool().handle(
                args(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), watcher.getId()), watcher);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("cannot watch itself"), result);
    }

    @Test
    void unknownTargetIsRefused() {
        AbstractAiSession watcher = session("idlewatch-tool-unknown-watcher", true);

        String result = new CreateIdleWatcherTool().handle(
                args(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), "no-such-session"), watcher);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("not open"), result);
    }

    @Test
    void listShowsTheCreatedWatcher() {
        AbstractAiSession watcher = session("idlewatch-tool-list-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-list-target", true);
        new CreateIdleWatcherTool().handle(
                args(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId()), watcher);
        rememberLatestWatcher(watcher.getId());

        String listed = new ListIdleWatchersTool().handle(args(), watcher);

        assertTrue(listed.contains(target.getId()), listed);
        assertFalse(listed.startsWith("No idle watchers"), listed);
    }

    /**
     * The not-found reply is a non-error message (not an "Error:"-prefixed one), matching StopAsyncBuild's "No queued
     * or running async build ... belongs to you" pattern.
     */
    @Test
    void cancellingTwiceTheSecondTimeIsTheNonErrorNotFoundReply() {
        AbstractAiSession watcher = session("idlewatch-tool-cancel-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-cancel-target", true);
        new CreateIdleWatcherTool().handle(
                args(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId()), watcher);
        String watcherId = IdleWatcherRegistry.getInstance().list(watcher.getId()).get(0).watcher().id();

        String first = new CancelIdleWatcherTool().handle(
                args(CancelIdleWatcherParamEnum.WATCHER_ID.key(), watcherId), watcher);
        assertFalse(first.startsWith("Error:"), first);
        assertTrue(first.contains(watcherId), first);

        String second = new CancelIdleWatcherTool().handle(
                args(CancelIdleWatcherParamEnum.WATCHER_ID.key(), watcherId), watcher);
        assertFalse(second.startsWith("Error:"), second);
        assertTrue(second.contains("No idle watcher " + watcherId), second);
        assertTrue(second.contains("belongs to you"), second);
    }

    @Test
    void noteOverFiveHundredCharsIsRefused() {
        AbstractAiSession watcher = session("idlewatch-tool-note-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-note-target", true);
        JsonObject o = new JsonObject();
        o.addProperty(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId());
        o.addProperty(CreateIdleWatcherParamEnum.NOTE.key(), "n".repeat(501));

        String result = new CreateIdleWatcherTool().handle(new ToolRequestArguments(o), watcher);

        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("500"), result);
    }

    @Test
    void createDefaultsInterruptToFalse() {
        AbstractAiSession watcher = session("idlewatch-tool-interrupt-default-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-interrupt-default-target", true);

        String result = new CreateIdleWatcherTool().handle(
                args(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId()), watcher);
        assertFalse(result.startsWith("Error:"), result);
        rememberLatestWatcher(watcher.getId());

        boolean interrupt = IdleWatcherRegistry.getInstance().list(watcher.getId()).get(0).watcher().interrupt();
        assertFalse(interrupt, "opt-in only — omitting the parameter must not request interrupts");
    }

    @Test
    void createAcceptsInterruptTrueAndReportsItInTheReplyAndTheList() {
        AbstractAiSession watcher = session("idlewatch-tool-interrupt-true-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-interrupt-true-target", true);
        JsonObject o = new JsonObject();
        o.addProperty(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId());
        o.addProperty(CreateIdleWatcherParamEnum.INTERRUPT.key(), true);

        String result = new CreateIdleWatcherTool().handle(new ToolRequestArguments(o), watcher);
        assertFalse(result.startsWith("Error:"), result);
        rememberLatestWatcher(watcher.getId());

        boolean interrupt = IdleWatcherRegistry.getInstance().list(watcher.getId()).get(0).watcher().interrupt();
        assertTrue(interrupt, "the flag must reach the armed watcher");
        assertTrue(result.toLowerCase(Locale.ROOT).contains("interrupt"), result);

        String listed = new ListIdleWatchersTool().handle(args(), watcher);
        assertTrue(listed.toLowerCase(Locale.ROOT).contains("interrupt"), listed);
    }

    @Test
    void interruptAsANonBooleanIsRefused() {
        AbstractAiSession watcher = session("idlewatch-tool-interrupt-shape-watcher", true);
        AbstractAiSession target = session("idlewatch-tool-interrupt-shape-target", true);
        JsonObject o = new JsonObject();
        o.addProperty(CreateIdleWatcherParamEnum.TARGET_SESSION_ID.key(), target.getId());
        o.addProperty(CreateIdleWatcherParamEnum.INTERRUPT.key(), "yes");

        String result = new CreateIdleWatcherTool().handle(new ToolRequestArguments(o), watcher);

        assertTrue(result.startsWith("Error:"), result);
    }
}
