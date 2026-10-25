package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SettingsTab;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openide.util.Lookup;

@Timeout(5)
class OpenAiCompatibleSettingsTabTest {

    @Test
    void reportsTheOpenAiCompatibleType() {
        assertEquals(AiTypeEnum.OPENAI_COMPATIBLE, new OpenAiCompatibleSettingsTab().getAiType());
    }

    @Test
    void isRegisteredAsASettingsTab() {
        assertTrue(Lookup.getDefault().lookupAll(SettingsTab.class).stream()
                .anyMatch(tab -> tab instanceof OpenAiCompatibleSettingsTab));
    }
}
