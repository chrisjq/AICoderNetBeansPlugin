package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AskUserQuestionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetClassMembersParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetClassMembersTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetJavadocParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetJavadocTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetProjectStructureTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetTypeHierarchyParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.help.GetTypeHierarchyTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetInstructionsTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.plugin.GetPluginVersionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.userinput.AskUserQuestionParamEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.userinput.AskUserQuestionTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * AUDIT 10 (help / plugin / userinput packages): proves every advertised parameter is honoured where it can be,
 * headless, without ever raising a real dialog.
 *
 * The three {@code help/} class-analysis tools (GetTypeHierarchyTool / GetClassMembersTool / GetJavadocTool) delegate
 * to providers that need the live NetBeans project index ({@code GlobalPathRegistry} + {@code JavaSource} +
 * {@code ClassIndex}), which does not exist on the plain surefire classpath — so their CONTENT (the "does
 * className/memberName change the output" proof) is untestable headless. What IS provable headless is that the required
 * parameter is actually read and validated (blank/absentclassName throws -32602), and that the tool degrades to a
 * graceful string rather than crashing when no source is available.
 *
 * AskUserQuestionTool is the only one that would raise a live UI dialog and block. handle() is constructor-injected
 * with a {@code Supplier<AiProcessEventListener>} (the designed testability seam); we supply a fake listener that
 * captures the {@code AskUserQuestionEvent} and completes its response future with a canned answer. This proves the
 * event carries the parsed questions and that handle() returns the listener's answer — with NO user prompted and no
 * real block.
 */
class HelpPluginUserInputAuditTest {

    private final AbstractAiSession session = fakeSession();

    private static final class FakeSession extends AbstractAiSession {

        FakeSession(AiSession s) {
            super(s);
        }

        @Override
        public String getId() {
            return getAiSession().id();
        }

        @Override
        public AiProcessEventListener getAiProcessEventListener() {
            return null;
        }

        @Override
        public Map<McpToolEnum, McpToolInterface> getMcpToolHandlers() {
            return Map.of();
        }
    }

    private static AbstractAiSession fakeSession() {
        return new FakeSession(AiSession.create(null, AiTypeEnum.CLAUDE));
    }

    private static ToolRequestArguments args(JsonObject o) {
        return new ToolRequestArguments(o);
    }

    private static JsonObject empty() {
        return new JsonObject();
    }

    // ---- GetTypeHierarchyTool ----
    @Test
    void typeHierarchyRequiresClassName() {
        GetTypeHierarchyTool tool = new GetTypeHierarchyTool();
        McpArgumentException noParam = assertThrows(McpArgumentException.class,
                () -> tool.handle(args(empty()), session));
        assertTrue(noParam.getMessage().contains(GetTypeHierarchyParamEnum.CLASS_NAME.key()), noParam.getMessage());
        assertEquals(-32602, noParam.getCode());

        JsonObject blank = new JsonObject();
        blank.addProperty(GetTypeHierarchyParamEnum.CLASS_NAME.key(), "  ");
        assertThrows(McpArgumentException.class, () -> tool.handle(args(blank), session));
    }

    @Test
    void typeHierarchyWithClassNameDegradesGracefullyWithoutProjectIndex() throws Exception {
        GetTypeHierarchyTool tool = new GetTypeHierarchyTool();
        JsonObject o = new JsonObject();
        o.addProperty(GetTypeHierarchyParamEnum.CLASS_NAME.key(), "java.lang.String");
        String result = tool.handle(args(o), session);
        // The className IS honoured (passed validation and was forwarded to the
        // provider). Headless there is no project index, so the provider returns a
        // graceful "source not found" marker; proving that deterministic degradation
        // is the strongest headless proof available. The content-difference proof
        // (real hierarchy vs members) needs the live index and is untestable headless.
        assertNotNull(result);
        assertFalse(result.contains("required"), "valid className must not be rejected: " + result);
        assertTrue(result.toLowerCase().contains("not found") || result.toLowerCase().contains("no source"),
                "expected graceful no-index degradation, got: " + result);
    }

    // ---- GetClassMembersTool ----
    @Test
    void classMembersRequiresClassName() {
        GetClassMembersTool tool = new GetClassMembersTool();
        McpArgumentException noParam = assertThrows(McpArgumentException.class,
                () -> tool.handle(args(empty()), session));
        assertTrue(noParam.getMessage().contains(GetClassMembersParamEnum.CLASS_NAME.key()), noParam.getMessage());
        assertEquals(-32602, noParam.getCode());

        JsonObject blank = new JsonObject();
        blank.addProperty(GetClassMembersParamEnum.CLASS_NAME.key(), "");
        assertThrows(McpArgumentException.class, () -> tool.handle(args(blank), session));
    }

    @Test
    void classMembersWithClassNameDegradesGracefullyWithoutProjectIndex() throws Exception {
        GetClassMembersTool tool = new GetClassMembersTool();
        JsonObject o = new JsonObject();
        o.addProperty(GetClassMembersParamEnum.CLASS_NAME.key(), "java.lang.String");
        String result = tool.handle(args(o), session);
        assertNotNull(result);
        assertFalse(result.contains("required"), "valid className must not be rejected: " + result);
        assertTrue(result.toLowerCase().contains("not found") || result.toLowerCase().contains("no source"),
                "expected graceful no-index degradation, got: " + result);
    }

    // ---- GetJavadocTool ----
    @Test
    void javadocRequiresClassName() {
        GetJavadocTool tool = new GetJavadocTool();
        McpArgumentException noParam = assertThrows(McpArgumentException.class,
                () -> tool.handle(args(empty()), session));
        assertTrue(noParam.getMessage().contains(GetJavadocParamEnum.CLASS_NAME.key()), noParam.getMessage());
        assertEquals(-32602, noParam.getCode());

        JsonObject blank = new JsonObject();
        blank.addProperty(GetJavadocParamEnum.CLASS_NAME.key(), "  ");
        assertThrows(McpArgumentException.class, () -> tool.handle(args(blank), session));
    }

    @Test
    void javadocAcceptsOptionalMemberNameWithoutError() throws Exception {
        GetJavadocTool tool = new GetJavadocTool();
        JsonObject o = new JsonObject();
        o.addProperty(GetJavadocParamEnum.CLASS_NAME.key(), "java.lang.String");
        // memberName is read and forwarded to the provider; its output-differentiating
        // effect can only be shown with the live project index (untestable headless).
        // Here we prove the optional parameter is accepted (no rejection) AND that the
        // tool degrades gracefully when no source file exists headless.
        o.addProperty(GetJavadocParamEnum.MEMBER_NAME.key(), "valueOf");
        String result = tool.handle(args(o), session);
        assertNotNull(result);
        assertFalse(result.contains("required"), "valid className must not be rejected: " + result);
        assertTrue(result.toLowerCase().contains("no java source") || result.toLowerCase().contains("not found"),
                "expected graceful no-source degradation, got: " + result);
    }

    // ---- GetProjectStructureTool (no parameters) ----
    @Test
    void projectStructureRuns() throws Exception {
        GetProjectStructureTool tool = new GetProjectStructureTool();
        // No parameters to honour: this tool depends on OpenProjects.getDefault().
        // Headless it returns either the real open-project tree or a graceful
        // "No projects open" marker — either way a non-blank String that does not
        // crash. There are no parameters whose effect can be proven.
        String result = tool.handle(args(empty()), session);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    // ---- GetPluginVersionTool (no parameters) ----
    @Test
    void pluginVersionEqualsInstallerVersion() throws Exception {
        GetPluginVersionTool tool = new GetPluginVersionTool();
        String version = tool.handle(args(empty()), session);
        assertNotNull(version);
        assertFalse(version.isBlank());
        assertEquals(Installer.VERSION, version);
    }

    // ---- AskUserQuestionTool ----
    @Test
    void askUserQuestionRequiresQuestions() {
        Supplier<AiProcessEventListener> neverUsed = () -> e -> {
            throw new AssertionError("listener must not fire when questions is missing");
        };
        AskUserQuestionTool tool = new AskUserQuestionTool(neverUsed);
        McpArgumentException noParam = assertThrows(McpArgumentException.class,
                () -> tool.handle(args(empty()), session));
        assertTrue(noParam.getMessage().contains("questions"), noParam.getMessage());
        assertEquals(-32602, noParam.getCode());

        JsonObject emptyArray = new JsonObject();
        emptyArray.add(AskUserQuestionParamEnum.QUESTIONS.key(), new JsonArray());
        assertThrows(McpArgumentException.class, () -> tool.handle(args(emptyArray), session));
    }

    @Test
    void askUserQuestionPublishesParsedQuestionsAndReturnsListenerAnswer() throws Exception {
        JsonArray questions = new JsonArray();
        JsonObject q = new JsonObject();
        q.addProperty(AskUserQuestionParamEnum.QUESTION.key(), "Continue?");
        q.addProperty(AskUserQuestionParamEnum.HEADER.key(), "go?");
        JsonArray opts = new JsonArray();
        JsonObject opt = new JsonObject();
        opt.addProperty(AskUserQuestionParamEnum.LABEL.key(), "Yes");
        opt.addProperty(AskUserQuestionParamEnum.DESCRIPTION.key(), "proceed");
        opts.add(opt);
        q.add(AskUserQuestionParamEnum.OPTIONS.key(), opts);
        q.addProperty(AskUserQuestionParamEnum.MULTI_SELECT.key(), false);
        questions.add(q);

        AtomicReference<AskUserQuestionEvent> captured = new AtomicReference<>();
        // Supplying a fake listener (constructor-injected seam) is NOT prompting the
        // user and NOT faking a security gate: it completes the event's response
        // future exactly as the real QuestionPanel listener does, so handle() returns
        // that answer immediately and never blocks.
        Supplier<AiProcessEventListener> fake = () -> event -> {
            AskUserQuestionEvent aq = (AskUserQuestionEvent) event;
            captured.set(aq);
            aq.response().complete("Yes");
        };

        AskUserQuestionTool tool = new AskUserQuestionTool(fake);
        JsonObject raw = new JsonObject();
        raw.add(AskUserQuestionParamEnum.QUESTIONS.key(), questions);

        String result = tool.handle(args(raw), session);

        assertEquals("Yes", result);
        AskUserQuestionEvent fired = captured.get();
        assertNotNull(fired, "the event must have been published to the listener");
        assertSame(questions, fired.questions(), "the published event must carry the exact parsed questions");
    }

    @Test
    void askUserQuestionForwardsItemWithoutQuestionKey() throws Exception {
        // A questions item without the required "question" key is accepted by tool
        // handle() (no per-item validation there); the item is forwarded verbatim to
        // the listener. Verify the item is passed through as-is rather than dropped.
        JsonArray questions = new JsonArray();
        JsonObject q = new JsonObject();
        JsonArray opts = new JsonArray();
        opts.add(new JsonObject());
        q.add(AskUserQuestionParamEnum.OPTIONS.key(), opts);
        questions.add(q);

        AtomicReference<AskUserQuestionEvent> captured = new AtomicReference<>();
        Supplier<AiProcessEventListener> fake = () -> event -> {
            AskUserQuestionEvent aq = (AskUserQuestionEvent) event;
            captured.set(aq);
            aq.response().complete("done");
        };
        AskUserQuestionTool tool = new AskUserQuestionTool(fake);
        JsonObject raw = new JsonObject();
        raw.add(AskUserQuestionParamEnum.QUESTIONS.key(), questions);

        String result = tool.handle(args(raw), session);
        assertEquals("done", result);
        assertNotNull(captured.get());
        assertSame(questions, captured.get().questions());
    }

    @Test
    void askUserQuestionSchemaDeclaresContract() {
        AskUserQuestionTool tool = new AskUserQuestionTool(() -> null);
        JsonObject schema = tool.schema(Set.of());
        JsonObject input = schema.getAsJsonObject(ToolSchemaKeyEnum.INPUT_SCHEMA.key());
        assertNotNull(input);
        JsonObject props = input.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(props.has(AskUserQuestionParamEnum.QUESTIONS.key()), "questions must be declared");

        JsonArray requiredTop = input.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertTrue(requiredTop.contains(new JsonPrimitive(AskUserQuestionParamEnum.QUESTIONS.key())),
                "questions must be required at the top level");

        JsonObject questionsProp = props.getAsJsonObject(AskUserQuestionParamEnum.QUESTIONS.key());
        assertTrue(questionsProp.has(ToolSchemaKeyEnum.ITEMS.key()), "questions must be an array of objects");
        JsonObject item = questionsProp.getAsJsonObject(ToolSchemaKeyEnum.ITEMS.key());
        JsonObject itemProps = item.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertNotNull(itemProps);
        assertTrue(itemProps.has(AskUserQuestionParamEnum.QUESTION.key()), "question key must be declared per item");
        assertTrue(itemProps.has(AskUserQuestionParamEnum.OPTIONS.key()), "options must be declared per item");
        assertTrue(itemProps.has(AskUserQuestionParamEnum.HEADER.key()), "optional header must be declared");
        assertTrue(itemProps.has(AskUserQuestionParamEnum.MULTI_SELECT.key()), "optional multiSelect must be declared");

        JsonArray itemRequired = item.getAsJsonArray(ToolSchemaKeyEnum.REQUIRED.key());
        assertTrue(itemRequired.contains(new JsonPrimitive(AskUserQuestionParamEnum.QUESTION.key())),
                "question must be required per item");
        assertTrue(itemRequired.contains(new JsonPrimitive(AskUserQuestionParamEnum.OPTIONS.key())),
                "options must be required per item");

        JsonObject options = itemProps.getAsJsonObject(AskUserQuestionParamEnum.OPTIONS.key());
        JsonObject optionItem = options.getAsJsonObject(ToolSchemaKeyEnum.ITEMS.key());
        JsonObject optionProps = optionItem.getAsJsonObject(ToolSchemaKeyEnum.PROPERTIES.key());
        assertTrue(optionProps.has(AskUserQuestionParamEnum.LABEL.key()), "option label must be declared");
        assertTrue(optionProps.has(AskUserQuestionParamEnum.DESCRIPTION.key()), "option description must be declared");
    }

    // ---- all seven tools are non-mutating (read-only) ----
    @Test
    void allAuditedToolsAreNonMutating() {
        assertFalse(new GetTypeHierarchyTool().isMutating());
        assertFalse(new GetClassMembersTool().isMutating());
        assertFalse(new GetJavadocTool().isMutating());
        assertFalse(new GetProjectStructureTool().isMutating());
        assertFalse(new GetPluginVersionTool().isMutating());
        assertFalse(new AskUserQuestionTool(() -> null).isMutating());
        assertFalse(new GetInstructionsTool().isMutating());
    }
}
