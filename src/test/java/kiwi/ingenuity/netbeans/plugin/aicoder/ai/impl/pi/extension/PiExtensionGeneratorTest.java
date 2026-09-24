package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.extension;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiTimeoutEnum;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PiExtensionGeneratorTest {

    private String sessionId;

    @AfterEach
    void cleanup() {
        if (sessionId != null) {
            PiExtensionFiles.delete(sessionId);
        }
    }

    @Test
    void buildConfig_containsEveryField() {
        JsonObject config = PiExtensionGenerator.buildConfig("session-1", "http://127.0.0.1:9/mcp/pi", "http://127.0.0.1:9/");

        assertEquals(Installer.VERSION, config.get("pluginVersion").getAsString());
        assertEquals("session-1", config.get("sessionId").getAsString());
        assertEquals("http://127.0.0.1:9/mcp/pi", config.get("mcpUrl").getAsString());
        assertEquals("http://127.0.0.1:9/", config.get("hookUrl").getAsString());
        assertTrue(config.get("gatedTools").isJsonArray());
        assertEquals(2, config.getAsJsonArray("gatedTools").size());
        assertEquals("edit", config.getAsJsonArray("gatedTools").get(0).getAsString());
        assertEquals("write", config.getAsJsonArray("gatedTools").get(1).getAsString());
        assertTrue(config.get("excludedTools").isJsonArray());
        assertEquals(PiTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis(), config.get("toolCallTimeoutMs").getAsLong());
        assertEquals(PiTimeoutEnum.MCP_HANDSHAKE_TIMEOUT_MILLIS.millis(), config.get("handshakeTimeoutMs").getAsLong());
        assertFalse(config.has("steeringEnabled"));
        assertFalse(config.has("steeringTexts"));
    }

    @Test
    void buildConfig_neverWritesASecretKey() {
        JsonObject config = PiExtensionGenerator.buildConfig("session-1", "http://127.0.0.1:9/mcp/pi", "http://127.0.0.1:9/");
        assertFalse(config.has("secretKey"));
    }

    @Test
    void buildConfig_excludedToolsFromPolicyAppear() {
        JsonObject config = PiExtensionGenerator.buildConfig("session-1", "http://127.0.0.1:9/mcp/pi", "http://127.0.0.1:9/");

        assertEquals(PiToolExposurePolicy.excludedTools(), toStringSet(config.getAsJsonArray("excludedTools")),
                "excludedTools must contain exactly the policy's tool names, not merely the same count");
    }

    /**
     * The real {@code PiToolExposurePolicy} is empty today, so the test above alone can't distinguish "same
     * size" from "same content" — a config carrying entirely different tool names but the same count would
     * also pass a size-only check. Builds the {@code JsonArray} the same way {@code buildConfig()} assembles
     * it, directly from a locally constructed non-empty excluded-tools set, to prove the set-equality
     * comparison above would actually catch that case once the real policy is ever non-empty.
     */
    @Test
    void excludedToolsComparison_catchesSameCountButDifferentNames() {
        Set<String> policyNames = Set.of("Bash", "WebRequest");
        JsonArray configuredNames = new JsonArray();
        configuredNames.add("SomethingElse");
        configuredNames.add("AndAnotherThing");

        assertEquals(policyNames.size(), configuredNames.size(), "set up to have matching counts");
        assertNotEquals(policyNames, toStringSet(configuredNames),
                "same count but different tool names must not be treated as equal");
    }

    private static Set<String> toStringSet(JsonArray array) {
        Set<String> out = new HashSet<>();
        array.forEach(el -> out.add(el.getAsString()));
        return out;
    }

    @Test
    void replacePlaceholderExactlyOnce_replacesTheSinglePlaceholder() throws IOException {
        String template = "before " + PiExtensionGeneratorAccess.placeholder() + " after";
        String result = PiExtensionGenerator.replacePlaceholderExactlyOnce(template, "REPLACED");
        assertEquals("before REPLACED after", result);
    }

    @Test
    void replacePlaceholderExactlyOnce_missingPlaceholderThrows() {
        assertThrows(IOException.class,
                () -> PiExtensionGenerator.replacePlaceholderExactlyOnce("no placeholder here", "REPLACED"));
    }

    @Test
    void replacePlaceholderExactlyOnce_duplicatePlaceholderThrows() {
        String template = PiExtensionGeneratorAccess.placeholder() + " ... " + PiExtensionGeneratorAccess.placeholder();
        assertThrows(IOException.class,
                () -> PiExtensionGenerator.replacePlaceholderExactlyOnce(template, "REPLACED"));
    }

    @Test
    void generate_writesOneFileWithEscapedQuotesBackslashesAndNonAsciiSurvivingIntact() throws IOException {
        sessionId = "quote\"back\\slash-" + UUID.randomUUID();
        // Literal non-ASCII characters (typed directly, never a backslash-u escape) to prove they survive Gson's escaping.
        String hookUrl = "http://127.0.0.1:9/café/";
        String mcpUrlWithPi = "http://127.0.0.1:9/mcp/πi";

        Path written = PiExtensionGenerator.generate(sessionId, mcpUrlWithPi, hookUrl);

        assertTrue(Files.isRegularFile(written));
        String content = Files.readString(written);

        // Exactly one AICODER_CONFIG assignment in the rendered file.
        int first = content.indexOf("const AICODER_CONFIG = ");
        assertTrue(first >= 0, "rendered file must contain the config assignment");
        int second = content.indexOf("const AICODER_CONFIG = ", first + 1);
        assertEquals(-1, second, "the placeholder must be replaced exactly once");

        // Pull out the JSON object literal and round-trip it to confirm escaping preserved every
        // character exactly, including the embedded quote, backslash and non-ASCII text.
        int jsonStart = first + "const AICODER_CONFIG = ".length();
        int jsonEnd = content.indexOf(";\n", jsonStart);
        String jsonLiteral = content.substring(jsonStart, jsonEnd);
        JsonObject parsed = com.google.gson.JsonParser.parseString(jsonLiteral).getAsJsonObject();
        assertEquals(sessionId, parsed.get("sessionId").getAsString());
        assertEquals(mcpUrlWithPi, parsed.get("mcpUrl").getAsString());
        assertEquals(hookUrl, parsed.get("hookUrl").getAsString());
    }

    @Test
    void generate_writesUnderThePluginConfigDirNotPisOwnAutoDiscoveryDirs() throws IOException {
        sessionId = "auto-discovery-check-" + UUID.randomUUID();
        Path written = PiExtensionGenerator.generate(sessionId, "http://127.0.0.1:9/mcp/pi", "http://127.0.0.1:9/");

        String pathString = written.toString();
        String home = System.getProperty("user.home");
        assertFalse(pathString.startsWith(Path.of(home, ".pi").toString()),
                "must not be written under pi's own ~/.pi auto-discovery directory");
        assertTrue(pathString.contains(".ai-coder"), "must be written under the plugin's own config directory");
    }

    @Test
    void generate_includesLiveSteeringLogicInTemplate() throws IOException {
        sessionId = "steering-" + UUID.randomUUID();
        Path written = PiExtensionGenerator.generate(sessionId, "http://127.0.0.1:9/mcp/pi", "http://127.0.0.1:9/");

        String content = Files.readString(written);
        assertTrue(content.contains("queryCurrentSteering"), "template must query live steering state");
        assertTrue(content.contains("registeredMcpTools"), "template must track registered MCP tools for steering");
        assertTrue(content.contains("AICODER_CONFIG.hookUrl"), "template must use the live hook endpoint");
        assertTrue(content.contains("let mcpToolsReady = false"), "steering must fail open until MCP registration is complete");
        assertTrue(content.contains("mcpToolsReady && !registeredMcpTools.has(call.toolName) && !gated.has(call.toolName)"),
                "steering must exclude MCP tools and the existing edit/write review gate");
        assertTrue(content.contains("registerMcpTools(pi, ctx, registeredMcpTools, () => {"),
                "registration must publish readiness only after populating the set");
        assertTrue(content.contains("return reviewGatedCall(call, ctx);"),
                "edit/write must retain the review gate when steering is off");
    }

    @Test
    void generatedSteeringQueriesLiveHookAndFailsOpen() throws IOException {
        sessionId = "steering-live-" + UUID.randomUUID();
        Path written = PiExtensionGenerator.generate(sessionId, "http://127.0.0.1:9/mcp/pi",
                "http://127.0.0.1:9/");

        String content = Files.readString(written);
        assertTrue(content.contains("async function queryCurrentSteering(call, signal)"),
                "native calls must resolve the current steering state");
        assertTrue(content.contains("postJsonNoFetch(AICODER_CONFIG.hookUrl"),
                "live steering must use the hook endpoint");
        assertTrue(content.contains("STEERING_QUERY_TIMEOUT_MS = 1000"),
                "steering probes must not inherit the 30-second handshake timeout");
        assertTrue(content.contains("session_id: AICODER_CONFIG.sessionId"),
                "the live query must identify this pi session");
        assertTrue(content.contains("catch (e) {\n        // Steering is guidance"),
                "hook failures must fail open");
        assertTrue(content.contains("const steeringDecision = await queryCurrentSteering(call, ctx && ctx.signal)"),
                "tool calls must await the live steering decision and propagate cancellation");
        assertTrue(content.contains("postJsonNoFetch(AICODER_CONFIG.hookUrl"),
                "the probe must use the live hook endpoint");
        assertTrue(content.contains("}, {}, STEERING_QUERY_TIMEOUT_MS, signal)"),
                "the probe must enforce its short timeout and cancellation signal");
        assertTrue(content.contains("    markMcpToolsReady();\n    if (ctx && ctx.ui"),
                "readiness must not depend on a UI context");
    }

    /**
     * Exposes the generator's private placeholder token to this test without widening its own visibility
     * beyond package-private.
     */
    private static final class PiExtensionGeneratorAccess {

        static String placeholder() {
            return "/*__AICODER_CONFIG__*/";
        }
    }
}
