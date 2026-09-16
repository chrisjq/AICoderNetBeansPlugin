package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build;

/**
 * The part of a build that runs when its turn in the queue comes. Everything that can be checked before that
 * (arguments, project path, scope) is validated before the build is queued.
 */
@FunctionalInterface
public interface BuildWork {

    BuildOutcome run(BuildControl control);
}
