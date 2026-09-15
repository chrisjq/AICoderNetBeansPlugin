package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.devops.build;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildAndTestAntProvider.AntBuildOptions;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.BuildOptionValidator;

/**
 * Shared schema-building and argument-extraction for the three Ant devops tools (BuildAntProjectTool,
 * CleanAndBuildAntProjectTool, RunAntTestsTool — #5 / F2) — see {@link MavenToolSchema}'s javadoc for why this is
 * shared rather than duplicated three times.
 */
public final class AntToolSchema {

    public static void addProperties(JsonObject props, String targetsDefaultDescription) {
        MavenToolSchema.addStringArray(props, McpToolPropertyEnum.TARGETS.key(),
                                       "Ant targets to run (e.g. \"jar\", \"dist\"), each array entry one target. "
                                       + "Replaces the default entirely when given — it is not merged with it. Default: " + targetsDefaultDescription + ".");
        MavenToolSchema.addObject(props, McpToolPropertyEnum.PROPERTIES.key(),
                                  "Ant -Dk=v properties, as a key/value map (never a raw string).");
        MavenToolSchema.addBoolean(props, McpToolPropertyEnum.KEEP_GOING.key(),
                                   "Ant -k: keep running other independent targets after one fails. Default: false.");
    }

    public static AntBuildOptions optionsFrom(ToolRequestArguments args, List<String> targets) {
        return new AntBuildOptions(
                targets,
                args.object(McpToolPropertyEnum.PROPERTIES.key()),
                args.bool(McpToolPropertyEnum.KEEP_GOING.key()));
    }

    public static List<String> targetsOrDefault(ToolRequestArguments args, String targetsKey, List<String> defaultTargets) {
        return args.has(targetsKey) ? BuildOptionValidator.toStringList(args.array(targetsKey)) : defaultTargets;
    }

    private AntToolSchema() {
    }
}
