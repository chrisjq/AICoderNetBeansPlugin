package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.openide.util.Utilities;

/**
 * Launches commands in the user's default terminal without waiting for the terminal process.
 */


    public final class TerminalUtil {

        @FunctionalInterface
        interface CommandFinder {

            boolean onPath(String command);
        }

        private static final List<String> LINUX_TERMINALS = List.of(
                "konsole", "gnome-terminal", "kitty", "alacritty", "foot", "wezterm", "ghostty", "xterm");

        private TerminalUtil() {
        }

        /**
         * Opens a new window of the user's default terminal app in workDir and runs argv there.
         *
         * @param workDir directory in which the command runs
         * @param argv command and arguments
         *
         * @throws IOException when no terminal can be launched
         */
        public static void openInTerminal(File workDir, List<String> argv) throws IOException {
            validate(workDir, argv);
            if (isFlatpak()) {
                throw new IOException("Terminal launch is unavailable inside a Flatpak sandbox.");
            }
            List<String> command;
            String override = PluginSettings.getTerminalCommand();
            if (!override.isBlank()) {
                command = overrideCommand(override, workDir, argv);
            }
            else if (Utilities.isWindows()) {
                command = windowsCommand(workDir, argv);
            }
            else if (Utilities.isMac()) {
                File script = Files.createTempFile("aicoder-launch-", ".command").toFile();
                Files.writeString(script.toPath(), macScript(workDir, argv), StandardCharsets.UTF_8);
                if (!script.setExecutable(true)) {
                    throw new IOException("Could not make the terminal launch script executable.");
                }
                script.deleteOnExit();
                command = List.of("open", script.getAbsolutePath());
            }
            else {
                command = linuxCommand(workDir, argv, TerminalUtil::commandOnPath,
                                       System.getenv("TERMINAL"), System.getenv("XDG_CURRENT_DESKTOP"),
                                       TerminalUtil::desktopTerminal);
                if (command.isEmpty()) {
                    throw new IOException("No terminal application found. Set $TERMINAL or install one of konsole, gnome-terminal, kitty, alacritty, foot, wezterm, ghostty, xterm.");
                }
            }
            new ProcessBuilder(command)
                    .directory(workDir)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        }

        /**
         * True when a terminal can be launched on this machine.
         *
         * @return whether a terminal launcher is available
         */
        public static boolean isAvailable() {
            boolean flatpak = isFlatpak();
            if (flatpak) {
                return false;
            }
            String override = PluginSettings.getTerminalCommand();
            if (availableForOverride(flatpak, override)) {
                return true;
            }
            if (Utilities.isWindows() || Utilities.isMac()) {
                return true;
            }
            String terminal = firstToken(System.getenv("TERMINAL"));
            if (!terminal.isEmpty() && commandOnPath(terminal)) {
                return true;
            }
            if (commandOnPath("xdg-terminal-exec") || commandOnPath("x-terminal-emulator")) {
                return true;
            }
            return LINUX_TERMINALS.stream().anyMatch(TerminalUtil::commandOnPath);
        }

        static boolean availableForOverride(boolean flatpak, String override) {
            return !flatpak && override != null && !override.isBlank();
        }

        static List<String> linuxCommand(File dir, List<String> argv, CommandFinder finder,
                                         String terminal, String desktop, Function<String, String> config) {
            if (finder.onPath("xdg-terminal-exec")) {
                return prepend("xdg-terminal-exec", argv);
            }
            String configured = firstToken(terminal);
            if (!configured.isEmpty() && finder.onPath(configured)) {
                return terminalCommand(configured, dir, argv);
            }
            if (finder.onPath("x-terminal-emulator")) {
                return terminalCommand("x-terminal-emulator", dir, argv);
            }
            String desktopName = desktop == null ? "" : desktop.toUpperCase();
            if (desktopName.contains("KDE")) {
                String configuredKde = firstToken(config.apply("kde"));
                if (!configuredKde.isEmpty() && finder.onPath(configuredKde)) {
                    return terminalCommand(configuredKde, dir, argv);
                }
            }
            if (desktopName.contains("GNOME")) {
                String configuredGnome = firstToken(config.apply("gnome"));
                if (!configuredGnome.isEmpty() && finder.onPath(configuredGnome)) {
                    return terminalCommand(configuredGnome, dir, argv);
                }
            }
            for (String candidate : LINUX_TERMINALS) {
                if (finder.onPath(candidate)) {
                    return terminalCommand(candidate, dir, argv);
                }
            }
            return List.of();
        }

        static List<String> linuxCommand(String terminal, File dir, List<String> argv) {
            String command = firstToken(terminal);
            return command.isEmpty() ? List.of() : terminalCommand(command, dir, argv);
        }

        static List<String> windowsCommand(File dir, List<String> argv) {
            List<String> command = new ArrayList<>(List.of("cmd", "/c", "start", "", "/D", quoteCmdArg(dir.getAbsolutePath()), "cmd", "/k"));
            argv.forEach(arg -> command.add(quoteCmdArg(arg)));
            return command;
        }

        static List<String> overrideCommand(String template, File dir, List<String> argv) {
            List<String> tokens = tokenize(template);
            List<String> command = new ArrayList<>();
            boolean commandPlaceholder = false;
            for (String token : tokens) {
                if ("{command}".equals(token)) {
                    command.addAll(argv);
                    commandPlaceholder = true;
                }
                else {
                    command.add(token.replace("{dir}", dir.getAbsolutePath()));
                }
            }
            if (!commandPlaceholder) {
                command.addAll(argv);
            }
            return command;
        }

        static List<String> tokenize(String template) {
            List<String> tokens = new ArrayList<>();
            StringBuilder token = new StringBuilder();
            char quote = 0;
            for (int i = 0; i < template.length(); i++) {
                char c = template.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                    else {
                        token.append(c);
                    }
                }
                else if (c == '\'' || c == '"') {
                    quote = c;
                }
                else if (Character.isWhitespace(c)) {
                    if (token.length() > 0) {
                        tokens.add(token.toString());
                        token.setLength(0);
                    }
                }
                else {
                    token.append(c);
                }
            }
            if (token.length() > 0) {
                tokens.add(token.toString());
            }
            return tokens;
        }

    static String macScript(File dir, List<String> argv) {
        StringBuilder script = new StringBuilder("#!/bin/bash\n");
        script.append("cd ").append(posixQuote(dir.getAbsolutePath())).append("\n");
        script.append("exec");
        argv.forEach(arg -> script.append(' ').append(posixQuote(arg)));
        script.append("\n");
        return script.toString();
    }

    static String posixQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static List<String> terminalCommand(String terminal, File dir, List<String> argv) {
        String name = new File(terminal).getName();
        List<String> command = new ArrayList<>();
        switch (name) {
            case "konsole" -> {
                command.addAll(List.of(terminal, "--workdir", dir.getAbsolutePath(), "-e"));
                command.addAll(argv);
            }
            case "gnome-terminal" -> {
                command.addAll(List.of(terminal, "--working-directory=" + dir.getAbsolutePath(), "--"));
                command.addAll(argv);
            }
            case "kitty" -> {
                command.addAll(List.of(terminal, "--directory", dir.getAbsolutePath()));
                command.addAll(argv);
            }
            case "alacritty" -> {
                command.addAll(List.of(terminal, "--working-directory", dir.getAbsolutePath(), "-e"));
                command.addAll(argv);
            }
            case "foot" -> {
                command.addAll(List.of(terminal, "--working-directory=" + dir.getAbsolutePath()));
                command.addAll(argv);
            }
            case "wezterm" -> {
                command.addAll(List.of(terminal, "start", "--cwd", dir.getAbsolutePath(), "--"));
                command.addAll(argv);
            }
            case "ghostty" -> {
                command.addAll(List.of(terminal, "--working-directory=" + dir.getAbsolutePath(), "-e"));
                command.addAll(argv);
            }
            default -> {
                command.add(terminal);
                command.add("-e");
                command.addAll(argv);
            }
        }
        return command;
    }

    private static List<String> prepend(String command, List<String> argv) {
        List<String> result = new ArrayList<>(argv.size() + 1);
        result.add(command);
        result.addAll(argv);
        return result;
    }

    private static String firstToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().split("\\s+", 2)[0];
    }

    private static boolean commandOnPath(String command) {
        if (command.contains(File.separator)) {
            return new File(command).isFile() && new File(command).canExecute();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        return Arrays.stream(path.split(File.pathSeparator))
                .map(dir -> new File(dir, command))
                .anyMatch(file -> file.isFile() && file.canExecute());
    }

    private static String desktopTerminal(String desktop) {
        List<List<String>> probes = switch (desktop) {
            case "kde" ->
                List.of(
                List.of("kreadconfig6", "--group", "General", "--key", "TerminalApplication"),
                List.of("kreadconfig5", "--group", "General", "--key", "TerminalApplication"));
            case "gnome" ->
                List.of(
                List.of("gsettings", "get", "org.gnome.desktop.default-applications.terminal", "exec"));
            default ->
                List.of();
        };
        for (List<String> probe : probes) {
            try {
                Process process = new ProcessBuilder(probe)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                boolean exited = process.waitFor(2, TimeUnit.SECONDS);
                if (!exited) {
                    process.destroyForcibly();
                    continue;
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.exitValue() == 0 && !output.isBlank()) {
                    return output.replaceAll("^['\\\"]|['\\\"]$", "").trim();
                }
            }
            catch (IOException e) {
                // Try the next desktop-specific probe.
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
        return "";
    }

    private static boolean isFlatpak() {
        return new File("/.flatpak-info").isFile();
    }

    private static void validate(File workDir, List<String> argv) throws IOException {
        if (workDir == null || !workDir.isDirectory()) {
            throw new IOException("Terminal working directory does not exist: " + workDir);
        }
        if (argv == null || argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IOException("A non-empty command is required.");
        }
    }

    static String quoteCmdArg(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
