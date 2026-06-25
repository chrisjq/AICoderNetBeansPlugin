package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import org.netbeans.api.java.source.JavaSource;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.windows.TopComponent;

public class DiagnosticsProvider {

    private static final Logger LOG = Logger.getLogger(DiagnosticsProvider.class.getName());

    public static String getDiagnostics() {
        // Collect open Java files on EDT to safely access the TopComponent registry.
        List<FileObject> javaFiles = new ArrayList<>();
        try {
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

        StringBuilder sb = new StringBuilder();
        for (FileObject fo : javaFiles) {
            JavaSource js = JavaSource.forFileObject(fo);
            if (js == null) {
                continue;
            }
            File f = FileUtil.toFile(fo);
            String path = f != null ? f.getPath() : fo.getPath();
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
            }
        }
        return sb.isEmpty() ? "No diagnostics found" : sb.toString().strip();
    }

    private DiagnosticsProvider() {
    }
}
