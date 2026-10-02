package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;

final class GitReadFilePaths {

    private GitReadFilePaths() {
    }

    static List<String> optional(ToolRequestArguments args, String key) throws McpArgumentException {
        String error = args.requireStringArrayIfPresent(key);
        if (error != null) {
            throw new McpArgumentException(-32602, error);
        }
        if (!args.has(key)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonElement element : args.array(key)) {
            paths.add(element.getAsString());
        }
        return paths;
    }
}
