package kiwi.ingenuity.netbeans.plugin.aicoder.ai.http;

/**
 * One image attached to a chat message, base64 encoded, with the size it is shown at (used for the token estimate).
 *
 * @param mimeType the media type, for example {@code image/png}
 * @param base64 the image bytes, base64 encoded
 * @param width the width in pixels; 0 or less when unknown
 * @param height the height in pixels; 0 or less when unknown
 */
public record ChatImage(String mimeType, String base64, int width, int height) {

    public ChatImage {
        mimeType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType;
        base64 = base64 == null ? "" : base64;
    }

    /**
     * The image as a {@code data:} URI, the form an {@code image_url} content part carries.
     */
    public String dataUri() {
        return "data:" + mimeType + ";base64," + base64;
    }
}
