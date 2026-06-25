package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure formatter for a directory tree. Kept free of NetBeans APIs so the output
 * shape (sorting, single-child collapse, depth limit, node cap) is
 * unit-testable. The provider builds a {@link Node} tree from the IDE's
 * {@code FileObject} graph and hands it here for rendering.
 */
public final class TreeFormatter {

    /**
     * A tree node: display name, whether it is a directory, and its children
     * (empty for files).
     */
    public record Node(String name, boolean dir, List<Node> children) {

    }

    /**
     * Renders the children of {@code root} (the root itself is not printed —
     * the caller labels the source root separately). Folders are listed before
     * files, then alphabetically. Chains of single-child directories are
     * collapsed onto one line (e.g. {@code a/b/c/}) to cut deep-package noise.
     *
     * @param indent the leading indent for top-level children
     * @param maxDepth maximum display depth (top-level children are depth 1)
     * @param maxNodes hard cap on printed lines; excess is replaced with a "...
     * N more, truncated" footer
     */
    public static String format(Node root, String indent, int maxDepth, int maxNodes) {
        List<String> lines = new ArrayList<>();
        for (Node child : sorted(root.children())) {
            appendNode(lines, child, indent, 1, maxDepth);
        }
        if (lines.size() > maxNodes) {
            int remaining = lines.size() - maxNodes;
            lines = new ArrayList<>(lines.subList(0, maxNodes));
            lines.add(indent + "... " + remaining + " more, truncated");
        }
        return String.join("\n", lines).strip();
    }

    private static void appendNode(List<String> lines, Node node, String indent,
            int depth, int maxDepth) {
        if (depth > maxDepth) {
            return;
        }
        if (!node.dir()) {
            lines.add(indent + node.name());
            return;
        }
        // Collapse single-child directory chains: a -> b -> c becomes "a/b/c/".
        StringBuilder label = new StringBuilder(node.name());
        Node cur = node;
        while (cur.children().size() == 1 && cur.children().get(0).dir()) {
            cur = cur.children().get(0);
            label.append('/').append(cur.name());
        }
        lines.add(indent + label + "/");
        for (Node child : sorted(cur.children())) {
            appendNode(lines, child, indent + "  ", depth + 1, maxDepth);
        }
    }

    private static List<Node> sorted(List<Node> nodes) {
        List<Node> copy = new ArrayList<>(nodes);
        copy.sort(Comparator.<Node, Boolean>comparing(n -> !n.dir())
                .thenComparing(Node::name));
        return copy;
    }

    private TreeFormatter() {
    }
}
