package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which imports {@code OrganiseImports} removes, the order the rest are written in, and the message
 * it returns.
 * <p>
 * No NetBeans types. The caller scans the file and supplies what each import names and what the code and its
 * javadoc reference; this class applies the rules. An import is removed only when it is provably unused:
 * <ul>
 * <li>A reference in a javadoc comment keeps an import, so an import used only by a {@code {@link}} or
 * {@code @throws} survives.</li>
 * <li>When the file has compile errors, an unresolved name might be exactly what an import is for. Then an
 * on-demand import is never removed, an import that does not resolve is never removed, and a single import is
 * removed only when its simple name appears nowhere in the file.</li>
 * <li>When the caller could not work out what an import brings in ({@code null} scope), it is kept.</li>
 * </ul>
 * The imports that remain are ordered by import group, then by the qualified name as text.
 */
public final class ImportOrganisePlanner {

    /**
     * One import as written. {@code name} is the qualified identifier, ending in {@code .*} for an on-demand
     * import. {@code groupId} is the position of the import's group in the user's code style.
     * <p>
     * {@code resolvedName} is the canonical qualified name of the type a single non-static import names, or
     * null when it does not resolve. {@code scopeOwners} are the qualified names whose members the import can
     * bring in: the package for a package import, or the type and all its supertypes for a type import or a
     * static import. Null means unknown. {@code memberNames} are the simple names of those members, used to
     * match javadoc references by name; null means unknown.
     */
    public record ImportDecl(
            String name,
            boolean isStatic,
            int groupId,
            String resolvedName,
            Set<String> scopeOwners,
            Set<String> memberNames) {

        public ImportDecl {
            scopeOwners = scopeOwners == null ? null : Set.copyOf(scopeOwners);
            memberNames = memberNames == null ? null : Set.copyOf(memberNames);
        }

        public boolean isOnDemand() {
            return name.endsWith(".*");
        }

        /**
         * The simple name a single import introduces, or the member name of a single static import.
         */
        public String simpleName() {
            return lastSegment(name);
        }

        public String display() {
            return isStatic ? "static " + name : name;
        }
    }

    /**
     * What the file refers to. {@code typeRefs} are canonical qualified names of types named by a simple
     * identifier. {@code staticRefs} are static members named by a simple identifier, written
     * {@code ownerQualifiedName#memberName}. {@code names} is every simple identifier in the code, resolved
     * or not. {@code docNames} is every identifier in a javadoc reference.
     */
    public record Usage(
            Set<String> typeRefs,
            Set<String> staticRefs,
            Set<String> names,
            Set<String> docNames,
            boolean hasErrors) {

        public Usage {
            typeRefs = typeRefs == null ? Set.of() : Set.copyOf(typeRefs);
            staticRefs = staticRefs == null ? Set.of() : Set.copyOf(staticRefs);
            names = names == null ? Set.of() : Set.copyOf(names);
            docNames = docNames == null ? Set.of() : Set.copyOf(docNames);
        }

        public static String staticRef(String ownerQualifiedName, String memberName) {
            return ownerQualifiedName + "#" + memberName;
        }
    }

    /**
     * {@code kept} is in the order it is written. {@code reordered} is true when that order differs from the
     * order the kept imports had in the file; removing an import alone is not a reorder.
     */
    public record Plan(List<ImportDecl> kept, List<ImportDecl> removed, boolean reordered) {

        public Plan {
            kept = List.copyOf(kept);
            removed = List.copyOf(removed);
        }

        public boolean changesAnything() {
            return !removed.isEmpty() || reordered;
        }
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private ImportOrganisePlanner() {
    }

    public static Plan plan(List<ImportDecl> imports, Usage usage) {
        List<ImportDecl> kept = new ArrayList<>();
        List<ImportDecl> removed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ImportDecl imp : imports) {
            if (!seen.add(imp.isStatic() + " " + imp.name())) {
                removed.add(imp);
            }
            else if (isUsed(imp, usage)) {
                kept.add(imp);
            }
            else {
                removed.add(imp);
            }
        }
        List<ImportDecl> sorted = new ArrayList<>(kept);
        sorted.sort(Comparator.comparingInt(ImportDecl::groupId).thenComparing(ImportDecl::name));
        return new Plan(sorted, removed, !sorted.equals(kept));
    }

    static boolean isUsed(ImportDecl imp, Usage usage) {
        return imp.isStatic() ? isStaticImportUsed(imp, usage) : isTypeImportUsed(imp, usage);
    }

    private static boolean isTypeImportUsed(ImportDecl imp, Usage usage) {
        if (imp.isOnDemand()) {
            if (usage.hasErrors() || imp.scopeOwners() == null) {
                return true;
            }
            for (String type : usage.typeRefs()) {
                if (imp.scopeOwners().contains(qualifierOf(type))) {
                    return true;
                }
            }
            return docNameMatches(imp, usage);
        }
        if (imp.resolvedName() == null) {
            return true;
        }
        if (usage.typeRefs().contains(imp.resolvedName()) || usage.docNames().contains(imp.simpleName())) {
            return true;
        }
        return usage.hasErrors() && usage.names().contains(imp.simpleName());
    }

    private static boolean isStaticImportUsed(ImportDecl imp, Usage usage) {
        if (imp.scopeOwners() == null) {
            return true;
        }
        if (imp.isOnDemand()) {
            if (usage.hasErrors()) {
                return true;
            }
            for (String ref : usage.staticRefs()) {
                if (imp.scopeOwners().contains(ownerOf(ref))) {
                    return true;
                }
            }
            for (String type : usage.typeRefs()) {
                if (imp.scopeOwners().contains(qualifierOf(type))) {
                    return true;
                }
            }
            return docNameMatches(imp, usage);
        }
        String member = imp.simpleName();
        if (usage.docNames().contains(member) || (usage.hasErrors() && usage.names().contains(member))) {
            return true;
        }
        for (String ref : usage.staticRefs()) {
            if (member.equals(memberOf(ref)) && imp.scopeOwners().contains(ownerOf(ref))) {
                return true;
            }
        }
        for (String type : usage.typeRefs()) {
            if (member.equals(lastSegment(type)) && imp.scopeOwners().contains(qualifierOf(type))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A javadoc reference is matched by name, because a reference to {@code Type#member} does not say which
     * import supplied {@code Type}. A name match can only keep an import, never remove one.
     */
    private static boolean docNameMatches(ImportDecl imp, Usage usage) {
        if (usage.docNames().isEmpty()) {
            return false;
        }
        if (imp.memberNames() == null) {
            return true;
        }
        for (String name : usage.docNames()) {
            if (imp.memberNames().contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The names to treat as present when the file has compile errors. A parse error can leave part of the
     * file without identifier trees, so the scanned names are not proof of absence: every word in the file
     * text outside the import statements counts too. {@code importSpans} are {@code [start, end)} offsets of
     * the import statements, which are blanked so an import does not count as its own use.
     */
    public static Set<String> namesWithTextWords(Set<String> scannedNames, String text, List<int[]> importSpans) {
        Set<String> names = new LinkedHashSet<>(scannedNames);
        if (text == null || text.isEmpty()) {
            return names;
        }
        StringBuilder masked = new StringBuilder(text);
        for (int[] span : importSpans) {
            int start = Math.max(0, span[0]);
            int end = Math.min(masked.length(), span[1]);
            for (int i = start; i < end; i++) {
                masked.setCharAt(i, ' ');
            }
        }
        Matcher word = WORD.matcher(masked);
        while (word.find()) {
            names.add(word.group());
        }
        return names;
    }

    /**
     * The message the tool returns.
     */
    public static String format(Plan plan, boolean hasErrors) {
        StringBuilder sb = new StringBuilder();
        List<ImportDecl> removed = plan.removed();
        if (!removed.isEmpty()) {
            sb.append("Removed ").append(removed.size()).append(removed.size() == 1 ? " unused import: " : " unused imports: ");
            for (int i = 0; i < removed.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(removed.get(i).display());
            }
            sb.append('.');
        }
        if (plan.reordered()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("Imports reordered.");
        }
        if (sb.isEmpty()) {
            sb.append("Imports already organised.");
        }
        if (hasErrors) {
            sb.append(" The file has compile errors, so only imports that are provably unused were removed.");
        }
        return sb.toString();
    }

    private static String qualifierOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }

    private static String lastSegment(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    private static String ownerOf(String staticRef) {
        int hash = staticRef.indexOf('#');
        return hash < 0 ? staticRef : staticRef.substring(0, hash);
    }

    private static String memberOf(String staticRef) {
        int hash = staticRef.indexOf('#');
        return hash < 0 ? "" : staticRef.substring(hash + 1);
    }
}
