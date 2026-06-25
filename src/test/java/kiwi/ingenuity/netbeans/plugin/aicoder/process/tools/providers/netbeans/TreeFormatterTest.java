package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.TreeFormatter;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.TreeFormatter.Node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TreeFormatterTest {

    private static Node dir(String name, Node... kids) {
        return new Node(name, true, List.of(kids));
    }

    private static Node file(String name) {
        return new Node(name, false, List.of());
    }

    @Test
    void sortsFoldersBeforeFilesThenAlphabetically() {
        Node root = dir("root", file("Zebra.java"), dir("sub"), file("Apple.java"));
        String out = TreeFormatter.format(root, "", 10, 100);
        // sub/ (folder) before files; files alpha
        int subIdx = out.indexOf("sub/");
        int appleIdx = out.indexOf("Apple.java");
        int zebraIdx = out.indexOf("Zebra.java");
        assertTrue(subIdx < appleIdx && appleIdx < zebraIdx, out);
    }

    @Test
    void collapsesSingleChildDirectoryChains() {
        // root -> a -> b -> c -> Foo.java   should render the package chain on one line
        Node root = dir("root", dir("a", dir("b", dir("c", file("Foo.java")))));
        String out = TreeFormatter.format(root, "", 10, 100);
        assertTrue(out.contains("a/b/c/"), out);
        assertTrue(!out.contains("\na/\n"), out);
    }

    @Test
    void capsTotalNodesAndAppendsTruncationFooter() {
        Node root = dir("root",
                file("A.java"), file("B.java"), file("C.java"), file("D.java"), file("E.java"));
        String out = TreeFormatter.format(root, "", 10, 2);
        assertTrue(out.contains("A.java"), out);
        assertTrue(out.contains("B.java"), out);
        assertTrue(!out.contains("E.java"), out);
        assertTrue(out.contains("more, truncated"), out);
    }

    @Test
    void respectsMaxDepth() {
        Node root = dir("root", dir("a", dir("b", file("Deep.java"))));
        String out = TreeFormatter.format(root, "", 1, 100);
        assertTrue(!out.contains("Deep.java"), out);
    }

    @Test
    void rootNameIsNotPrinted_onlyItsChildren() {
        Node root = dir("root", file("Only.java"));
        String out = TreeFormatter.format(root, "", 10, 100);
        assertEquals("Only.java", out.strip());
    }
}
