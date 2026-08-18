package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.io.File;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;

/**
 * Shortens absolute paths for display.
 *
 * <p>
 * Confirm prompts and notifications show a path back to the user, and an
 * absolute path is both long enough to dominate the panel and mostly redundant —
 * the leading directories are the same for every file in the project. This
 * renders a path inside an open project relative to that project but keeps the
 * project directory name, so a prompt reads {@code MyProject/target/scratch.txt}
 * rather than {@code /home/user/code/MyProject/target/scratch.txt} — short, but
 * still saying which project it is when several are open.
 */
public final class ProjectPathUtil {

    private static final int MAX_LEN = 128;

    private ProjectPathUtil() {
    }

    /**
     * Project directory name plus the path within it, or the unchanged absolute
     * path when the file belongs to no open project. Long results are truncated
     * from the left, keeping the end — the file name matters more than the
     * directories above it.
     *
     * @param fp absolute path; returned unchanged when null or blank
     */
    public static String shortPath(String fp) {
        if (fp == null || fp.isBlank()) {
            return fp;
        }
        for (Project p : OpenProjects.getDefault().getOpenProjects()) {
            File projectDir = new File(p.getProjectDirectory().getPath());
            String base = projectDir.getAbsolutePath();
            if (fp.startsWith(base)) {
                String rel = fp.substring(base.length());
                if (rel.startsWith(File.separator)) {
                    rel = rel.substring(1);
                }
                // Keep the project directory name: with several projects open,
                // "target/scratch.txt" alone does not say which one it belongs to.
                return truncate(projectDir.getName() + File.separator + rel);
            }
        }
        // Outside every open project: keep the whole path. Shortening is only safe
        // when the part removed is redundant, which it is not here — "delete
        // hosts?" would not say which file, or that it sits outside the project
        // at all. Truncation still applies, from the left, so the file name and
        // its nearest directories survive.
        return truncate(fp);
    }

    private static String truncate(String s) {
        return s.length() > MAX_LEN ? "..." + s.substring(s.length() - (MAX_LEN - 3)) : s;
    }
}
