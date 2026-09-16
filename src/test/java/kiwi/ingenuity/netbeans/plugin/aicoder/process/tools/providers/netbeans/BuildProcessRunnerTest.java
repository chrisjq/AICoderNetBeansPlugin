package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildControl;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildOutcome;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.build.BuildStatusEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BuildProcessRunnerTest {

    @Test
    void success() {
        BuildOutcome outcome = BuildProcessRunner.run(prepared("-version"), new BuildControl(10_000));
        assertEquals(BuildStatusEnum.SUCCESS, outcome.status());
    }

    @Test
    void nonZeroExit() {
        BuildOutcome outcome = BuildProcessRunner.run(prepared("-this-option-does-not-exist"), new BuildControl(10_000));
        assertEquals(BuildStatusEnum.FAILED, outcome.status());
        assertTrue(outcome.result().contains("BUILD FAILED") || outcome.result().contains("Error"));
    }

    @Test
    void timeoutKillsProcess() {
        BuildOutcome outcome = BuildProcessRunner.run(sleeper(2_000), new BuildControl(50));
        assertEquals(BuildStatusEnum.TIMED_OUT, outcome.status());
        assertTrue(outcome.result().contains("Timed out after"));
    }

    @Test
    void cancellationControlKillsProcess() throws Exception {
        BuildControl control = new BuildControl(TimeUnit.SECONDS.toMillis(10));
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(100);
                control.cancel();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();
        BuildOutcome outcome = BuildProcessRunner.run(sleeper(5_000), control);
        canceller.join(2_000);
        assertEquals(BuildStatusEnum.FAILED, outcome.status());
    }

    private static PreparedBuild prepared(String... args) {
        List<String> command = javaCommand(args);
        return new PreparedBuild(null, "runner-test-" + UUID.randomUUID(), new File("."), command,
                                 BuildOutputFormatter.Backend.MAVEN);
    }

    private static PreparedBuild sleeper(long millis) {
        return prepared(BuildProcessRunnerTest.class.getName() + "$Sleeper", Long.toString(millis));
    }

    private static List<String> javaCommand(String... args) {
        String executable = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        for (String arg : args) {
            command.add(arg);
        }
        return command;
    }

    public static final class Sleeper {

        public static void main(String[] args) throws Exception {
            Thread.sleep(Long.parseLong(args[0]));
        }
    }
}
