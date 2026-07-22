package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.AiMcpRegistrar;

public class OllamaMcpRegistrar extends AiMcpRegistrar {

    public OllamaMcpRegistrar(String sessionId) {
        this(sessionId, AiTypeEnum.OLLAMA_LOCAL);
    }

    public OllamaMcpRegistrar(String sessionId, AiTypeEnum type) {
        super(sessionId, type);
    }

    @Override
    public void addMcpEndpoint(String endpointUrl) {
    }

    @Override
    public void removeMcpEndpoint() {
    }

    @Override
    public boolean registerHooks(String serverBaseUrl) {
        return true;
    }

    @Override
    public void unregisterHooks() {
    }
}
