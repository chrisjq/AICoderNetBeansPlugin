package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.io.File;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TerminalUtilTest {

    private static final File DIR = new File("/tmp/work dir");

    @Test
    void terminalArgumentLayoutsAreProviderSpecific() {
        List<String> argv = List.of("echo", "hello world");
        assertEquals(List.of("konsole", "--workdir", DIR.getAbsolutePath(), "-e", "echo", "hello world"),
                     TerminalUtil.linuxCommand("konsole", DIR, argv));
        assertEquals(List.of("gnome-terminal", "--working-directory=" + DIR.getAbsolutePath(), "--", "echo", "hello world"),
                     TerminalUtil.linuxCommand("gnome-terminal", DIR, argv));
        assertEquals(List.of("kitty", "--directory", DIR.getAbsolutePath(), "echo", "hello world"),
                     TerminalUtil.linuxCommand("kitty", DIR, argv));
        assertEquals(List.of("wezterm", "start", "--cwd", DIR.getAbsolutePath(), "--", "echo", "hello world"),
                     TerminalUtil.linuxCommand("wezterm", DIR, argv));
        assertEquals(List.of("alacritty", "--working-directory", DIR.getAbsolutePath(), "-e", "echo", "hello world"),
                     TerminalUtil.linuxCommand("alacritty", DIR, argv));
        assertEquals(List.of("foot", "--working-directory=" + DIR.getAbsolutePath(), "echo", "hello world"),
                     TerminalUtil.linuxCommand("foot", DIR, argv));
        assertEquals(List.of("ghostty", "--working-directory=" + DIR.getAbsolutePath(), "-e", "echo", "hello world"),
                     TerminalUtil.linuxCommand("ghostty", DIR, argv));
        assertEquals(List.of("xterm", "-e", "echo", "hello world"),
                     TerminalUtil.linuxCommand("xterm", DIR, argv));
    }

    @Test
    void linuxFallbackOrderUsesFirstAvailableTerminal() {
        Set<String> available = Set.of("x-terminal-emulator", "kitty");
        TerminalUtil.CommandFinder finder = available::contains;
        assertEquals(List.of("x-terminal-emulator", "-e", "echo", "hello"),
                     TerminalUtil.linuxCommand(DIR, List.of("echo", "hello"), finder, "missing", "", key -> ""));
    }

    @Test
    void desktopAndConfiguredTerminalAreUsedBeforeGenericFallback() {
        Set<String> available = Set.of("konsole");
        TerminalUtil.CommandFinder finder = available::contains;
        assertEquals(List.of("konsole", "--workdir", DIR.getAbsolutePath(), "-e", "echo"),
                     TerminalUtil.linuxCommand(DIR, List.of("echo"), finder, "missing", "KDE", key -> "/usr/bin/konsole"));
    }

    @Test
    void windowsArgumentsQuoteSpaces() {
        assertEquals(List.of("cmd", "/c", "start", "", "/D", "\"" + DIR.getAbsolutePath() + "\"", "cmd", "/k",
                             "echo", "\"hello world\""),
                     TerminalUtil.windowsCommand(DIR, List.of("echo", "hello world")));
    }

    @Test
    void macScriptUsesSafePosixQuotes() {
        assertEquals("#!/bin/bash\ncd '/tmp/work dir'\nexec 'echo' 'a'\\''b c'\n",
                     TerminalUtil.macScript(DIR, List.of("echo", "a'b c")));
    }

    @Test
    void overrideTokenisesQuotesAndSplicesPlaceholders() {
        assertEquals(List.of("konsole", "--workdir", DIR.getAbsolutePath(), "-e", "echo", "hello world"),
                     TerminalUtil.overrideCommand("konsole --workdir {dir} -e {command}", DIR,
                                                  List.of("echo", "hello world")));
        assertEquals(List.of("wt", "-d", DIR.getAbsolutePath(), "echo", "hello world"),
                     TerminalUtil.overrideCommand("wt -d '{dir}'", DIR, List.of("echo", "hello world")));
        assertEquals(List.of("gnome-terminal", "--working-directory=" + DIR.getAbsolutePath(), "--", "echo", "hello world"),
                     TerminalUtil.overrideCommand("gnome-terminal --working-directory={dir} -- {command}", DIR,
                                                  List.of("echo", "hello world")));
        assertEquals(List.of("foot", "--working-directory=" + DIR.getAbsolutePath(), "echo", "hello world"),
                     TerminalUtil.overrideCommand("foot --working-directory={dir}", DIR,
                                                  List.of("echo", "hello world")));
        assertEquals(List.of("xterm", "-e", "echo", "hello world"),
                     TerminalUtil.overrideCommand("xterm -e", DIR, List.of("echo", "hello world")));
    }

    @Test
    void tokenizerSupportsSingleAndDoubleQuotes() {
        assertEquals(List.of("one two", "three four", "five"),
                     TerminalUtil.tokenize("  'one two' \"three four\" five "));
    }

    @Test
    void overrideAvailabilityIgnoresAutoDetection() {
        assertTrue(TerminalUtil.availableForOverride(false, "custom-terminal"));
        assertFalse(TerminalUtil.availableForOverride(true, "custom-terminal"));
    }

}
