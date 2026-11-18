package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.sdk;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;

/**
 * Runs one short-lived Copilot CLI JSON-RPC exchange without either SDK.
 */
public final class CopilotRawRpc {

    /**
     * What a successful ping without a protocol version means: a CLI from before the field existed.
     */
    private static final int DEFAULT_PROTOCOL = 2;
    private static final Gson GSON = new Gson();

    private CopilotRawRpc() {
    }

    /**
     * The protocol version the CLI reports for {@code ping}. The probe gets as long as a session start, because a cold
     * CLI start is the slow part. A ping that fails, or that reports a protocol that is not a whole number, throws.
     */
    public static int probeProtocol(String executablePath) {
        try {
            return call(executablePath, null, null, TimeoutEnum.MCP_REGISTRATION_WAIT_MILLIS.millis()).protocolVersion();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Copilot protocol probe failed", exception);
        }
    }

    public static Response call(String executablePath, String method, JsonObject params,
                                long timeoutMillis) throws IOException {
        // stderr is never read, so it is discarded: an unread pipe would stall a CLI that logs a lot.
        Process process = new ProcessBuilder(serverCommand(executablePath))
                .redirectError(Redirect.DISCARD).start();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMillis);
                killProcessTree(process);
            }
            catch (InterruptedException ignored) {
            }
        }, "copilot-rpc-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            OutputStream output = process.getOutputStream();
            writeFramed(output, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}");
            if (method != null) {
                String parameterText = params == null ? "{}" : GSON.toJson(params);
                writeFramed(output, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\""
                            + method + "\",\"params\":" + parameterText + "}");
            }
            output.flush();
            int protocol = DEFAULT_PROTOCOL;
            JsonObject result = null;
            String body;
            while ((body = readFramed(process.getInputStream())) != null) {
                JsonObject response = GSON.fromJson(body, JsonObject.class);
                if (response == null || !response.has("id") || !response.get("id").isJsonPrimitive()
                        || !response.getAsJsonPrimitive("id").isNumber()) {
                    continue;
                }
                int id = response.get("id").getAsInt();
                if (id == 1) {
                    try {
                        protocol = protocol(response);
                        CopilotSdkSelector.recordProtocol(executablePath, protocol);
                    }
                    catch (IOException pingFailure) {
                        // A probe has nothing else to report, so it fails. A call that only wants its own method's
                        // answer goes on to read it; the protocol it reports is then unknown and is not cached.
                        if (method == null) {
                            throw pingFailure;
                        }
                    }
                }
                else if (id == 2) {
                    result = response.has("result") && response.get("result").isJsonObject()
                             ? response.getAsJsonObject("result") : null;
                    return new Response(protocol, result);
                }
                if (method == null && id == 1) {
                    return new Response(protocol, null);
                }
            }
            throw new IOException("RPC stream closed before response");
        }
        finally {
            watchdog.interrupt();
            killProcessTree(process);
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
        }
    }

    /**
     * Kills the process and everything it started. On Windows the CLI runs as a child of {@code cmd /c}, so killing
     * only the shell leaves the real CLI running and the read waiting for an end of stream that never comes. The
     * children are listed first, because a dead parent no longer has any.
     */
    static void killProcessTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        }
        catch (RuntimeException unsupported) {
            // the process itself is still killed below
        }
        process.destroyForcibly();
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        }
        catch (IOException | RuntimeException ignored) {
            // nothing more to do for a process that is already dead
        }
    }

    /**
     * The protocol a {@code ping} response reports. A successful result without a {@code protocolVersion} is an old CLI
     * and means protocol 2. A response that is an error, has no result, or reports a version that is not a whole number
     * is a failed probe and throws.
     */
    static int protocol(JsonObject ping) throws IOException {
        if (ping == null || !ping.has("result") || !ping.get("result").isJsonObject()) {
            throw new IOException("Copilot ping failed: " + pingFailureText(ping));
        }
        JsonObject result = ping.getAsJsonObject("result");
        JsonElement version = result.get("protocolVersion");
        if (version == null || version.isJsonNull()) {
            return DEFAULT_PROTOCOL;
        }
        if (version.isJsonPrimitive()) {
            JsonPrimitive primitive = version.getAsJsonPrimitive();
            try {
                BigDecimal number = primitive.isNumber() ? primitive.getAsBigDecimal()
                                    : new BigDecimal(primitive.getAsString().trim());
                return number.intValueExact();
            }
            catch (NumberFormatException | ArithmeticException notAWholeNumber) {
                // reported below
            }
        }
        throw new IOException("Copilot ping reported a protocolVersion that is not a whole number: " + version);
    }

    private static String pingFailureText(JsonObject ping) {
        if (ping != null && ping.has("error") && ping.get("error").isJsonObject()) {
            JsonObject error = ping.getAsJsonObject("error");
            if (error.has("message") && error.get("message").isJsonPrimitive()) {
                return error.get("message").getAsString();
            }
            return error.toString();
        }
        return "no result";
    }

    static List<String> serverCommand(String executablePath) {
        String path = executablePath == null || executablePath.isBlank() ? "copilot" : executablePath;
        List<String> command = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (path.endsWith(".js")) {
            command.add("node");
            command.add(path);
        }
        else if (os.contains("win")) {
            command.add("cmd");
            command.add("/c");
            command.add(path);
        }
        else {
            command.add(path);
        }
        command.add("--server");
        command.add("--no-auto-update");
        command.add("--log-level");
        command.add("error");
        command.add("--stdio");
        return command;
    }

    static void writeFramed(OutputStream output, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(body);
    }

    static String readFramed(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            header.write(value);
            byte[] bytes = header.toByteArray();
            int length = bytes.length;
            if (length >= 4 && bytes[length - 4] == '\r' && bytes[length - 3] == '\n'
                    && bytes[length - 2] == '\r' && bytes[length - 1] == '\n') {
                break;
            }
        }
        if (header.size() == 0) {
            return null;
        }
        int contentLength = -1;
        for (String line : header.toString(StandardCharsets.UTF_8).split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        if (contentLength < 0) {
            return null;
        }
        byte[] body = input.readNBytes(contentLength);
        return body.length == contentLength ? new String(body, StandardCharsets.UTF_8) : null;
    }

    public record Response(int protocolVersion, JsonObject result) {

    }
}
