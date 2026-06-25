package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.project.ActionProvider;
import org.openide.util.Lookup;

public class ProjectActionProvider {

    public static String cleanProject() {
        return invokeAction(ActionProvider.COMMAND_CLEAN, "Clean");
    }

    public static String buildProject() {
        return invokeAction(ActionProvider.COMMAND_BUILD, "Build");
    }

    public static String cleanAndBuildProject() {
        return invokeAction(ActionProvider.COMMAND_REBUILD, "Clean and build");
    }

    private static String invokeAction(String command, String label) {
        Project[] projects = OpenProjects.getDefault().getOpenProjects();
        if (projects.length == 0) {
            return "No open project found";
        }
        Project project = projects[0];
        ActionProvider ap = project.getLookup().lookup(ActionProvider.class);
        if (ap == null) {
            return "Project does not support ActionProvider";
        }
        boolean supported = false;
        for (String cmd : ap.getSupportedActions()) {
            if (cmd.equals(command)) {
                supported = true;
                break;
            }
        }
        if (!supported) {
            return "Project does not support action: " + command;
        }
        ap.invokeAction(command, Lookup.EMPTY);
        return label + " triggered — check the Output window for results";
    }

    private ProjectActionProvider() {
    }
}
