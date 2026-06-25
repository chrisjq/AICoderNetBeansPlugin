package kiwi.ingenuity.netbeans.plugin.aicoder.process;

public enum McpSectionEnum {
    DEVOPS_BUILD("Build Code"),
    DEVOPS_TEST("Test Code"),
    GIT("Git"),
    HELP("Help & Information"),
    REFACTORING("Refactoring (IDE-safe - all references updated automatically)"),
    SEARCH("Search/Find (faster and IDE-aware)"),
    SYSTEM("Core System Functions"),
    UI_BUILD("UI -> Build"),
    UI_FILES("UI -> Files & Text"),
    UI_NAVIGATION("UI -> Navigation"),
    UI_SOURCE("UI -> Source Code Formatting"),
    UI_DIALOG("UI -> Dialog Actions"),
    USER_INPUT("Request Input from User"),
    PLUGIN("Plugin");

    private final String title;

    McpSectionEnum(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
