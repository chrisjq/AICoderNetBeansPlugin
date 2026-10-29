package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import java.time.Duration;
import java.util.prefs.Preferences;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatiblePluginSettingsLimitsTest {

    private Preferences preferences;

    @BeforeEach
    void setUp() {
        preferences = MemoryPreferences.create();
        OpenAiCompatiblePluginSettings.useForTest(preferences);
    }

    @AfterEach
    void tearDown() throws Exception {
        OpenAiCompatiblePluginSettings.useForTest(null);
        preferences.removeNode();
    }

    @Test
    @Timeout(10)
    void unsetValuesReadAsSpecDefaultsAndLimitsMatchTheRecordDefaults() {
        assertEquals(8192, OpenAiCompatiblePluginSettings.getAssumedWindow());
        assertEquals(6144, OpenAiCompatiblePluginSettings.getMinimumWindowForTools());
        assertEquals(0.8, OpenAiCompatiblePluginSettings.getTrainingLengthFactor());
        assertEquals(2000, OpenAiCompatiblePluginSettings.getSummaryToolResultChars());
        assertEquals(0.02, OpenAiCompatiblePluginSettings.getCalibrationTolerance());
        assertEquals(3.5, OpenAiCompatiblePluginSettings.getEstimatorCharsPerToken());
        assertEquals("5m", OpenAiCompatiblePluginSettings.getKeepAlive());
        assertEquals(10L << 20, OpenAiCompatiblePluginSettings.getDebugLogSizeCapBytes());
        assertEquals(OpenAiCompatibleLimits.defaults(), OpenAiCompatiblePluginSettings.fromPluginSettings());
    }

    @Test
    @Timeout(10)
    void everyPluginWideValueRoundTrips() {
        OpenAiCompatiblePluginSettings.setAssumedWindow(12000);
        OpenAiCompatiblePluginSettings.setMinimumWindowForTools(4096);
        OpenAiCompatiblePluginSettings.setTrainingLengthFactor(0.5);
        OpenAiCompatiblePluginSettings.setSmallWindowWarning(8000);
        OpenAiCompatiblePluginSettings.setWindowReadingsBeforeShrink(3);
        OpenAiCompatiblePluginSettings.setSummaryToolResultChars(1500);
        OpenAiCompatiblePluginSettings.setSummaryRepairNudges(2);
        OpenAiCompatiblePluginSettings.setOverflowRetries(0);
        OpenAiCompatiblePluginSettings.setCalibrationTolerance(0.05);
        OpenAiCompatiblePluginSettings.setEstimatorCharsPerToken(4.0);
        OpenAiCompatiblePluginSettings.setEstimatorOverhead(6);
        OpenAiCompatiblePluginSettings.setEstimatorSmoothing(0.25);
        OpenAiCompatiblePluginSettings.setEstimatorMinRatio(0.6);
        OpenAiCompatiblePluginSettings.setEstimatorMaxRatio(1.8);
        OpenAiCompatiblePluginSettings.setEmptyReplyNudges(2);
        OpenAiCompatiblePluginSettings.setToolDescriptionCapChars(800);
        OpenAiCompatiblePluginSettings.setParameterDescriptionCapChars(200);
        OpenAiCompatiblePluginSettings.setSchemaDepth(3);
        OpenAiCompatiblePluginSettings.setTranscriptCapBytes(128L << 20);
        OpenAiCompatiblePluginSettings.setToolArgumentCapBytes(2L << 20);
        OpenAiCompatiblePluginSettings.setTotalRequestCapMinutes(30);
        OpenAiCompatiblePluginSettings.setConnectTimeoutSeconds(5);
        OpenAiCompatiblePluginSettings.setAbandonedToolWaitSeconds(120);
        OpenAiCompatiblePluginSettings.setProbeTimeoutSeconds(4);
        OpenAiCompatiblePluginSettings.setRedetectIntervalSeconds(90);
        OpenAiCompatiblePluginSettings.setProbeBodyCapBytes(2L << 20);
        OpenAiCompatiblePluginSettings.setPerModelProbes(4);
        OpenAiCompatiblePluginSettings.setTransportRetries(2);
        OpenAiCompatiblePluginSettings.setRetryBackoffSeconds(3);
        OpenAiCompatiblePluginSettings.setRetryAfterCapSeconds(45);
        OpenAiCompatiblePluginSettings.setKeepAlive("");
        OpenAiCompatiblePluginSettings.setDebugLogSizeCapBytes(20L << 20);
        OpenAiCompatiblePluginSettings.setDebugLogRolledFiles(2);
        OpenAiCompatiblePluginSettings.setDebugLogQueueBytes(16L << 20);
        OpenAiCompatiblePluginSettings.setDebugLogQueueEntries(5000);
        OpenAiCompatiblePluginSettings.setDebugLogMaxEntryBytes(512L << 10);
        OpenAiCompatiblePluginSettings.setDebugLogDrainSeconds(4);

        assertEquals(12000, OpenAiCompatiblePluginSettings.getAssumedWindow());
        assertEquals(4096, OpenAiCompatiblePluginSettings.getMinimumWindowForTools());
        assertEquals(0.5, OpenAiCompatiblePluginSettings.getTrainingLengthFactor());
        assertEquals(8000, OpenAiCompatiblePluginSettings.getSmallWindowWarning());
        assertEquals(3, OpenAiCompatiblePluginSettings.getWindowReadingsBeforeShrink());
        assertEquals(1500, OpenAiCompatiblePluginSettings.getSummaryToolResultChars());
        assertEquals(2, OpenAiCompatiblePluginSettings.getSummaryRepairNudges());
        assertEquals(0, OpenAiCompatiblePluginSettings.getOverflowRetries());
        assertEquals(0.05, OpenAiCompatiblePluginSettings.getCalibrationTolerance());
        assertEquals(4.0, OpenAiCompatiblePluginSettings.getEstimatorCharsPerToken());
        assertEquals(6, OpenAiCompatiblePluginSettings.getEstimatorOverhead());
        assertEquals(0.25, OpenAiCompatiblePluginSettings.getEstimatorSmoothing());
        assertEquals(0.6, OpenAiCompatiblePluginSettings.getEstimatorMinRatio());
        assertEquals(1.8, OpenAiCompatiblePluginSettings.getEstimatorMaxRatio());
        assertEquals(2, OpenAiCompatiblePluginSettings.getEmptyReplyNudges());
        assertEquals(800, OpenAiCompatiblePluginSettings.getToolDescriptionCapChars());
        assertEquals(200, OpenAiCompatiblePluginSettings.getParameterDescriptionCapChars());
        assertEquals(3, OpenAiCompatiblePluginSettings.getSchemaDepth());
        assertEquals(128L << 20, OpenAiCompatiblePluginSettings.getTranscriptCapBytes());
        assertEquals(2L << 20, OpenAiCompatiblePluginSettings.getToolArgumentCapBytes());
        assertEquals(30, OpenAiCompatiblePluginSettings.getTotalRequestCapMinutes());
        assertEquals(5, OpenAiCompatiblePluginSettings.getConnectTimeoutSeconds());
        assertEquals(120, OpenAiCompatiblePluginSettings.getAbandonedToolWaitSeconds());
        assertEquals(4, OpenAiCompatiblePluginSettings.getProbeTimeoutSeconds());
        assertEquals(90, OpenAiCompatiblePluginSettings.getRedetectIntervalSeconds());
        assertEquals(2L << 20, OpenAiCompatiblePluginSettings.getProbeBodyCapBytes());
        assertEquals(4, OpenAiCompatiblePluginSettings.getPerModelProbes());
        assertEquals(2, OpenAiCompatiblePluginSettings.getTransportRetries());
        assertEquals(3, OpenAiCompatiblePluginSettings.getRetryBackoffSeconds());
        assertEquals(45, OpenAiCompatiblePluginSettings.getRetryAfterCapSeconds());
        assertEquals("", OpenAiCompatiblePluginSettings.getKeepAlive());
        assertEquals(20L << 20, OpenAiCompatiblePluginSettings.getDebugLogSizeCapBytes());
        assertEquals(2, OpenAiCompatiblePluginSettings.getDebugLogRolledFiles());
        assertEquals(16L << 20, OpenAiCompatiblePluginSettings.getDebugLogQueueBytes());
        assertEquals(5000, OpenAiCompatiblePluginSettings.getDebugLogQueueEntries());
        assertEquals(512L << 10, OpenAiCompatiblePluginSettings.getDebugLogMaxEntryBytes());
        assertEquals(4, OpenAiCompatiblePluginSettings.getDebugLogDrainSeconds());
    }

    @Test
    @Timeout(10)
    void fromPluginSettingsCarriesStoredValuesAndClampsToSpecFloors() {
        OpenAiCompatiblePluginSettings.setConnectTimeoutSeconds(0);
        OpenAiCompatiblePluginSettings.setTranscriptCapBytes(1);
        OpenAiCompatiblePluginSettings.setToolArgumentCapBytes(1);
        OpenAiCompatiblePluginSettings.setProbeBodyCapBytes(1);
        OpenAiCompatiblePluginSettings.setTransportRetries(-3);
        OpenAiCompatiblePluginSettings.setProbeTimeoutSeconds(90);
        OpenAiCompatiblePluginSettings.setRedetectIntervalSeconds(60);
        OpenAiCompatiblePluginSettings.setTotalRequestCapMinutes(0);

        OpenAiCompatibleLimits limits = OpenAiCompatiblePluginSettings.fromPluginSettings();

        assertEquals(Duration.ofSeconds(1), limits.connectTimeout());
        assertEquals(1L << 20, limits.transcriptCapBytes());
        assertEquals(1L << 20, limits.toolArgumentCapBytes());
        assertEquals(1L << 20, limits.probeBodyCapBytes());
        assertEquals(0, limits.transportRetries());
        assertEquals(Duration.ofSeconds(59), limits.probeTimeout());
        assertEquals(Duration.ofSeconds(601), limits.totalCap());
    }

    @Test
    @Timeout(10)
    void assumedWindowNeverFallsBelowTheMinimumForTools() {
        OpenAiCompatiblePluginSettings.setMinimumWindowForTools(4096);
        OpenAiCompatiblePluginSettings.setAssumedWindow(1000);
        assertEquals(4096, OpenAiCompatiblePluginSettings.getAssumedWindow());

        OpenAiCompatiblePluginSettings.setMinimumWindowForTools(100);
        assertEquals(2048, OpenAiCompatiblePluginSettings.getMinimumWindowForTools());
        assertEquals(2048, OpenAiCompatiblePluginSettings.getAssumedWindow());
    }
}
