package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.userinput;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AskUserQuestionEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessEventListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolSchemas;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import org.openide.util.Exceptions;

public class AskUserQuestionTool implements McpToolInterface {

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private final Supplier<AiProcessEventListener> listenerSupplier;

    public AskUserQuestionTool(Supplier<AiProcessEventListener> listenerSupplier) {
        this.listenerSupplier = listenerSupplier;
    }

    @Override
    public McpSectionEnum section() {
        return McpSectionEnum.USER_INPUT;
    }

    @Override
    public String instruction(Set<McpInstructionOptionEnum> options) {
        if (!options.contains(McpInstructionOptionEnum.TOOL_INSTRUCTION)) {
            return null;
        }
        if (options.contains(McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS)) {
            return "AskUserQuestion - present structured choices when you need a decision from the user";
        }
        return "AskUserQuestion -> INSTEAD OF free-text questions in chat - present structured choices when you need a decision from the user";
    }

    @Override
    public JsonObject schema(Set<McpInstructionOptionEnum> options) {
        JsonObject tool = new JsonObject();
        tool.addProperty(ToolSchemaKeyEnum.NAME.key(), McpToolEnum.ASK_USER_QUESTION.toolName());
        tool.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(),
                "Ask the user one or more questions with selectable options. "
                + "Use this whenever you need clarification or a decision from the user.");

        JsonObject schema = new JsonObject();
        schema.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject props = new JsonObject();

        JsonObject questionsArr = new JsonObject();
        questionsArr.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject item = new JsonObject();
        item.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject itemProps = new JsonObject();

        JsonObject qProp = new JsonObject();
        qProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        qProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "The question to ask");
        itemProps.add(AskUserQuestionParamEnum.QUESTION.key(), qProp);

        JsonObject hProp = new JsonObject();
        hProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        hProp.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), "Short chip label (max 12 chars)");
        itemProps.add(AskUserQuestionParamEnum.HEADER.key(), hProp);

        JsonObject optArr = new JsonObject();
        optArr.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject optItem = new JsonObject();
        optItem.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        JsonObject optProps = new JsonObject();
        JsonObject lProp = new JsonObject();
        lProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        optProps.add(AskUserQuestionParamEnum.LABEL.key(), lProp);
        JsonObject dProp = new JsonObject();
        dProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        optProps.add(AskUserQuestionParamEnum.DESCRIPTION.key(), dProp);
        optItem.add(ToolSchemaKeyEnum.PROPERTIES.key(), optProps);
        optArr.add(ToolSchemaKeyEnum.ITEMS.key(), optItem);
        itemProps.add(AskUserQuestionParamEnum.OPTIONS.key(), optArr);

        JsonObject msProp = new JsonObject();
        msProp.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        msProp.addProperty(ToolSchemaKeyEnum.DEFAULT.key(), false);
        itemProps.add(AskUserQuestionParamEnum.MULTI_SELECT.key(), msProp);

        item.add(ToolSchemaKeyEnum.PROPERTIES.key(), itemProps);
        JsonArray itemRequired = new JsonArray();
        itemRequired.add(AskUserQuestionParamEnum.QUESTION.key());
        itemRequired.add(AskUserQuestionParamEnum.OPTIONS.key());
        item.add(ToolSchemaKeyEnum.REQUIRED.key(), itemRequired);
        questionsArr.add(ToolSchemaKeyEnum.ITEMS.key(), item);
        props.add(AskUserQuestionParamEnum.QUESTIONS.key(), questionsArr);

        schema.add(ToolSchemaKeyEnum.PROPERTIES.key(), props);
        JsonArray required = new JsonArray();
        required.add(AskUserQuestionParamEnum.QUESTIONS.key());
        schema.add(ToolSchemaKeyEnum.REQUIRED.key(), required);
        tool.add(ToolSchemaKeyEnum.INPUT_SCHEMA.key(), schema);
        return McpToolSchemas.applyCredentialsIfRequested(tool, options);
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public String handle(ToolRequestArguments args, AbstractAiSession session) throws McpArgumentException {
        JsonArray questions = args.array(AskUserQuestionParamEnum.QUESTIONS.key());
        if (questions == null || questions.isEmpty()) {
            throw new McpArgumentException(-32602, "Missing questions parameter");
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        listenerSupplier.get().onAiProcessEvent(new AskUserQuestionEvent(questions, future));
        try {
            return future.get(TimeoutEnum.USER_QUESTION_WAIT_MILLIS.millis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            return "No response (timed out)";
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted waiting for user response";
        }
        catch (Exception e) {
            Exceptions.printStackTrace(e);
            return "Error retrieving response:\n" + stackTrace(e);
        }
    }

}
