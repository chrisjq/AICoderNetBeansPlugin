package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;

/**
 * Shared parameter-name key for the required {@code projectPath} parameter used by every MCP tool that
 * targets a project or git repository — git tools, build/test tools, IDE actions (e.g. RunProject),
 * GetJavadoc, and others. This parameter is required on all of them: NetBeans' "main project" (or first open
 * project) notion is ambiguous — and can be plain wrong — whenever multiple projects/repositories are open at
 * the same time, or when a git repository lives outside any open project's directory.
 */
public enum ProjectPathParamEnum {
    PROJECT_PATH(McpToolPropertyEnum.PROJECT_PATH);

    private final McpToolPropertyEnum property;

    ProjectPathParamEnum(McpToolPropertyEnum property) {
        this.property = property;
    }

    public String key() {
        return property.key();
    }
}
