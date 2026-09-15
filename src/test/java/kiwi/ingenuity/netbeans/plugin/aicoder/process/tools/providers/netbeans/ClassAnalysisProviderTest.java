package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ClassAnalysisProviderTest {

    @Test
    void typeHierarchyRefusalSaysBinaryTypesAreOutOfScopeAndWhereToLook() {
        // Live v1.4.15: GetTypeHierarchy java.lang.Exception answered only "Source file not found for java.lang.Exception".
        assertEquals("No source for java.lang.Exception in an open project. GetTypeHierarchy only covers types whose"
                + " source is in an open project. Use GetJavadoc to see"
                + " such a type's superclass and interfaces.",
                     ClassAnalysisProvider.noSourceMessage("java.lang.Exception"));
    }
}
