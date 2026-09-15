package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.netbeans.api.project.Project;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;

class DiagnosticsProviderTest {

    @Test
    void ownerNullReturnsMessage() {
        String msg = DiagnosticsProvider.projectNotOpenMessageOrNull("test.java", null, new HashSet<>());
        assertTrue(msg != null && msg.contains("project not open"));
        assertTrue(msg.contains("unknown"));
    }

    @Test
    void ownerNotInOpenSetReturnsMessage() throws Exception {
        File tempDir = File.createTempFile("test", "").getParentFile();
        FileObject fo = FileUtil.toFileObject(tempDir);
        Project owner = new TestProject(fo);
        Set<Project> openProjects = new HashSet<>();

        String msg = DiagnosticsProvider.projectNotOpenMessageOrNull("test.java", owner, openProjects);
        assertTrue(msg != null && msg.contains("project not open"));
        assertTrue(msg.contains(tempDir.getAbsolutePath()));
    }

    @Test
    void ownerInOpenSetReturnsNull() throws Exception {
        File tempDir = File.createTempFile("test", "").getParentFile();
        FileObject fo = FileUtil.toFileObject(tempDir);
        Project owner = new TestProject(fo);
        Set<Project> openProjects = new HashSet<>();
        openProjects.add(owner);

        String msg = DiagnosticsProvider.projectNotOpenMessageOrNull("test.java", owner, openProjects);
        assertNull(msg);
    }

    static class TestProject implements Project {

        private final FileObject dir;

        TestProject(FileObject dir) {
            this.dir = dir;
        }

        @Override
        public FileObject getProjectDirectory() {
            return dir;
        }

        @Override
        public Lookup getLookup() {
            return Lookup.EMPTY;
        }
    }
}
