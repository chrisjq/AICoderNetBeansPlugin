package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

/**
 * Parameter-name keys for the SearchTypesTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum SearchTypesParamEnum {
    FILE_PATH("filePath"),
    NAME("name"),
    KIND("kind"),
    INCLUDE_DEPS("includeDeps");

    private final String key;

    SearchTypesParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
