package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;

/**
 * Tells whether a failed request was refused because it carried images, and owns the wording the turn loop writes about
 * images, so that wording echoed back in an error is never taken for a refusal. Shared by the HTTP client (which must
 * not blind-retry such a request with the images still in it) and the turn loop (which resends without them).
 */
public final class ImageRejection {

    /**
     * Starts the text of the user message that carries tool images.
     */
    public static final String IMAGE_MESSAGE_PREFIX = "Image from ";
    public static final String IMAGE_SETTING_OFF_NOTE
            = "[The image was not shown: 'Model can view images' is Off for this session.]";
    public static final String IMAGE_NO_VISION_NOTE
            = "[The image was not shown: this model cannot view images; set 'Model can view images' to On if it can.]";

    private static final List<String> MARKERS = List.of("image", "vision", "multimodal", "multi-modal", "mmproj",
                                                        "content part");
    private static final String TOOL_PLACEHOLDER = "@@tool@@";
    /**
     * The attached note, whatever the tool name, in lower case.
     */
    private static final Pattern OWN_ATTACHED_NOTE = attachedNotePattern();
    /**
     * The count a multi-image line carries, for example {@code , 3 images:}.
     */
    private static final Pattern OWN_IMAGE_COUNT = Pattern.compile(", \\d+ images:");
    /**
     * A data URI of any type, as the request carries images, in lower case. Gson may write base64 padding as an escaped
     * code point, so that six-character form is taken with it.
     */
    private static final Pattern ECHOED_DATA_URI = Pattern.compile(
            "data:[a-z0-9.+-]+/[a-z0-9.+-]+;base64,[a-z0-9+/=]*(?:" + "\\\\" + "u003d)*");
    /**
     * The request's own field name and part type for an image as they appear in echoed JSON: {@code "image_url"} in
     * double quotes, possibly escaped. A bare or single-quoted {@code image_url} is a server's own wording (for example
     * "image_url is only supported by certain models") and is kept, since it is a real refusal.
     */
    private static final Pattern ECHOED_IMAGE_FIELD = Pattern.compile("\\\\?\"image_url\\\\?\"");

    private ImageRejection() {
    }

    /**
     * The note a tool result carries when its image is attached to the next message.
     */
    public static String attachedNote(String toolName) {
        return "[The image is attached to the next message; call " + toolName + " again to see it again.]";
    }

    /**
     * How the image message names a call that returned {@code count} images: a colon for one, the count for more.
     */
    public static String countSuffix(int count) {
        return count == 1 ? ":" : ", " + count + " images:";
    }

    /**
     * Whether any message of the request carries images.
     */
    public static boolean carriesImages(List<ChatMessage> messages) {
        if (messages == null) {
            return false;
        }
        for (ChatMessage message : messages) {
            if (message != null && message.hasImages()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a failed request was refused because it carried images: a rejection or server error whose message,
     * without this plugin's own image wording, mentions images, vision, multimodal input or content parts, or says
     * content must be a string. Only meaningful when the request did carry images.
     */
    public static boolean isImageRejection(MappedError error) {
        if (error == null) {
            return false;
        }
        MappedError.Kind kind = error.kind();
        if (kind != MappedError.Kind.UNNAMED_REJECTION && kind != MappedError.Kind.REJECTED_FIELD
                && kind != MappedError.Kind.SERVER_ERROR) {
            return false;
        }
        String text = withoutOwnImageText(error.message() == null ? "" : error.message().toLowerCase(Locale.ROOT));
        for (String marker : MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return text.contains("content") && text.contains("string");
    }

    /**
     * {@code lowerCaseMessage} without the request's own image parts (data URIs of any type, the {@code image_url}
     * field and part type) and the text the turn loop itself writes about images (the image message's prefix and count,
     * and the result notes). A server that echoes the request in its error is then not taken to be refusing images.
     */
    static String withoutOwnImageText(String lowerCaseMessage) {
        // An error that quotes the request back would otherwise match on the request's own image parts.
        String text = ECHOED_DATA_URI.matcher(lowerCaseMessage).replaceAll(" ");
        text = ECHOED_IMAGE_FIELD.matcher(text).replaceAll(" ");
        text = OWN_ATTACHED_NOTE.matcher(text).replaceAll(" ");
        text = OWN_IMAGE_COUNT.matcher(text).replaceAll(" ");
        text = text.replace(IMAGE_SETTING_OFF_NOTE.toLowerCase(Locale.ROOT), " ")
                .replace(IMAGE_NO_VISION_NOTE.toLowerCase(Locale.ROOT), " ");
        return text.replace(IMAGE_MESSAGE_PREFIX.toLowerCase(Locale.ROOT), " ");
    }

    private static Pattern attachedNotePattern() {
        String note = attachedNote(TOOL_PLACEHOLDER).toLowerCase(Locale.ROOT);
        int at = note.indexOf(TOOL_PLACEHOLDER);
        return Pattern.compile(Pattern.quote(note.substring(0, at)) + ".*?"
                + Pattern.quote(note.substring(at + TOOL_PLACEHOLDER.length())));
    }
}
