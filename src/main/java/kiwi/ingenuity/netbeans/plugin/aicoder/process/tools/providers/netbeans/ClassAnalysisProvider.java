package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.netbeans.api.java.source.ClassIndex;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.openide.filesystems.FileObject;

public class ClassAnalysisProvider {

    public static String getClassMembers(String className) {
        if (className == null || className.isBlank()) {
            return "className is required";
        }
        String normalizedName = className.replace('$', '.');
        FileObject fo = FileUtils.locateSourceFile(normalizedName);
        if (fo == null) {
            return "Source file not found for " + normalizedName;
        }
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return "Cannot create JavaSource for " + normalizedName;
        }

        AtomicReference<String> result = new AtomicReference<>("No class found at cursor position");
        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.RESOLVED);
                TypeElement te = cc.getElements().getTypeElement(normalizedName);
                if (te == null) {
                    result.set("Type not found: " + normalizedName);
                    return;
                }
                StringBuilder sb = new StringBuilder("Members of ").append(normalizedName).append(":\n\n");
                for (Element e : te.getEnclosedElements()) {
                    ElementKind kind = e.getKind();
                    if (kind == ElementKind.INSTANCE_INIT || kind == ElementKind.STATIC_INIT) {
                        continue;
                    }
                    String modStr = e.getModifiers().stream()
                            .map(Modifier::toString).collect(Collectors.joining(" "));
                    if (!modStr.isEmpty()) {
                        modStr += " ";
                    }
                    if (e instanceof ExecutableElement ee) {
                        String params = ee.getParameters().stream()
                                .map(p -> p.asType().toString() + " " + p.getSimpleName())
                                .collect(Collectors.joining(", "));
                        String ret = kind == ElementKind.CONSTRUCTOR
                                ? "" : " : " + ee.getReturnType();
                        sb.append("  [").append(kind).append("] ")
                                .append(modStr).append(e.getSimpleName())
                                .append("(").append(params).append(")").append(ret).append("\n");
                    }
                    else {
                        sb.append("  [").append(kind).append("] ")
                                .append(modStr).append(e.getSimpleName())
                                .append(" : ").append(e.asType()).append("\n");
                    }
                }
                result.set(sb.toString());
            }, true);
        }
        catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        return result.get();
    }

    public static String getTypeHierarchy(String className) {
        if (className == null || className.isBlank()) {
            return "className is required";
        }
        String normalizedName = className.replace('$', '.');
        FileObject fo = FileUtils.locateSourceFile(normalizedName);
        if (fo == null) {
            return "Source file not found for " + normalizedName;
        }
        JavaSource js = JavaSource.forFileObject(fo);
        if (js == null) {
            return "Cannot create JavaSource for " + normalizedName;
        }

        AtomicReference<ElementHandle<TypeElement>> handleRef = new AtomicReference<>();
        StringBuilder sb = new StringBuilder("Type hierarchy for ").append(normalizedName).append(":\n\n");

        try {
            js.runUserActionTask(cc -> {
                cc.toPhase(JavaSource.Phase.RESOLVED);
                TypeElement te = cc.getElements().getTypeElement(normalizedName);
                if (te == null) {
                    return;
                }
                handleRef.set(ElementHandle.create(te));

                // Supertype chain (stop before Object)
                List<String> chain = new ArrayList<>();
                TypeMirror sup = te.getSuperclass();
                while (sup != null && sup.getKind() == TypeKind.DECLARED) {
                    TypeElement supEl = (TypeElement) ((DeclaredType) sup).asElement();
                    String fqn = supEl.getQualifiedName().toString();
                    if ("java.lang.Object".equals(fqn)) {
                        break;
                    }
                    chain.add(fqn);
                    sup = supEl.getSuperclass();
                }
                if (!chain.isEmpty()) {
                    sb.append("Extends:\n");
                    for (String s : chain) {
                        sb.append("  ← ").append(s).append("\n");
                    }
                    sb.append("\n");
                }

                List<? extends TypeMirror> ifaces = te.getInterfaces();
                if (!ifaces.isEmpty()) {
                    sb.append("Implements:\n");
                    for (TypeMirror iface : ifaces) {
                        if (iface.getKind() != TypeKind.DECLARED) {
                            continue;
                        }
                        TypeElement ifEl = (TypeElement) ((DeclaredType) iface).asElement();
                        sb.append("  → ").append(ifEl.getQualifiedName()).append("\n");
                    }
                    sb.append("\n");
                }
            }, true);
        }
        catch (IOException e) {
            return "Error: " + e.getMessage();
        }

        ElementHandle<TypeElement> handle = handleRef.get();
        if (handle == null) {
            return "Type not found: " + normalizedName;
        }

        // Subtypes via ClassIndex.getElements() — returns type handles, not files
        ClassIndex ci = js.getClasspathInfo().getClassIndex();
        Set<ElementHandle<TypeElement>> subtypes = ci.getElements(
                handle,
                EnumSet.of(ClassIndex.SearchKind.IMPLEMENTORS),
                EnumSet.of(ClassIndex.SearchScope.SOURCE));

        if (subtypes != null && !subtypes.isEmpty()) {
            sb.append("Known subtypes (").append(subtypes.size()).append("):\n");
            for (ElementHandle<TypeElement> sub : subtypes) {
                sb.append("  → ").append(sub.getQualifiedName()).append("\n");
            }
        }
        else {
            sb.append("No known subtypes in source.\n");
        }

        return sb.toString();
    }

    private ClassAnalysisProvider() {
    }
}
