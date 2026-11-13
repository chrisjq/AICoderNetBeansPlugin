package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatImage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DataUriEliderTest {

    @Test
    @Timeout(5)
    void aDataUriBecomesItsTypeAndDecodedSize() {
        // "QUJDRA==" decodes to the four bytes "ABCD".
        String elided = DataUriElider.elide("{\"url\":\"data:image/png;base64,QUJDRA==\"}");

        assertEquals("{\"url\":\"[image image/png 4 bytes]\"}", elided);
    }

    @Test
    @Timeout(5)
    void textWithoutADataUriIsReturnedUnchanged() {
        String text = "{\"content\":\"no images here\"}";

        assertSame(text, DataUriElider.elide(text));
    }

    @Test
    @Timeout(5)
    void aSerialisedRequestKeepsNoBase64() {
        String base64 = "A".repeat(40_000);
        ChatMessage message = new ChatMessage(ChatRole.USER, "Image from Read (call c1):", List.of(), null,
                                              List.of(new ChatImage("image/jpeg", base64 + "==", 100, 100)));
        ChatRequestSpec spec = new ChatRequestSpec("http://localhost:1/v1", "m", List.of(message), null, null, null,
                                                   null, null);
        JsonObject payload = RequestBuilder.build(spec, new CompatibilityFlags());

        String elided = DataUriElider.elide(payload.toString());

        assertFalse(elided.contains(base64), "the base64 must not reach the log");
        assertTrue(elided.contains("[image image/jpeg "), elided);
        assertTrue(elided.contains("Image from Read (call c1):"), elided);
        assertTrue(elided.length() < 1_000, "the log line is short: " + elided.length());
    }

    @Test
    @Timeout(5)
    void everyUriInTheTextIsElided() {
        String elided = DataUriElider.elide("data:image/png;base64,QUJD and data:image/gif;base64,REVGRw==");

        assertEquals("[image image/png 3 bytes] and [image image/gif 4 bytes]", elided);
    }
}
