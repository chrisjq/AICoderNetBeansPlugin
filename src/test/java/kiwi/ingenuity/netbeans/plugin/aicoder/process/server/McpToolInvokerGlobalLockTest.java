package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.DeleteAiMessageTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ai.SendAiMessageTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildAntProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildGradleProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.BuildMavenProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildAntProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildGradleProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.CleanAndBuildMavenProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.DownloadMavenJavadocTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build.DownloadMavenSourcesTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunAntTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunGradleTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.test.RunMavenTestsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.BuildProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.CleanAndBuildProjectTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ui.build.CleanProjectTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolInvokerGlobalLockTest {

    private static final long WAIT_SECONDS = 5;

    @Test
    void explicitlyUnlockedMutatingToolRunsWhileDefaultMutatingToolHoldsGlobalLock() throws Exception {
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        BlockingMutatingTool holder = new BlockingMutatingTool(holderEntered, holderRelease);
        ExplicitlyUnlockedTool unlocked = new ExplicitlyUnlockedTool(new CountDownLatch(1), new CountDownLatch(0));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> holdingCall = executor.submit(() -> invoke(holder));
            assertTrue(holderEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            assertEquals("done", invoke(unlocked));
            assertEquals(0, unlocked.entered.getCount());

            holderRelease.countDown();
            assertEquals("done", holdingCall.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            holderRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void defaultMutatingToolsStillSerialiseThroughGlobalLock() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        BlockingMutatingTool first = new BlockingMutatingTool(firstEntered, firstRelease);
        BlockingMutatingTool second = new BlockingMutatingTool(secondEntered, secondRelease);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstCall = executor.submit(() -> invoke(first));
            assertTrue(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Future<String> secondCall = executor.submit(() -> invoke(second));
            assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS),
                        "the second default-mutating invocation must wait for the global lock");

            firstRelease.countDown();
            assertEquals("done", firstCall.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(secondEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            secondRelease.countDown();
            assertEquals("done", secondCall.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            firstRelease.countDown();
            secondRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void ownFileLockingBypassesGlobalLockThroughTheDefaultContract() throws Exception {
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        BlockingMutatingTool holder = new BlockingMutatingTool(holderEntered, holderRelease);
        OwnFileLockingTool ownFileLocking = new OwnFileLockingTool(new CountDownLatch(1), new CountDownLatch(0));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> holdingCall = executor.submit(() -> invoke(holder));
            assertTrue(holderEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            assertEquals("done", invoke(ownFileLocking));
            assertEquals(0, ownFileLocking.entered.getCount());

            holderRelease.countDown();
            assertEquals("done", holdingCall.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            holderRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sendAiMessageHandleRunsWhileDefaultMutatingToolHoldsGlobalLock() throws Exception {
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch holderRelease = new CountDownLatch(1);
        BlockingMutatingTool holder = new BlockingMutatingTool(holderEntered, holderRelease);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> holdingCall = executor.submit(() -> invoke(holder));
            assertTrue(holderEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

            JsonObject args = new JsonObject();
            args.addProperty("targetSessionId", "target");
            args.addProperty("subject", "subject");
            args.addProperty("message", "message");
            String result = McpToolInvoker.invoke(McpToolEnum.SEND_AI_MESSAGE, new SendAiMessageTool(), args, null);
            assertEquals("Error: sessionId is required", result);

            holderRelease.countDown();
            assertEquals("done", holdingCall.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        finally {
            holderRelease.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void queuedBuildAndInboxHandlersBypassGlobalLockButRemainMutating() {
        for (McpToolInterface tool : handlersThatBypassGlobalLock()) {
            assertFalse(tool.requiresGlobalMutationLock(), tool.getClass().getSimpleName());
            assertTrue(tool.isMutating(), tool.getClass().getSimpleName());
        }
    }

    private static String invoke(McpToolInterface tool) throws McpArgumentException {
        return McpToolInvoker.invoke(McpToolEnum.GET_PLUGIN_VERSION, tool, new JsonObject(), null);
    }

    private static List<McpToolInterface> handlersThatBypassGlobalLock() {
        return List.of(
                new BuildMavenProjectTool(),
                new CleanAndBuildMavenProjectTool(),
                new RunMavenTestsTool(),
                new BuildGradleProjectTool(),
                new CleanAndBuildGradleProjectTool(),
                new RunGradleTestsTool(),
                new BuildAntProjectTool(),
                new CleanAndBuildAntProjectTool(),
                new RunAntTestsTool(),
                new DownloadMavenSourcesTool(),
                new DownloadMavenJavadocTool(),
                new BuildProjectTool(),
                new CleanProjectTool(),
                new CleanAndBuildProjectTool(),
                new SendAiMessageTool(),
                new DeleteAiMessageTool());
    }

    private static class BlockingMutatingTool implements McpToolInterface {

        final CountDownLatch entered;
        final CountDownLatch release;

        private BlockingMutatingTool(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public McpSectionEnum section() {
            return McpSectionEnum.PLUGIN;
        }

        @Override
        public String instruction(Set<McpInstructionOptionEnum> options) {
            return null;
        }

        @Override
        public JsonObject schema(Set<McpInstructionOptionEnum> options) {
            JsonObject schema = new JsonObject();
            JsonObject input = new JsonObject();
            input.add(ToolSchemaKeyEnum.PROPERTIES.key(), new JsonObject());
            input.add(ToolSchemaKeyEnum.REQUIRED.key(), new JsonArray());
            schema.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), input);
            return schema;
        }

        @Override
        public String handle(ToolRequestArguments args, AbstractAiSession session) {
            entered.countDown();
            try {
                release.await();
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }
    }

    private static final class ExplicitlyUnlockedTool extends BlockingMutatingTool {

        private ExplicitlyUnlockedTool(CountDownLatch entered, CountDownLatch release) {
            super(entered, release);
        }

        @Override
        public boolean requiresGlobalMutationLock() {
            return false;
        }
    }

    private static final class OwnFileLockingTool extends BlockingMutatingTool {

        private OwnFileLockingTool(CountDownLatch entered, CountDownLatch release) {
            super(entered, release);
        }

        @Override
        public boolean usesOwnFileLocking() {
            return true;
        }
    }
}
