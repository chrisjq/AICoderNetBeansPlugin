package kiwi.ingenuity.netbeans.plugin.aicoder.process;

/**
 * The single source of truth for MCP tool property (parameter) names.
 * <p>
 * Every tool exposes its parameters through a per-tool {@code *ParamEnum}, which names the subset it accepts; the wire
 * key itself comes from here, so the twenty tools that take a file path cannot end up advertising twenty slightly
 * different spellings of it. Anything that would otherwise write a property name as a string literal —
 * {@code props.add(...)} in a schema, {@code args.str(...)} in a handler — takes it from this enum instead.
 * <p>
 * Keys are camelCase without exception. {@code WriteFile} and {@code ApplyEdit} previously used snake_case
 * ({@code file_path}, {@code old_string}, {@code new_string}) while every other tool used camelCase, which meant an AI
 * that had learned one spelling supplied an argument the other tool silently ignored. McpToolPropertyEnumTest enforces
 * the convention.
 */
public enum McpToolPropertyEnum {
    /**
     * Requested operation.
     */
    ACTION("action"),
    /**
     * Whether to include every matching item.
     */
    ALL("all"),
    /**
     * Maven -am: build listed projects' dependencies too.
     */
    ALSO_MAKE("alsoMake"),
    /**
     * HTTP request body.
     */
    BODY("body"),
    /**
     * Git branch name.
     */
    BRANCH("branch"),
    /**
     * Whether matching honours letter case.
     */
    CASE_SENSITIVE("caseSensitive"),
    /**
     * Fully qualified class name.
     */
    CLASS_NAME("className"),
    /**
     * One-based source column.
     */
    COLUMN("column"),
    /**
     * Whether a refactoring proceeds despite non-fatal problems, reporting them instead of refusing. Never overrides a
     * fatal problem.
     */
    COMMIT_WITH_WARNING("commitWithWarning"),
    /**
     * Registered database connection name.
     */
    CONNECTION_NAME("connectionName"),
    /**
     * Text content to write.
     */
    CONTENT("content"),
    /**
     * Lines of surrounding context to include per match.
     */
    CONTEXT_LINES("contextLines"),
    /**
     * Gradle --continue / Maven -fae equivalent for Gradle: keep running other tasks after a failure instead of
     * stopping at the first one.
     */
    CONTINUE_ON_FAILURE("continueOnFailure"),
    /**
     * Whether a missing target may be created.
     */
    CREATE("create"),
    /**
     * Default value for a new parameter.
     */
    DEFAULT_VALUE("defaultValue"),
    /**
     * Session description text.
     */
    DESCRIPTION("description"),
    /**
     * Restrict subtype search to direct children.
     */
    DIRECT_SUBCLASSES_ONLY("directSubclassesOnly"),
    /**
     * Last line of a requested range.
     */
    END_LINE("endLine"),
    /**
     * Directory below which to search for files.
     */
    DIRECTORY_PATH("directoryPath"),
    /**
     * Whether a message requires a reply.
     */
    EXPECTS_REPLY("expectsReply"),
    /**
     * Maven -fae: run every module before failing, reporting all failures at the end instead of stopping at the first
     * one.
     */
    FAIL_AT_END("failAtEnd"),
    /**
     * File path used to scope an operation.
     */
    FILE("file"),
    /**
     * Absolute path of the file to act on.
     */
    FILE_PATH("filePath"),
    /**
     * Absolute paths of several files to act on together.
     */
    FILE_PATHS("filePaths"),
    /**
     * Glob pattern limiting candidate files.
     */
    FILE_PATTERN("filePattern"),
    /**
     * Files affected by a Git operation.
     */
    FILES("files"),
    /**
     * Whether to include subtypes.
     */
    FIND_SUBCLASSES("findSubclasses"),
    /**
     * Whether history follows renames.
     */
    FOLLOW("follow"),
    /**
     * Whether to force a Git operation.
     */
    FORCE("force"),
    /**
     * Whether an opened editor should receive focus.
     */
    FOCUS("focus"),
    /**
     * Maven goals to run (e.g. {@code package}, {@code clean install}). Gradle's equivalent is {@link #TASKS}, Ant's is
     * {@link #TARGETS} — each build system keeps its own vocabulary rather than sharing this key.
     */
    GOALS("goals"),
    /**
     * Question heading shown to the user.
     */
    HEADER("header"),
    /**
     * HTTP request headers.
     */
    HEADERS("headers"),
    /**
     * Whether a message should interrupt the recipient.
     */
    IMPORTANT("important"),
    /**
     * Whether hidden files and directories are excluded from a walk.
     */
    IGNORE_HIDDEN("ignoreHidden"),
    /**
     * Whether search includes dependency artifacts.
     */
    INCLUDE_DEPS("includeDeps"),
    /**
     * Whether stash includes untracked files.
     */
    INCLUDE_UNTRACKED("includeUntracked"),
    /**
     * Zero-based stash entry index.
     */
    INDEX("index"),
    /**
     * Whether query text is a regular expression.
     */
    IS_REGEX("isRegex"),
    /**
     * Ant -k: keep running other independent targets after one fails, instead of stopping immediately.
     */
    KEEP_GOING("keepGoing"),
    /**
     * Search match kind.
     */
    KIND("kind"),
    /**
     * Human-readable question label.
     */
    LABEL("label"),
    /**
     * Maximum result count.
     */
    LIMIT("limit"),
    /**
     * One-based source line.
     */
    LINE("line"),
    /**
     * Maximum returned character count.
     */
    MAX_CHARS("maxChars"),
    /**
     * Maximum directory depth to descend during a recursive walk.
     */
    MAX_DEPTH("maxDepth"),
    /**
     * Maximum number of matches to include in a result.
     */
    MAX_MATCHES("maxMatches"),
    /**
     * Member name to inspect.
     */
    MEMBER_NAME("memberName"),
    /**
     * Message content or annotation.
     */
    MESSAGE("message"),
    /**
     * Single inbox message identifier.
     */
    MESSAGE_ID("messageId"),
    /**
     * Inbox message identifiers.
     */
    MESSAGE_IDS("messageIds"),
    /**
     * HTTP request method.
     */
    METHOD("method"),
    /**
     * Method name to change.
     */
    METHOD_NAME("methodName"),
    /**
     * Whether multiple answers may be selected.
     */
    MULTI_SELECT("multiSelect"),
    /**
     * Name of the selected resource.
     */
    NAME("name"),
    /**
     * Replacement identifier name.
     */
    NEW_NAME("newName"),
    /**
     * Replacement text for an edit.
     */
    NEW_STRING("newString"),
    /**
     * Maven -o / Gradle --offline: work from the local repository/cache only, without contacting remote repos.
     */
    OFFLINE("offline"),
    /**
     * Exact text to replace.
     */
    OLD_STRING("oldString"),
    /**
     * Git operation to perform.
     */
    OPERATION("operation"),
    /**
     * Selectable answers to a question.
     */
    OPTIONS("options"),
    /**
     * Original parameter position.
     */
    ORIGINAL_INDEX("originalIndex"),
    /**
     * Whether to create an overload.
     */
    OVERLOAD_METHOD("overloadMethod"),
    /**
     * Gradle --parallel: run independent tasks (e.g. across subprojects) concurrently.
     */
    PARALLEL("parallel"),
    /**
     * Desired method parameters.
     */
    PARAMETERS("parameters"),
    /**
     * Literal text or regex to match lines against.
     */
    PATTERN("pattern"),
    /**
     * Maven -P: profiles to activate.
     */
    PROFILES("profiles"),
    /**
     * Maven -pl: comma/colon-delimited module subset (submitted as a list here, joined internally).
     */
    PROJECT_LIST("projectList"),
    /**
     * Target project or repository path.
     */
    PROJECT_PATH("projectPath"),
    /**
     * Maven -Dk=v / Ant -Dk=v / Gradle -Pk=v: build properties as a key/value map, never a raw string — see
     * {@link #SYSTEM_PROPERTIES} for Gradle's separate -D-style system properties.
     */
    PROPERTIES("properties"),
    /**
     * Search or SQL query text.
     */
    QUERY("query"),
    /**
     * Question shown to the user.
     */
    QUESTION("question"),
    /**
     * Questions to present to the user.
     */
    QUESTIONS("questions"),
    /**
     * Gradle --refresh-dependencies: bypass the dependency cache and re-resolve everything.
     */
    REFRESH_DEPENDENCIES("refreshDependencies"),
    /**
     * Git remote name.
     */
    REMOTE("remote"),
    /**
     * Whether a reply should interrupt its sender.
     */
    REPLY_IMPORTANT("replyImportant"),
    /**
     * Original message being answered.
     */
    REPLY_TO_MESSAGE_ID("replyToMessageId"),
    /**
     * Maven -rf: resume a reactor build from this module (e.g. {@code :my-module}).
     */
    RESUME_FROM("resumeFrom"),
    /**
     * Desired method return type.
     */
    RETURN_TYPE("returnType"),
    /**
     * Git revision identifier.
     */
    REVISION("revision"),
    /**
     * Git revision identifiers.
     */
    REVISIONS("revisions"),
    /**
     * Whether search includes comments.
     */
    SEARCH_IN_COMMENTS("searchInComments"),
    /**
     * Session authentication secret.
     */
    SECRET_KEY("secretKey"),
    /**
     * AI session identifier.
     */
    SESSION_ID("sessionId"),
    /**
     * Maven -DskipTests / Gradle -x test: skip running tests as part of the build. Default differs by tool — see each
     * tool's own description for what it preserves from before this option existed.
     */
    SKIP_TESTS("skipTests"),
    /**
     * Source file path for a copy or move.
     */
    SOURCE_PATH("sourcePath"),
    /**
     * Read-only SQL query.
     */
    SQL("sql"),
    /**
     * Whether Git diff is staged.
     */
    STAGED("staged"),
    /**
     * First line of a requested range.
     */
    START_LINE("startLine"),
    /**
     * Message subject line.
     */
    SUBJECT("subject"),
    /**
     * Gradle -Dk=v: JVM system properties, distinct from Gradle's own -Pk=v project properties — see
     * {@link #PROPERTIES}.
     */
    SYSTEM_PROPERTIES("systemProperties"),
    /**
     * Database table name.
     */
    TABLE_NAME("tableName"),
    /**
     * Destination directory path.
     */
    TARGET_DIRECTORY("targetDirectory"),
    /**
     * Destination Java package.
     */
    TARGET_PACKAGE("targetPackage"),
    /**
     * Optional destination project root — omitted keeps a move inside the source file's own project; given, moves
     * across module boundaries into this project instead.
     */
    TARGET_PROJECT_PATH("targetProjectPath"),
    /**
     * Ant targets to run (e.g. {@code jar}, {@code dist}). Maven's equivalent is {@link #GOALS}, Gradle's is
     * {@link #TASKS}.
     */
    TARGETS("targets"),
    /**
     * Receiving AI session identifier.
     */
    TARGET_SESSION_ID("targetSessionId"),
    /**
     * Gradle tasks to run (e.g. {@code build}, {@code assemble}, {@code :module:build}). Maven's equivalent is
     * {@link #GOALS}, Ant's is {@link #TARGETS}.
     */
    TASKS("tasks"),
    /**
     * Optional test class filter.
     */
    TEST_CLASS("testClass"),
    /**
     * Maven -T: number of threads/modules to build in parallel (e.g. {@code 4} or {@code 1C}).
     */
    THREADS("threads"),
    /**
     * HTTP request timeout in seconds.
     */
    TIMEOUT_SECONDS("timeoutSeconds"),
    /**
     * Requested resource type.
     */
    TYPE("type"),
    /**
     * Maven -U: force a check for updated releases/snapshots on remote repositories.
     */
    UPDATE_SNAPSHOTS("updateSnapshots"),
    /**
     * Git rebase upstream.
     */
    UPSTREAM("upstream"),
    /**
     * HTTP request URL.
     */
    URL("url");

    private final String key;

    McpToolPropertyEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
