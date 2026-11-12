package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import javax.swing.Timer;

/**
 * Schedules delayed stream-splitting work.
 */
interface SplitScheduler {

    Runnable schedule(Runnable task, long delayMillis);

    static SplitScheduler swing() {
        return (task, delayMillis) -> {
            Timer timer = new Timer((int) Math.min(Integer.MAX_VALUE,
                                                   Math.max(0L, delayMillis)), event -> task.run());
            timer.setRepeats(false);
            timer.start();
            return timer::stop;
        };
    }
}
