package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

/**
 * Broad category shown when configuring MCP tool exposure.
 */
public enum McpToolCategoryEnum {
    ALWAYS_ON("Always on"),
    FILES("Files"),
    CODE("Code"),
    EDITOR("Editor"),
    BUILD("Build"),
    GIT("Git"),
    EXTERNAL("External"),
    COLLABORATION("Collaboration"),
    MEMORY("Memory (OpenAI Compatible only)");

    private final String label;

    McpToolCategoryEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
