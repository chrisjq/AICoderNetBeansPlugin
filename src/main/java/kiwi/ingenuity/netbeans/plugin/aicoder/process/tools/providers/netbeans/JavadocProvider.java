package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.platform.JavaPlatformManager;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class JavadocProvider {

    private static final int MAX_AVAILABLE_MEMBERS = 50;

    public static String getJavadoc(String projectPath, String className, String memberName) {
        if (projectPath == null || projectPath.isBlank()) {
            return GitCommonParamEnum.PROJECT_PATH.key() + " is required";
        }
        if (className == null || className.isBlank()) {
            return McpToolPropertyEnum.CLASS_NAME.key() + " is required";
        }
        Project project = resolveProject(projectPath);
        if (project == null) {
            return "No open project matches projectPath: " + projectPath;
        }
        ClasspathInfo cpInfo = buildClasspathInfo(project);
        if (cpInfo == null) {
            return "Project has no Java source group, so no classpath could be built: " + projectPath;
        }
        JavaSource js = JavaSource.create(cpInfo);
        if (js == null) {
            return "Cannot create JavaSource for project: " + projectPath;
        }

        AtomicReference<String> result = new AtomicReference<>("Class not found on classpath: " + className);
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                TypeElement te = cc.getElements().getTypeElement(className);
                if (te == null) {
                    return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append(te.getKind()).append(" ").append(className);
                TypeMirror superclass = te.getSuperclass();
                if (superclass != null && !superclass.toString().equals("java.lang.Object")) {
                    sb.append("\nextends ").append(superclass);
                }
                if (!te.getInterfaces().isEmpty()) {
                    sb.append("\nimplements ").append(
                            te.getInterfaces().stream().map(TypeMirror::toString)
                                    .collect(Collectors.joining(", ")));
                }
                String classDoc = cc.getElements().getDocComment(te);
                if (classDoc != null) {
                    sb.append("\n\n").append(classDoc.trim());
                }

                boolean memberMatched = false;
                List<String> availableMembers = new ArrayList<>();
                for (Element enc : te.getEnclosedElements()) {
                    ElementKind kind = enc.getKind();
                    if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR
                            && kind != ElementKind.FIELD && kind != ElementKind.ENUM_CONSTANT) {
                        continue;
                    }
                    boolean visible = enc.getModifiers().contains(Modifier.PUBLIC)
                            || enc.getModifiers().contains(Modifier.PROTECTED);
                    if (!visible) {
                        continue;
                    }
                    if (availableMembers.size() < MAX_AVAILABLE_MEMBERS) {
                        availableMembers.add(enc.getSimpleName().toString());
                    }
                    // Substring matching is deliberate: it lets callers search a member name fragment.
                    if (memberName != null && !memberName.isBlank()
                            && !enc.getSimpleName().toString().contains(memberName)) {
                        continue;
                    }

                    memberMatched = true;
                    sb.append("\n\n");
                    if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
                        ExecutableElement ee = (ExecutableElement) enc;
                        String mods = ee.getModifiers().stream().map(Modifier::toString)
                                .collect(Collectors.joining(" "));
                        sb.append(mods);
                        if (kind == ElementKind.METHOD) {
                            sb.append(" ").append(ee.getReturnType());
                        }
                        sb.append(" ").append(ee.getSimpleName()).append("(");
                        sb.append(ee.getParameters().stream()
                                .map(p -> p.asType() + " " + p.getSimpleName())
                                .collect(Collectors.joining(", ")));
                        sb.append(")");
                        if (!ee.getThrownTypes().isEmpty()) {
                            sb.append(" throws ").append(ee.getThrownTypes().stream()
                                    .map(TypeMirror::toString).collect(Collectors.joining(", ")));
                        }
                    }
                    else {
                        VariableElement ve = (VariableElement) enc;
                        String mods = ve.getModifiers().stream().map(Modifier::toString)
                                .collect(Collectors.joining(" "));
                        sb.append(mods).append(" ").append(ve.asType())
                                .append(" ").append(ve.getSimpleName());
                        if (ve.getConstantValue() != null) {
                            sb.append(" = ").append(ve.getConstantValue());
                        }
                    }
                    String doc = cc.getElements().getDocComment(enc);
                    if (doc != null) {
                        sb.append("\n  ").append(doc.trim().replace("\n", "\n  "));
                    }
                }
                String missingMember = !memberMatched
                        ? memberNotFoundMessage(className, memberName, availableMembers) : null;
                if (missingMember != null) {
                    sb.append("\n\n").append(missingMember);
                }
                result.set(sb.toString());
            }, true);
        }
        catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        return result.get();
    }

    /**
     * Resolves an absolute project path to an open {@link Project}, matching by normalized project directory. Returns
     * null when no open project's root equals the request — the caller then rejects rather than guessing a fallback.
     */
    private static Project resolveProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }
        File requested = FileUtil.normalizeFile(new File(projectPath));
        for (Project candidate : OpenProjects.getDefault().getOpenProjects()) {
            File root = FileUtil.toFile(candidate.getProjectDirectory());
            if (root != null && FileUtil.normalizeFile(root).equals(requested)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Builds a {@link ClasspathInfo} for the chosen project from its Java source ROOTS rather than from any sample
     * source file. Anchoring a {@code ClasspathInfo} on a file yields the classpath OF THAT FILE, so the visible
     * dependencies would vary with which root happened to provide the anchor (main vs test) or fail entirely when the
     * project had no reachable Java file. Constructing the three classpaths explicitly removes that dependency on file
     * placement.
     * <p>
     * All of the project's Java source groups are merged with {@link ClassPathSupport#createProxyClassPath} for each of
     * BOOT/COMPILE/SOURCE, so a single query sees both main- and test-scoped dependencies. A class a caller might
     * legitimately ask about (including a test-only dependency) therefore resolves instead of failing confusingly; the
     * proxy is a union, so nothing visible on any individual root is dropped. A missing boot path on a group falls back
     * to the default platform's bootstrap libraries.
     * <p>
     * Returns null when the project has no Java source group: {@code ClassPath.getClassPath} only yields a real
     * classpath for a recognised source ROOT such as {@code src/main/java}, never for a bare project directory, so
     * falling back to the project directory would silently build a JDK-only classpath and then report a misleading
     * "Class not found" for the project's own classes. The caller refuses explicitly instead.
     */
    private static ClasspathInfo buildClasspathInfo(Project project) {
        SourceGroup[] groups = ProjectUtils.getSources(project).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA);
        if (groups.length == 0) {
            return null;
        }

        List<ClassPath> boot = new ArrayList<>();
        List<ClassPath> compile = new ArrayList<>();
        List<ClassPath> source = new ArrayList<>();
        for (SourceGroup group : groups) {
            FileObject root = group.getRootFolder();
            addNonNull(boot, ClassPath.getClassPath(root, ClassPath.BOOT));
            addNonNull(compile, ClassPath.getClassPath(root, ClassPath.COMPILE));
            addNonNull(source, ClassPath.getClassPath(root, ClassPath.SOURCE));
        }
        return ClasspathInfo.create(
                boot.isEmpty() ? defaultBootPath() : ClassPathSupport.createProxyClassPath(boot.toArray(new ClassPath[0])),
                compile.isEmpty() ? ClassPath.EMPTY : ClassPathSupport.createProxyClassPath(compile.toArray(new ClassPath[0])),
                source.isEmpty() ? ClassPath.EMPTY : ClassPathSupport.createProxyClassPath(source.toArray(new ClassPath[0])));
    }

    private static void addNonNull(List<ClassPath> into, ClassPath path) {
        if (path != null) {
            into.add(path);
        }
    }

    private static ClassPath defaultBootPath() {
        try {
            ClassPath boot = JavaPlatformManager.getDefault().getDefaultPlatform().getBootstrapLibraries();
            return boot != null ? boot : ClassPath.EMPTY;
        }
        catch (Throwable t) {
            return ClassPath.EMPTY;
        }
    }

    static String memberNotFoundMessage(String className, String memberName, List<String> availableMembers) {
        if (memberName == null || memberName.isBlank()) {
            return null;
        }
        return "No member matching '" + memberName + "' found on " + className
                + ". Available public/protected members: " + availableMembers.stream().limit(50)
                        .collect(Collectors.joining(", "));
    }

    static boolean hasMemberMatch(List<String> memberNames, String memberName) {
        return memberName != null && !memberName.isBlank()
                && memberNames.stream().anyMatch(name -> name.contains(memberName));
    }

    private JavadocProvider() {
    }
}
