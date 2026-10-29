package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;

/**
 * A reusable OpenAI-compatible server endpoint stored in the plugin settings.
 */
public record SavedServer(String id, String name, String url, ServerTypeEnum serverType) {

    public SavedServer {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        name = name == null ? "" : name.trim();
        url = BaseUrls.normalise(url == null ? "" : url.trim());
        serverType = serverType == null ? ServerTypeEnum.AUTO : serverType;
    }

    public static SavedServer create(String name, String url, ServerTypeEnum serverType) {
        return new SavedServer(null, name, url, serverType);
    }

    public SavedServer withValues(String newName, String newUrl, ServerTypeEnum newType) {
        return new SavedServer(id, newName, newUrl, newType);
    }

    public String label() {
        return name.isBlank() ? url : name + " — " + url;
    }
}
