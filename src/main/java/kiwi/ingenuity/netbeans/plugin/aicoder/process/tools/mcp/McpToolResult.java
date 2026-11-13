package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp;

import java.util.List;

/**
 * What a tool returns: the text the model reads, plus any images that go with it.
 *
 * <p>
 * Almost every tool returns text only and goes through {@link McpToolInterface#handle}. A tool that has pictures to
 * show overrides {@link McpToolInterface#handleResult} instead. A result is never written to the IDE log, so the base64
 * of an image cannot reach it.
 *
 * @param text the text part, never null
 * @param images the images that follow the text, never null
 */
public record McpToolResult(String text, List<ImagePart> images) {

    /**
     * One image, base64 encoded, with the dimensions it will be shown at.
     *
     * @param mimeType the media type, for example {@code image/png}
     * @param base64 the image bytes, base64 encoded
     * @param width the width in pixels
     * @param height the height in pixels
     */
    public record ImagePart(String mimeType, String base64, int width, int height) {

    }

    public McpToolResult {
        text = text == null ? "" : text;
        images = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * A result with text and no images.
     */
    public static McpToolResult text(String text) {
        return new McpToolResult(text, List.of());
    }

    /**
     * A result with text and one image.
     */
    public static McpToolResult withImage(String text, ImagePart image) {
        return new McpToolResult(text, image == null ? List.of() : List.of(image));
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }
}
