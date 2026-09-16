package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOutputFormatter;

/**
 * Rejects incorrectly shaped JSON build options before a prepared build can enter the shared queue.
 */
final class BuildOptionShapeValidator {

    /**
     * @param backend which build tool the prepared build resolved to, or null when that could not be determined (there
     * is no prepared build, or preparing it already failed) — every backend's option checks are then run together
     * instead of just one, since a wrong-type option (e.g. {@code goals} sent as a plain string) can itself be why
     * preparation failed, and that shape error must still be found and take precedence over the misleading provider
     * error it caused. This is safe because each backend's checks only ever inspect its own option names
     * ({@code goals}/{@code projectList}/{@code profiles}/{@code resumeFrom}/{@code threads}/… for Maven;
     * {@code tasks}/{@code systemProperties}/… for Gradle; {@code targets}/… for Ant), so a key absent for that backend
     * simply passes.
     */
    static String validate(BuildOutputFormatter.Backend backend, ToolRequestArguments args) {
        String error = args.requireBooleanIfPresent(McpToolPropertyEnum.ASYNC.key());
        if (error != null) {
            return error;
        }
        error = args.requireStringIfPresent(McpToolPropertyEnum.PROJECT_PATH.key());
        if (error != null) {
            return error;
        }
        // Checked once, for every backend, regardless of whether this tool call is a test tool: a non-test tool being
        // refused for a wrongly shaped testClass it will never use is fine, and simpler than tracking which tools care.
        error = args.requireStringIfPresent(McpToolPropertyEnum.TEST_CLASS.key());
        if (error != null) {
            return error;
        }
        if (backend == null) {
            error = validateMaven(args);
            if (error != null) {
                return error;
            }
            error = validateGradle(args);
            if (error != null) {
                return error;
            }
            return validateAnt(args);
        }
        return switch (backend) {
            case MAVEN ->
                validateMaven(args);
            case GRADLE ->
                validateGradle(args);
            case ANT ->
                validateAnt(args);
        };
    }

    private static String validateMaven(ToolRequestArguments args) {
        String error = stringArrays(args, McpToolPropertyEnum.GOALS.key(), McpToolPropertyEnum.PROJECT_LIST.key(),
                                    McpToolPropertyEnum.PROFILES.key());
        if (error != null) {
            return error;
        }
        error = strings(args, McpToolPropertyEnum.RESUME_FROM.key(), McpToolPropertyEnum.THREADS.key());
        if (error != null) {
            return error;
        }
        error = args.requireObjectIfPresent(McpToolPropertyEnum.PROPERTIES.key(), "key/value map");
        return error != null ? error : booleans(args, McpToolPropertyEnum.ALSO_MAKE.key(), McpToolPropertyEnum.SKIP_TESTS.key(),
                                                McpToolPropertyEnum.OFFLINE.key(), McpToolPropertyEnum.UPDATE_SNAPSHOTS.key(),
                                                McpToolPropertyEnum.FAIL_AT_END.key());
    }

    private static String validateGradle(ToolRequestArguments args) {
        String error = stringArrays(args, McpToolPropertyEnum.TASKS.key());
        if (error != null) {
            return error;
        }
        error = args.requireObjectIfPresent(McpToolPropertyEnum.PROPERTIES.key(), "key/value map");
        if (error != null) {
            return error;
        }
        error = args.requireObjectIfPresent(McpToolPropertyEnum.SYSTEM_PROPERTIES.key(), "key/value map");
        return error != null ? error : booleans(args, McpToolPropertyEnum.SKIP_TESTS.key(), McpToolPropertyEnum.OFFLINE.key(),
                                                McpToolPropertyEnum.REFRESH_DEPENDENCIES.key(), McpToolPropertyEnum.PARALLEL.key(),
                                                McpToolPropertyEnum.CONTINUE_ON_FAILURE.key());
    }

    private static String validateAnt(ToolRequestArguments args) {
        String error = stringArrays(args, McpToolPropertyEnum.TARGETS.key());
        if (error != null) {
            return error;
        }
        error = args.requireObjectIfPresent(McpToolPropertyEnum.PROPERTIES.key(), "key/value map");
        return error != null ? error : booleans(args, McpToolPropertyEnum.KEEP_GOING.key());
    }

    private static String stringArrays(ToolRequestArguments args, String... keys) {
        for (String key : keys) {
            String error = args.requireStringArrayIfPresent(key);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private static String strings(ToolRequestArguments args, String... keys) {
        for (String key : keys) {
            String error = args.requireStringIfPresent(key);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private static String booleans(ToolRequestArguments args, String... keys) {
        for (String key : keys) {
            String error = args.requireBooleanIfPresent(key);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private BuildOptionShapeValidator() {
    }
}
