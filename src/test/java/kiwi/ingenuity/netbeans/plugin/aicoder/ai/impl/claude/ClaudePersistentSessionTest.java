package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.session.ClaudePersistentSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClaudePersistentSessionTest {

    @Test
    void framesUserMessageAsStreamJsonLine() {
        String line = ClaudePersistentSession.frameUserMessage("hello world");
        assertTrue(line.endsWith("\n"), "must be newline-terminated for stream-json");
        JsonObject o = new Gson().fromJson(line.trim(), JsonObject.class);
        assertEquals("user", o.get("type").getAsString());
        JsonObject msg = o.getAsJsonObject("message");
        assertEquals("user", msg.get("role").getAsString());
        String text = msg.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertEquals("hello world", text);
    }

    @Test
    void framesUserMessageEscapingNewlinesAndQuotes() {
        String line = ClaudePersistentSession.frameUserMessage("a\"b\nc");
        JsonObject o = new Gson().fromJson(line.trim(), JsonObject.class);
        String text = o.getAsJsonObject("message").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString();
        assertEquals("a\"b\nc", text);
    }
}
