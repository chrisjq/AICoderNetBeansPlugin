package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ThinkTagStripperTest {

    @Test
    @Timeout(5)
    void textBeforeAndAfterAThinkBlockIsVisible() {
        ThinkTagStripper stripper = new ThinkTagStripper();

        assertEquals("hello world", stripper.feed("hello <think>secret</think>world"));
        assertEquals("", stripper.flush());
        assertEquals("secret", stripper.reasoning());
    }

    @Test
    @Timeout(5)
    void tagsSplitAcrossDeltasAreRecognised() {
        ThinkTagStripper stripper = new ThinkTagStripper();

        assertEquals("pre ", stripper.feed("pre <thi"));
        assertEquals("", stripper.feed("nk>hid"));
        assertEquals("", stripper.feed("den</th"));
        assertEquals(" post", stripper.feed("ink> post"));
        assertEquals("", stripper.flush());
        assertEquals("hidden", stripper.reasoning());
    }

    @Test
    @Timeout(5)
    void nestedThinkTagsStayInReasoningUntilTheMatchingClose() {
        ThinkTagStripper stripper = new ThinkTagStripper();

        assertEquals("ab", stripper.feed("a<think>x<think>y</think>z</think>b"));
        assertEquals("", stripper.flush());
        assertEquals("xyz", stripper.reasoning());
    }

    @Test
    @Timeout(5)
    void unclosedThinkKeepsItsTextAsReasoningOnFlush() {
        ThinkTagStripper stripper = new ThinkTagStripper();

        assertEquals("pre ", stripper.feed("pre <think>abc"));
        assertEquals("", stripper.flush());
        assertEquals("abc", stripper.reasoning());
    }
}
