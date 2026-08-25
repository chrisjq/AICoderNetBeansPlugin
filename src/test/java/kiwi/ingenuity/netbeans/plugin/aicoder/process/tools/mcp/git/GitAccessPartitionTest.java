package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolHandlerFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class GitAccessPartitionTest {

    private static final Set<String> EXPECTED_READ = new TreeSet<>(Set.of(
            "GetGitStatus", "GetGitDiff", "GitLog", "GitShow", "GitBlame"));

    private static final Set<String> EXPECTED_WRITE = new TreeSet<>(Set.of(
            "GitAdd", "GitBranch", "GitCheckout", "GitCherryPick", "GitCommit",
            "GitDeleteBranch", "GitFetch", "GitMerge", "GitPull", "GitPush",
            "GitRebase", "GitRemote", "GitReset", "GitRevert", "GitStash", "GitTag"));

    @Test
    void theReadWriteSplitIsExactlyWhatTheSpecSays() {
        Map<McpToolEnum, McpToolInterface> handlers = ToolHandlerFactory.getToolHandlers(null);
        Set<String> actualRead = new TreeSet<>();
        Set<String> actualWrite = new TreeSet<>();

        for (Map.Entry<McpToolEnum, McpToolInterface> entry : handlers.entrySet()) {
            if (entry.getValue().section() != McpSectionEnum.GIT) {
                continue;
            }
            (entry.getValue().isMutating() ? actualWrite : actualRead)
                    .add(entry.getKey().toolName());
        }

        assertEquals(EXPECTED_READ, actualRead, "read-only git tools");
        assertEquals(EXPECTED_WRITE, actualWrite, "mutating git tools");
        assertEquals(21, actualRead.size() + actualWrite.size(), "total git tools");
    }
}
