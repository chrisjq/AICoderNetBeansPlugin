package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.settings.PiPluginSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PiModelDiscoveryTest {

    @Test
    void parseModels_realCliOutput_extractsProviderSlashModel() {
        List<String> lines = List.of(
                "provider        model            context  max-out  thinking  images",
                "github-copilot  claude-sonnet-5  1M       128K     yes       yes   "
        );
        List<String> models = PiModelDiscovery.parseModels(lines);
        assertEquals(List.of("github-copilot/claude-sonnet-5"), models);
    }

    @Test
    void parseModels_multipleRows() {
        List<String> lines = List.of(
                "provider        model            context  max-out  thinking  images",
                "github-copilot  claude-sonnet-5  1M       128K     yes       yes",
                "anthropic       claude-opus-4-8  200K     16K      yes       yes"
        );
        List<String> models = PiModelDiscovery.parseModels(lines);
        assertEquals(List.of("github-copilot/claude-sonnet-5", "anthropic/claude-opus-4-8"), models);
    }

    @Test
    void parseModels_emptyOrHeaderOnly_returnsEmpty() {
        List<String> headerOnly = List.of("provider        model            context  max-out  thinking  images");
        assertTrue(PiModelDiscovery.parseModels(headerOnly).isEmpty());
        assertTrue(PiModelDiscovery.parseModels(List.of()).isEmpty());
    }

    @Test
    void parseModels_unauthenticatedOutput_returnsEmpty() {
        List<String> lines = List.of("", "No models available. Run `pi auth login` first.", "");
        assertTrue(PiModelDiscovery.parseModels(lines).isEmpty());
    }

    @Test
    void parseModels_rejectsNonProviderLookingFirstColumn() {
        List<String> lines = List.of("Error: not authenticated with any provider");
        assertTrue(PiModelDiscovery.parseModels(lines).isEmpty());
    }

    @Test
    void parseModels_deduplicatesRepeatedRows() {
        List<String> lines = List.of(
                "github-copilot  claude-sonnet-5  1M  128K  yes  yes",
                "github-copilot  claude-sonnet-5  1M  128K  yes  yes"
        );
        assertEquals(List.of("github-copilot/claude-sonnet-5"), PiModelDiscovery.parseModels(lines));
    }

    @Test
    void parseModels_ignoresBlankLines() {
        List<String> lines = java.util.Arrays.asList(
                "", "   ", "github-copilot  claude-sonnet-5  1M  128K  yes  yes", ""
        );
        assertEquals(List.of("github-copilot/claude-sonnet-5"), PiModelDiscovery.parseModels(lines));
    }

    @Test
    void discoverAsync_secondCallWhileFirstInProgress_stillCompletesRatherThanHangingForever(@TempDir Path dir) throws Exception {
        // A caller (e.g. the Refresh button) that disables a control before calling discoverAsync and re-enables
        // it only from onResult must never be left with a control disabled forever just because another discovery
        // (e.g. the IDE-startup sweep) is already running — the busy call must still get a completion signal.
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh\nsleep 0.3\necho github-copilot claude-sonnet-5 1M 128K yes yes\n");
        exe.toFile().setExecutable(true);
        PiPluginSettings.setDiscoveredModels(new String[]{"cached/model"});
        try {
            CompletableFuture<List<String>> first = new CompletableFuture<>();
            CompletableFuture<List<String>> second = new CompletableFuture<>();

            PiModelDiscovery.discoverAsync(exe.toString(), first::complete);
            // No race: tryDiscover's compareAndSet(inProgress) happens on the caller's thread before the
            // background thread is even created, so by the time the first discoverAsync() call above returns,
            // inProgress is already true and this second call is guaranteed to take the busy-skip path.
            PiModelDiscovery.discoverAsync(exe.toString(), second::complete);

            List<String> secondResult = second.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("cached/model"), secondResult, "busy-skip must report the cache, not hang forever");

            first.get(10, TimeUnit.SECONDS);
        }
        finally {
            PiPluginSettings.setDiscoveredModels(new String[0]);
        }
    }

    @Test
    void discoverAsync_everyAttemptFails_stillCallsBackAfterExhaustingRetries(@TempDir Path dir) throws Exception {
        // A CLI that is installed but always fails (bad auth, wrong args, ...) must not retry forever without ever
        // telling the caller it's done — Refresh must re-enable even when discovery never once succeeds.
        Path exe = dir.resolve("pi");
        Files.writeString(exe, "#!/bin/sh\nexit 1\n");
        exe.toFile().setExecutable(true);
        PiPluginSettings.setDiscoveredModels(new String[]{"cached/model"});
        try {
            CompletableFuture<List<String>> result = new CompletableFuture<>();
            PiModelDiscovery.discoverAsync(exe.toString(), result::complete);
            // 20 near-instant failing process spawns; generous bound for slow CI.
            List<String> got = result.get(60, TimeUnit.SECONDS);
            assertEquals(List.of("cached/model"), got, "exhausted retries must report the cache, not silence");
        }
        finally {
            PiPluginSettings.setDiscoveredModels(new String[0]);
        }
    }
}
