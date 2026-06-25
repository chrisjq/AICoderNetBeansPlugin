package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

/**
 * Parameter-name keys for the SearchInFilesTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum SearchInFilesParamEnum {
    FILE_PATH("filePath"),
    QUERY("query"),
    FILE_PATTERN("filePattern"),
    CASE_SENSITIVE("caseSensitive"),
    IS_REGEX("isRegex");

    private final String key;

    SearchInFilesParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
