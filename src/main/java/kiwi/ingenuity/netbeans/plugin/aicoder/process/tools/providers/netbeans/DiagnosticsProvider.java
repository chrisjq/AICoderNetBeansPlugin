package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.windows.TopComponent;

public class DiagnosticsProvider {

    private static final Logger LOG = Logger.getLogger(DiagnosticsProvider.class.getName());

    public static String getDiagnostics() {
        Set<FileObject> javaFiles = new LinkedHashSet<>();
        try {
            // Collect open Java files on EDT to safely access the TopComponent registry.
            SwingUtilities.invokeAndWait(() -> {
                for (TopComponent tc : TopComponent.getRegistry().getOpened()) {
                    DataObject dob = tc.getLookup().lookup(DataObject.class);
                    if (dob == null) {
                        continue;
                    }
                    FileObject fo = dob.getPrimaryFile();
                    if ("java".equals(fo.getExt())) {
                        javaFiles.add(fo);
                    }
                }
            });
        }
        catch (Exception e) {
            return "Error listing open files: " + e.getMessage();
        }

        if (javaFiles.isEmpty()) {
            return "No Java files open";
        }

        Set<Project> openProjects = new LinkedHashSet<>();
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            openProjects.add(p);
        }

        StringBuilder sb = new StringBuilder();
        List<String> failures = new ArrayList<>();
        for (FileObject fo : javaFiles) {
            File f = FileUtil.toFile(fo);
            String path = f != null ? f.getPath() : fo.getPath();

            Project owningProject = FileOwnerQuery.getOwner(fo);
            String projectNotOpenMsg = projectNotOpenMessageOrNull(path, owningProject, openProjects);
            if (projectNotOpenMsg != null) {
                sb.append(projectNotOpenMsg).append("\n");
                continue;
            }

            JavaSource js = JavaSource.forFileObject(fo);
            if (js == null) {
                failures.add(path);
                continue;
            }
            try {
                js.runUserActionTask(cc -> {
                    cc.toPhase(JavaSource.Phase.RESOLVED);
                    // Iterate as Object to avoid javax.tools.Diagnostic class loader conflict;
                    // use reflection to extract kind/line/message safely.
                    for (Object diag : cc.getDiagnostics()) {
                        try {
                            String kind = diag.getClass().getMethod("getKind")
                                    .invoke(diag).toString();
                            long line = (Long) diag.getClass().getMethod("getLineNumber")
                                    .invoke(diag);
                            String msg = (String) diag.getClass()
                                    .getMethod("getMessage", Locale.class)
                                    .invoke(diag, Locale.ENGLISH);
                            sb.append("[").append(kind).append("] ")
                                    .append(path).append(":").append(line)
                                    .append(" — ").append(msg).append("\n");
                        }
                        catch (ReflectiveOperationException ex) {
                            sb.append(path).append(": ").append(diag).append("\n");
                        }
                    }
                }, true);
            }
            catch (Throwable e) {
                LOG.log(Level.FINE, "Could not get diagnostics for " + path, e);
                failures.add(path);
            }
        }
        return formatDiagnosticsResult(sb.toString(), failures);
    }

    static String formatDiagnosticsResult(String diagnostics, List<String> failures) {
        String diagnosticText = diagnostics == null ? "" : diagnostics.strip();
        if (!failures.isEmpty()) {
            String failureMessage = "Could not analyse diagnostics for: " + String.join(", ", failures);
            return diagnosticText.isEmpty() ? failureMessage : diagnosticText + "\n" + failureMessage;
        }
        return diagnosticText.isEmpty() ? "No diagnostics found" : diagnosticText;
    }

    static String projectNotOpenMessageOrNull(String filePath, Project owner, Set<Project> openProjects) {
        if (owner == null || !openProjects.contains(owner)) {
            String projectPath = "unknown";
            if (owner != null) {
                File projDir = FileUtil.toFile(owner.getProjectDirectory());
                projectPath = projDir != null ? projDir.getAbsolutePath() : owner.getProjectDirectory().getPath();
            }
            return "[NOT ANALYSED] " + filePath + " — project not open: " + projectPath + "; diagnostics unreliable, open the module to get accurate results";
        }
        return null;
    }

    private DiagnosticsProvider() {
    }
}
