package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.search;

/**
 * Parameter-name keys for the FindUsagesTool MCP tool, shared between its
 * schema() definition and handle() argument extraction so the two cannot drift.
 */
public enum FindUsagesParamEnum {
    CLASS_NAME("className"),
    MEMBER_NAME("memberName"),
    FIND_SUBCLASSES("findSubclasses"),
    DIRECT_SUBCLASSES_ONLY("directSubclassesOnly"),
    SEARCH_IN_COMMENTS("searchInComments");

    private final String key;

    FindUsagesParamEnum(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
