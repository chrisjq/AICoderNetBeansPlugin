package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks in Grok multi-project CLI flags ({@code --cwd} + path-scoped
 * {@code --allow} rules) that stand in for Claude's {@code --add-dir}.
 */
class GrokAiProcessManagerProjectDirsTest {

    @TempDir
    File tempRoot;

    @Test
    void appendProjectDirArgs_nullWorkDirAndEmptyProjDirs_addsNothing() {
        List<String> args = new ArrayList<>();
        GrokAiProcessManager.appendProjectDirArgs(args, null, List.of());
        assertTrue(args.isEmpty());
    }

    @Test
    void appendProjectDirArgs_workDirOnly_addsCwd() throws IOException {
        File work = Files.createDirectory(tempRoot.toPath().resolve("work")).toFile();
        List<String> args = new ArrayList<>();
        GrokAiProcessManager.appendProjectDirArgs(args, work, List.of());
        assertEquals(List.of("--cwd", work.getCanonicalPath()), args);
    }

    @Test
    void appendProjectDirArgs_multipleProjects_addsCwdAndAllowRules() throws IOException {
        File work = Files.createDirectory(tempRoot.toPath().resolve("main")).toFile();
        File extra = Files.createDirectory(tempRoot.toPath().resolve("other")).toFile();
        List<String> args = new ArrayList<>();
        GrokAiProcessManager.appendProjectDirArgs(args, work, List.of(work, extra));

        assertEquals("--cwd", args.get(0));
        assertEquals(work.getCanonicalPath(), args.get(1));

        String workGlob = work.getCanonicalPath() + "/**";
        String extraGlob = extra.getCanonicalPath() + "/**";
        assertTrue(args.contains("Read(" + workGlob + ")"));
        assertTrue(args.contains("Edit(" + workGlob + ")"));
        assertTrue(args.contains("Write(" + workGlob + ")"));
        assertTrue(args.contains("Grep(" + workGlob + ")"));
        assertTrue(args.contains("Read(" + extraGlob + ")"));
        assertTrue(args.contains("Edit(" + extraGlob + ")"));
        assertTrue(args.contains("Write(" + extraGlob + ")"));
        assertTrue(args.contains("Grep(" + extraGlob + ")"));

        // Every allow value is preceded by the --allow flag.
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).startsWith("Read(")
                    || args.get(i).startsWith("Edit(")
                    || args.get(i).startsWith("Write(")
                    || args.get(i).startsWith("Grep(")) {
                assertEquals("--allow", args.get(i - 1));
            }
        }
    }

    @Test
    void appendProjectDirArgs_skipsMissingDirectories() throws IOException {
        File work = Files.createDirectory(tempRoot.toPath().resolve("work")).toFile();
        File missing = new File(tempRoot, "does-not-exist");
        List<String> args = new ArrayList<>();
        GrokAiProcessManager.appendProjectDirArgs(args, work, List.of(missing));
        assertEquals(List.of("--cwd", work.getCanonicalPath()), args);
        assertFalse(args.stream().anyMatch(s -> s.startsWith("Read(")));
    }
}
