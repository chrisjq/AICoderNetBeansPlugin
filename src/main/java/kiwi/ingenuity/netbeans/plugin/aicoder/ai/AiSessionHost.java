package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;

public interface AiSessionHost {

    File resolveWorkDir();

    void suppressNextTurn(String statusMessage, String completionMessage);

    AiSessionSettings getSessionSettings();

    void updateSessionSettings(AiSessionSettings newSettings);
}
