package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An error that quotes the request back, image parts and all, is not an image refusal unless the server itself says so.
 */
class ImageRejectionTest {

    private static final String ECHOED_PART = "{\"type\":\"image_url\",\"image_url\":{\"url\":"
            + "\"data:image/png;base64,iVBORw0KGgo=\"}}";

    private static MappedError rejection(String message) {
        return new MappedError(MappedError.Kind.UNNAMED_REJECTION, message, null, null);
    }

    @Test
    @Timeout(5)
    void anUnrelatedComplaintQuotingTheImagePartIsNotARefusal() {
        String message = "invalid request body " + ECHOED_PART + ": temperature must be at most 2";

        assertFalse(ImageRejection.isImageRejection(rejection(message)), message);
    }

    @Test
    @Timeout(5)
    void anEscapedEchoWithGsonPaddingIsNotARefusalEither() {
        String message = "bad request: {\\\"type\\\":\\\"image_url\\\",\\\"image_url\\\":{\\\"url\\\":"
                + "\\\"data:image/jpeg;base64,QUJD" + "\\" + "u003d\\\"}} top_p out of range";

        assertFalse(ImageRejection.isImageRejection(rejection(message)), message);
    }

    @Test
    @Timeout(5)
    void aRealRefusalAlongsideTheEchoStillCounts() {
        assertTrue(ImageRejection.isImageRejection(rejection(ECHOED_PART + " image input is not supported")));
    }

    @Test
    @Timeout(5)
    void theServersOwnImageUrlWordingStillCounts() {
        assertTrue(ImageRejection.isImageRejection(
                rejection("Invalid content type. image_url is only supported by certain models.")));
        assertTrue(ImageRejection.isImageRejection(
                rejection("messages[1].content[1].type: 'image_url' is not supported")));
    }
}
