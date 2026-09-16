package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A consistent view of the build queue at one moment.
 *
 * @param takenAt when the snapshot was taken
 * @param current the running build first, then queued builds in the order they will run
 * @param recent the most recent finished builds, newest first
 * @param longestSuccessByProject the longest a build of each project has taken to run successfully, keyed by project
 * key. Carried in the snapshot rather than read back from the queue while the report is rendered, so every figure in
 * one report comes from the same moment.
 */
public record BuildQueueSnapshot(Instant takenAt, List<BuildJob> current, List<BuildJob> recent,
                                 Map<String, Duration> longestSuccessByProject) {

}
