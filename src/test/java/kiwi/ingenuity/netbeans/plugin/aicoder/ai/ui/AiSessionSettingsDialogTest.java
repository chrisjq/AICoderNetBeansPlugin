package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AiSessionSettingsDialogTest {

    @Test
    @Timeout(20)
    void okIsHeldUntilTheKeyringStateIsReadyThenTheSaveRuns() throws Exception {
        AiSession session = AiSession.create("/tmp", AiTypeEnum.CLAUDE);
        session.settings().setSessionInstructions("old");
        CompletableFuture<Void> ready = new CompletableFuture<>();
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        AtomicInteger saveStarts = new AtomicInteger();
        AiSessionSettingsDialog dialog = new AiSessionSettingsDialog(session);
        dialog.setKeyStateReadySource(() -> ready);
        dialog.setKeySaveSource(() -> {
            saveStarts.incrementAndGet();
            return save;
        });
        dialog.setErrorSink(message -> {
        });
        try {
            dialog.setSessionInstructionsForTest("new");
            onEdt(dialog::onOk);
            assertFalse(dialog.isOkEnabledForTest(), "OK waits while the keyring state is loading");
            assertEquals(0, saveStarts.get(), "no save starts before the state is known");

            ready.complete(null);
            onEdt(() -> {
            });
            assertEquals(1, saveStarts.get(), "the save starts once the state is ready");

            save.complete(true);
            onEdt(() -> {
            });
            assertEquals("new", session.settings().sessionInstructions());
        }
        finally {
            dialog.dispose();
        }
    }

    @Test
    @Timeout(20)
    void aSecondOkWhileTheFirstSaveIsPendingWaitsForItInsteadOfStartingAnother() throws Exception {
        AiSession session = AiSession.create("/tmp", AiTypeEnum.CLAUDE);
        session.settings().setSessionInstructions("old");
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        AtomicInteger saveStarts = new AtomicInteger();
        AiSessionSettingsDialog dialog = new AiSessionSettingsDialog(session);
        dialog.setKeySaveSource(() -> {
            saveStarts.incrementAndGet();
            return save;
        });
        dialog.setErrorSink(message -> {
        });
        try {
            dialog.setSessionInstructionsForTest("new");
            onEdt(dialog::onOk);
            onEdt(dialog::onOk);
            assertEquals(1, saveStarts.get(), "a second OK must not start a second save");

            save.complete(true);
            onEdt(() -> {
            });
            assertEquals("new", session.settings().sessionInstructions(), "the wait commits the form");
        }
        finally {
            dialog.dispose();
        }
    }

    /**
     * Runs {@code action} on the EDT and waits for it, so queued completions from the dialog have run before a check.
     */
    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    /**
     * A dialog for a Claude session, with its key save and revert replaced by controls the test owns.
     */
    private static AiSessionSettingsDialog dialogWithKeySave(AiSession session, CompletableFuture<Boolean> save,
                                                             List<String> errors, List<CompletableFuture<Boolean>> reverts) {
        AiSessionSettingsDialog dialog = new AiSessionSettingsDialog(session);
        dialog.setKeySaveSource(() -> save);
        dialog.setErrorSink(errors::add);
        dialog.setKeyChangeReverter(change -> {
            reverts.add(change);
            return CompletableFuture.completedFuture(null);
        });
        return dialog;
    }

    @Test
    @Timeout(20)
    void aFailedKeySaveDoesNotCommitAndReEnablesOk() throws Exception {
        AiSession session = AiSession.create("/tmp", AiTypeEnum.CLAUDE);
        session.settings().setSessionInstructions("old");
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        List<String> errors = new ArrayList<>();
        AiSessionSettingsDialog dialog = dialogWithKeySave(session, save, errors, new ArrayList<>());
        try {
            dialog.setSessionInstructionsForTest("new");
            onEdt(dialog::onOk);
            assertFalse(dialog.isOkEnabledForTest(), "OK is disabled while the key save is pending");

            save.complete(false);
            onEdt(() -> {
            });

            assertNull(dialog.getResultConfig(), "a failed save must not produce a committed result");
            assertEquals("old", session.settings().sessionInstructions(), "live settings are unchanged");
            assertTrue(dialog.isOkEnabledForTest(), "OK is enabled again after the failure");
            assertEquals(1, errors.size());
        }
        finally {
            dialog.dispose();
        }
    }

    @Test
    @Timeout(20)
    void aSuccessfulKeySaveCommitsTheCandidate() throws Exception {
        AiSession session = AiSession.create("/tmp", AiTypeEnum.CLAUDE);
        session.settings().setSessionInstructions("old");
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        AiSessionSettingsDialog dialog = dialogWithKeySave(session, save, new ArrayList<>(), new ArrayList<>());
        try {
            dialog.setSessionInstructionsForTest("new");
            onEdt(dialog::onOk);

            save.complete(true);
            onEdt(() -> {
            });

            assertNotNull(dialog.getResultConfig(), "a successful save commits");
            assertEquals("new", session.settings().sessionInstructions());
        }
        finally {
            dialog.dispose();
        }
    }

    @Test
    @Timeout(20)
    void closingBeforeTheSaveCompletesDoesNotCommitAndRevertsTheKeyring() throws Exception {
        AiSession session = AiSession.create("/tmp", AiTypeEnum.CLAUDE);
        session.settings().setSessionInstructions("old");
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        List<CompletableFuture<Boolean>> reverts = new ArrayList<>();
        AiSessionSettingsDialog dialog = dialogWithKeySave(session, save, new ArrayList<>(), reverts);
        dialog.setSessionInstructionsForTest("new");
        onEdt(dialog::onOk);

        onEdt(dialog::dispose);
        save.complete(true);
        onEdt(() -> {
        });

        assertNull(dialog.getResultConfig(), "closing without committing leaves no result");
        assertEquals("old", session.settings().sessionInstructions(), "the live settings are not committed");
        assertEquals(1, reverts.size(), "the keyring change is reverted on close");
        assertTrue(reverts.get(0) == save, "the revert is given the original keyring future");
    }

    @Test
    @Timeout(20)
    void theWaitIgnoresCloseAndCancelUntilTheSaveFinishes() throws Exception {
        AiSession session = AiSession.create("/tmp", AiTypeEnum.CLAUDE);
        CompletableFuture<Boolean> save = new CompletableFuture<>();
        AiSessionSettingsDialog dialog = dialogWithKeySave(session, save, new ArrayList<>(), new ArrayList<>());
        try {
            onEdt(dialog::onOk);

            assertEquals(WindowConstants.DO_NOTHING_ON_CLOSE, dialog.getDefaultCloseOperation(),
                         "window close is ignored while the save is pending");
            save.complete(false);
            onEdt(() -> {
            });
            assertEquals(WindowConstants.DISPOSE_ON_CLOSE, dialog.getDefaultCloseOperation(),
                         "the window closes normally again once the wait is over");
        }
        finally {
            dialog.dispose();
        }
    }

    @Test
    void commitKeepsRuntimeFieldsWrittenBetweenSnapshotAndCommit() {
        AiSession session = AiSession.create("/tmp", AiTypeEnum.CLAUDE);
        AiSessionSettings live = session.settings();
        live.setMaxHistory(7);
        AiSessionSettings snapshot = AiSessionSettingsDialog.copySettings(session, live);
        AiSessionSettings candidate = AiSessionSettingsDialog.copySettings(session, live);
        candidate.setMaxHistory(99);
        live.setMcpRoutingId("routing-1234abcd");

        AiSessionSettingsDialog.commitSettings(session, live, snapshot, candidate);

        assertEquals(99, live.maxHistory(), "the field the user changed is committed");
        assertEquals("routing-1234abcd", live.mcpRoutingId(), "a runtime field the user did not change survives");
    }

    @Test
    void copyAndCommitPreserveClearedOpenAiFieldsAndInheritedNulls() {
        AiSession session = AiSession.create("/tmp", AiTypeEnum.OPENAI_COMPATIBLE);
        OpenAiCompatibleSessionSettings live = (OpenAiCompatibleSessionSettings) session.settings();
        live.setSummaryModel("summary");
        live.setTemperature(0.5);
        live.setSessionInstructions("old");
        OpenAiCompatibleSessionSettings candidate = (OpenAiCompatibleSessionSettings) AiSessionSettingsDialog.copySettings(session, live);
        candidate.setSummaryModel(null);
        candidate.setTemperature(null);
        candidate.setSessionInstructions(null);
        AiSessionSettingsDialog.commitSettings(session, live, candidate);
        assertNull(live.summaryModel());
        assertNull(live.temperature());
        assertNull(live.sessionInstructions());
    }

    @Test
    void boundedKeySaveFailsWhenKeyringFutureNeverCompletes() {
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        assertThrows(CompletionException.class,
                     () -> AiSessionSettingsDialog.awaitKeySave(pending, Duration.ofMillis(20)).join());
    }

    @Test
    void boundedKeySaveTimeoutDoesNotCompleteOriginalFuture() {
        CompletableFuture<Boolean> original = new CompletableFuture<>();
        CompletableFuture<Boolean> bounded = AiSessionSettingsDialog.awaitKeySave(original, Duration.ofMillis(20));
        assertThrows(CompletionException.class, bounded::join);
        original.complete(true);
        assertEquals(Boolean.TRUE, original.join());
    }

    @Test
    void formPlacesConfigBeforeDescriptionAndInstructions() {
        AiTypeEnum type = AiTypeEnum.values()[0];
        AiSession session = AiSession.create("/tmp", type);
        AiSessionSettingsDialog dialog = new AiSessionSettingsDialog(session);
        try {
            JTabbedPane tabs = dialog.buildForm();
            assertEquals(3, tabs.getTabCount());
            assertEquals("Session", tabs.getTitleAt(0));
            assertEquals("General", tabs.getTitleAt(1));
            assertEquals("MCP Tools", tabs.getTitleAt(2));

            Component sessionTab = tabs.getComponentAt(0);
            assertTrue(indexOfText(sessionTab, "Session name:") < indexOfText(sessionTab, "Description:"));
            assertTrue(indexOfText(sessionTab, "Description:") < indexOfText(sessionTab, "Session Instructions:"));
            assertNotNull(findFirst(sessionTab, JTextField.class));
        }
        finally {
            dialog.dispose();
        }
    }

    private static int indexOfText(Component root, String text) {
        List<Component> components = new ArrayList<>();
        collectComponents(root, components);
        for (int index = 0; index < components.size(); index++) {
            Component component = components.get(index);
            if (component instanceof JLabel label && text.equals(label.getText())) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static <T extends Component> T findFirst(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                T found = findFirst(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectComponents(Component root, List<Component> into) {
        into.add(root);
        if (root instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                collectComponents(child, into);
            }
        }
    }
}
