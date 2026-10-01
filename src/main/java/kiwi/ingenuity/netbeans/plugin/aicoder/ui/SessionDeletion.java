package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;

/**
 * Permanently deletes a stored session. A session that has an open tab is deleted by that tab, which stops it
 * writing the session back and does the file work off the calling thread; any other session is deleted
 * directly.
 */
public final class SessionDeletion {

    private SessionDeletion() {
    }

    /**
     * @param closeAndDeleteTab closes the open tab for the given session id and deletes the session, or
     *                          returns null when no tab is open for it
     *
     * @return completes when the stored session has been removed
     */
    public static CompletableFuture<Void> delete(String sessionId, Function<String, CompletableFuture<Void>> closeAndDeleteTab,
                                                 SessionPersistenceManager manager) throws IOException {
        CompletableFuture<Void> tabDeletion = closeAndDeleteTab.apply(sessionId);
        if (tabDeletion != null) {
            return tabDeletion;
        }
        manager.delete(sessionId);
        return CompletableFuture.completedFuture(null);
    }
}
