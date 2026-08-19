package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings;

import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ModelCreateSettingsPanel;

/**
 * Session-create panel for Codex. Model only — Codex has no analogue of
 * OpenCode's build/plan mode, and reasoning effort is an info-bar concern
 * (design doc §6), not a create-time default, so it is not offered here.
 *
 * <p>
 * Extends the shared panel rather than wrapping it. OpenCode and Ollama wrap it
 * because they add controls of their own beside the model — a mode selector and
 * a base URL respectively — and so need to own the layout. Codex adds nothing,
 * so wrapping bought only four delegating methods.
 */
public final class CodexCreateSettingsPanel extends ModelCreateSettingsPanel<CodexSessionSettings> {

    public CodexCreateSettingsPanel(AiModelCatalog catalog) {
        super(AiTypeEnum.CODEX, catalog, CodexSessionSettings::model, CodexSessionSettings::setModel);
    }

    @Override
    protected List<String> knownModels() {
        return Arrays.asList(CodexPluginSettings.getKnownModels());
    }

    @Override
    protected String defaultModel() {
        return CodexPluginSettings.getModel();
    }
}
