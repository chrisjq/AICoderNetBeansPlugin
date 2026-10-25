package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory;

import java.io.IOException;
import java.nio.file.Path;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;

/**
 * Where an OpenAI Compatible session keeps its files. The session's own folder holds the saved context, the backend
 * debug log and, in a {@code memory} folder beside them, the session's notes; the backend and the memory tools both
 * resolve it here so they always agree.
 */
public final class SessionMemoryFolders {

    /**
     * Finds a session's own folder.
     */
    @FunctionalInterface
    public interface SessionDirResolver {

        Path resolve(String sessionId) throws IOException;
    }

    /**
     * The name of the notes folder inside the session's folder.
     */
    public static final String MEMORY_FOLDER_NAME = "memory";

    private static final SessionDirResolver DEFAULT = id -> PluginUtil
            .getPluginAiSessionConfigDir(AiTypeEnum.OPENAI_COMPATIBLE).resolve(id);
    private static volatile SessionDirResolver resolver = DEFAULT;

    private SessionMemoryFolders() {
    }

    /**
     * @return the session's own folder (not created here)
     */
    public static Path sessionDir(String sessionId) throws IOException {
        return resolver.resolve(sessionId);
    }

    /**
     * @return the session's notes folder inside {@code sessionDir}
     */
    public static Path memoryDir(Path sessionDir) {
        return sessionDir.resolve(MEMORY_FOLDER_NAME);
    }

    /**
     * @return the notes of one session, found through the shared session folder
     */
    public static SessionMemoryStore storeFor(String sessionId) throws IOException {
        return new SessionMemoryStore(memoryDir(sessionDir(sessionId)));
    }

    /**
     * Test seam: where session folders are; null restores the real location.
     */
    public static void setResolverForTest(SessionDirResolver replacement) {
        resolver = replacement == null ? DEFAULT : replacement;
    }
}
