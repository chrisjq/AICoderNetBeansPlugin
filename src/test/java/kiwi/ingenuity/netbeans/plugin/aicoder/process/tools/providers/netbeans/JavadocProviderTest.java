package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class JavadocProviderTest {

    @Test
    void missingMemberMessageNamesRequestedAndAvailableMembers() {
        String result = JavadocProvider.memberNotFoundMessage("example.Type", "missingMember",
                List.of("presentMember", "otherMember"));

        assertEquals("No member matching 'missingMember' found on example.Type. "
                + "Available public/protected members: presentMember, otherMember", result);
    }

    @Test
    void matchingOrBlankMemberDoesNotProduceMissingMessage() {
        assertTrue(JavadocProvider.hasMemberMatch(List.of("getSessionId"), "Session"));
        assertNull(JavadocProvider.memberNotFoundMessage("example.Type", "", List.of("presentMember")));
        assertNull(JavadocProvider.memberNotFoundMessage("example.Type", null, List.of("presentMember")));
    }

    @Test
    void availableMemberListIsCappedAtFifty() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            if (names.size() < 50) {
                names.add("member" + i);
            }
        }
        String result = JavadocProvider.memberNotFoundMessage("example.Type", "missing", names);

        assertEquals(50, result.substring(result.indexOf(": ") + 2).split(", ").length);
        assertFalse(result.contains("member50"));
    }
}
