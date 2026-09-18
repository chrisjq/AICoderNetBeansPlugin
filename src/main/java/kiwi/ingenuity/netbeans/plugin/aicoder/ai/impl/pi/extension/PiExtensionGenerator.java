package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.extension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiTimeoutEnum;

/**
 * Renders {@code aicoder-pi-extension.ts.template} into one session's extension file, replacing a single placeholder
 * with a generated {@code const AICODER_CONFIG = <json>;} block written with Gson so every value is safely escaped. See
 * the spec's *Extension file generation and lifetime*. No {@code secretKey} is ever written here; credentials travel as
 * MCP tool arguments from the per-turn identity block, exactly as for the other backends.
 */
public final class PiExtensionGenerator {

    private static final Logger LOG = Logger.getLogger(PiExtensionGenerator.class.getName());
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String TEMPLATE_RESOURCE
            = "/kiwi/ingenuity/netbeans/plugin/aicoder/ai/impl/pi/aicoder-pi-extension.ts.template";

    /**
     * Must appear in the template exactly once; replaced with the generated {@code const AICODER_CONFIG = ...;}
     * statement.
     */
    static final String CONFIG_PLACEHOLDER = "/*__AICODER_CONFIG__*/";

    /**
     * Generates and atomically writes this session's extension file, returning its path.
     *
     * @param sessionId the plugin session UUID (the id {@code McpHookServer} resolves through {@code SessionRegistry})
     * @param mcpUrl {@code McpServerRegistry.endpointUrlFor(AiTypeEnum.PI)} — must already be non-null (the shared
     * server must be running)
     * @param hookUrl the hook server base URL plus {@code "/"}
     */
    public static Path generate(String sessionId, String mcpUrl, String hookUrl) throws IOException {
        String template = loadTemplate();
        String configJson = GSON.toJson(buildConfig(sessionId, mcpUrl, hookUrl));
        String rendered = replacePlaceholderExactlyOnce(template, "const AICODER_CONFIG = " + configJson + ";");
        Path target = PiExtensionFiles.pathFor(sessionId);
        writeAtomically(target, rendered);
        return target;
    }

    /**
     * Package-private so {@code PiExtensionGeneratorTest} can assert on the exact field set/escaping without writing a
     * file.
     */
    static JsonObject buildConfig(String sessionId, String mcpUrl, String hookUrl) {
        JsonObject config = new JsonObject();
        config.addProperty("pluginVersion", Installer.VERSION);
        config.addProperty("sessionId", sessionId);
        config.addProperty("mcpUrl", mcpUrl);
        config.addProperty("hookUrl", hookUrl);
        JsonArray gatedTools = new JsonArray();
        gatedTools.add("edit");
        gatedTools.add("write");
        config.add("gatedTools", gatedTools);
        JsonArray excludedTools = new JsonArray();
        PiToolExposurePolicy.excludedTools().forEach(excludedTools::add);
        config.add("excludedTools", excludedTools);
        config.addProperty("toolCallTimeoutMs", PiTimeoutEnum.MCP_TOOL_TIMEOUT_MILLIS.millis());
        config.addProperty("handshakeTimeoutMs", PiTimeoutEnum.MCP_HANDSHAKE_TIMEOUT_MILLIS.millis());
        return config;
    }

    private static String loadTemplate() throws IOException {
        try (InputStream is = PiExtensionGenerator.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (is == null) {
                throw new IOException("pi extension template resource not found: " + TEMPLATE_RESOURCE);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String replacePlaceholderExactlyOnce(String template, String replacement) throws IOException {
        int first = template.indexOf(CONFIG_PLACEHOLDER);
        if (first < 0) {
            throw new IOException("pi extension template is missing the config placeholder " + CONFIG_PLACEHOLDER);
        }
        int second = template.indexOf(CONFIG_PLACEHOLDER, first + CONFIG_PLACEHOLDER.length());
        if (second >= 0) {
            throw new IOException("pi extension template has more than one config placeholder " + CONFIG_PLACEHOLDER);
        }
        return template.substring(0, first) + replacement + template.substring(first + CONFIG_PLACEHOLDER.length());
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            trySetOwnerOnlyPermissions(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static void trySetOwnerOnlyPermissions(Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, perms);
            }
        }
        catch (IOException e) {
            LOG.log(Level.FINE, "Could not restrict pi extension file permissions", e);
        }
    }

    private PiExtensionGenerator() {
    }
}
