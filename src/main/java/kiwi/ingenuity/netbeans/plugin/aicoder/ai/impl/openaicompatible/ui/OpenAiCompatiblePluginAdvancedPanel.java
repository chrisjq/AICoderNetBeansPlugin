package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.debuglog.BackendDebugLogWriter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettingsKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.FormLayoutUtil;

/**
 * Plugin-wide Advanced values for the OpenAI Compatible defaults page (spec §8.1/§8.2, "Per session: no"). Each control
 * shows a "(default: X)" hint and uses the §8.1 floors as spinner minima.
 */
final class OpenAiCompatiblePluginAdvancedPanel {

    private static final int WINDOW_MAX = 1_000_000;
    private static final int NUMERIC_FIELD_WIDTH = 120;
    private static final int COUNT_MAX = 1_000_000;
    private static final int SECONDS_MAX = 86_400;
    private static final int MINUTES_MAX = 1_440;
    private static final int MB_MAX = 4_096;
    private static final int MINIMUM_WINDOW_FLOOR = 2_048;
    private static final int SIZE_CAP_FLOOR_MB = 1;
    private static final long MB = 1L << 20;

    private final JPanel panel = new JPanel(new GridBagLayout());
    private final List<Runnable> changeListeners = new ArrayList<>();
    private boolean updating;

    private final IntSpinner assumedWindow;
    private final IntSpinner minimumWindowForTools;
    private final DoubleSpinner trainingLengthFactor;
    private final IntSpinner smallWindowWarning;
    private final IntSpinner windowReadingsBeforeShrink;
    private final IntSpinner summaryToolResultChars;
    private final IntSpinner summaryRepairNudges;
    private final IntSpinner overflowRetries;
    private final DoubleSpinner calibrationTolerance;
    private final DoubleSpinner estimatorCharsPerToken;
    private final IntSpinner estimatorOverhead;
    private final DoubleSpinner estimatorSmoothing;
    private final DoubleSpinner estimatorMinRatio;
    private final DoubleSpinner estimatorMaxRatio;
    private final IntSpinner emptyReplyNudges;
    private final IntSpinner toolDescriptionCapChars;
    private final IntSpinner parameterDescriptionCapChars;
    private final IntSpinner schemaDepth;
    private final IntSpinner transcriptCapMb;
    private final IntSpinner toolArgumentCapMb;
    private final IntSpinner totalRequestCapMinutes;
    private final IntSpinner connectTimeoutSeconds;
    private final IntSpinner abandonedToolWaitSeconds;
    private final IntSpinner probeTimeoutSeconds;
    private final IntSpinner redetectIntervalSeconds;
    private final IntSpinner probeBodyCapMb;
    private final IntSpinner perModelProbes;
    private final IntSpinner transportRetries;
    private final IntSpinner retryBackoffSeconds;
    private final IntSpinner retryAfterCapSeconds;
    private final JTextField keepAlive = new JTextField(8);
    private final JLabel keepAliveHint;
    private final IntSpinner debugLogSizeCapMb;
    private final IntSpinner debugLogRolledFiles;
    private final IntSpinner debugLogQueueMb;
    private final IntSpinner debugLogQueueEntries;
    private final IntSpinner debugLogMaxEntryKb;
    private final IntSpinner debugLogDrainSeconds;
    private final JLabel warningLabel = new JLabel(" ");

    OpenAiCompatiblePluginAdvancedPanel() {
        assumedWindow = intSpinner(OpenAiCompatiblePluginSettingsKeyEnum.ASSUMED_WINDOW.defaultInt(),
                                   MINIMUM_WINDOW_FLOOR, WINDOW_MAX, "tokens");
        minimumWindowForTools = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.MINIMUM_WINDOW_FOR_TOOLS.defaultInt(),
                MINIMUM_WINDOW_FLOOR, WINDOW_MAX, "tokens");
        trainingLengthFactor = doubleSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.TRAINING_LENGTH_FACTOR.defaultDouble(),
                0.01d, 1.0d, 0.05d, "");
        smallWindowWarning = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.SMALL_WINDOW_WARNING.defaultInt(),
                MINIMUM_WINDOW_FLOOR, WINDOW_MAX, "tokens");
        windowReadingsBeforeShrink = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.WINDOW_READINGS_BEFORE_SHRINK.defaultInt(),
                1, COUNT_MAX, "");
        summaryToolResultChars = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.SUMMARY_TOOL_RESULT_CHARS.defaultInt(),
                1, COUNT_MAX, "chars");
        summaryRepairNudges = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.SUMMARY_REPAIR_NUDGES.defaultInt(),
                0, COUNT_MAX, "");
        overflowRetries = intSpinner(OpenAiCompatiblePluginSettingsKeyEnum.OVERFLOW_RETRIES.defaultInt(),
                                     0, COUNT_MAX, "");
        calibrationTolerance = doubleSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.CALIBRATION_TOLERANCE.defaultDouble(),
                0.0d, 1.0d, 0.01d, "");
        estimatorCharsPerToken = doubleSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_CHARS_PER_TOKEN.defaultDouble(),
                0.5d, 20.0d, 0.1d, "");
        estimatorOverhead = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_OVERHEAD.defaultInt(),
                0, COUNT_MAX, "");
        estimatorSmoothing = doubleSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_SMOOTHING.defaultDouble(),
                0.0d, 1.0d, 0.05d, "");
        estimatorMinRatio = doubleSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_MIN_RATIO.defaultDouble(),
                0.01d, 10.0d, 0.1d, "");
        estimatorMaxRatio = doubleSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.ESTIMATOR_MAX_RATIO.defaultDouble(),
                0.1d, 10.0d, 0.1d, "");
        emptyReplyNudges = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.EMPTY_REPLY_NUDGES.defaultInt(),
                0, COUNT_MAX, "");
        toolDescriptionCapChars = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.TOOL_DESCRIPTION_CAP_CHARS.defaultInt(),
                1, COUNT_MAX, "chars");
        parameterDescriptionCapChars = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.PARAMETER_DESCRIPTION_CAP_CHARS.defaultInt(),
                1, COUNT_MAX, "chars");
        schemaDepth = intSpinner(OpenAiCompatiblePluginSettingsKeyEnum.SCHEMA_DEPTH.defaultInt(),
                                 1, 32, "");
        transcriptCapMb = intSpinner(bytesToMb(
                OpenAiCompatiblePluginSettingsKeyEnum.TRANSCRIPT_CAP_BYTES.defaultLong()),
                                     SIZE_CAP_FLOOR_MB, MB_MAX, "MB");
        toolArgumentCapMb = intSpinner(bytesToMb(
                OpenAiCompatiblePluginSettingsKeyEnum.TOOL_ARGUMENT_CAP_BYTES.defaultLong()),
                                       SIZE_CAP_FLOOR_MB, MB_MAX, "MB");
        totalRequestCapMinutes = intSpinner((int) OpenAiCompatiblePluginSettingsKeyEnum.TOTAL_REQUEST_CAP_MINUTES
                .defaultLong(), 1, MINUTES_MAX, "min");
        connectTimeoutSeconds = intSpinner((int) OpenAiCompatiblePluginSettingsKeyEnum.CONNECT_TIMEOUT_SECONDS
                .defaultLong(), 1, SECONDS_MAX, "s");
        abandonedToolWaitSeconds = intSpinner((int) OpenAiCompatiblePluginSettingsKeyEnum.ABANDONED_TOOL_WAIT_SECONDS
                .defaultLong(), 1, SECONDS_MAX, "s");
        probeTimeoutSeconds = intSpinner((int) OpenAiCompatiblePluginSettingsKeyEnum.PROBE_TIMEOUT_SECONDS.defaultLong(),
                                         1, SECONDS_MAX, "s");
        redetectIntervalSeconds = intSpinner((int) OpenAiCompatiblePluginSettingsKeyEnum.REDETECT_INTERVAL_SECONDS
                .defaultLong(), 2, SECONDS_MAX, "s");
        probeBodyCapMb = intSpinner(bytesToMb(
                OpenAiCompatiblePluginSettingsKeyEnum.PROBE_BODY_CAP_BYTES.defaultLong()),
                                    SIZE_CAP_FLOOR_MB, MB_MAX, "MB");
        perModelProbes = intSpinner(OpenAiCompatiblePluginSettingsKeyEnum.PER_MODEL_PROBES.defaultInt(),
                                    1, COUNT_MAX, "");
        transportRetries = intSpinner(OpenAiCompatiblePluginSettingsKeyEnum.TRANSPORT_RETRIES.defaultInt(),
                                      0, COUNT_MAX, "");
        retryBackoffSeconds = intSpinner((int) OpenAiCompatiblePluginSettingsKeyEnum.RETRY_BACKOFF_SECONDS.defaultLong(),
                                         0, SECONDS_MAX, "s");
        retryAfterCapSeconds = intSpinner((int) OpenAiCompatiblePluginSettingsKeyEnum.RETRY_AFTER_CAP_SECONDS
                .defaultLong(), 1, SECONDS_MAX, "s");
        keepAliveHint = new JLabel("(default: "
                + OpenAiCompatiblePluginSettingsKeyEnum.KEEP_ALIVE.defaultString() + ")");
        debugLogSizeCapMb = intSpinner(bytesToMb(
                OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_SIZE_CAP_BYTES.defaultLong()),
                                       SIZE_CAP_FLOOR_MB, MB_MAX, "MB");
        debugLogRolledFiles = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_ROLLED_FILES.defaultInt(),
                0, 100, "");
        debugLogQueueMb = intSpinner(bytesToMb(
                OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_QUEUE_BYTES.defaultLong()),
                                     SIZE_CAP_FLOOR_MB, MB_MAX, "MB");
        debugLogQueueEntries = intSpinner(
                OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_QUEUE_ENTRIES.defaultInt(),
                1, COUNT_MAX, "");
        debugLogMaxEntryKb = intSpinner((int) (OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_MAX_ENTRY_BYTES
                .defaultLong() / 1024L), 1, 1_048_576, "KB");
        debugLogDrainSeconds = intSpinner((int) OpenAiCompatiblePluginSettingsKeyEnum.DEBUG_LOG_DRAIN_SECONDS
                .defaultLong(), 1, SECONDS_MAX, "s");
        keepAlive.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fireChange();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fireChange();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fireChange();
            }
        });
        buildLayout();
    }

    private void buildLayout() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        int row = 0;
        row = addHeader(c, row, "Context");
        row = addField(c, row, "Assumed window:", assumedWindow);
        row = addField(c, row, "Minimum window for tools:", minimumWindowForTools);
        row = addField(c, row, "Training-length factor:", trainingLengthFactor);
        row = addField(c, row, "Small-window warning below:", smallWindowWarning);
        row = addField(c, row, "Window readings before shrinking:", windowReadingsBeforeShrink);
        row = addHeader(c, row, "Summary and estimator");
        row = addField(c, row, "Summary tool-result length:", summaryToolResultChars);
        row = addField(c, row, "Summary repair nudges:", summaryRepairNudges);
        row = addField(c, row, "Overflow retries:", overflowRetries);
        row = addField(c, row, "Calibration tolerance:", calibrationTolerance);
        row = addField(c, row, "Estimator chars/token:", estimatorCharsPerToken);
        row = addField(c, row, "Estimator overhead:", estimatorOverhead);
        row = addField(c, row, "Estimator smoothing:", estimatorSmoothing);
        row = addField(c, row, "Estimator min ratio:", estimatorMinRatio);
        row = addField(c, row, "Estimator max ratio:", estimatorMaxRatio);
        row = addField(c, row, "Empty-reply nudges:", emptyReplyNudges);
        row = addHeader(c, row, "Caps");
        row = addField(c, row, "Tool description cap:", toolDescriptionCapChars);
        row = addField(c, row, "Parameter description cap:", parameterDescriptionCapChars);
        row = addField(c, row, "Schema nesting depth:", schemaDepth);
        row = addField(c, row, "Transcript cap:", transcriptCapMb);
        row = addField(c, row, "Tool-call argument cap:", toolArgumentCapMb);
        row = addHeader(c, row, "Timeouts and probes");
        row = addField(c, row, "Total request cap:", totalRequestCapMinutes);
        row = addField(c, row, "Connect timeout:", connectTimeoutSeconds);
        row = addField(c, row, "Abandoned-tool wait:", abandonedToolWaitSeconds);
        row = addField(c, row, "Probe timeout:", probeTimeoutSeconds);
        row = addField(c, row, "Re-detect interval:", redetectIntervalSeconds);
        row = addField(c, row, "Probe body cap:", probeBodyCapMb);
        row = addField(c, row, "Per-model probes:", perModelProbes);
        row = addHeader(c, row, "Transport");
        row = addField(c, row, "Transport retries:", transportRetries);
        row = addField(c, row, "Retry backoff:", retryBackoffSeconds);
        row = addField(c, row, "Retry-After cap:", retryAfterCapSeconds);
        row = addKeepAlive(c, row);
        row++;
        row = addHeader(c, row, "Debug log");
        row = addField(c, row, "Debug log size cap:", debugLogSizeCapMb);
        row = addField(c, row, "Debug log rolled files:", debugLogRolledFiles);
        row = addField(c, row, "Debug log queue:", debugLogQueueMb);
        row = addField(c, row, "Debug log queue entries:", debugLogQueueEntries);
        row = addField(c, row, "Debug log max entry:", debugLogMaxEntryKb);
        addField(c, row, "Debug log drain:", debugLogDrainSeconds);
        c.gridy = row + 1;
        c.gridx = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(warningLabel, c);
        c.gridwidth = 1;
        refreshWarnings();
    }

    /**
     * Shows the spec §8.1 conflicts in the warning line. Saving is not blocked: {@link #store()} applies the same rules
     * as clamps.
     */
    private void refreshWarnings() {
        List<String> warnings = new ArrayList<>();
        if (assumedWindow.get() < minimumWindowForTools.get()) {
            warnings.add("Assumed window is raised to the minimum for tools when saved.");
        }
        if (probeTimeoutSeconds.get() >= redetectIntervalSeconds.get()) {
            warnings.add("Probe timeout must be below the re-detect interval; it is lowered when saved.");
        }
        if (debugLogQueueMb.get() > debugLogSizeCapMb.get()) {
            warnings.add("The debug queue is limited to the log size when saved.");
        }
        if ((long) debugLogMaxEntryKb.get() * 1024L > (long) debugLogQueueMb.get() * MB) {
            warnings.add("The debug entry size is limited to the queue size when saved.");
        }
        warningLabel.setText(warnings.isEmpty() ? " " : String.join(" · ", warnings));
    }

    private int addHeader(GridBagConstraints c, int row, String title) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(FormLayoutUtil.label(title), c);
        c.gridwidth = 1;
        return row + 1;
    }

    private int addField(GridBagConstraints c, int row, String label, IntSpinner field) {
        return addField(c, row, label, field.spinner, field.hint);
    }

    private int addField(GridBagConstraints c, int row, String label, DoubleSpinner field) {
        return addField(c, row, label, field.spinner, field.hint);
    }

    private int addField(GridBagConstraints c, int row, String label, JComponent field, JLabel hint) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        panel.add(FormLayoutUtil.label(label), c);
        c.gridx = 1;
        panel.add(field, c);
        c.gridx = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(hint, c);
        return row + 1;
    }

    private int addKeepAlive(GridBagConstraints c, int row) {
        return addField(c, row, "keep_alive (Ollama):", keepAlive, keepAliveHint);
    }

    JComponent component() {
        return panel;
    }

    void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    void load() {
        updating = true;
        try {
            assumedWindow.set(OpenAiCompatiblePluginSettings.getAssumedWindow());
            minimumWindowForTools.set(OpenAiCompatiblePluginSettings.getMinimumWindowForTools());
            trainingLengthFactor.set(OpenAiCompatiblePluginSettings.getTrainingLengthFactor());
            smallWindowWarning.set(OpenAiCompatiblePluginSettings.getSmallWindowWarning());
            windowReadingsBeforeShrink.set(OpenAiCompatiblePluginSettings.getWindowReadingsBeforeShrink());
            summaryToolResultChars.set(OpenAiCompatiblePluginSettings.getSummaryToolResultChars());
            summaryRepairNudges.set(OpenAiCompatiblePluginSettings.getSummaryRepairNudges());
            overflowRetries.set(OpenAiCompatiblePluginSettings.getOverflowRetries());
            calibrationTolerance.set(OpenAiCompatiblePluginSettings.getCalibrationTolerance());
            estimatorCharsPerToken.set(OpenAiCompatiblePluginSettings.getEstimatorCharsPerToken());
            estimatorOverhead.set(OpenAiCompatiblePluginSettings.getEstimatorOverhead());
            estimatorSmoothing.set(OpenAiCompatiblePluginSettings.getEstimatorSmoothing());
            estimatorMinRatio.set(OpenAiCompatiblePluginSettings.getEstimatorMinRatio());
            estimatorMaxRatio.set(OpenAiCompatiblePluginSettings.getEstimatorMaxRatio());
            emptyReplyNudges.set(OpenAiCompatiblePluginSettings.getEmptyReplyNudges());
            toolDescriptionCapChars.set(OpenAiCompatiblePluginSettings.getToolDescriptionCapChars());
            parameterDescriptionCapChars.set(OpenAiCompatiblePluginSettings.getParameterDescriptionCapChars());
            schemaDepth.set(OpenAiCompatiblePluginSettings.getSchemaDepth());
            transcriptCapMb.set(bytesToMb(OpenAiCompatiblePluginSettings.getTranscriptCapBytes()));
            toolArgumentCapMb.set(bytesToMb(OpenAiCompatiblePluginSettings.getToolArgumentCapBytes()));
            totalRequestCapMinutes.set((int) OpenAiCompatiblePluginSettings.getTotalRequestCapMinutes());
            connectTimeoutSeconds.set((int) OpenAiCompatiblePluginSettings.getConnectTimeoutSeconds());
            abandonedToolWaitSeconds.set((int) OpenAiCompatiblePluginSettings.getAbandonedToolWaitSeconds());
            probeTimeoutSeconds.set((int) OpenAiCompatiblePluginSettings.getProbeTimeoutSeconds());
            redetectIntervalSeconds.set((int) OpenAiCompatiblePluginSettings.getRedetectIntervalSeconds());
            probeBodyCapMb.set(bytesToMb(OpenAiCompatiblePluginSettings.getProbeBodyCapBytes()));
            perModelProbes.set(OpenAiCompatiblePluginSettings.getPerModelProbes());
            transportRetries.set(OpenAiCompatiblePluginSettings.getTransportRetries());
            retryBackoffSeconds.set((int) OpenAiCompatiblePluginSettings.getRetryBackoffSeconds());
            retryAfterCapSeconds.set((int) OpenAiCompatiblePluginSettings.getRetryAfterCapSeconds());
            keepAlive.setText(OpenAiCompatiblePluginSettings.getKeepAlive());
            debugLogSizeCapMb.set(bytesToMb(OpenAiCompatiblePluginSettings.getDebugLogSizeCapBytes()));
            debugLogRolledFiles.set(OpenAiCompatiblePluginSettings.getDebugLogRolledFiles());
            debugLogQueueMb.set(bytesToMb(OpenAiCompatiblePluginSettings.getDebugLogQueueBytes()));
            debugLogQueueEntries.set(OpenAiCompatiblePluginSettings.getDebugLogQueueEntries());
            debugLogMaxEntryKb.set(bytesToKb(OpenAiCompatiblePluginSettings.getDebugLogMaxEntryBytes()));
            debugLogDrainSeconds.set((int) OpenAiCompatiblePluginSettings.getDebugLogDrainSeconds());
        }
        finally {
            updating = false;
        }
    }

    void store() {
        int minTools = minimumWindowForTools.get();
        OpenAiCompatiblePluginSettings.setMinimumWindowForTools(minTools);
        OpenAiCompatiblePluginSettings.setAssumedWindow(Math.max(assumedWindow.get(), minTools));
        OpenAiCompatiblePluginSettings.setTrainingLengthFactor(trainingLengthFactor.get());
        OpenAiCompatiblePluginSettings.setSmallWindowWarning(smallWindowWarning.get());
        OpenAiCompatiblePluginSettings.setWindowReadingsBeforeShrink(windowReadingsBeforeShrink.get());
        OpenAiCompatiblePluginSettings.setSummaryToolResultChars(summaryToolResultChars.get());
        OpenAiCompatiblePluginSettings.setSummaryRepairNudges(summaryRepairNudges.get());
        OpenAiCompatiblePluginSettings.setOverflowRetries(overflowRetries.get());
        OpenAiCompatiblePluginSettings.setCalibrationTolerance(calibrationTolerance.get());
        OpenAiCompatiblePluginSettings.setEstimatorCharsPerToken(estimatorCharsPerToken.get());
        OpenAiCompatiblePluginSettings.setEstimatorOverhead(estimatorOverhead.get());
        OpenAiCompatiblePluginSettings.setEstimatorSmoothing(estimatorSmoothing.get());
        OpenAiCompatiblePluginSettings.setEstimatorMinRatio(estimatorMinRatio.get());
        OpenAiCompatiblePluginSettings.setEstimatorMaxRatio(estimatorMaxRatio.get());
        OpenAiCompatiblePluginSettings.setEmptyReplyNudges(emptyReplyNudges.get());
        OpenAiCompatiblePluginSettings.setToolDescriptionCapChars(toolDescriptionCapChars.get());
        OpenAiCompatiblePluginSettings.setParameterDescriptionCapChars(parameterDescriptionCapChars.get());
        OpenAiCompatiblePluginSettings.setSchemaDepth(schemaDepth.get());
        OpenAiCompatiblePluginSettings.setTranscriptCapBytes(mbToBytes(transcriptCapMb.get()));
        OpenAiCompatiblePluginSettings.setToolArgumentCapBytes(mbToBytes(toolArgumentCapMb.get()));
        OpenAiCompatiblePluginSettings.setTotalRequestCapMinutes(totalRequestCapMinutes.get());
        OpenAiCompatiblePluginSettings.setConnectTimeoutSeconds(connectTimeoutSeconds.get());
        OpenAiCompatiblePluginSettings.setAbandonedToolWaitSeconds(abandonedToolWaitSeconds.get());
        int redetect = Math.max(2, redetectIntervalSeconds.get());
        int probe = Math.min(probeTimeoutSeconds.get(), redetect - 1);
        OpenAiCompatiblePluginSettings.setProbeTimeoutSeconds(Math.max(1, probe));
        OpenAiCompatiblePluginSettings.setRedetectIntervalSeconds(redetect);
        OpenAiCompatiblePluginSettings.setProbeBodyCapBytes(mbToBytes(probeBodyCapMb.get()));
        OpenAiCompatiblePluginSettings.setPerModelProbes(perModelProbes.get());
        OpenAiCompatiblePluginSettings.setTransportRetries(transportRetries.get());
        OpenAiCompatiblePluginSettings.setRetryBackoffSeconds(retryBackoffSeconds.get());
        OpenAiCompatiblePluginSettings.setRetryAfterCapSeconds(retryAfterCapSeconds.get());
        OpenAiCompatiblePluginSettings.setKeepAlive(keepAlive.getText().trim());
        int logMb = Math.max(SIZE_CAP_FLOOR_MB, debugLogSizeCapMb.get());
        int queueMb = Math.min(Math.max(SIZE_CAP_FLOOR_MB, debugLogQueueMb.get()), logMb);
        int maxEntryKb = Math.min(debugLogMaxEntryKb.get(), (int) Math.min(Integer.MAX_VALUE, queueMb * (MB / 1024L)));
        OpenAiCompatiblePluginSettings.setDebugLogSizeCapBytes(mbToBytes(logMb));
        OpenAiCompatiblePluginSettings.setDebugLogRolledFiles(debugLogRolledFiles.get());
        OpenAiCompatiblePluginSettings.setDebugLogQueueBytes(mbToBytes(queueMb));
        OpenAiCompatiblePluginSettings.setDebugLogQueueEntries(debugLogQueueEntries.get());
        OpenAiCompatiblePluginSettings.setDebugLogMaxEntryBytes(kbToBytes(Math.max(1, maxEntryKb)));
        OpenAiCompatiblePluginSettings.setDebugLogDrainSeconds(debugLogDrainSeconds.get());
        BackendDebugLogWriter.setShutdownDrainBound(
                Duration.ofSeconds(OpenAiCompatiblePluginSettings.getDebugLogDrainSeconds()));
    }

    int assumedWindow() {
        return assumedWindow.get();
    }

    void assumedWindow(int value) {
        assumedWindow.set(value);
    }

    int connectTimeoutSeconds() {
        return connectTimeoutSeconds.get();
    }

    void connectTimeoutSeconds(int value) {
        connectTimeoutSeconds.set(value);
    }

    String keepAlive() {
        return keepAlive.getText();
    }

    void keepAlive(String value) {
        keepAlive.setText(value == null ? "" : value);
    }

    private IntSpinner intSpinner(int defaultValue, int min, int max, String unit) {
        return new IntSpinner(defaultValue, min, max, unit);
    }

    private DoubleSpinner doubleSpinner(double defaultValue, double min, double max, double step, String unit) {
        return new DoubleSpinner(defaultValue, min, max, step, unit);
    }

    private void fireChange() {
        refreshWarnings();
        if (updating) {
            return;
        }
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    private static int bytesToMb(long bytes) {
        return (int) Math.max(1L, bytes / MB);
    }

    private static int bytesToKb(long bytes) {
        return (int) Math.max(1L, bytes / 1024L);
    }

    private static long mbToBytes(int mb) {
        return (long) mb * MB;
    }

    private static long kbToBytes(int kb) {
        return (long) kb * 1024L;
    }

    private final class IntSpinner {

        private final JSpinner spinner;
        private final JLabel hint;

        private IntSpinner(int defaultValue, int min, int max, String unit) {
            this.spinner = new JSpinner(new SpinnerNumberModel(defaultValue, min, max, 1));
            this.spinner.setPreferredSize(new Dimension(NUMERIC_FIELD_WIDTH, spinner.getPreferredSize().height));
            String suffix = unit.isEmpty() ? "" : " " + unit;
            this.hint = new JLabel("(default: " + defaultValue + suffix + ")");
            spinner.addChangeListener(e -> fireChange());
        }

        private int get() {
            return ((Number) spinner.getValue()).intValue();
        }

        private void set(int value) {
            spinner.setValue(value);
        }
    }

    private final class DoubleSpinner {

        private final JSpinner spinner;
        private final JLabel hint;

        private DoubleSpinner(double defaultValue, double min, double max, double step, String unit) {
            this.spinner = new JSpinner(new SpinnerNumberModel(defaultValue, min, max, step));
            this.spinner.setPreferredSize(new Dimension(NUMERIC_FIELD_WIDTH, spinner.getPreferredSize().height));
            String suffix = unit.isEmpty() ? "" : " " + unit;
            this.hint = new JLabel("(default: " + defaultValue + suffix + ")");
            spinner.addChangeListener(e -> fireChange());
        }

        private double get() {
            return ((Number) spinner.getValue()).doubleValue();
        }

        private void set(double value) {
            spinner.setValue(value);
        }
    }
}
