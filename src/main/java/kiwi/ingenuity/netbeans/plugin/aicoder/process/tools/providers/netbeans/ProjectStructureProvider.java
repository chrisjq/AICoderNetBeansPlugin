package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans.TreeFormatter.Node;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class ProjectStructureProvider {

    private static final Logger LOG = Logger.getLogger(ProjectStructureProvider.class.getName());

    /**
     * Hard cap on printed tree lines per source root, to bound output size.
     */
    private static final int MAX_NODES = 400;

    public static String getProjectStructure() {
        Project[] projects = OpenProjects.getDefault().getOpenProjects();
        if (projects.length == 0) {
            return "No projects open";
        }
        StringBuilder sb = new StringBuilder();
        for (Project project : projects) {
            String name = ProjectUtils.getInformation(project).getDisplayName();
            sb.append("Project: ").append(name).append("\n");
            Sources sources = ProjectUtils.getSources(project);
            SourceGroup[] groups = sources.getSourceGroups("java");
            if (groups.length == 0) {
                sb.append("  [no java source groups — showing project root]\n");
                Node root = buildNode(project.getProjectDirectory(), 0, 5);
                appendNonBlank(sb, TreeFormatter.format(root, "  ", 5, MAX_NODES));
            }
            else {
                for (SourceGroup group : groups) {
                    File rootFile = FileUtil.toFile(group.getRootFolder());
                    String rootPath = rootFile != null ? rootFile.getPath() : group.getRootFolder().getPath();
                    sb.append("  Source root: ").append(rootPath).append("\n");
                    Node root = buildNode(group.getRootFolder(), 0, 8);
                    appendNonBlank(sb, TreeFormatter.format(root, "    ", 8, MAX_NODES));
                }
            }
        }
        return sb.toString();
    }

    private static void appendNonBlank(StringBuilder sb, String tree) {
        if (tree != null && !tree.isBlank()) {
            sb.append(tree).append("\n");
        }
    }

    /**
     * Builds a {@link Node} tree of folders and {@code .java} files under the
     * given folder. Descent stops past {@code maxDepth} so the transient tree
     * stays bounded for large projects; {@link TreeFormatter} applies the same
     * depth limit when rendering.
     */
    private static Node buildNode(FileObject folder, int depth, int maxDepth) {
        List<Node> children = new ArrayList<>();
        if (depth < maxDepth) {
            for (FileObject child : folder.getChildren()) {
                if (child.isFolder()) {
                    children.add(buildNode(child, depth + 1, maxDepth));
                }
                else if (child.hasExt("java")) {
                    children.add(new Node(child.getNameExt(), false, List.of()));
                }
            }
        }
        return new Node(folder.getNameExt(), true, children);
    }

    private ProjectStructureProvider() {
    }
}
