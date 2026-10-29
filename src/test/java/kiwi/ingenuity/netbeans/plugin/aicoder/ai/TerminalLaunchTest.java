package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class TerminalLaunchTest {

    @Test
    void commandAddsDistinctProjectDirectories() {
        File workDir = new File("/tmp/aicoder-terminal-launch-work");
        File firstProject = new File("/tmp/aicoder-terminal-launch-project-one");
        File secondProject = new File("/tmp/aicoder-terminal-launch-project-two");

        List<String> command = new TerminalLaunch("claude", "/usr/bin/claude", true)
                .command(workDir, List.of(firstProject, secondProject));

        assertEquals(List.of("/usr/bin/claude",
                             "--add-dir", firstProject.getAbsolutePath(),
                             "--add-dir", secondProject.getAbsolutePath()), command);
    }

    @Test
    void commandExcludesWorkingDirectoryAndDuplicateProjects() {
        File workDir = new File("/tmp/aicoder-terminal-launch-work");
        File project = new File("/tmp/aicoder-terminal-launch-project");
        File duplicateProject = new File(project, ".");

        List<String> command = new TerminalLaunch("codex", "/usr/bin/codex", true)
                .command(workDir, List.of(workDir, new File(workDir, "."), project, duplicateProject));

        assertEquals(List.of("/usr/bin/codex", "--add-dir", project.getAbsolutePath()), command);
    }

    @Test
    void commandForCliWithoutAddDirContainsOnlyExecutablePath() {
        List<String> command = new TerminalLaunch("pi", "/usr/bin/pi", false)
                .command(new File("/tmp/aicoder-terminal-launch-work"),
                         List.of(new File("/tmp/aicoder-terminal-launch-project")));

        assertEquals(List.of("/usr/bin/pi"), command);
    }
}
