package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

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
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.JavaSource;
import org.openide.filesystems.FileObject;

public class JavadocProvider {

    private static final int MAX_AVAILABLE_MEMBERS = 50;

    public static String getJavadoc(String className, String memberName) {
        if (className == null || className.isBlank()) {
            return "className is required";
        }
        FileObject sampleFo = FileUtils.findProjectSourceFile();
        if (sampleFo == null) {
            return "No Java source file found in open project";
        }

        ClasspathInfo cpInfo = ClasspathInfo.create(sampleFo);
        JavaSource js = JavaSource.create(cpInfo);
        if (js == null) {
            return "Cannot create JavaSource for project classpath";
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
