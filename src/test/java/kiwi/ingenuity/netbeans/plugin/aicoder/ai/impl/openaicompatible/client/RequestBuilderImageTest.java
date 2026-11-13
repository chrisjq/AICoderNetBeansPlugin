package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatImage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatMessage;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.ChatRole;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A message with images is written as a content array: the text part, then one image_url part per image.
 */
class RequestBuilderImageTest {

    private static JsonObject messageOf(ChatMessage message) {
        ChatRequestSpec spec = new ChatRequestSpec("http://localhost:1/v1", "m", List.of(message), null, null, null,
                                                   null, null);
        return RequestBuilder.build(spec, new CompatibilityFlags()).getAsJsonArray("messages").get(0)
                .getAsJsonObject();
    }

    @Test
    @Timeout(5)
    void imagesBecomeAContentArrayWithTheTextFirst() {
        ChatMessage message = new ChatMessage(ChatRole.USER, "Image from Read (call c1):", List.of(), null,
                                              List.of(new ChatImage("image/png", "QUJD", 2, 2),
                                                      new ChatImage("image/jpeg", "REVG", 3, 3)));

        JsonArray content = messageOf(message).getAsJsonArray("content");

        assertEquals(3, content.size(), content.toString());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("Image from Read (call c1):", content.get(0).getAsJsonObject().get("text").getAsString());
        JsonObject first = content.get(1).getAsJsonObject();
        assertEquals("image_url", first.get("type").getAsString());
        assertEquals("data:image/png;base64,QUJD", first.getAsJsonObject("image_url").get("url").getAsString());
        assertEquals("data:image/jpeg;base64,REVG",
                     content.get(2).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString());
    }

    @Test
    @Timeout(5)
    void anImageWithoutTextHasNoTextPart() {
        ChatMessage message = new ChatMessage(ChatRole.USER, "", List.of(), null,
                                              List.of(new ChatImage("image/png", "QUJD", 2, 2)));

        JsonArray content = messageOf(message).getAsJsonArray("content");

        assertEquals(1, content.size(), content.toString());
        assertEquals("image_url", content.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    @Timeout(5)
    void aMessageWithoutImagesKeepsPlainStringContent() {
        JsonObject message = messageOf(new ChatMessage(ChatRole.TOOL, "file text", List.of(), "c1"));

        assertTrue(message.get("content").isJsonPrimitive(), message.toString());
        assertEquals("file text", message.get("content").getAsString());
    }
}
