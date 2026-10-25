package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import com.google.gson.JsonObject;
import java.time.Duration;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleAdvancedSettingsTest {

    private static OpenAiCompatibleSessionSettings roundTrip(OpenAiCompatibleSessionSettings original) {
        JsonObject json = new JsonObject();
        original.populateJsonObject(json);
        OpenAiCompatibleSessionSettings restored = new OpenAiCompatibleSessionSettings();
        new OpenAiCompatibleSettingsCreator().update(restored, json);
        return restored;
    }

    @Test
    @Timeout(5)
    void everyNewFieldSurvivesAJsonRoundTrip() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        original.setFirstByteTimeoutSeconds(30);
        original.setIdleTimeoutSeconds(0);
        original.setTotalCapMinutes(15);
        original.setMaxToolRounds(25);
        original.setMaxLengthContinuations(3);
        original.setMaxMalformedRounds(4);
        original.setRepetitionWindow(6);
        original.setUnproductiveRounds(5);
        original.setNativeFailuresBeforeSchema(7);

        OpenAiCompatibleSessionSettings restored = roundTrip(original);

        assertEquals(30, restored.firstByteTimeoutSeconds());
        assertEquals(0, restored.idleTimeoutSeconds());
        assertEquals(15, restored.totalCapMinutes());
        assertEquals(25, restored.maxToolRounds());
        assertEquals(3, restored.maxLengthContinuations());
        assertEquals(4, restored.maxMalformedRounds());
        assertEquals(6, restored.repetitionWindow());
        assertEquals(5, restored.unproductiveRounds());
        assertEquals(7, restored.nativeFailuresBeforeSchema());
    }

    @Test
    @Timeout(5)
    void unsetFieldsStayOutOfTheJsonAndComeBackNull() {
        OpenAiCompatibleSessionSettings original = new OpenAiCompatibleSessionSettings();
        JsonObject json = new JsonObject();
        original.populateJsonObject(json);
        for (OpenAiCompatibleSessionSettingsKeyEnum key : new OpenAiCompatibleSessionSettingsKeyEnum[]{
            OpenAiCompatibleSessionSettingsKeyEnum.FIRST_BYTE_TIMEOUT_SECONDS,
            OpenAiCompatibleSessionSettingsKeyEnum.IDLE_TIMEOUT_SECONDS,
            OpenAiCompatibleSessionSettingsKeyEnum.TOTAL_CAP_MINUTES,
            OpenAiCompatibleSessionSettingsKeyEnum.MAX_TOOL_ROUNDS,
            OpenAiCompatibleSessionSettingsKeyEnum.MAX_LENGTH_CONTINUATIONS,
            OpenAiCompatibleSessionSettingsKeyEnum.MAX_MALFORMED_ROUNDS,
            OpenAiCompatibleSessionSettingsKeyEnum.REPETITION_WINDOW,
            OpenAiCompatibleSessionSettingsKeyEnum.UNPRODUCTIVE_ROUNDS,
            OpenAiCompatibleSessionSettingsKeyEnum.NATIVE_FAILURES_BEFORE_SCHEMA}) {
            assertFalse(json.has(key.key()), key.key());
        }

        OpenAiCompatibleSessionSettings restored = roundTrip(original);
        assertNull(restored.firstByteTimeoutSeconds());
        assertNull(restored.maxToolRounds());
        assertNull(restored.nativeFailuresBeforeSchema());
    }

    @Test
    @Timeout(5)
    void applyToReflectsOverridesAndLeavesNullsUnchanged() {
        OpenAiCompatibleLimits base = OpenAiCompatibleLimits.defaults();

        OpenAiCompatibleSessionSettings none = new OpenAiCompatibleSessionSettings();
        assertEquals(base, none.applyTo(base));

        OpenAiCompatibleSessionSettings idleOnly = new OpenAiCompatibleSessionSettings();
        idleOnly.setIdleTimeoutSeconds(10);
        OpenAiCompatibleLimits onlyIdle = idleOnly.applyTo(base);
        assertEquals(Duration.ofSeconds(10), onlyIdle.idleTimeout());
        assertEquals(base.firstByteTimeout(), onlyIdle.firstByteTimeout());
        assertEquals(base.totalCap(), onlyIdle.totalCap());
        assertEquals(base.connectTimeout(), onlyIdle.connectTimeout());

        OpenAiCompatibleSessionSettings all = new OpenAiCompatibleSessionSettings();
        all.setFirstByteTimeoutSeconds(30);
        all.setIdleTimeoutSeconds(0);
        all.setTotalCapMinutes(5);
        OpenAiCompatibleLimits applied = all.applyTo(base);
        assertEquals(Duration.ofSeconds(30), applied.firstByteTimeout());
        assertTrue(applied.idleTimeoutOff());
        assertEquals(Duration.ofMinutes(5), applied.totalCap());
    }

    @Test
    @Timeout(5)
    void timeoutFloorsAreClampedByTheLimitsRecord() {
        OpenAiCompatibleLimits base = OpenAiCompatibleLimits.defaults();

        OpenAiCompatibleSessionSettings zeroFirstByte = new OpenAiCompatibleSessionSettings();
        zeroFirstByte.setFirstByteTimeoutSeconds(0);
        assertEquals(Duration.ofSeconds(1), zeroFirstByte.applyTo(base).firstByteTimeout());

        OpenAiCompatibleSessionSettings shortTotal = new OpenAiCompatibleSessionSettings();
        shortTotal.setFirstByteTimeoutSeconds(100);
        shortTotal.setTotalCapMinutes(1);
        OpenAiCompatibleLimits applied = shortTotal.applyTo(base);
        assertTrue(applied.totalCap().compareTo(applied.firstByteTimeout()) > 0);
        assertTrue(applied.totalCap().compareTo(applied.idleTimeout()) > 0);
    }

    @Test
    @Timeout(5)
    void loopCountsFollowTheDefaultWhenUnsetAndClampToTheirFloors() {
        OpenAiCompatibleSessionSettings unset = new OpenAiCompatibleSessionSettings();
        assertEquals(0, unset.effectiveMaxToolRounds(OpenAiCompatibleSessionSettings.DEFAULT_MAX_TOOL_ROUNDS));
        assertEquals(2, unset.effectiveMaxLengthContinuations(
                     OpenAiCompatibleSessionSettings.DEFAULT_MAX_LENGTH_CONTINUATIONS));
        assertEquals(2, unset.effectiveMaxMalformedRounds(OpenAiCompatibleSessionSettings.DEFAULT_MAX_MALFORMED_ROUNDS));
        assertEquals(4, unset.effectiveRepetitionWindow(OpenAiCompatibleSessionSettings.DEFAULT_REPETITION_WINDOW));
        assertEquals(3, unset.effectiveUnproductiveRounds(OpenAiCompatibleSessionSettings.DEFAULT_UNPRODUCTIVE_ROUNDS));
        assertEquals(2, unset.effectiveNativeFailuresBeforeSchema(
                     OpenAiCompatibleSessionSettings.DEFAULT_NATIVE_FAILURES_BEFORE_SCHEMA));

        OpenAiCompatibleSessionSettings low = new OpenAiCompatibleSessionSettings();
        low.setMaxToolRounds(-5);
        low.setMaxLengthContinuations(-1);
        low.setMaxMalformedRounds(0);
        low.setRepetitionWindow(1);
        low.setUnproductiveRounds(0);
        low.setNativeFailuresBeforeSchema(0);
        assertEquals(0, low.effectiveMaxToolRounds(9));
        assertEquals(0, low.effectiveMaxLengthContinuations(9));
        assertEquals(1, low.effectiveMaxMalformedRounds(9));
        assertEquals(2, low.effectiveRepetitionWindow(9));
        assertEquals(1, low.effectiveUnproductiveRounds(9));
        assertEquals(1, low.effectiveNativeFailuresBeforeSchema(9));

        OpenAiCompatibleSessionSettings set = new OpenAiCompatibleSessionSettings();
        set.setMaxToolRounds(25);
        set.setRepetitionWindow(6);
        assertEquals(25, set.effectiveMaxToolRounds(0));
        assertEquals(6, set.effectiveRepetitionWindow(4));
    }
}
