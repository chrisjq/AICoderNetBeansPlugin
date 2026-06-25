package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AbstractAiSessionSettings;

public interface AiSessionHost {

    File resolveWorkDir();

    void suppressNextTurn(String statusMessage, String completionMessage);

    AbstractAiSessionSettings getSessionSettings();

    void updateSessionSettings(AbstractAiSessionSettings newSettings);
}
