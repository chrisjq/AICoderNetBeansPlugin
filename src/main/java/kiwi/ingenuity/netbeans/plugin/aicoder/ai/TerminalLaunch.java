package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.ProjectPathUtil;

/**
 * Describes how to open a new session of a command-line AI backend in a terminal.
 */
public record TerminalLaunch(String executableName, String executablePath, boolean supportsAddDir) {

    /**
     * Builds the executable command, adding distinct project directories where the CLI supports them.
     */
    public List<String> command(File workDir, List<File> projectDirs) {
        List<String> argv = new ArrayList<>();
        argv.add(executablePath);
        if (!supportsAddDir || projectDirs == null) {
            return List.copyOf(argv);
        }

        String canonicalWorkDir = workDir == null ? null : ProjectPathUtil.canonical(workDir.getPath());
        Set<String> seen = new HashSet<>();
        for (File projectDir : projectDirs) {
            if (projectDir == null) {
                continue;
            }
            String canonicalProjectDir = ProjectPathUtil.canonical(projectDir.getPath());
            if (canonicalProjectDir.equals(canonicalWorkDir) || !seen.add(canonicalProjectDir)) {
                continue;
            }
            argv.add("--add-dir");
            argv.add(projectDir.getAbsolutePath());
        }
        return List.copyOf(argv);
    }
}
