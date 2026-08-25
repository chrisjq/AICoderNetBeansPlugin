package kiwi.ingenuity.netbeans.plugin.aicoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

class GitAccessOptionEnumTest {

    @Test
    void mutatingMapsToWriteAndNonMutatingMapsToRead() {
        assertEquals(GitAccessOptionEnum.WRITE, GitAccessOptionEnum.forMutating(true));
        assertEquals(GitAccessOptionEnum.READ, GitAccessOptionEnum.forMutating(false));
    }

    @Test
    void everyOptionCarriesADistinctLabel() {
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            assertNotNull(option.label(), option.name());
        }
        assertEquals(AccessControlLabelEnum.ALLOW_GIT_READ, GitAccessOptionEnum.READ.label());
        assertEquals(AccessControlLabelEnum.ALLOW_GIT_WRITE, GitAccessOptionEnum.WRITE.label());
    }
}
