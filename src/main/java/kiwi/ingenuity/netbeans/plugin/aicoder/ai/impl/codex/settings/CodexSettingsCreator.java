package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.CodexAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiModelSessionSettingsCreator;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

/**
 * Creates and updates Codex-specific AI session settings. Handles instantiation
 * and configuration updates for the Codex AI implementation.
 */
public class CodexSettingsCreator extends AiModelSessionSettingsCreator<CodexSessionSettings> {

    @Override
    public CodexSessionSettings create() {
        return new CodexSessionSettings();
    }

    @Override
    public AiSessionCreateSettingsPanel<CodexSessionSettings> createSettingsPanel() {
        return new CodexCreateSettingsPanel(CodexAiImplementation.modelCatalog());
    }

    @Override
    public void update(CodexSessionSettings settings, JsonObject cfgObj) {
        super.update(settings, cfgObj);
        String threadIdKey = CodexSessionSettingsKeyEnum.THREAD_ID.key();
        if (cfgObj.has(threadIdKey) && !cfgObj.get(threadIdKey).isJsonNull()) {
            settings.setThreadId(cfgObj.get(threadIdKey).getAsString());
        }
    }

    @Override
    public void applyDefaultSettingsFromGlobal(AiSessionSettings settings) {
    }
}
