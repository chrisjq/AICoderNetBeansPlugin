package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshot;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshotSource;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedSnapshotSources;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RunningSessionDetectedViewTest {

    private static final class FakeSource implements DetectedSnapshotSource {

        final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        volatile DetectedSnapshot current = new DetectedSnapshot(null, true, null, "Auto", "Drop", 0,
                                                                 new WindowReading(8192, WindowReading.Source.ASSUMED,
                                                                                   false));

        @Override
        public DetectedSnapshot snapshot() {
            return current;
        }

        @Override
        public void addListener(Runnable listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(Runnable listener) {
            listeners.remove(listener);
        }

        void change(DetectedSnapshot next) {
            current = next;
            listeners.forEach(Runnable::run);
        }
    }

    private static final String ID = "detected-view-test";

    @AfterEach
    void clean() {
        DetectedSnapshotSources.unregister(ID, DetectedSnapshotSources.forSession(ID));
    }

    private static AiSession session(AiTypeEnum type) {
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setServerType(ServerTypeEnum.AUTO);
        settings.setBaseUrl("http://localhost:1234");
        settings.setModel("m");
        return new AiSession(ID, "OC", null, type, null, settings, Instant.now(), Instant.now());
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private static DetectedFacts lmStudioFacts() {
        return DetectedFacts.of("g").withKind(ServerKind.LMSTUDIO).withApiBase("http://localhost:1234/v1")
                .withStreaming(Boolean.TRUE).withModels(List.of("a", "b"));
    }

    @Test
    @Timeout(10)
    void thereIsNoViewForANonOpenAiCompatibleSessionOrOneThatIsNotRunning() {
        assertNull(RunningSessionDetectedView.forSession(null));
        assertNull(RunningSessionDetectedView.forSession(session(AiTypeEnum.OPENAI_COMPATIBLE)),
                   "no source is registered, so the session is not running");

        DetectedSnapshotSources.register(ID, new FakeSource());
        assertNull(RunningSessionDetectedView.forSession(session(AiTypeEnum.OLLAMA_LOCAL)));
    }

    @Test
    @Timeout(10)
    void theViewShowsTheSnapshotAndFollowsItsChangesWhileOpen() throws Exception {
        FakeSource source = new FakeSource();
        DetectedSnapshotSources.register(ID, source);

        RunningSessionDetectedView view = RunningSessionDetectedView.forSession(session(AiTypeEnum.OPENAI_COMPATIBLE));

        assertNotNull(view);
        assertEquals(1, source.listeners.size(), "it subscribes while shown");
        flushEdt();
        assertEquals("N/A", view.panel().reachabilityValue(), "nothing detected yet");

        source.change(new DetectedSnapshot(lmStudioFacts(), true, Boolean.TRUE, "Auto", "Drop", 0,
                                           new WindowReading(8192, WindowReading.Source.ASSUMED, false)));
        flushEdt();
        assertEquals("LM Studio (detected)", view.panel().kindValue());
        assertEquals("http://localhost:1234/v1", view.panel().apiBaseValue());
        assertEquals("yes", view.panel().streamingValue());
        assertEquals("2", view.panel().modelCountValue());
    }

    @Test
    @Timeout(10)
    void aServerTheLastProbeCouldNotReachShowsAsUnreachableEvenWithOldFacts() throws Exception {
        FakeSource source = new FakeSource();
        DetectedSnapshotSources.register(ID, source);
        RunningSessionDetectedView view = RunningSessionDetectedView.forSession(session(AiTypeEnum.OPENAI_COMPATIBLE));
        WindowReading window = new WindowReading(8192, WindowReading.Source.ASSUMED, false);

        source.change(new DetectedSnapshot(lmStudioFacts(), true, Boolean.TRUE, "Auto", "Drop", 0, window));
        flushEdt();
        assertEquals("OK", view.panel().reachabilityValue());

        source.change(new DetectedSnapshot(lmStudioFacts(), false, Boolean.TRUE, "Auto", "Drop", 0, window));
        flushEdt();
        assertEquals("N/A", view.panel().reachabilityValue());
        assertTrue(view.panel().reachabilityTooltip().contains("http://localhost:1234"));

        source.change(new DetectedSnapshot(lmStudioFacts(), true, Boolean.TRUE, "Auto", "Drop", 0, window));
        flushEdt();
        assertEquals("OK", view.panel().reachabilityValue(), "reachable again");
    }

    @Test
    @Timeout(10)
    void disposeStopsListening() {
        FakeSource source = new FakeSource();
        DetectedSnapshotSources.register(ID, source);
        RunningSessionDetectedView view = RunningSessionDetectedView.forSession(session(AiTypeEnum.OPENAI_COMPATIBLE));
        assertEquals(1, source.listeners.size());

        view.dispose();

        assertTrue(source.listeners.isEmpty());
    }

    @Test
    @Timeout(10)
    void theRegistryHandsBackOnlyTheSessionsOwnSourceAndIgnoresAStaleUnregister() {
        FakeSource first = new FakeSource();
        FakeSource second = new FakeSource();
        DetectedSnapshotSources.register(ID, first);
        DetectedSnapshotSources.register(ID, second);

        DetectedSnapshotSources.unregister(ID, first);
        assertEquals(second, DetectedSnapshotSources.forSession(ID), "a restarted session's source is kept");

        DetectedSnapshotSources.unregister(ID, second);
        assertNull(DetectedSnapshotSources.forSession(ID));
        assertNull(DetectedSnapshotSources.forSession(null));
    }
}
