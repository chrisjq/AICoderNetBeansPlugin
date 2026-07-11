package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class AiSessionSettingsTest {

    @Test
    void effectiveAllowWebRequests_explicitTrueOverridesGlobal() {
        AiSessionSettings cfg = new AiSessionSettings(
                null, null, null, null, null, null, null, true);
        assertEquals(true, cfg.effectiveAllowWebRequests());
    }

    @Test
    void effectiveAllowWebRequests_explicitFalseOverridesGlobal() {
        AiSessionSettings cfg = new AiSessionSettings(
                null, null, null, null, null, null, null, false);
        assertEquals(false, cfg.effectiveAllowWebRequests());
    }

    @Test
    void effectiveAllowWebRequests_nullFallsThroughToGlobalDefault() {
        AiSessionSettings cfg = new AiSessionSettings(
                null, null, null, null, null, null, null, null);
        assertEquals(PluginSettings.isAllowWebRequests(), cfg.effectiveAllowWebRequests());
    }

    @Test
    void effectiveAllowWebRequestPost_explicitFalseOverridesGlobal() {
        AiSessionSettings cfg = new AiSessionSettings();
        cfg.setAllowWebRequestAccess(WebRequestAccessOptionEnum.POST, false);
        assertEquals(false,
                cfg.effectiveAllowWebRequestAccess(WebRequestAccessOptionEnum.POST));
    }

    @Test
    void effectiveAllowWebRequestPost_nullFallsThroughToGlobalDefault() {
        AiSessionSettings cfg = new AiSessionSettings();
        assertEquals(PluginSettings.isAllowWebRequestAccess(WebRequestAccessOptionEnum.POST),
                cfg.effectiveAllowWebRequestAccess(WebRequestAccessOptionEnum.POST));
    }

    @Test
    void setAutoAccept_preservesAllowWebRequestAccess() {
        AiSessionSettings cfg = new AiSessionSettings(
                null, null, null, null, null, null, null, true);
        cfg.setAllowWebRequestAccess(WebRequestAccessOptionEnum.POST, false);
        cfg.setAutoAccept(true);
        assertEquals(true, cfg.effectiveAllowWebRequests());
        assertEquals(false,
                cfg.effectiveAllowWebRequestAccess(WebRequestAccessOptionEnum.POST));
    }
}
