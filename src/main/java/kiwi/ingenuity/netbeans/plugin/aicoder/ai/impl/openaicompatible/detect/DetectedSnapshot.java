package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

/**
 * What a running session knows about its endpoint right now, for the Detected panel (spec §8.4).
 *
 * @param facts the last accepted detection snapshot, or null before the first probe answered
 * @param reachable false while the last probe cycle found the server unreachable
 * @param streaming FALSE once the server was seen not to stream, what detection says otherwise, null when unknown
 * @param toolCallingMode the tool-calling mode in force, for example "Auto" or "Schema (switched from Auto)"
 * @param compactionStrategy the compaction the settings resolve to now, for example "Drop" or "Summarise"
 * @param prunedCount how many tool results have been pruned so far
 * @param window the context window in force and where it came from
 */
public record DetectedSnapshot(DetectedFacts facts, boolean reachable, Boolean streaming, String toolCallingMode,
                               String compactionStrategy, int prunedCount, WindowReading window) {

}
