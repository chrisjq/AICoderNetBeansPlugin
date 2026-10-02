package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.McpSteeringRefusalEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Grok's {@code session/request_permission} write shape — {@code rawInput: {variant:"Write", file_path,
 * content}} — bridged through the shared
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.acp.AbstractAcpClientHandler} into the same
 * {@link PermissionEvent}/diff-panel mechanism OpenCode uses (Boss's decision: reuse the shared bridge, don't
 * steer Grok away from its native edit tool). Also locks in the option-id mapping: Grok's options array
 * spells "allow once" and "reject" differently from OpenCode's literal {@code "once"}/
 * {@code "reject"}, and the reply must use the id named in the actual request, not either hardcoded literal.
 */
@Timeout(10)
class GrokAcpClientHandlerPermissionTest {

    private static JsonObject grokWriteToolCall(String filePath, String content) {
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("variant", "Write");
        rawInput.addProperty("file_path", filePath);
        rawInput.addProperty("content", content);
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("kind", "edit");
        toolCall.addProperty("title", "Write " + filePath);
        toolCall.add("rawInput", rawInput);
        return toolCall;
    }

    private static JsonObject requestWithGrokOptions(JsonObject toolCall) {
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        JsonArray options = new JsonArray();
        options.add(option("allow-once", "Allow once", "allow_once"));
        options.add(option("allow-edits-session", "Allow for session", "allow_always"));
        options.add(option("reject", "Reject", "reject_once"));
        params.add("options", options);
        return params;
    }

    private static JsonObject option(String optionId, String name, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("optionId", optionId);
        o.addProperty("name", name);
        o.addProperty("kind", kind);
        return o;
    }

    @Test
    void grokWriteRawInputShape_extractsPathAndContent_raisesPermissionEventWithGrokOptionIds() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });

        JsonObject toolCall = grokWriteToolCall("/tmp/project/Foo.java", "class Foo {}");
        CompletableFuture<JsonObject> reply = handler.onRequestPermission(requestWithGrokOptions(toolCall));

        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof PermissionEvent, "a Grok write must raise the same PermissionEvent OpenCode's does");
        PermissionEvent event = (PermissionEvent) fired.get(0);
        assertEquals("/tmp/project/Foo.java", event.filePath(), "path must come from rawInput.file_path");
        assertEquals("class Foo {}", event.writeContent(), "content must come from rawInput.content, not a diff block");

        event.response().complete(kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision.allowed());
        JsonObject result = reply.get();
        String optionId = result.getAsJsonObject("outcome").get("optionId").getAsString();
        assertEquals("allow-once", optionId,
                "the reply must use GROK's own allow_once optionId (\"allow-once\"), not OpenCode's literal \"once\"");
    }

    @Test
    void grokWriteRejected_repliesWithGrokOwnRejectOptionId() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });

        JsonObject toolCall = grokWriteToolCall("/tmp/project/Bar.java", "class Bar {}");
        CompletableFuture<JsonObject> reply = handler.onRequestPermission(requestWithGrokOptions(toolCall));

        PermissionEvent event = (PermissionEvent) fired.get(0);
        event.response().complete(kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision.denied("no"));
        JsonObject result = reply.get();
        String optionId = result.getAsJsonObject("outcome").get("optionId").getAsString();
        assertEquals("reject", optionId);
    }

    @Test
    void requestWithNoOptionsArray_fallsBackToOpenCodesLiteralIds() throws Exception {
        // A request shaped without an options array (older probe, or a backend that omits it) must keep
        // working exactly as it did before Grok's dynamic-option-id resolution existed.
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });
        JsonObject toolCall = grokWriteToolCall("/tmp/project/Baz.java", "class Baz {}");
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(params);
        PermissionEvent event = (PermissionEvent) fired.get(0);
        event.response().complete(kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision.allowed());
        JsonObject result = reply.get();
        assertEquals("once", result.getAsJsonObject("outcome").get("optionId").getAsString());
    }

    // ---- Review finding 6: isMutationRequest must catch Grok's write shape regardless of kind ----
    @Test
    void grokWriteMislabelledKindOther_stillRaisesPermissionEventNotTheNoDiffConfirmPath() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });

        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("variant", "Write");
        rawInput.addProperty("file_path", "/tmp/project/Sneaky.java");
        rawInput.addProperty("content", "class Sneaky {}");
        JsonObject toolCall = new JsonObject();
        // "other" is in ACCESS_KINDS — without the rawInput-shape check in isMutationRequest, this would
        // wrongly route through the no-diff ConfirmEvent access path instead of the diff panel.
        toolCall.addProperty("kind", "other");
        toolCall.addProperty("title", "Write /tmp/project/Sneaky.java");
        toolCall.add("rawInput", rawInput);
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        handler.onRequestPermission(params);

        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof PermissionEvent,
                "a write disguised as kind=\"other\" must still reach the diff panel, not a no-diff ConfirmEvent: " + fired);
    }

    // ---- Review finding 7 (+ Boss's refinement): fail closed, by optionId text, never an invented literal ----
    @Test
    void optionsArrayWithUnrecognisedKind_fallsBackToOptionIdText() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });
        JsonObject toolCall = grokWriteToolCall("/tmp/project/Weird.java", "class Weird {}");
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        JsonArray options = new JsonArray();
        options.add(option("do-allow-it", "Allow", "some_future_allow_variant"));
        options.add(option("do-reject-it", "Reject", "some_future_reject_variant"));
        params.add("options", options);

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(params);
        PermissionEvent event = (PermissionEvent) fired.get(0);
        event.response().complete(PermissionDecision.allowed());
        JsonObject result = reply.get();
        assertEquals("do-allow-it", result.getAsJsonObject("outcome").get("optionId").getAsString(),
                "no recognised kind, but the optionId text itself says \"allow\" — must use that, never invent \"once\"");
    }

    @Test
    void optionsArrayWithNothingRecognisable_repliesCancelled_neverInventsAnId() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });
        JsonObject toolCall = grokWriteToolCall("/tmp/project/Mystery.java", "class Mystery {}");
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        JsonArray options = new JsonArray();
        options.add(option("opt-a", "Option A", "mystery_kind_1"));
        options.add(option("opt-b", "Option B", "mystery_kind_2"));
        params.add("options", options);

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(params);
        PermissionEvent event = (PermissionEvent) fired.get(0);
        event.response().complete(PermissionDecision.denied("no"));
        JsonObject result = reply.get();
        assertEquals("cancelled", result.getAsJsonObject("outcome").get("outcome").getAsString(),
                "nothing recognisable at all, by kind or by optionId text — must cancel, never invent \"reject\"");
    }

    // ---- Boss's verify follow-up: the fallback must never widen a single approval into a standing one ----
    @Test
    void optionsArrayWithOnlyAllowAlways_fallbackNeverGrantsAlways_repliesCancelled() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });
        JsonObject toolCall = grokWriteToolCall("/tmp/project/Danger.java", "class Danger {}");
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        JsonArray options = new JsonArray();
        // The only allow-shaped optionId available is scoped to "always" — an unrecognised kind means the
        // strict match can't see that, so only the optionId-text fallback stands between this and silently
        // granting a standing approval for what the user believed was a single click.
        options.add(option("allow-always", "Allow always", "some_unrecognised_allow_kind"));
        params.add("options", options);

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(params);
        PermissionEvent event = (PermissionEvent) fired.get(0);
        event.response().complete(PermissionDecision.allowed());
        JsonObject result = reply.get();
        assertEquals("cancelled", result.getAsJsonObject("outcome").get("outcome").getAsString(),
                "the only allow-shaped option is \"always\"-scoped — the fallback must never grant it just "
                + "because the user approved, and must never invent a different id either");
    }

    /**
     * BigP_1's follow-up confirmation: Grok's REAL array order lists the session-scoped option BEFORE the
     * once-scoped one ({@code allow-edits-session} then {@code allow-once} — see
     * {@code requestWithGrokOptions}), so the fallback's "first match wins" rule must not pick the
     * always/session-scoped one just because it is seen first. With unrecognised kinds forcing the
     * optionId-text fallback, the exclusion must skip {@code allow-edits-session} and keep looking, landing
     * on {@code allow-once}.
     */
    @Test
    void fallback_withGroksRealArrayOrder_allowEditsSessionBeforeAllowOnce_stillChoosesAllowOnce() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });
        JsonObject toolCall = grokWriteToolCall("/tmp/project/Order.java", "class Order {}");
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        JsonArray options = new JsonArray();
        options.add(option("allow-edits-session", "Allow for session", "some_future_always_kind"));
        options.add(option("allow-once", "Allow once", "some_future_once_kind"));
        options.add(option("reject", "Reject", "some_future_reject_kind"));
        params.add("options", options);

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(params);
        PermissionEvent event = (PermissionEvent) fired.get(0);
        event.response().complete(PermissionDecision.allowed());
        JsonObject result = reply.get();
        assertEquals("allow-once", result.getAsJsonObject("outcome").get("optionId").getAsString(),
                "allow-edits-session appears first in array order but must be skipped for its \"session\" scope, "
                + "landing on allow-once, never the wider grant seen first");
    }

    // ---- Review finding 8: steering ON must not steer away OUR OWN tool reached via Grok's lazy use_tool ----
    @Test
    void useToolWrappingOurOwnMcpTool_withSteeringOn_isAutoAllowed_noPrompt() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        }, null, null, sid -> true, "plugin-session-1");

        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("tool_name", "mcp__aicoder-nb-ki-plugin__GetFileContent");
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "use_tool");
        toolCall.addProperty("kind", "other");
        toolCall.add("rawInput", rawInput);
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(params);

        assertTrue(fired.isEmpty(), "one of OUR OWN tools must never raise any event, not even a ConfirmEvent: " + fired);
        JsonObject result = reply.get();
        assertEquals("once", result.getAsJsonObject("outcome").get("optionId").getAsString(),
                "no options array here, so the literal OpenCode-shaped fallback id is what allow-once resolves to");
    }

    @Test
    void useToolWrappingAForeignTool_withSteeringOn_isSteeredAway() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        }, null, null, sid -> true, "plugin-session-1");

        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("tool_name", "some_native_grok_tool");
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "use_tool");
        toolCall.addProperty("kind", "other");
        toolCall.add("rawInput", rawInput);
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);

        handler.onRequestPermission(params);

        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof McpSteeringRefusalEvent,
                "a native tool call that is NOT one of ours must still be steered away when steering is on: " + fired);
    }

    private static JsonObject useToolRequest(String toolName, JsonArray options) {
        JsonObject toolInput = new JsonObject();
        toolInput.addProperty("sessionId", "ses-1");
        toolInput.addProperty("secretKey", "secret-1");
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("variant", "UseTool");
        rawInput.addProperty("tool_name", toolName);
        rawInput.add("tool_input", toolInput);
        JsonObject xaiTool = new JsonObject();
        xaiTool.addProperty("name", "use_tool");
        JsonObject meta = new JsonObject();
        meta.add("x.ai/tool", xaiTool);
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("kind", "other");
        toolCall.addProperty("title", toolName);
        toolCall.add("rawInput", rawInput);
        toolCall.add("_meta", meta);
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        params.add("options", options);
        return params;
    }

    private static JsonArray realGrokOptions() {
        JsonArray options = new JsonArray();
        options.add(option("always-allow", "Always allow", "allow_always"));
        options.add(option("allow-once", "Allow once", "allow_once"));
        options.add(option("reject-once", "Reject once", "reject_once"));
        return options;
    }

    /**
     * LEAD on the grok-4.6 exit-143 bug: the exact {@code use_tool} permission request captured from a live
     * probe for grok-4.6's bootstrap {@code GetInstructions} call. Its
     * {@code title}/{@code rawInput.tool_name} is {@code "aicoder-nb-ki-plugin__GetInstructions"} — the
     * plugin's server name plus "__", NOT the full {@code "mcp__aicoder-nb-ki-plugin__GetInstructions"} form.
     * Before the prefix fix, this was unrecognised as ours and steered away; a steering refusal on grok-4.6's
     * very first, mandatory tool call is what let the agent reach the state where it terminates.
     *
     * <p>
     * Recognised as ours must now mean auto-allowed with NO prompt at all — not merely "not steered". A
     * ConfirmEvent on every single plugin-tool call an agent makes through {@code use_tool} (GetInstructions,
     * GetFileContent, PeerMessageSend, ...) would be unusable; our tools carry their own gating already.
     */
    @Test
    void grok46LiveUseToolForGetInstructions_serverPrefixedName_isAutoAllowed_noPrompt() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        }, null, null, sid -> true, "plugin-session-1");

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(
                useToolRequest("aicoder-nb-ki-plugin__GetInstructions", realGrokOptions()));

        assertTrue(fired.isEmpty(), "one of our own tools must never raise any event — no prompt at all: " + fired);
        JsonObject result = reply.get();
        assertEquals("allow-once", result.getAsJsonObject("outcome").get("optionId").getAsString(),
                "must auto-reply with grok's own real allow_once id immediately");
    }

    /**
     * SECURITY: the server-name-prefix match must be exact, never a generic "strip to the last __". A foreign
     * MCP server whose tool happens to share one of our bare tool names (e.g. a user's own "othersrv" server
     * also having an "ApplyEdit") must NOT be recognised as ours just because the text after its "__" matches
     * — that would wrongly exempt a foreign tool call from steering.
     */
    @Test
    void useToolForAForeignServersSameNamedTool_isNotRecognisedAsOurs_stillSteered() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        }, null, null, sid -> true, "plugin-session-1");

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(
                useToolRequest("othersrv__ApplyEdit", realGrokOptions()));

        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof McpSteeringRefusalEvent,
                "a foreign server's tool must still be steered away, matching bare name or not: " + fired);
        JsonObject result = reply.get();
        assertEquals("reject-once", result.getAsJsonObject("outcome").get("optionId").getAsString());
    }

    /**
     * A native (non-use_tool) Grok write must still go through the diff panel unchanged — the "our own tools"
     * auto-allow must not accidentally swallow Grok's own built-in tool calls too.
     */
    @Test
    void nativeGrokWriteTool_isNotOurs_stillGoesToDiffPanel() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });

        JsonObject toolCall = grokWriteToolCall("/tmp/project/Native.java", "class Native {}");
        CompletableFuture<JsonObject> reply = handler.onRequestPermission(requestWithGrokOptions(toolCall));

        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof PermissionEvent,
                "a native Grok write must still raise the diff-panel PermissionEvent: " + fired);
        ((PermissionEvent) fired.get(0)).response().complete(PermissionDecision.allowed());
        JsonObject result = reply.get();
        assertEquals("allow-once", result.getAsJsonObject("outcome").get("optionId").getAsString());
    }

    /**
     * SECURITY (Coder_1 + BigP_1, HIGH): a native write whose {@code title} is spoofed to exactly one of our
     * tool names, with kind "edit" and no {@code use_tool} wrapper shape at all, must still reach the diff
     * panel — never auto-allowed. Before the fix, the bare title match alone would have skipped it entirely.
     */
    @Test
    void nativeWriteWithTitleSpoofedToOurToolName_stillGoesToDiffPanel_neverAutoAllowed() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });

        JsonObject toolCall = grokWriteToolCall("/tmp/project/Spoofed.java", "class Spoofed {}");
        toolCall.addProperty("title", "aicoder-nb-ki-plugin__ApplyEdit");

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(requestWithGrokOptions(toolCall));

        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof PermissionEvent,
                "a title spoofed to our tool name must not bypass the diff panel: " + fired);
        ((PermissionEvent) fired.get(0)).response().complete(PermissionDecision.allowed());
        JsonObject result = reply.get();
        assertEquals("allow-once", result.getAsJsonObject("outcome").get("optionId").getAsString());
    }

    /**
     * SECURITY (Coder_1 + BigP_1, HIGH): a native write whose {@code rawInput.tool_name} is spoofed to one of
     * our tool names, but with no {@code "UseTool"} variant (i.e. not structurally a {@code use_tool}
     * wrapper), must still reach the diff panel. Before the fix, {@code rawInput.tool_name} alone (or even
     * {@code rawInput.name}/{@code toolName}/{@code tool}) would have been enough to auto-allow it.
     */
    @Test
    void nativeWriteWithRawInputToolNameSpoofed_noUseToolVariant_stillGoesToDiffPanel() throws Exception {
        List<AiProcessEvent> fired = new ArrayList<>();
        GrokAcpClientHandler handler = new GrokAcpClientHandler(fired::add, () -> {
        });

        JsonObject toolCall = grokWriteToolCall("/tmp/project/Spoofed2.java", "class Spoofed2 {}");
        // rawInput.variant is "Write" (from grokWriteToolCall), NOT "UseTool" — not a use_tool wrapper shape,
        // however the tool name field is spoofed.
        toolCall.getAsJsonObject("rawInput").addProperty("tool_name", "aicoder-nb-ki-plugin__ApplyEdit");

        CompletableFuture<JsonObject> reply = handler.onRequestPermission(requestWithGrokOptions(toolCall));

        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof PermissionEvent,
                "a spoofed rawInput.tool_name with no UseTool variant must not bypass the diff panel: " + fired);
        ((PermissionEvent) fired.get(0)).response().complete(PermissionDecision.allowed());
        JsonObject result = reply.get();
        assertEquals("allow-once", result.getAsJsonObject("outcome").get("optionId").getAsString());
    }
}
