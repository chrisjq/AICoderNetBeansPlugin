package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.beans.PropertyChangeListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.netbeans.spi.project.ProjectConfiguration;
import org.netbeans.spi.project.ProjectConfigurationProvider;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

/**
 * Tests for ProjectActionProvider path-alias resolution.
 *
 * Verifies that paths through symlinks are correctly resolved to the same real path as direct paths, so that
 * project matching works consistently regardless of how the path is spelled.
 */
class ProjectActionProviderTest {

    @Test
    void projectPathResolution_followsSymlinks() throws Exception {
        // Create a temp directory
        Path tempDir = Files.createTempDirectory("project-action-test-");
        try {
            // Create a symlink to it
            Path symlinkDir = Files.createTempDirectory("project-action-test-symlink-parent-").resolve("symlink");
            try {
                Files.createSymbolicLink(symlinkDir, tempDir);

                // Verify both spellings resolve to the same real path
                Path realTemp = tempDir.toRealPath();
                Path realSymlink = symlinkDir.toRealPath();

                assertTrue(realTemp.equals(realSymlink),
                        "Symlink and direct path should resolve to the same real path: "
                        + "tempDir=" + tempDir + ", symlinkDir=" + symlinkDir
                        + ", realTemp=" + realTemp + ", realSymlink=" + realSymlink);
            }
            catch (UnsupportedOperationException e) {
                // Symlinks not supported on this OS (e.g., Windows without admin)
                // Skip the test gracefully
            }
            finally {
                if (Files.exists(symlinkDir)) {
                    Files.delete(symlinkDir);
                    Files.delete(symlinkDir.getParent());
                }
            }
        }
        finally {
            Files.deleteIfExists(tempDir);
        }
    }

    /**
     * Deterministic regardless of environment: the blank-path check is the very first thing {@code resolve()}
     * does, before anything that depends on {@code McpServerRegistry} or open-project state.
     */
    @Test
    void runProject_blankProjectPath_isRefused() {
        String result = ProjectActionProvider.runProject("test-session", "  ", false);

        assertTrue(result.contains("projectPath is required"), result);
    }

    @Test
    void runProject_nonDirectoryPath_isRefused() throws Exception {
        Path tempFile = Files.createTempFile("run-project-test-", ".txt");
        try {
            String result = ProjectActionProvider.runProject("test-session", tempFile.toString(), false);

            assertTrue(result.contains("is not a directory"), result);
        }
        finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void runProject_debugVariant_blankProjectPath_isRefused() {
        String result = ProjectActionProvider.runProject("test-session", "", true);

        assertTrue(result.contains("projectPath is required"), result);
    }

    /**
     * BigP_1's review finding: an empty context would run the project's INACTIVE configuration's behaviour
     * for anything {@code ActionProvider} that is configuration-sensitive. No real {@code Project} is needed
     * — {@code buildActionContext} takes the project's own lookup directly, so a fake one proves the three
     * cases without a NetBeans project.
     */
    @Test
    void buildActionContext_noConfigurationProvider_isEmpty() {
        Lookup context = ProjectActionProvider.buildActionContext(Lookup.EMPTY);

        assertSame(Lookup.EMPTY, context);
    }

    @Test
    void buildActionContext_withActiveConfiguration_includesIt() {
        FakeConfig active = new FakeConfig();
        Lookup projectLookup = Lookups.fixed(new FakeConfigProvider(active));

        Lookup context = ProjectActionProvider.buildActionContext(projectLookup);

        assertSame(active, context.lookup(ProjectConfiguration.class));
    }

    @Test
    void buildActionContext_providerWithNoActiveConfiguration_isEmpty() {
        Lookup projectLookup = Lookups.fixed(new FakeConfigProvider(null));

        Lookup context = ProjectActionProvider.buildActionContext(projectLookup);

        assertSame(Lookup.EMPTY, context);
    }

    private static final class FakeConfig implements ProjectConfiguration {

        @Override
        public String getDisplayName() {
            return "FakeConfig";
        }
    }

    private static final class FakeConfigProvider implements ProjectConfigurationProvider<FakeConfig> {

        private final FakeConfig active;

        FakeConfigProvider(FakeConfig active) {
            this.active = active;
        }

        @Override
        public Collection<FakeConfig> getConfigurations() {
            return active == null ? List.of() : List.of(active);
        }

        @Override
        public FakeConfig getActiveConfiguration() {
            return active;
        }

        @Override
        public void setActiveConfiguration(FakeConfig configuration) {
        }

        @Override
        public boolean hasCustomizer() {
            return false;
        }

        @Override
        public void customize() {
        }

        @Override
        public boolean configurationsAffectAction(String command) {
            return false;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener lst) {
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener lst) {
        }
    }
}
