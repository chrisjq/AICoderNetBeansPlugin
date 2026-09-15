package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.Parameterizable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.git.GitCommonParamEnum;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.platform.JavaPlatformManager;
import org.netbeans.api.java.project.JavaProjectConstants;
import org.netbeans.api.java.queries.JavadocForBinaryQuery;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationInfo;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.api.java.source.ui.ElementJavadoc;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.ui.OpenProjects;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

public class JavadocProvider {

    private static final Logger LOG = Logger.getLogger(JavadocProvider.class.getName());
    private static final int MAX_AVAILABLE_MEMBERS = 50;
    private static final String OBJECT_TYPE = "java.lang.Object";
    private static final long DOC_TIMEOUT_SECONDS = 5;
    // Plain printable marker: it must never contain control characters, which make git treat this file as binary.
    private static final String DOC_PLACEHOLDER = "@@javadoc#";
    private static final int REMOTE_CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int REMOTE_READ_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_REMOTE_PAGE_BYTES = 4 * 1024 * 1024;
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    // javac prints a type annotation after the package: "java.lang.@org.jspecify.annotations.Nullable Object".
    private static final Pattern QUALIFIED_TYPE_ANNOTATIONS = Pattern.compile(
            "((?:[A-Za-z_$][\\w$]*\\.)+)((?:@[\\w$.]+(?:\\([^)]*\\))?\\s+)+)");
    private static final Pattern ANNOTATION_QUALIFIER = Pattern.compile("@(?:[\\w$]+\\.)+");
    private static final Pattern TYPE_ANNOTATION = Pattern.compile("@[\\w$.]+(?:\\([^)]*\\))?\\s+");
    private static final String NO_JAVADOC = "No Javadoc found.";

    /**
     * A remote Javadoc page to fetch directly, and the member section on it ({@code null} anchor = class description).
     */
    record RemoteDocTarget(String pageUrl, String anchorId) {

    }

    public static String getJavadoc(String projectPath, String className, String memberName) {
        if (projectPath == null || projectPath.isBlank()) {
            return GitCommonParamEnum.PROJECT_PATH.key() + " is required";
        }
        if (className == null || className.isBlank()) {
            return McpToolPropertyEnum.CLASS_NAME.key() + " is required";
        }
        Project project = resolveProject(projectPath);
        if (project == null) {
            StringBuilder sb = new StringBuilder("No open project matches projectPath: ").append(projectPath).append("\nOpen projects:");
            for (Project p : OpenProjects.getDefault().getOpenProjects()) {
                File f = FileUtil.toFile(p.getProjectDirectory());
                if (f != null) {
                    sb.append("\n  ").append(f.getAbsolutePath());
                }
            }
            return sb.toString();
        }
        ClasspathInfo cpInfo = buildClasspathInfo(project);
        if (cpInfo == null) {
            return "Project has no Java source group: " + projectPath + "\nIt may be an aggregator (pom) project — pass the path of a child module that has Java sources.";
        }
        // Javadoc is created in the context of one of the project's own source files (see createJavadocs), so the lookup
        // anchors on one. Signatures still resolve against cpInfo, so which file it is doesn't matter here.
        boolean memberSearch = memberName != null && !memberName.isBlank();
        FileObject anchor = firstJavaFile(project);
        JavaSource js = anchor != null ? JavaSource.create(cpInfo, anchor) : JavaSource.create(cpInfo);
        if (js == null) {
            return "Cannot create JavaSource for project: " + projectPath;
        }

        SourceGroup[] groups = ProjectUtils.getSources(project).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA);
        StringBuilder classpath = new StringBuilder("Searched project: ").append(projectPath).append("\n  source groups:");
        for (SourceGroup g : groups) {
            File f = FileUtil.toFile(g.getRootFolder());
            if (f != null) {
                classpath.append("\n    ").append(g.getName()).append(" (").append(f.getAbsolutePath()).append(")");
            }
        }
        classpath.append("\n  compile classpath of every source group above\nNot searched: other open projects.");
        final String classpathInfo = classpath.toString();

        AtomicReference<String> result = new AtomicReference<>("Class not found: " + className + "\n" + classpathInfo);
        List<ElementHandle<? extends Element>> docHandles = new ArrayList<>();
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                TypeElement te = cc.getElements().getTypeElement(className);
                if (te == null) {
                    return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append(te.getKind()).append(" ").append(className).append(formatTypeParameters(typeParameterBounds(te)));
                TypeMirror superclass = te.getSuperclass();
                // An interface's superclass is a NONE type whose toString() is "none" — only a real class is printed.
                if (superclass != null && superclass.getKind() == TypeKind.DECLARED
                        && !superclass.toString().equals(OBJECT_TYPE)) {
                    sb.append("\nextends ").append(readableType(superclass.toString()));
                }
                if (!te.getInterfaces().isEmpty()) {
                    sb.append("\nimplements ").append(
                            te.getInterfaces().stream().map(i -> readableType(i.toString()))
                                    .collect(Collectors.joining(", ")));
                }
                // A class lookup shows the class's own Javadoc; a member lookup shows only the matched members' Javadoc.
                if (anchor != null && !memberSearch) {
                    docHandles.add(ElementHandle.create(te));
                    sb.append(docPlaceholder(docHandles.size() - 1));
                }

                boolean memberMatched = false;
                String ownType = te.getQualifiedName().toString();
                Set<String> ownMemberNames = new LinkedHashSet<>();
                Set<String> inheritedMemberNames = new LinkedHashSet<>();
                Map<String, Integer> inheritedCounts = new LinkedHashMap<>();
                Set<String> declaredTypes = new LinkedHashSet<>();
                Elements elements = cc.getElements();
                for (Element enc : elements.getAllMembers(te)) {
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
                    Element declarer = enc.getEnclosingElement();
                    String declaringType = declarer instanceof TypeElement ? ((TypeElement) declarer).getQualifiedName().toString() : declarer.toString();
                    declaredTypes.add(declaringType);
                    String memberDisplayName = displayNameOf(kind, enc.getSimpleName().toString(),
                                                             declarer.getSimpleName().toString());
                    boolean own = declaringType.equals(ownType);
                    (own ? ownMemberNames : inheritedMemberNames).add(memberDisplayName);
                    if (!isListed(own, memberName, memberDisplayName)) {
                        if (!own && !memberSearch && !OBJECT_TYPE.equals(declaringType)) {
                            inheritedCounts.merge(declaringType, 1, Integer::sum);
                        }
                        continue;
                    }

                    memberMatched = true;
                    sb.append("\n\n");

                    if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
                        ExecutableElement ee = (ExecutableElement) enc;
                        String mods = ee.getModifiers().stream().map(Modifier::toString)
                                .collect(Collectors.joining(" "));
                        sb.append(mods);
                        String typeParameters = formatTypeParameters(typeParameterBounds(ee));
                        if (!typeParameters.isEmpty()) {
                            sb.append(" ").append(typeParameters);
                        }
                        if (kind == ElementKind.METHOD) {
                            sb.append(" ").append(readableType(ee.getReturnType().toString()));
                        }
                        sb.append(" ").append(memberDisplayName).append("(");
                        sb.append(formatParameters(
                                ee.getParameters().stream().map(p -> readableType(p.asType().toString()))
                                        .collect(Collectors.toList()),
                                ee.getParameters().stream().map(p -> p.getSimpleName().toString())
                                        .collect(Collectors.toList()),
                                ee.isVarArgs()));
                        sb.append(")");
                        if (!ee.getThrownTypes().isEmpty()) {
                            sb.append(" throws ").append(ee.getThrownTypes().stream()
                                    .map(t -> readableType(t.toString())).collect(Collectors.joining(", ")));
                        }
                    }
                    else {
                        VariableElement ve = (VariableElement) enc;
                        String mods = ve.getModifiers().stream().map(Modifier::toString)
                                .collect(Collectors.joining(" "));
                        sb.append(mods).append(" ").append(readableType(ve.asType().toString()))
                                .append(" ").append(ve.getSimpleName());
                        if (ve.getConstantValue() != null) {
                            sb.append(" = ").append(ve.getConstantValue());
                        }
                    }
                    if (!declaringType.equals(te.getQualifiedName().toString())) {
                        sb.append(" (declared in ").append(declaringType).append(")");
                    }
                    // Doc text only for members the caller named, never on a class listing. Only a handle is kept here;
                    // the Javadoc itself is created and fetched after this task (see createJavadocs).
                    if (anchor != null && memberSearch) {
                        docHandles.add(ElementHandle.create(enc));
                        sb.append(docPlaceholder(docHandles.size() - 1));
                    }
                }
                if (!memberSearch) {
                    String inherited = inheritedMembersSummary(inheritedCounts);
                    if (inherited != null) {
                        sb.append("\n\n").append(inherited);
                    }
                }
                String missingMember = !memberMatched
                                       ? memberNotFoundMessage(className, memberName,
                                                               availableMemberNames(ownMemberNames, inheritedMemberNames),
                                                               declaredTypes) : null;
                if (missingMember != null) {
                    sb.append("\n\n").append(missingMember);
                }
                result.set(sb.toString());
            }, true);
        }
        catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        RemoteDocTarget[] targets = new RemoteDocTarget[docHandles.size()];
        List<ElementJavadoc> docs;
        try {
            docs = createJavadocs(docHandles, anchor, js, targets);
        }
        catch (IOException e) {
            // Signatures are still useful without doc text; the placeholders are blanked rather than left in the output.
            docs = Collections.nCopies(docHandles.size(), null);
        }
        return fillDocPlaceholders(result.get(), fetchDocTexts(docs, Arrays.asList(targets)));
    }

    /**
     * Resolves an absolute project path to an open {@link Project}, matching by real project directory (symlinks
     * resolved). Returns null when no open project's root equals the request — the caller then rejects rather than
     * guessing a fallback.
     */
    private static Project resolveProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }
        File requestedFile = new File(projectPath);
        File requestedReal = FileUtils.toRealPath(requestedFile);
        if (requestedReal == null) {
            return null;
        }
        for (Project candidate : OpenProjects.getDefault().getOpenProjects()) {
            File root = FileUtil.toFile(candidate.getProjectDirectory());
            if (root != null) {
                File rootReal = FileUtils.toRealPath(root);
                if (rootReal != null && rootReal.equals(requestedReal)) {
                    return candidate;
                }
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

    static String memberNotFoundMessage(String className, String memberName, List<String> availableMembers, Set<String> declaredTypes) {
        if (memberName == null || memberName.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("No member matching '").append(memberName).append("' found on ").append(className);
        if (!declaredTypes.isEmpty()) {
            sb.append("\nSearched types: ").append(String.join(", ", declaredTypes));
        }
        sb.append("\nAvailable public/protected members: ").append(availableMembers.stream().limit(50)
                .collect(Collectors.joining(", ")));
        return sb.toString();
    }

    private static FileObject firstJavaFile(Project project) {
        for (SourceGroup group : ProjectUtils.getSources(project).getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)) {
            Enumeration<? extends FileObject> children = group.getRootFolder().getChildren(true);
            while (children.hasMoreElements()) {
                FileObject fo = children.nextElement();
                if (fo.isData() && "java".equals(fo.getExt())) {
                    return fo;
                }
            }
        }
        return null;
    }

    /**
     * Creates each element's ElementJavadoc in the anchor file's own context ({@link JavaSource#forFileObject}),
     * falling back to the lookup context for an element the file's classpath cannot resolve (e.g. a test-only
     * dependency when the anchor is a main source). A null entry means no Javadoc could be created for that element.
     * For each resolved element {@code targets} also receives its remote Javadoc page, if any, for
     * {@link #fetchDocTexts}'s fallback.
     */
    private static List<ElementJavadoc> createJavadocs(List<ElementHandle<? extends Element>> handles, FileObject anchor,
                                                       JavaSource lookup, RemoteDocTarget[] targets) throws IOException {
        ElementJavadoc[] docs = new ElementJavadoc[handles.size()];
        if (handles.isEmpty() || anchor == null) {
            return Arrays.asList(docs);
        }
        int[] fromEditorContext = new int[1];
        JavaSource editorContext = JavaSource.forFileObject(anchor);
        if (editorContext != null) {
            editorContext.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                for (int i = 0; i < docs.length; i++) {
                    Element element = handles.get(i).resolve(cc);
                    if (element != null) {
                        docs[i] = ElementJavadoc.create(cc, element);
                        targets[i] = remoteDocTarget(cc, handles.get(i), element);
                        fromEditorContext[0]++;
                    }
                }
            }, true);
        }
        if (fromEditorContext[0] < docs.length) {
            lookup.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                for (int i = 0; i < docs.length; i++) {
                    if (docs[i] == null) {
                        Element element = handles.get(i).resolve(cc);
                        if (element != null) {
                            docs[i] = ElementJavadoc.create(cc, element);
                            targets[i] = remoteDocTarget(cc, handles.get(i), element);
                        }
                    }
                }
            }, true);
        }
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "GetJavadoc: {0} doc(s); {1} created in the editor context of {2}, the rest in the lookup context",
                    new Object[]{docs.length, fromEditorContext[0], anchor.getPath()});
        }
        return Arrays.asList(docs);
    }

    /**
     * Where to fetch an element's Javadoc directly when {@link ElementJavadoc} cannot: the page under the http(s)
     * Javadoc root that {@link JavadocForBinaryQuery} reports for the class's owning root, plus the member's anchor.
     * <p>
     * Live on NetBeans 31 with JDK 21, this public lookup resolved JDK classes to
     * {@code https://docs.oracle.com/en/java/javase/21/docs/api/}, yet ElementJavadoc returned "Javadoc not found"
     * without ever opening a network stream (JavadocHelper FINE logging stayed silent): its internal class-file root
     * resolution does not reach the root this lookup finds. Returns null when the owning root has no http(s) Javadoc
     * root; local Javadoc and sources stay with ElementJavadoc, which already handles them.
     */
    private static RemoteDocTarget remoteDocTarget(CompilationInfo info, ElementHandle<? extends Element> handle,
                                                   Element element) {
        try {
            String binaryClassName = SourceUtils.getJVMSignature(handle)[0];
            String resourceName = binaryClassName.replace('.', '/') + ".class";
            ClasspathInfo.PathKind[] kinds = {ClasspathInfo.PathKind.BOOT, ClasspathInfo.PathKind.MODULE_BOOT,
                ClasspathInfo.PathKind.COMPILE};
            for (ClasspathInfo.PathKind kind : kinds) {
                ClassPath cp = info.getClasspathInfo().getClassPath(kind);
                FileObject resource = cp != null ? cp.findResource(resourceName) : null;
                FileObject root = resource != null ? cp.findOwnerRoot(resource) : null;
                if (root == null) {
                    continue;
                }
                for (URL docRoot : JavadocForBinaryQuery.findJavadoc(root.toURL()).getRoots()) {
                    if (!"http".equals(docRoot.getProtocol()) && !"https".equals(docRoot.getProtocol())) {
                        continue;
                    }
                    String module = moduleFromRoot(root.toURL().toExternalForm());
                    if (module == null) {
                        ModuleElement moduleElement = info.getElements().getModuleOf(element);
                        module = moduleElement != null && !moduleElement.isUnnamed()
                                 ? moduleElement.getQualifiedName().toString() : null;
                    }
                    String packageName = info.getElements().getPackageOf(element).getQualifiedName().toString();
                    return new RemoteDocTarget(jdkPageUrl(docRoot.toExternalForm(), module, packageName, binaryClassName),
                                               anchorIdOf(info, element));
                }
                return null;
            }
        }
        catch (RuntimeException e) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "GetJavadoc remote: cannot compute a remote Javadoc target", e);
            }
        }
        return null;
    }

    /**
     * The id javadoc gives an element's detail section: {@code name(erased,param,types)} for methods and constructors
     * (constructors are named {@code <init>}), the simple name for fields, null for a type (its class description).
     */
    private static String anchorIdOf(CompilationInfo info, Element element) {
        ElementKind kind = element.getKind();
        if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
            ExecutableElement ee = (ExecutableElement) element;
            List<String> erased = ee.getParameters().stream()
                    .map(p -> stripTypeAnnotations(info.getTypes().erasure(p.asType()).toString()))
                    .collect(Collectors.toList());
            return memberAnchor(kind == ElementKind.CONSTRUCTOR ? "<init>" : ee.getSimpleName().toString(), erased,
                                ee.isVarArgs());
        }
        if (kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT) {
            return element.getSimpleName().toString();
        }
        return null;
    }

    /**
     * Javadoc's member anchor, e.g. {@code format(java.lang.String,java.lang.Object...)}: erased parameter types joined
     * without spaces, and a varargs last parameter written with {@code ...} instead of {@code []}.
     */
    static String memberAnchor(String name, List<String> erasedParameterTypes, boolean varArgs) {
        List<String> params = new ArrayList<>(erasedParameterTypes);
        if (varArgs && !params.isEmpty()) {
            int last = params.size() - 1;
            String type = params.get(last);
            if (type.endsWith("[]")) {
                params.set(last, type.substring(0, type.length() - 2) + "...");
            }
        }
        return name + "(" + String.join(",", params) + ")";
    }

    /**
     * The javadoc page of a class under a Javadoc root, e.g. {@code <root>java.desktop/javax/swing/JPanel.html}. JDK 9+
     * javadoc prefixes the module; a nested class's page is {@code Outer.Inner.html}.
     */
    static String jdkPageUrl(String docRoot, String module, String packageName, String binaryClassName) {
        String root = docRoot.endsWith("/") ? docRoot : docRoot + "/";
        String classPart = packageName.isEmpty() ? binaryClassName : binaryClassName.substring(packageName.length() + 1);
        String packagePath = packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
        return root + (module != null ? module + "/" : "") + packagePath + classPart.replace('$', '.') + ".html";
    }

    /**
     * The module name in a JDK module root URL such as {@code nbjrt:file:/usr/lib/jvm/default/!/modules/java.desktop/},
     * or null for a root that is not a module root.
     */
    static String moduleFromRoot(String rootUrl) {
        int at = rootUrl.lastIndexOf("/modules/");
        if (at < 0) {
            return null;
        }
        String rest = rootUrl.substring(at + "/modules/".length());
        int slash = rest.indexOf('/');
        String module = slash >= 0 ? rest.substring(0, slash) : rest;
        return module.isEmpty() ? null : module;
    }

    /**
     * True for ElementJavadoc's own "Javadoc not found" result, recognised by its {@code id="not-found"} markup rather
     * than the (localised) message text.
     */
    static boolean isNotFoundJavadoc(String html) {
        return html == null || html.contains("id=\"not-found\"");
    }

    /**
     * Fetches the target page (cached per call, since overloads share a page) and extracts the class description or the
     * member's detail section as plain text, or null when the page or section is unavailable.
     */
    private static String remoteJavadocText(RemoteDocTarget target, Map<String, String> pageCache) {
        String page = pageCache.computeIfAbsent(target.pageUrl(), JavadocProvider::fetchRemotePage);
        if (page == null) {
            return null;
        }
        String fragment = target.anchorId() == null ? extractClassDescription(page)
                          : extractMemberDetail(page, target.anchorId());
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "GetJavadoc remote: {0} anchor {1} -> section {2}",
                    new Object[]{target.pageUrl(), target.anchorId(), fragment != null ? "found" : "not found"});
        }
        return fragment == null ? null : htmlToText(fragment);
    }

    /**
     * GETs a javadoc page with bounded timeouts. A redirect to anything but the requested page is treated as missing:
     * docs.oracle.com answers a wrong page URL with a 302 to its docs home page rather than a 404.
     */
    private static String fetchRemotePage(String pageUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(pageUrl).openConnection();
            connection.setConnectTimeout(REMOTE_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(REMOTE_READ_TIMEOUT_MILLIS);
            int status = connection.getResponseCode();
            String finalUrl = connection.getURL().toExternalForm();
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "GetJavadoc remote: GET {0} -> HTTP {1}, final URL {2}",
                        new Object[]{pageUrl, status, finalUrl});
            }
            if (status != HttpURLConnection.HTTP_OK || !finalUrl.equals(pageUrl)) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readNBytes(MAX_REMOTE_PAGE_BYTES), StandardCharsets.UTF_8);
            }
        }
        catch (IOException | ClassCastException e) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "GetJavadoc remote: GET " + pageUrl + " failed", e);
            }
            return null;
        }
        finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * The HTML of a member's {@code <section class="detail" id="...">} without its heading and signature (both already
     * printed by this tool), or null when the page has no such section.
     */
    static String extractMemberDetail(String page, String anchorId) {
        int at = page.indexOf("id=\"" + escapeHtmlAttribute(anchorId) + "\"");
        if (at < 0) {
            return null;
        }
        int open = page.indexOf('>', at);
        int close = page.indexOf("</section>", at);
        if (open < 0 || close < 0 || close < open) {
            return null;
        }
        String section = page.substring(open + 1, close).replaceFirst("(?s)<h3>.*?</h3>", "");
        int signature = section.indexOf("<div class=\"member-signature\">");
        if (signature >= 0) {
            int signatureEnd = balancedDivEnd(section, signature);
            if (signatureEnd > signature) {
                section = section.substring(0, signature) + section.substring(signatureEnd);
            }
        }
        return section;
    }

    /**
     * The first {@code <div class="block">} of the class-description section, or null when the class has none.
     */
    static String extractClassDescription(String page) {
        int at = page.indexOf("id=\"class-description\"");
        if (at < 0) {
            return null;
        }
        int block = page.indexOf("<div class=\"block\">", at);
        int nextSection = page.indexOf("<section class=", at);
        if (block < 0 || (nextSection >= 0 && nextSection < block)) {
            return null;
        }
        int end = balancedDivEnd(page, block);
        return end > block ? page.substring(block, end) : null;
    }

    /**
     * The index just past the {@code </div>} closing the div that opens at {@code start}, or -1 when it is unclosed.
     */
    static int balancedDivEnd(String html, int start) {
        int depth = 0;
        int index = start;
        while (index < html.length()) {
            int open = html.indexOf("<div", index);
            int close = html.indexOf("</div>", index);
            if (close < 0) {
                return -1;
            }
            if (open >= 0 && open < close) {
                depth++;
                index = open + "<div".length();
            }
            else {
                depth--;
                index = close + "</div>".length();
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    static String escapeHtmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String decodeNumericEntities(String text) {
        return NUMERIC_ENTITY.matcher(text).replaceAll(match -> {
            String value = match.group(1);
            try {
                int codePoint = value.startsWith("x") ? Integer.parseInt(value.substring(1), 16) : Integer.parseInt(value);
                return Matcher.quoteReplacement(new String(Character.toChars(codePoint)));
            }
            catch (IllegalArgumentException e) {
                return Matcher.quoteReplacement(match.group());
            }
        });
    }

    static String docPlaceholder(int index) {
        return DOC_PLACEHOLDER + index + "@@";
    }

    /**
     * Resolves each element's javadoc as plain text, or null when there is none. Runs after the parser task has
     * returned, so no parser lock is held while ElementJavadoc computes or while a remote page is fetched. When
     * ElementJavadoc reports "not found" and the element has a remote Javadoc page, that page is fetched directly.
     */
    private static List<String> fetchDocTexts(List<ElementJavadoc> docs, List<RemoteDocTarget> targets) {
        List<String> texts = new ArrayList<>();
        Map<String, String> pageCache = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            ElementJavadoc doc = docs.get(i);
            RemoteDocTarget target = i < targets.size() ? targets.get(i) : null;
            String html = null;
            if (doc != null) {
                try {
                    Future<String> future = doc.getTextAsync();
                    html = future != null ? future.get(DOC_TIMEOUT_SECONDS, TimeUnit.SECONDS) : null;
                }
                catch (TimeoutException e) {
                    html = "(javadoc not available within " + DOC_TIMEOUT_SECONDS + "s)";
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (ExecutionException e) {
                    html = null;
                }
            }
            if (doc != null && target != null && isNotFoundJavadoc(html)) {
                String remote = remoteJavadocText(target, pageCache);
                if (remote != null) {
                    texts.add(remote);
                    continue;
                }
            }
            texts.add(docHtmlToText(html));
        }
        return texts;
    }

    static String fillDocPlaceholders(String text, List<String> docTexts) {
        String out = text;
        for (int i = 0; i < docTexts.size(); i++) {
            String doc = docTexts.get(i);
            // Blank lines stay empty rather than carrying the two-space indent as trailing whitespace.
            String replacement = doc == null || doc.isBlank() ? ""
                                 : "\n" + doc.lines().map(line -> line.isBlank() ? "" : "  " + line)
                            .collect(Collectors.joining("\n"));
            out = out.replace(docPlaceholder(i), replacement);
        }
        return out;
    }

    /**
     * ElementJavadoc's HTML opens with the containing type and the member's signature in a {@code <pre>} block, which
     * this tool already prints; only what follows it is kept.
     */
    static String stripSignatureHeader(String html) {
        int end = html.indexOf("</pre>");
        return end >= 0 ? html.substring(end + "</pre>".length()) : html;
    }

    /**
     * ElementJavadoc's HTML as plain text: null when there is none, a short {@link #NO_JAVADOC} instead of NetBeans'
     * long "Javadoc not found ... Attach Javadoc..." text, which suggests IDE actions a tool caller cannot take.
     */
    static String docHtmlToText(String html) {
        if (html == null) {
            return null;
        }
        return isNotFoundJavadoc(html) ? NO_JAVADOC : htmlToText(stripSignatureHeader(html));
    }

    /**
     * Javadoc HTML as plain text. A {@code <dt>} heading starts a paragraph and its {@code <dd>} follows on the next
     * line; {@code <sup>} becomes {@code ^} (so 2<sup>31</sup> reads 2^31); the single leading space javadoc leaves on
     * wrapped source lines is dropped.
     */
    static String htmlToText(String html) {
        String text = html
                .replaceAll("(?i)<li[^>]*>", "\n- ")
                .replaceAll("(?i)\\s*<dt(\\s[^>]*)?>", "\n\n")
                .replaceAll("(?i)\\s*<dd(\\s[^>]*)?>", "\n")
                .replaceAll("(?i)<sup(\\s[^>]*)?>", "^")
                .replaceAll("(?i)<(br|p|blockquote)(\\s[^>]*)?/?>|</(p|pre|blockquote|dl|ul|ol)>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&#x200B;", "");
        text = decodeNumericEntities(text).replace("&amp;", "&");
        return text.replaceAll("[ \\t]+\n", "\n").replaceAll("(?m)^ (?=\\S)", "").replaceAll("\n{3,}", "\n\n").strip();
    }

    /**
     * A type as source would spell it: javac's {@code java.lang.@org.jspecify.annotations.Nullable Object} becomes
     * {@code @Nullable java.lang.Object}, also inside type arguments. Unannotated types are unchanged.
     */
    static String readableType(String type) {
        return QUALIFIED_TYPE_ANNOTATIONS.matcher(type).replaceAll(match -> Matcher.quoteReplacement(
                ANNOTATION_QUALIFIER.matcher(match.group(2)).replaceAll("@") + match.group(1)));
    }

    /**
     * A type with its type annotations removed, as javadoc's member anchors spell parameter types.
     */
    static String stripTypeAnnotations(String type) {
        return TYPE_ANNOTATION.matcher(type).replaceAll("");
    }

    /**
     * A parameter list as source spells it, with a varargs last parameter written {@code Type... name}.
     */
    static String formatParameters(List<String> types, List<String> names, boolean varArgs) {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            if (varArgs && i == types.size() - 1 && type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2) + "...";
            }
            params.add(type + " " + names.get(i));
        }
        return String.join(", ", params);
    }

    /**
     * Name a member is shown and searched under. javac names every constructor {@code <init>}; callers know it by the
     * class name, so a constructor takes its declaring type's simple name.
     */
    static String displayNameOf(ElementKind kind, String simpleName, String declaringTypeSimpleName) {
        return kind == ElementKind.CONSTRUCTOR ? declaringTypeSimpleName : simpleName;
    }

    private static Map<String, List<String>> typeParameterBounds(Parameterizable element) {
        Map<String, List<String>> bounds = new LinkedHashMap<>();
        for (TypeParameterElement tp : element.getTypeParameters()) {
            bounds.put(tp.getSimpleName().toString(),
                       tp.getBounds().stream().map(b -> readableType(b.toString())).collect(Collectors.toList()));
        }
        return bounds;
    }

    /**
     * A generic type's or method's type-parameter clause, e.g. {@code <T extends Number & Comparable<T>>}, or "" when
     * it has none. The implicit {@code java.lang.Object} bound is omitted, as it is in source.
     */
    static String formatTypeParameters(Map<String, List<String>> boundsByName) {
        if (boundsByName.isEmpty()) {
            return "";
        }
        return boundsByName.entrySet().stream().map(e -> {
            List<String> bounds = e.getValue().stream().filter(b -> !OBJECT_TYPE.equals(b)).collect(Collectors.toList());
            return bounds.isEmpty() ? e.getKey() : e.getKey() + " extends " + String.join(" & ", bounds);
        }).collect(Collectors.joining(", ", "<", ">"));
    }

    /**
     * Whether a member is listed in full. Without {@code memberName} only the class's own members are listed, so a
     * lookup on a deep hierarchy (JPanel inherits ~340 members) doesn't bury them. With it, inherited members are
     * searched too, which is how {@code ArrayList.stream} resolves to {@code Collection}. Substring matching is
     * deliberate: it lets callers search a member name fragment.
     */
    static boolean isListed(boolean ownMember, String memberName, String memberDisplayName) {
        if (memberName == null || memberName.isBlank()) {
            return ownMember;
        }
        return memberDisplayName.contains(memberName);
    }

    /**
     * One line standing in for the inherited members a lookup without {@code memberName} leaves out, or null when there
     * are none. {@code java.lang.Object}'s members are not counted: every type has them.
     */
    static String inheritedMembersSummary(Map<String, Integer> inheritedCounts) {
        if (inheritedCounts.isEmpty()) {
            return null;
        }
        int total = inheritedCounts.values().stream().mapToInt(Integer::intValue).sum();
        return "Plus " + total + " inherited public/protected member(s) from " + String.join(", ", inheritedCounts.keySet())
                + " — pass " + McpToolPropertyEnum.MEMBER_NAME.key() + " to search them.";
    }

    /**
     * Distinct member names for the not-found message: the class's own first, then inherited, capped at
     * {@link #MAX_AVAILABLE_MEMBERS}. Overloads don't repeat, and inherited names can't crowd out the class's own.
     */
    static List<String> availableMemberNames(Set<String> ownMemberNames, Set<String> inheritedMemberNames) {
        Set<String> names = new LinkedHashSet<>(ownMemberNames);
        names.addAll(inheritedMemberNames);
        return names.stream().limit(MAX_AVAILABLE_MEMBERS).collect(Collectors.toList());
    }

    static boolean hasMemberMatch(List<String> memberNames, String memberName) {
        return memberName != null && !memberName.isBlank()
                && memberNames.stream().anyMatch(name -> name.contains(memberName));
    }

    private JavadocProvider() {
    }
}
