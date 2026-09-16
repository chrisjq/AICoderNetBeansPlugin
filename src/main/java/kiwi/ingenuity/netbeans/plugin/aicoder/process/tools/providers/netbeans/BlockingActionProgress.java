package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.netbeans.spi.project.ActionProgress;

/**
 * An {@link ActionProgress} that lets a caller block on an IDE action's own started()/finished() callbacks, for the
 * {@code ActionProvider} implementations that support reporting them. A provider that does not support {@link
 * ActionProgress} never calls back at all, so {@link #awaitStarted} timing out is how that absence is detected.
 */
class BlockingActionProgress extends ActionProgress {

    private final CountDownLatch startedLatch = new CountDownLatch(1);
    private final CountDownLatch finishedLatch = new CountDownLatch(1);
    private volatile boolean success;

    @Override
    protected void started() {
        startedLatch.countDown();
    }

    @Override
    public void finished(boolean success) {
        this.success = success;
        finishedLatch.countDown();
    }

    boolean awaitStarted(long timeoutMillis) throws InterruptedException {
        return startedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    boolean awaitFinished(long timeoutMillis) throws InterruptedException {
        return finishedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    boolean isSuccess() {
        return success;
    }
}
