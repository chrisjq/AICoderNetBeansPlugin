package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link GitExecutableLocator} — the JGit git-binary discovery algorithm this plugin re-implements so
 * partial staging runs the same git binary NetBeans uses. No real git, no child process: every collaborator (env,
 * platform flags, user home, the command runner and the executable-file predicate) is injected with a fake, so each of
 * JGit's branches is exercised deterministically regardless of the machine the tests run on.
 */
class GitExecutableLocatorTest {

    /**
     * A directory only ever passed to the (fake) command runner, which ignores it — so its value never affects any
     * assertion.
     */
    private static final File HOME = new File("/home/test");

    @TempDir
    Path tmp;

    // ---- fake collaborators -------------------------------------------------
    private static Function<String, String> env(String path) {
        return name -> "PATH".equals(name) ? path : null;
    }

    private static Predicate<File> exec(Set<String> paths) {
        return f -> paths.contains(f.getPath());
    }

    /**
     * A command runner driven by an exact-match response map plus an explicit set of commands that must fail. An
     * unmapped command throws, which is what lets a test *prove* a probe was never run.
     */
    private static GitExecutableLocator.CommandRunner runner(Map<String, String> responses,
                                                             Set<String> throwKeys) {
        return (dir, command) -> {
            String key = String.join(" ", command);
            if (throwKeys.contains(key)) {
                throw new IOException("forced failure: " + key);
            }
            String value = responses.get(key);
            if (value == null) {
                throw new IOException("unmapped command: " + key);
            }
            return value;
        };
    }

    private static Set<String> paths(String... entries) {
        return Arrays.stream(entries).collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> responses(String... keyValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static GitExecutableLocator locator(String path, boolean windows, boolean mac,
                                                Map<String, String> responses, Set<String> throwKeys, Set<String> exec) {
        // The exists test defaults to the executable test; use the overload below to make them differ.
        return new GitExecutableLocator(env(path), windows, mac, HOME,
                                        runner(responses, throwKeys), exec(exec), exec(exec));
    }

    /**
     * Overload where the {@code exists} test (the macOS CLT-backend check) may differ from the executable test, so a
     * test can prove a backend that exists but is not executable still counts as real.
     */
    private static GitExecutableLocator locator(String path, boolean windows, boolean mac,
                                                Map<String, String> responses, Set<String> throwKeys,
                                                Set<String> exec, Set<String> exists) {
        return new GitExecutableLocator(env(path), windows, mac, HOME,
                                        runner(responses, throwKeys), exec(exec), exec(exists));
    }

    /**
     * Absolute form of {@code new File(dir, name)} — the shape {@code searchPath} returns.
     */
    private static String abs(String dir, String name) {
        return new File(dir, name).getAbsoluteFile().getPath();
    }

    private static final String LOGIN_SHELL = "bash --login -c which git";
    private static final String XCODE_SELECT = "xcode-select -p";
    private static final String SHIM = "/usr/bin/git";

    // ---- the requested cases ------------------------------------------------
    @Test
    @Timeout(10)
    void linuxFirstPathDirWins() {
        var loc = locator("/dir1:/dir2", false, false,
                      Map.of(), Set.of(), paths("/dir1/git", "/dir2/git"));
        assertEquals(abs("/dir1", "git"), loc.locate().get().getPath());
    }

    @Test
    @Timeout(10)
    void windowsDirOrderBeatsNameOrder() {
        // git.cmd sits in the FIRST dir, git.exe only in the second. JGit walks dirs outer /
        // names inner, so the closer dir must win even though git.exe is the "preferred" name.
        var loc = locator("/dir1:/dir2", true, false,
                      Map.of(), Set.of(), paths("/dir1/git.cmd", "/dir2/git.exe"));
        assertEquals(abs("/dir1", "git.cmd"), loc.locate().get().getPath());
    }

    @Test
    @Timeout(10)
    void nonExecutableIsSkipped() {
        // /dir1/git exists but is not executable; the search must skip it and take /dir2/git.
        var loc = locator("/dir1:/dir2", false, false,
                      Map.of(), Set.of(), paths("/dir2/git"));
        assertEquals(abs("/dir2", "git"), loc.locate().get().getPath());
    }

    @Test
    @Timeout(10)
    void emptyPathReturnsEmpty() {
        for (String path : new String[]{"", "   "}) {
            var loc = locator(path, false, false,
                          Map.of(), Set.of(), paths("/usr/bin/git"));
            assertFalse(loc.locate().isPresent(), "blank PATH must yield empty: '" + path + "'");
        }
    }

    @Test
    @Timeout(10)
    void macOsShimUpgradedToLoginShellResult() {
        // PATH only offers the /usr/bin/git XCode shim and a bash; the login shell supplies the
        // real terminal-location git, which is not the shim so it is returned as-is.
        var loc = locator("/usr/bin", false, true,
                      responses(LOGIN_SHELL, "/opt/homebrew/bin/git"),
                      Set.of(), paths("/usr/bin/git", "/usr/bin/bash"));
        assertEquals(new File("/opt/homebrew/bin/git").getPath(), loc.locate().get().getPath());
    }

    @Test
    @Timeout(10)
    void macOsShimWithEmptyXcodeSelectReturnsEmpty() {
        // PATH yields only the shim; the login shell re-resolves to the shim; xcode-select then
        // reports no CLT install -> JGit drops it to avoid the install prompt.
        var loc = locator("/usr/bin", false, true,
                      responses(LOGIN_SHELL, SHIM, XCODE_SELECT, ""),
                      Set.of(), paths("/usr/bin/git", "/usr/bin/bash"));
        assertFalse(loc.locate().isPresent());
    }

    @Test
    @Timeout(10)
    void macOsShimWithRealBackendKept() {
        // The shim is confirmed by a real CLT backend existing at <xcode>/usr/bin/git, so the
        // /usr/bin/git shim is retained.
        String xcode = "/Applications/CLT";
        String backend = new File(new File(xcode), SHIM.substring(1)).getPath();
        var loc = locator("/usr/bin", false, true,
                      responses(LOGIN_SHELL, SHIM, XCODE_SELECT, xcode),
                      Set.of(), paths("/usr/bin/git", "/usr/bin/bash", backend));
        assertEquals(SHIM, loc.locate().get().getPath());
    }

    @Test
    @Timeout(10)
    void macOsBackendExistsButNotExecutableKept() {
        // The CLT-backend check is JGit's exists(), NOT "is executable". The backend file exists but
        // is not executable: it must still confirm the shim, so the shim is kept. The exec set
        // deliberately excludes the backend (so it fails the executable test) while the exists set
        // includes it — proving the check runs the exists() predicate, not the executable one.
        String xcode = "/Applications/CLT";
        String backend = new File(new File(xcode), SHIM.substring(1)).getPath();
        var loc = locator("/usr/bin", false, true,
                      responses(LOGIN_SHELL, SHIM, XCODE_SELECT, xcode),
                      Set.of(), paths("/usr/bin/git", "/usr/bin/bash"), paths(backend));
        assertEquals(SHIM, loc.locate().get().getPath());
    }

    @Test
    @Timeout(10)
    void windowsGitExeFoundNoFallback() {
        // git.exe is on the PATH, so the bash login-shell fallback must never run — the runner
        // has no mapping and would throw if it did.
        var loc = locator("/dir1:/dir2", true, false,
                      Map.of(), Set.of(), paths("/dir1/git.exe", "/dir2/bash.exe"));
        assertEquals(abs("/dir1", "git.exe"), loc.locate().get().getPath());
    }

    @Test
    @Timeout(10)
    void windowsBashFallbackWhenGitMissing() {
        // No git.exe / git.cmd, but bash.exe is on the PATH: the login-shell probe runs and its
        // output is resolved to a native path.
        String loginOut = tmp.resolve("git").toFile().getAbsolutePath();
        var loc = locator("/dir1:/dir2", true, false,
                      responses(LOGIN_SHELL, loginOut),
                      Set.of(), paths("/dir2/bash.exe"));
        assertEquals(new File(loginOut).getPath(), loc.locate().get().getPath());
    }

    @Test
    @Timeout(10)
    void failedLoginShellKeepsPathResult() {
        // PATH yields the /usr/bin/git shim, so the login-shell probe runs; it FAILS. JGit keeps
        // whatever the PATH search found. A later xcode-select confirms a real backend, so the
        // shim survives to the returned result. If the failure had (wrongly) nulled the result,
        // the xcode branch would be skipped and this would return empty.
        String xcode = "/Applications/CLT";
        String backend = new File(new File(xcode), SHIM.substring(1)).getPath();
        var loc = locator("/usr/bin", false, true,
                      responses(XCODE_SELECT, xcode),
                      Set.of(LOGIN_SHELL), paths("/usr/bin/git", "/usr/bin/bash", backend));
        assertTrue(loc.locate().isPresent(), "a failed probe must keep the PATH result");
        assertEquals(SHIM, loc.locate().get().getPath());
    }
}
