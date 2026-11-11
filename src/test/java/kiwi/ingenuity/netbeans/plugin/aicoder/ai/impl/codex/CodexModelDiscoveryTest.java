package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.codex.settings.CodexPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.testutil.MemoryPreferences;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CodexModelDiscoveryTest {

    @AfterEach
    void restoreBudget() {
        CodexModelDiscovery.discoveryBudgetMillisForTests = null;
    }

    @Test
    @Timeout(5)
    void parsesModelsAcrossPages() {
        JsonObject first = page("next", entry("visible-one"), entry("visible-two"));
        JsonObject second = page(null, entry("visible-three"));

        assertEquals(List.of("visible-one", "visible-two"), CodexModelDiscovery.parseModels(first));
        assertEquals("next", CodexModelDiscovery.nextCursor(first));
        assertEquals(List.of("visible-three"), CodexModelDiscovery.parseModels(second));
        assertNull(CodexModelDiscovery.nextCursor(second));
    }

    @Test
    @Timeout(5)
    void parseModelsDropsHiddenEntries() {
        JsonObject hidden = entry("not-for-picker");
        hidden.addProperty(CodexJsonKeyEnum.HIDDEN.key(), true);

        assertEquals(List.of("visible"), CodexModelDiscovery.parseModels(page(null, entry("visible"), hidden)));
    }

    @Test
    @Timeout(5)
    void stopsPaginationWhenCursorRepeats() {
        Set<String> seen = new LinkedHashSet<>();

        assertTrue(CodexModelDiscovery.acceptCursor(seen, "next"));
        assertFalse(CodexModelDiscovery.acceptCursor(seen, "next"));
    }

    @Test
    @Timeout(15)
    void silentCodexReturnsEmptyWithinTheBudgetInsteadOfHanging(@TempDir Path dir) throws Exception {
        CodexModelDiscovery.discoveryBudgetMillisForTests = 400L;
        Path codex = executableScript(dir, "codex-silent", "cat >/dev/null");

        long started = System.nanoTime();
        List<String> models = assertTimeoutPreemptively(Duration.ofSeconds(10),
                                                        () -> CodexModelDiscovery.discover(codex.toString()));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(models.isEmpty(), "a Codex that never answers yields no models");
        assertTrue(elapsedMillis < 5_000, "discovery must give up within the budget, took " + elapsedMillis + " ms");
    }

    @Test
    @Timeout(5)
    void theSessionProbePublishesOnlyACompleteModelList() {
        CodexPluginSettings.useForTests(MemoryPreferences.create());
        AiModelCatalog catalog = CodexAiImplementation.modelCatalog();
        List<String> before = catalog.getCachedModels();
        try {
            assertFalse(CodexAiProcessManager.publishIfCompleteList(page("more", entry("gpt-a"), entry("gpt-b"))),
                        "a page with a nextCursor is partial and must not be published");
            assertEquals(before, catalog.getCachedModels());

            assertTrue(CodexAiProcessManager.publishIfCompleteList(page(null, entry("gpt-a"), entry("gpt-b"))));
            assertEquals(List.of("gpt-a", "gpt-b"), catalog.getCachedModels());
        }
        finally {
            catalog.publish(before);
            catalog.invalidate();
            CodexPluginSettings.useForTests(null);
        }
    }

    @Test
    @Timeout(20)
    void discoverUnionsEveryPageOfACompleteList(@TempDir Path dir) throws Exception {
        CodexModelDiscovery.discoveryBudgetMillisForTests = 10_000L;
        Path codex = pagingCodex(dir, "codex-two-pages",
                                 "{\"data\":[{\"id\":\"gpt-a\"}],\"nextCursor\":\"next\"}",
                                 "{\"data\":[{\"id\":\"gpt-b\"}]}");

        List<String> models = assertTimeoutPreemptively(Duration.ofSeconds(15),
                                                        () -> CodexModelDiscovery.discover(codex.toString()));

        assertEquals(List.of("gpt-a", "gpt-b"), models);
    }

    @Test
    @Timeout(20)
    void aRepeatedCursorIsAFailedAttemptNotAPartialList(@TempDir Path dir) throws Exception {
        CodexModelDiscovery.discoveryBudgetMillisForTests = 10_000L;
        Path codex = pagingCodex(dir, "codex-repeating",
                                 "{\"data\":[{\"id\":\"gpt-a\"}],\"nextCursor\":\"next\"}",
                                 "{\"data\":[{\"id\":\"gpt-b\"}],\"nextCursor\":\"next\"}");

        List<String> models = assertTimeoutPreemptively(Duration.ofSeconds(15),
                                                        () -> CodexModelDiscovery.discover(codex.toString()));

        assertTrue(models.isEmpty(), "a list that ends on a repeated cursor must not be treated as complete");
    }

    @Test
    @Timeout(20)
    void aPageWithoutADataArrayIsAFailedAttemptNotAPartialList(@TempDir Path dir) throws Exception {
        CodexModelDiscovery.discoveryBudgetMillisForTests = 10_000L;
        Path codex = pagingCodex(dir, "codex-no-data",
                                 "{\"data\":[{\"id\":\"gpt-a\"}],\"nextCursor\":\"next\"}",
                                 "{\"items\":[{\"id\":\"gpt-b\"}]}");

        List<String> models = assertTimeoutPreemptively(Duration.ofSeconds(15),
                                                        () -> CodexModelDiscovery.discover(codex.toString()));

        assertTrue(models.isEmpty(), "a later page without a data array must fail the whole attempt");
    }

    @Test
    @Timeout(30)
    void sessionProbeNeverPublishesAPageWithoutADataArray(@TempDir Path dir) throws Exception {
        CodexPluginSettings.useForTests(MemoryPreferences.create());
        AiModelCatalog catalog = CodexAiImplementation.modelCatalog();
        List<String> before = catalog.getCachedModels();
        try {
            Path codex = pagingCodex(dir, "codex-probe-no-data",
                                     "{\"data\":[{\"id\":\"gpt-a\"}],\"nextCursor\":\"next\"}",
                                     "{\"items\":[{\"id\":\"gpt-b\"}]}");

            assertThrows(IllegalStateException.class, () -> readModelListFrom(codex));
            assertEquals(before, catalog.getCachedModels(), "a malformed page must not publish a partial list");
        }
        finally {
            catalog.publish(before);
            catalog.invalidate();
            CodexPluginSettings.useForTests(null);
        }
    }

    @Test
    @Timeout(30)
    void discoverAsyncIsSingleFlightAndDoesNotRetryAnEmptySuccess(@TempDir Path dir) throws Exception {
        CodexModelDiscovery.discoveryBudgetMillisForTests = 10_000L;
        awaitDiscoveryIdle();
        Path starts = dir.resolve("starts");
        Path codex = countingCodex(dir, "codex-empty", starts);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<List<String>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        CodexModelDiscovery.discoverAsync(codex.toString(), models -> {
                                      callbacks.incrementAndGet();
                                      result.set(models);
                                      done.countDown();
                                  });
        // The script sleeps first, so this second call lands while the first is still in flight and must be ignored.
        CodexModelDiscovery.discoverAsync(codex.toString(), models -> callbacks.incrementAndGet());

        assertTrue(done.await(20, TimeUnit.SECONDS), "discovery must finish");
        Thread.sleep(300);
        assertEquals(1, callbacks.get(), "a second call while discovery runs must be ignored");
        assertTrue(result.get().isEmpty(), "a successful empty answer is final");
        assertEquals(1, Files.readAllLines(starts).size(), "an empty success must not be retried");
    }

    @Test
    @Timeout(30)
    void sessionProbeReadsEveryPageAndPublishesTheWholeList(@TempDir Path dir) throws Exception {
        CodexPluginSettings.useForTests(MemoryPreferences.create());
        AiModelCatalog catalog = CodexAiImplementation.modelCatalog();
        List<String> before = catalog.getCachedModels();
        try {
            Path codex = pagingCodex(dir, "codex-probe-two-pages",
                                     "{\"data\":[{\"id\":\"gpt-a\"}],\"nextCursor\":\"next\"}",
                                     "{\"data\":[{\"id\":\"gpt-b\"}]}");

            assertTrue(CodexAiProcessManager.publishIfCompleteList(readModelListFrom(codex)));
            assertEquals(List.of("gpt-a", "gpt-b"), catalog.getCachedModels());
        }
        finally {
            catalog.publish(before);
            catalog.invalidate();
            CodexPluginSettings.useForTests(null);
        }
    }

    @Test
    @Timeout(30)
    void sessionProbeNeverPublishesAnEndlessCursor(@TempDir Path dir) throws Exception {
        CodexPluginSettings.useForTests(MemoryPreferences.create());
        AiModelCatalog catalog = CodexAiImplementation.modelCatalog();
        List<String> before = catalog.getCachedModels();
        try {
            Path codex = pagingCodex(dir, "codex-probe-endless",
                                     "{\"data\":[{\"id\":\"gpt-a\"}],\"nextCursor\":\"next\"}",
                                     "{\"data\":[{\"id\":\"gpt-b\"}],\"nextCursor\":\"next\"}");

            assertThrows(IllegalStateException.class, () -> readModelListFrom(codex));
            assertEquals(before, catalog.getCachedModels(), "an endless cursor must not publish a partial list");
        }
        finally {
            catalog.publish(before);
            catalog.invalidate();
            CodexPluginSettings.useForTests(null);
        }
    }

    /**
     * Reads every model/list page through a real client over the fake script's pipes, as the session probe does.
     */
    private static JsonObject readModelListFrom(Path codex) throws Exception {
        Process process = new ProcessBuilder(codex.toString(), "app-server")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        CodexJsonRpcClient client = new CodexJsonRpcClient(process.getOutputStream(), process.getInputStream(),
                                                           (method, params) -> {
                                                           },
                                                           (method, params) -> CompletableFuture.completedFuture(new JsonObject()),
                                                           cause -> {
                                                           });
        try {
            client.sendRequest("initialize", new JsonObject()).get(10, TimeUnit.SECONDS);
            client.sendNotification("initialized", new JsonObject());
            return CodexModelDiscovery.readModelList(client, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        }
        finally {
            client.close();
            process.destroyForcibly();
        }
    }

    /**
     * Waits for a discovery started by an earlier test to finish, so this test's own call is the one that runs.
     */
    private static void awaitDiscoveryIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (CodexModelDiscovery.discoveryInProgressForTests() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static Path pagingCodex(Path dir, String name, String firstPage, String secondPage) throws Exception {
        String body = """
                while IFS= read -r line; do
                  id=$(printf '%s' "$line" | sed -n 's/.*"id":\\([0-9][0-9]*\\).*/\\1/p')
                  case "$line" in
                    *'"method":"initialize"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{}}\\n' "$id" ;;
                    *'"method":"model/list"'*)
                      case "$line" in
                        *'"cursor"'*) printf '{"jsonrpc":"2.0","id":%s,"result":%s}\\n' "$id" '<SECOND>' ;;
                        *) printf '{"jsonrpc":"2.0","id":%s,"result":%s}\\n' "$id" '<FIRST>' ;;
                      esac ;;
                  esac
                done
                """.replace("<FIRST>", firstPage).replace("<SECOND>", secondPage);
        return executableScript(dir, name, body);
    }

    private static Path countingCodex(Path dir, String name, Path starts) throws Exception {
        String body = """
                sleep 1
                echo started >> '<STARTS>'
                while IFS= read -r line; do
                  id=$(printf '%s' "$line" | sed -n 's/.*"id":\\([0-9][0-9]*\\).*/\\1/p')
                  case "$line" in
                    *'"method":"initialize"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{}}\\n' "$id" ;;
                    *'"method":"model/list"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"data":[]}}\\n' "$id" ;;
                  esac
                done
                """.replace("<STARTS>", starts.toString());
        return executableScript(dir, name, body);
    }

    private static Path executableScript(Path dir, String name, String body) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                               "fake executable scripts need POSIX permission bits");
        Path path = dir.resolve(name);
        Files.writeString(path, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
                                                   PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE));
        return path;
    }

    private static JsonObject page(String cursor, JsonObject... entries) {
        JsonArray data = new JsonArray();
        for (JsonObject entry : entries) {
            data.add(entry);
        }
        JsonObject page = new JsonObject();
        page.add(CodexJsonKeyEnum.DATA.key(), data);
        if (cursor != null) {
            page.addProperty(CodexJsonKeyEnum.NEXT_CURSOR.key(), cursor);
        }
        return page;
    }

    private static JsonObject entry(String id) {
        JsonObject entry = new JsonObject();
        entry.addProperty(CodexJsonKeyEnum.ID.key(), id);
        return entry;
    }
}
