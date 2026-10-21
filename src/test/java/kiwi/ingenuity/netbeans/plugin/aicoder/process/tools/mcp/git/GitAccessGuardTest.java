package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetPluginVersionTool;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class GitAccessGuardTest {

    @Test
    void defaultsAllowEveryGitTool() {
        AbstractAiSession session = newSession();
        assertNull(GitAccessGuard.denialOrNull(new GetGitStatusTool(), session));
        assertNull(GitAccessGuard.denialOrNull(new GitCommitTool(), session));
    }

    @Test
    void masterOffRefusesReadAndWriteAlike() {
        AbstractAiSession session = newSession();
        session.getSettings().setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        session.getSettings().setAllowGitAccess(Boolean.FALSE);

        String read = GitAccessGuard.denialOrNull(new GetGitStatusTool(), session);
        String write = GitAccessGuard.denialOrNull(new GitCommitTool(), session);

        assertNotNull(read);
        assertNotNull(write);
        assertTrue(read.contains("Allow git access"), read);
    }

    @Test
    void writeOffStillAllowsReads() {
        AbstractAiSession session = newSession();
        session.getSettings().setAllowGitAccessOption(GitAccessOptionEnum.WRITE, Boolean.FALSE);

        assertNull(GitAccessGuard.denialOrNull(new GetGitStatusTool(), session),
                   "a read-only tool must survive WRITE being off");
        assertNotNull(GitAccessGuard.denialOrNull(new GitCommitTool(), session));
    }

    @Test
    void readOffStillAllowsWrites() {
        AbstractAiSession session = newSession();
        session.getSettings().setAllowGitAccessOption(GitAccessOptionEnum.READ, Boolean.FALSE);

        assertNotNull(GitAccessGuard.denialOrNull(new GetGitStatusTool(), session));
        assertNull(GitAccessGuard.denialOrNull(new GitCommitTool(), session),
                   "READ and WRITE must be independent, not one flag read twice");
    }

    @Test
    void theOptionRefusalNamesTheOptionNotTheMaster() {
        AbstractAiSession session = newSession();
        session.getSettings().setAllowGitAccessOption(GitAccessOptionEnum.WRITE, Boolean.FALSE);

        String denial = GitAccessGuard.denialOrNull(new GitCommitTool(), session);
        assertTrue(denial.contains("Allow git commands that modify the repository"), denial);
    }

    @Test
    void masterIsCheckedBeforeTheOption() {
        AbstractAiSession session = newSession();
        session.getSettings().setAllowGitAccess(Boolean.FALSE);
        session.getSettings().setAllowGitAccessOption(GitAccessOptionEnum.WRITE, Boolean.FALSE);

        String denial = GitAccessGuard.denialOrNull(new GitCommitTool(), session);
        assertTrue(denial.contains("Git access is disabled"), denial);
    }

    @Test
    void nonGitToolsAreNeverGated() {
        AbstractAiSession session = newSession();
        session.getSettings().setAllowGitAccess(Boolean.FALSE);
        assertNull(GitAccessGuard.denialOrNull(new GetPluginVersionTool(), session));
    }

    @Test
    void anMcpLayerRefusalNamesTheToolGroupNotTheHiddenConfigSetting() {
        AbstractAiSession session = newSession();
        session.getSettings().setAllowGitAccess(Boolean.TRUE);
        session.getSettings().setAllowGitAccessOption(GitAccessOptionEnum.WRITE, Boolean.TRUE);
        session.getSettings().setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.GIT_WRITE)));

        String denial = GitAccessGuard.denialOrNull(new GitCommitTool(), session);
        assertNotNull(denial);
        assertTrue(denial.contains("The 'Git write' tool group is switched off for this session"), denial);
        assertFalse(denial.contains("Allow git commands that modify the repository"), denial);
        assertNull(GitAccessGuard.denialOrNull(new GetGitStatusTool(), session));
    }

    @Test
    void aConfigLayerOptionRefusalUnderAnEnabledMcpGroupKeepsTheOptionText() {
        AbstractAiSession session = newSession();
        session.getSettings().setMcpToolConfig(McpToolConfig.ALL_ENABLED);
        session.getSettings().setAllowGitAccessOption(GitAccessOptionEnum.WRITE, Boolean.FALSE);

        String denial = GitAccessGuard.denialOrNull(new GitCommitTool(), session);
        assertTrue(denial.contains("Allow git commands that modify the repository"), denial);
        assertFalse(denial.contains("tool group is switched off"), denial);
    }

    @Test
    void whenBothLayersRefuseTheMcpLayerIsReported() {
        AbstractAiSession session = newSession();
        session.getSettings().setMcpToolConfig(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.GIT_READ)));
        session.getSettings().setAllowGitAccess(Boolean.FALSE);

        String denial = GitAccessGuard.denialOrNull(new GetGitStatusTool(), session);
        assertTrue(denial.contains("The 'Git read' tool group is switched off"), denial);
    }

    private static AbstractAiSession newSession() {
        return new AbstractAiSession(AiSession.create(null, AiTypeEnum.CLAUDE)) {
            @Override
            public String getId() {
                return "git-guard-test";
            }

            @Override
            public AiProcessEventListener getAiProcessEventListener() {
                return null;
            }

            @Override
            public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
                return Map.of();
            }
        };
    }
}
