package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.RunningSessionDetectedView;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogBounds;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogPlacement;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.ScrollablePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings.AiSessionConfigPanelMode;
import org.openide.windows.WindowManager;

/**
 * Session identity and instructions plus the common generic configuration UI.
 *
 * <p>
 * OK first waits for the keyring state the panel needs, then takes a snapshot of the live settings and builds a
 * candidate from it. If a key save is pending, the dialog waits for it (bounded) with OK, Cancel and close disabled,
 * then commits only the fields the user changed. If the dialog is closed without committing while that save is pending
 * or has succeeded, the keyring change is reverted.
 */
public class AiSessionSettingsDialog extends JDialog {

    private static final String DIALOG_BOUNDS_KEY = "session-configuration";
    private static final String TITLE = "Session Configuration";
    private static int lastTabIndex;
    private static final Duration KEY_WAIT = Duration.ofSeconds(10);
    private static final String KEY_ERROR = "The API key could not be stored in the system keyring.";
    private static final String KEYRING_LOADING = "The keyring is still loading; try again.";

    public static AiSessionSettingsDialog show(AiSession session) {
        AiSessionSettingsDialog dialog = new AiSessionSettingsDialog(session);
        DialogBounds.restore(DIALOG_BOUNDS_KEY, dialog, 560, 400);
        DialogPlacement.centre(dialog, WindowManager.getDefault().getMainWindow());
        dialog.setVisible(true);
        return dialog;
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row, JLabel label, Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.weighty = 0;
        panel.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }
    private final AiSession session;
    private final AiSessionSettings liveSettings;
    private volatile boolean closed;
    private boolean committed;
    private boolean waiting;
    private final JTextField nameField = new JTextField(24);
    private final JTextArea descriptionArea = new JTextArea(3, 24);
    private final JTextArea sessionInstructionsArea = new JTextArea(4, 24);
    private final AiSessionConfigPanel configPanel = new AiSessionConfigPanel(AiSessionConfigPanelMode.SESSION);
    /**
     * The live Detected panel of a running OpenAI Compatible session; null for every other session.
     */
    private final RunningSessionDetectedView detectedView;
    private final JButton okButton = new JButton("OK");
    private final JButton cancelButton = new JButton("Cancel");
    private int closeOperationBeforeWait = DISPOSE_ON_CLOSE;
    /**
     * The original keyring future of the last OK that had a key save pending, kept so a later close can revert it.
     */
    private CompletableFuture<Boolean> pendingKeySave;
    private Supplier<CompletableFuture<Void>> keyStateReadySource;
    private Supplier<CompletableFuture<Boolean>> keySaveSource;
    private Function<CompletableFuture<Boolean>, CompletableFuture<Void>> keyChangeReverter;
    private Consumer<String> errorSink;
    private AiSessionSettings result;
    private String resultName;
    private String resultDescription;

    AiSessionSettingsDialog(AiSession session) {
        super(WindowManager.getDefault().getMainWindow(), TITLE, true);
        this.session = session;
        this.liveSettings = session.settings();
        AiSessionSettings settings = liveSettings;
        nameField.setText(session.name());
        descriptionArea.setText(session.description() == null ? "" : session.description());
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        sessionInstructionsArea.setText(settings.sessionInstructions() == null ? "" : settings.sessionInstructions());
        sessionInstructionsArea.setLineWrap(true);
        sessionInstructionsArea.setWrapStyleWord(true);
        configPanel.setSessionId(session.id());
        configPanel.loadSession(settings, session.aiType());
        configPanel.setSessionDebugLogDir(sessionDebugLogDir(session));
        detectedView = RunningSessionDetectedView.forSession(session);
        keyStateReadySource = configPanel::keyStateReady;
        keySaveSource = configPanel::keySaveFuture;
        keyChangeReverter = configPanel::revertKeyChange;
        errorSink = message -> DialogPlacement.showMessageDialog(this, message, TITLE, JOptionPane.ERROR_MESSAGE);
        // Closing with the window X must run dispose(), which reverts an uncommitted keyring change.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        add(buildForm(), BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        getRootPane().setDefaultButton(okButton);
        okButton.addActionListener(e -> onOk());
        cancelButton.addActionListener(e -> dispose());
        buttons.add(okButton);
        buttons.add(cancelButton);
        add(buttons, BorderLayout.SOUTH);
        pack();

        DialogBounds.applyDefaultSize(this, WindowManager.getDefault().getMainWindow(), 560, 400);
        setMinimumSize(new Dimension(560, 400));
    }

    @Override
    public void dispose() {
        if (!closed) {
            if (sessionTabs != null) {
                lastTabIndex = sessionTabs.getSelectedIndex();
            }
            closed = true;
            DialogBounds.remember(DIALOG_BOUNDS_KEY, this);
            if (detectedView != null) {
                detectedView.dispose();
            }
            CompletableFuture<Boolean> pending = pendingKeySave;
            pendingKeySave = null;
            if (!committed && pending != null) {
                // Taken before configPanel.dispose(): the revert captures what it needs from the panel first.
                keyChangeReverter.apply(pending);
            }
            configPanel.dispose();
        }
        super.dispose();
    }

    /**
     * OK: wait for the keyring state, then either commit now or wait (bounded) for the key save before committing.
     */
    void onOk() {
        CompletableFuture<Void> ready = keyStateReadySource.get();
        if (!ready.isDone()) {
            awaitThen(ready, KEYRING_LOADING, this::onOk);
            return;
        }
        CompletableFuture<Boolean> inFlight = pendingKeySave;
        if (inFlight != null && !inFlight.isDone()) {
            // A save from an earlier OK is still running: wait for that one rather than start another.
            awaitThen(inFlight, KEY_ERROR, this::commitNow);
            return;
        }
        AiSessionSettings snapshot = copySettings(session, liveSettings);
        AiSessionSettings candidate = buildResult(liveSettings);
        CompletableFuture<Boolean> save = keySaveSource.get();
        if (save == null) {
            commitResult(snapshot, candidate);
            dispose();
            return;
        }
        pendingKeySave = save;
        awaitThen(save, KEY_ERROR, () -> {
              commitResult(snapshot, candidate);
              dispose();
          });
    }

    /**
     * Commits the form as it is now, once the save it waited for has succeeded.
     */
    private void commitNow() {
        AiSessionSettings snapshot = copySettings(session, liveSettings);
        AiSessionSettings candidate = buildResult(liveSettings);
        commitResult(snapshot, candidate);
        dispose();
    }

    /**
     * Waits (bounded) for {@code gate} with OK and Cancel disabled and close ignored. On success runs {@code onSuccess}
     * on the EDT; a failure or a timeout re-enables the controls and reports {@code errorMessage}.
     */
    private void awaitThen(CompletableFuture<?> gate, String errorMessage, Runnable onSuccess) {
        setWaiting(true);
        awaitKeySave(gate, KEY_WAIT).whenComplete((value, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            setWaiting(false);
            if (error == null && !Boolean.FALSE.equals(value)) {
                onSuccess.run();
            }
            else {
                result = null;
                if (sessionTabs != null) {
                    sessionTabs.setSelectedIndex(0);
                }
                errorSink.accept(errorMessage);
            }
        }));
    }

    /**
     * While a key save is pending, OK and Cancel are disabled and close is ignored; afterwards the previous states are
     * restored.
     */
    private void setWaiting(boolean value) {
        if (value == waiting) {
            return;
        }
        waiting = value;
        okButton.setEnabled(!value);
        cancelButton.setEnabled(!value);
        if (value) {
            closeOperationBeforeWait = getDefaultCloseOperation();
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        }
        else {
            setDefaultCloseOperation(closeOperationBeforeWait);
        }
    }

    private JTabbedPane sessionTabs;

    static int lastTabIndexForTest() {
        return lastTabIndex;
    }

    static void resetLastTabForTest() {
        lastTabIndex = 0;
    }

    private JScrollPane scrollableTab(Component component) {
        ScrollablePanel container = new ScrollablePanel(new BorderLayout());
        container.add(component, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(container);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    JTabbedPane buildForm() {
        sessionTabs = new JTabbedPane();

        ScrollablePanel session = new ScrollablePanel(new GridBagLayout());
        session.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        addRow(session, c, 0, new JLabel("Session name:"), nameField);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        session.add(configPanel.typeSection(), c);
        if (detectedView != null) {
            GridBagConstraints detectedConstraints = (GridBagConstraints) c.clone();
            detectedConstraints.gridy = 2;
            session.add(detectedView.component(), detectedConstraints);
        }

        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setPreferredSize(new Dimension(300, 200));
        descriptionScroll.setMinimumSize(new Dimension(200, 200));
        addRow(session, c, 3, new JLabel("Description:"), descriptionScroll);

        JScrollPane instructionsScroll = new JScrollPane(sessionInstructionsArea);
        instructionsScroll.setPreferredSize(new Dimension(300, 200));
        instructionsScroll.setMinimumSize(new Dimension(200, 200));
        addRow(session, c, 4, new JLabel("Session Instructions:"), instructionsScroll);

        c.gridy = 5;
        c.weighty = 1;
        session.add(new JPanel(), c);

        sessionTabs.addTab("Session", scrollableTab(session));
        sessionTabs.addTab("General", scrollableTab(configPanel.generalSection()));
        sessionTabs.addTab("MCP Tools", scrollableTab(configPanel.mcpSection()));
        sessionTabs.setSelectedIndex(Math.min(lastTabIndex, sessionTabs.getTabCount() - 1));

        return sessionTabs;
    }

    private AiSessionSettings buildResult(AiSessionSettings original) {
        resultName = nameField.getText().trim();
        resultDescription = descriptionArea.getText().trim();
        AiSessionSettings candidate = copySettings(session, original);
        configPanel.applySession(candidate);
        String instructions = sessionInstructionsArea.getText().trim();
        candidate.setSessionInstructions(instructions.isBlank() ? null : instructions);
        return candidate;
    }

    static AiSessionSettings copySettings(AiSession session, AiSessionSettings original) {
        AiSessionSettings candidate = session.aiType().createDefaultSettings();
        candidate.copyFrom(original);
        return candidate;
    }

    static void commitSettings(AiSession session, AiSessionSettings original, AiSessionSettings candidate) {
        original.copyFrom(candidate);
    }

    /**
     * Commits only the fields the candidate changed from the snapshot taken at OK; runtime fields written meanwhile
     * stay.
     */
    static void commitSettings(AiSession session, AiSessionSettings original, AiSessionSettings snapshot,
                               AiSessionSettings candidate) {
        original.copyChangedFrom(snapshot, candidate);
    }

    static <T> CompletableFuture<T> awaitKeySave(CompletableFuture<T> save, Duration timeout) {
        return save.copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void commitResult(AiSessionSettings snapshot, AiSessionSettings candidate) {
        commitSettings(session, liveSettings, snapshot, candidate);
        committed = true;
        result = liveSettings;
    }

    public AiSessionSettings getResultConfig() {
        return result;
    }

    public String getResultName() {
        return resultName;
    }

    public String getResultDescription() {
        return resultDescription;
    }

    /**
     * Replaces where the keyring-state gate comes from (tests control when it completes).
     */
    void setKeyStateReadySource(Supplier<CompletableFuture<Void>> source) {
        keyStateReadySource = source;
    }

    /**
     * Replaces how the keyring save is observed (tests drive a future they control).
     */
    void setKeySaveSource(Supplier<CompletableFuture<Boolean>> source) {
        keySaveSource = source;
    }

    /**
     * Replaces how a keyring change is reverted when the dialog closes without committing (tests record the call).
     */
    void setKeyChangeReverter(Function<CompletableFuture<Boolean>, CompletableFuture<Void>> reverter) {
        keyChangeReverter = reverter;
    }

    /**
     * Replaces how a failed keyring save is reported (tests record the message instead of blocking on a dialog).
     */
    void setErrorSink(Consumer<String> sink) {
        errorSink = sink;
    }

    /**
     * Sets the session-instructions text the candidate is built from (tests use it to make a change to commit).
     */
    void setSessionInstructionsForTest(String text) {
        sessionInstructionsArea.setText(text);
    }

    boolean isOkEnabledForTest() {
        return okButton.isEnabled();
    }

    /**
     * The session folder that holds {@code backend-debug.log}, without creating it. A missing plugin root leaves the
     * Open log folder button disabled rather than failing the dialog.
     */
    private static Path sessionDebugLogDir(AiSession session) {
        if (session == null || session.aiType() == null || session.id() == null) {
            return null;
        }
        try {
            return PluginUtil.getPluginConfigDir().resolve(session.aiType().key()).resolve(session.id());
        }
        catch (IOException ex) {
            return null;
        }
    }
}
