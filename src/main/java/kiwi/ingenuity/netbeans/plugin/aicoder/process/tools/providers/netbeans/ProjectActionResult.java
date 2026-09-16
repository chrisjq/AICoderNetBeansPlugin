package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

/**
 * The outcome of an IDE build action invoked through {@link ProjectActionProvider}.
 */
public record ProjectActionResult(String message, Kind kind) {

    public enum Kind {
        COMPLETED,
        FAILED,
        NOT_TRACKED,
        NOT_AWAITED,
        STILL_RUNNING,
        ERROR
    }

    static ProjectActionResult completed(String message) {
        return new ProjectActionResult(message, Kind.COMPLETED);
    }

    static ProjectActionResult failed(String message) {
        return new ProjectActionResult(message, Kind.FAILED);
    }

    static ProjectActionResult notTracked(String message) {
        return new ProjectActionResult(message, Kind.NOT_TRACKED);
    }

    static ProjectActionResult notAwaited(String message) {
        return new ProjectActionResult(message, Kind.NOT_AWAITED);
    }

    static ProjectActionResult stillRunning(String message) {
        return new ProjectActionResult(message, Kind.STILL_RUNNING);
    }

    static ProjectActionResult error(String message) {
        return new ProjectActionResult(message, Kind.ERROR);
    }
}
