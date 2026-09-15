package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolSchemaKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestMavenProvider.MavenBuildOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOptionValidator;

/**
 * Shared schema-building and argument-extraction for the three Maven devops tools (BuildMavenProjectTool,
 * CleanAndBuildMavenProjectTool, RunMavenTestsTool — #5 / F2). All three expose the identical option set under the
 * identical {@link McpToolPropertyEnum} wire keys (each tool's own {@code *ParamEnum} just curates that same set for
 * its schema), so the property-building and arg-extraction logic is genuinely shared rather than duplicated three times
 * with drift risk. Only each tool's OWN default for {@code goals}/{@code skipTests} differs, which is why those two are
 * still parameters here rather than baked in.
 */
public final class MavenToolSchema {

    public static void addProperties(JsonObject props, String goalsDefaultDescription, boolean skipTestsDefault) {
        addStringArray(props, McpToolPropertyEnum.GOALS.key(),
                       "Maven goals to run, each array entry exactly one goal — never put more than one goal in a single "
                       + "entry (e.g. [\"clean\", \"install\"], not [\"clean install\"]; also \"package\", \"verify\"). "
                       + "Replaces the default entirely when given — it is not merged with it. Default: " + goalsDefaultDescription + ".");
        addStringArray(props, McpToolPropertyEnum.PROJECT_LIST.key(),
                       "Maven -pl: module subset to build, each entry a module path or Maven coordinate (e.g. \":my-module\").");
        addBoolean(props, McpToolPropertyEnum.ALSO_MAKE.key(),
                   "Maven -am: also build the dependencies of the modules named in " + McpToolPropertyEnum.PROJECT_LIST.key() + ". Default: false.");
        addString(props, McpToolPropertyEnum.RESUME_FROM.key(),
                  "Maven -rf: resume a reactor build from this module (e.g. \":my-module\").");
        addBoolean(props, McpToolPropertyEnum.SKIP_TESTS.key(),
                   "Maven -DskipTests. Default: " + skipTestsDefault + ".");
        addBoolean(props, McpToolPropertyEnum.OFFLINE.key(),
                   "Maven -o: resolve only from the local repository, without contacting remote repositories. Default: false.");
        addBoolean(props, McpToolPropertyEnum.UPDATE_SNAPSHOTS.key(),
                   "Maven -U: force a check for updated snapshots/releases. Default: false.");
        addStringArray(props, McpToolPropertyEnum.PROFILES.key(), "Maven -P: profiles to activate.");
        addObject(props, McpToolPropertyEnum.PROPERTIES.key(),
                  "Maven -Dk=v system/project properties, as a key/value map (never a raw string).");
        addString(props, McpToolPropertyEnum.THREADS.key(), "Maven -T: thread/module parallelism (e.g. \"4\" or \"1C\").");
        addBoolean(props, McpToolPropertyEnum.FAIL_AT_END.key(),
                   "Maven -fae: attempt every module before failing, reporting all failures at the end. Default: false.");
    }

    public static MavenBuildOptions optionsFrom(ToolRequestArguments args, List<String> goals, boolean skipTests) {
        return new MavenBuildOptions(
                goals,
                BuildOptionValidator.toStringList(args.array(McpToolPropertyEnum.PROJECT_LIST.key())),
                args.bool(McpToolPropertyEnum.ALSO_MAKE.key()),
                args.str(McpToolPropertyEnum.RESUME_FROM.key()),
                skipTests,
                args.bool(McpToolPropertyEnum.OFFLINE.key()),
                args.bool(McpToolPropertyEnum.UPDATE_SNAPSHOTS.key()),
                BuildOptionValidator.toStringList(args.array(McpToolPropertyEnum.PROFILES.key())),
                args.object(McpToolPropertyEnum.PROPERTIES.key()),
                args.str(McpToolPropertyEnum.THREADS.key()),
                args.bool(McpToolPropertyEnum.FAIL_AT_END.key()));
    }

    static void addStringArray(JsonObject props, String key, String description) {
        JsonObject p = new JsonObject();
        p.addProperty(ToolSchemaKeyEnum.TYPE.key(), "array");
        JsonObject items = new JsonObject();
        items.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        p.add(ToolSchemaKeyEnum.ITEMS.key(), items);
        p.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        props.add(key, p);
    }

    static void addBoolean(JsonObject props, String key, String description) {
        JsonObject p = new JsonObject();
        p.addProperty(ToolSchemaKeyEnum.TYPE.key(), "boolean");
        p.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        props.add(key, p);
    }

    static void addString(JsonObject props, String key, String description) {
        JsonObject p = new JsonObject();
        p.addProperty(ToolSchemaKeyEnum.TYPE.key(), "string");
        p.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        props.add(key, p);
    }

    static void addObject(JsonObject props, String key, String description) {
        JsonObject p = new JsonObject();
        p.addProperty(ToolSchemaKeyEnum.TYPE.key(), "object");
        p.addProperty(ToolSchemaKeyEnum.DESCRIPTION.key(), description);
        props.add(key, p);
    }

    private MavenToolSchema() {
    }
}
