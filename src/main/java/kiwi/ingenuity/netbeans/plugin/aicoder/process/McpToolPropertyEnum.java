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
     * File path used to scope an operation.
     */
    FILE("file"),
    /**
     * Absolute path of the file to act on.
     */
    FILE_PATH("filePath"),
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
     * Desired method parameters.
     */
    PARAMETERS("parameters"),
    /**
     * Literal text or regex to match lines against.
     */
    PATTERN("pattern"),
    /**
     * Target project or repository path.
     */
    PROJECT_PATH("projectPath"),
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
     * Receiving AI session identifier.
     */
    TARGET_SESSION_ID("targetSessionId"),
    /**
     * Optional test class filter.
     */
    TEST_CLASS("testClass"),
    /**
     * HTTP request timeout in seconds.
     */
    TIMEOUT_SECONDS("timeoutSeconds"),
    /**
     * Requested resource type.
     */
    TYPE("type"),
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
