package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which unresolved simple names {@code FixImports} may import, and the message it returns.
 * <p>
 * No NetBeans types and no dialogs. Callers supply the classpath candidates and how the name is used; this
 * class drops candidates that cannot fit that use, ranks whoever is left, and imports a name only when one
 * candidate remains — or, when {@code pickBest} is set, when one candidate ranks strictly above the rest. A
 * tie is never broken by list order. Names that do resolve are collected even when other names in the same
 * list stay ambiguous or unresolved, so one doubtful name cannot roll the others back.
 */
public final class ImportCandidatePlanner {

    public enum TypeKind {
        CLASS, INTERFACE, ANNOTATION, ENUM, RECORD, UNKNOWN
    }

    public enum Status {
        ADDED, PICKED, AMBIGUOUS, UNRESOLVED, NOT_NEEDED
    }

    /**
     * How one simple name is used in the file. Constraints from every use are combined: a candidate must
     * satisfy all of them. {@code requiredTypeArgumentCount} is null when the name is not used with type
     * arguments. {@code contradictoryTypeArgumentCounts} means two uses demand different counts, so nothing
     * can fit. {@code memberNames} are names selected on this type ({@code Outer.Inner} or {@code Type.FOO}):
     * nested types, static or not, and static members.
     */
    public record Usage(
            boolean constructed,
            boolean annotation,
            boolean extended,
            boolean implemented,
            Integer requiredTypeArgumentCount,
            boolean contradictoryTypeArgumentCounts,
            Set<String> memberNames) {

        public Usage {
            memberNames = memberNames == null ? Set.of() : Set.copyOf(memberNames);
        }

        public static Usage none() {
            return new Usage(false, false, false, false, null, false, Set.of());
        }

        public static Usage ofConstructed() {
            return new Usage(true, false, false, false, null, false, Set.of());
        }

        public static Usage annotationUse() {
            return new Usage(false, true, false, false, null, false, Set.of());
        }

        public static Usage ofExtended() {
            return new Usage(false, false, true, false, null, false, Set.of());
        }

        public static Usage ofImplemented() {
            return new Usage(false, false, false, true, null, false, Set.of());
        }

        public static Usage generic(int typeArgumentCount) {
            return new Usage(false, false, false, false, typeArgumentCount, false, Set.of());
        }

        /**
         * A name selected on this type. Covers a nested type ({@code new Outer.Inner()} or
         * {@code Outer.Inner}) whether or not that nested type is static, and a static member
         * ({@code Type.FOO}).
         */
        public static Usage member(String name) {
            return new Usage(false, false, false, false, null, false, Set.of(name));
        }

        public Usage merge(Usage other) {
            if (other == null) {
                return this;
            }
            Integer count = requiredTypeArgumentCount;
            boolean conflict = contradictoryTypeArgumentCounts || other.contradictoryTypeArgumentCounts;
            if (other.requiredTypeArgumentCount != null) {
                if (count == null) {
                    count = other.requiredTypeArgumentCount;
                }
                else if (!count.equals(other.requiredTypeArgumentCount)) {
                    conflict = true;
                }
            }
            LinkedHashMap<String, Boolean> members = new LinkedHashMap<>();
            memberNames.forEach(name -> members.put(name, Boolean.TRUE));
            other.memberNames.forEach(name -> members.put(name, Boolean.TRUE));
            return new Usage(constructed || other.constructed,
                    annotation || other.annotation,
                    extended || other.extended,
                    implemented || other.implemented,
                    count,
                    conflict,
                    members.keySet());
        }
    }

    /**
     * One classpath type that has the simple name being resolved. {@code typeParameterCount} is null when the
     * type could not be inspected. {@code memberNamesKnown} is false when nested types and static members
     * were not inspected; an unknown fact never drops the candidate. {@code memberNames} lists nested types
     * (static or not) and static members when they were inspected.
     */
    public record Candidate(
            String qualifiedName,
            String packageName,
            TypeKind kind,
            boolean abstractType,
            Integer typeParameterCount,
            boolean memberNamesKnown,
            Set<String> memberNames,
            boolean projectSource) {

        public Candidate {
            packageName = packageName == null ? "" : packageName;
            kind = kind == null ? TypeKind.UNKNOWN : kind;
            memberNames = memberNames == null ? Set.of() : Set.copyOf(memberNames);
        }
    }

    /**
     * {@code importedPackages} are packages the file already names in an import, used only to rank.
     * {@code onDemandPackages} are non-static star imports ({@code import java.util.*}), which already make
     * every type in that package visible. The file's own package is visible without an import.
     */
    public record FileFit(String filePackage, Set<String> importedPackages, Set<String> onDemandPackages) {

        public FileFit(String filePackage, Set<String> importedPackages) {
            this(filePackage, importedPackages, Set.of());
        }

        public FileFit {
            filePackage = filePackage == null ? "" : filePackage;
            importedPackages = importedPackages == null ? Set.of() : Set.copyOf(importedPackages);
            onDemandPackages = onDemandPackages == null ? Set.of() : Set.copyOf(onDemandPackages);
        }
    }

    /**
     * A surviving candidate in best-first order. {@code reason} is the strongest preference that applies, or
     * null when the candidate is only still here because nothing ruled it out.
     */
    public record Ranked(String qualifiedName, String reason) {

    }

    public record Decision(
            String simpleName,
            Status status,
            String importedQualifiedName,
            List<Ranked> ranked,
            int candidateCount,
            String hint,
            String unresolvedReason,
            boolean hadCandidates) {

        public Decision {
            ranked = ranked == null ? List.of() : List.copyOf(ranked);
        }
    }

    private ImportCandidatePlanner() {
    }

    /**
     * Drops candidates that cannot be what this use site needs. A candidate whose kind or member list is
     * unknown is kept: missing information is not evidence against it. Contradictory constraints (two
     * different type-argument counts, or both {@code extends} and {@code implements}) drop everyone.
     */
    public static List<Candidate> filterByUsage(Usage usage, List<Candidate> candidates) {
        if (usage == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (usage.contradictoryTypeArgumentCounts()) {
            return List.of();
        }
        List<Candidate> kept = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (fits(usage, candidate)) {
                kept.add(candidate);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Best first. A package the file already imports, and the file's own package, rank above everything else
     * and tie with each other. A project source type then ranks above a library type. Equal scores stay tied
     * and are ordered by qualified name only so the message is stable — that order must not be treated as a
     * preference.
     */
    public static List<Ranked> rank(List<Candidate> candidates, FileFit file) {
        FileFit fit = file == null ? new FileFit("", Set.of()) : file;
        List<Ranked> ranked = new ArrayList<>();
        for (Candidate candidate : sort(candidates, fit)) {
            ranked.add(new Ranked(candidate.qualifiedName(), reason(candidate, fit)));
        }
        return List.copyOf(ranked);
    }

    /**
     * @param pickBest when false, more than one surviving candidate is ambiguous and nothing is imported for
     *                 that name. When true, the unique top-ranked candidate is imported. Equal ranks are a
     *                 tie either way.
     */
    public static Decision decide(String simpleName, Usage usage, List<Candidate> candidates, FileFit file, boolean pickBest) {
        Usage use = usage == null ? Usage.none() : usage;
        FileFit fit = file == null ? new FileFit("", Set.of()) : file;
        List<Candidate> distinct = dedupe(candidates);
        if (distinct.isEmpty()) {
            return unresolved(simpleName, "no matching type on the classpath", false);
        }
        List<Candidate> fitting = filterByUsage(use, distinct);
        if (fitting.isEmpty()) {
            return unresolved(simpleName, "no candidate fits how it is used", true);
        }
        if (fitting.size() == 1) {
            if (alreadyInScope(fitting.get(0), fit)) {
                return notNeeded(simpleName);
            }
            return added(simpleName, fitting.get(0).qualifiedName());
        }
        List<Ranked> ranked = rank(fitting, fit);
        if (pickBest && uniqueTop(fitting, fit)) {
            Candidate top = candidateNamed(fitting, ranked.get(0).qualifiedName());
            if (alreadyInScope(top, fit)) {
                return notNeeded(simpleName);
            }
            return picked(simpleName, ranked.get(0).qualifiedName(), ranked.size());
        }
        String hint = uniqueTop(fitting, fit)
                      ? "add the one you mean with ApplyEdit, or call again with pickBest=true"
                      : "tied, so pickBest cannot choose either; add the one you mean with ApplyEdit";
        return ambiguous(simpleName, ranked, hint);
    }

    /**
     * Qualified names to import, in input order. An ambiguous or unresolved sibling does not remove the
     * others.
     */
    public static List<String> importsToApply(List<Decision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        List<String> imports = new ArrayList<>();
        for (Decision decision : decisions) {
            if (decision.status() == Status.ADDED || decision.status() == Status.PICKED) {
                imports.add(decision.importedQualifiedName());
            }
        }
        return List.copyOf(imports);
    }

    /**
     * Package a file-fit should remember for one import. The resolved package wins: an import of the nested
     * type {@code com.acme.Outer.Helper} belongs to {@code com.acme}, and stripping the last name would
     * record {@code com.acme.Outer}. The text parse is only the fallback when the import does not resolve.
     */
    public static String importedPackage(String resolvedPackage, String qualifiedText, boolean staticImport) {
        if (resolvedPackage != null && !resolvedPackage.isBlank()) {
            return resolvedPackage;
        }
        return packageFromQualifiedImport(qualifiedText, staticImport);
    }

    private static String packageFromQualifiedImport(String qualifiedText, boolean staticImport) {
        if (qualifiedText == null || qualifiedText.isBlank()) {
            return null;
        }
        String owner = qualifiedText;
        if (owner.endsWith(".*")) {
            owner = owner.substring(0, owner.length() - 2);
        }
        else {
            int dot = owner.lastIndexOf('.');
            if (dot <= 0) {
                return null;
            }
            owner = owner.substring(0, dot);
        }
        if (staticImport) {
            int dot = owner.lastIndexOf('.');
            if (dot <= 0) {
                return null;
            }
            owner = owner.substring(0, dot);
        }
        return owner.isBlank() ? null : owner;
    }

    /**
     * @param errorsWithoutNames true when this file had compile errors but no unresolved type name could be
     *                           read from them. That must not be reported as "No missing imports."
     */
    public static String format(List<Decision> decisions, boolean errorsWithoutNames) {
        if (decisions == null || decisions.isEmpty()) {
            return errorsWithoutNames
                   ? "The file has compile errors, but no unresolved type name could be read from them."
                   : "No missing imports.";
        }
        List<String> added = new ArrayList<>();
        List<Decision> picked = new ArrayList<>();
        List<Decision> ambiguous = new ArrayList<>();
        List<Decision> unresolved = new ArrayList<>();
        for (Decision decision : decisions) {
            switch (decision.status()) {
                case ADDED ->
                    added.add(decision.importedQualifiedName());
                case PICKED -> {
                    added.add(decision.importedQualifiedName());
                    picked.add(decision);
                }
                case AMBIGUOUS ->
                    ambiguous.add(decision);
                case UNRESOLVED ->
                    unresolved.add(decision);
                case NOT_NEEDED -> {
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!added.isEmpty()) {
            sb.append("Added ").append(added.size()).append(added.size() == 1 ? " import: " : " imports: ");
            sb.append(String.join(", ", added)).append('.');
        }
        for (Decision decision : picked) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(decision.simpleName()).append(" -> ").append(decision.importedQualifiedName())
                    .append(" (picked best of ").append(decision.candidateCount()).append(").");
        }
        if (!ambiguous.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("Could not decide ").append(ambiguous.size()).append(": ");
            for (int i = 0; i < ambiguous.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                Decision decision = ambiguous.get(i);
                sb.append(decision.simpleName()).append(" \u2014 ");
                sb.append(joinRanked(decision.ranked()));
                sb.append(" (").append(decision.hint() == null ? "" : decision.hint()).append(')');
            }
            sb.append('.');
        }
        if (!unresolved.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("Unresolved ").append(unresolved.size()).append(": ");
            for (int i = 0; i < unresolved.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Decision decision = unresolved.get(i);
                sb.append(decision.simpleName()).append(" (")
                        .append(decision.unresolvedReason() == null ? "unresolved" : decision.unresolvedReason())
                        .append(')');
            }
            sb.append('.');
        }
        if (sb.isEmpty()) {
            return errorsWithoutNames
                   ? "The file has compile errors, but no unresolved type name could be read from them."
                   : "No missing imports.";
        }
        return sb.toString();
    }

    private static boolean fits(Usage usage, Candidate candidate) {
        if (usage.annotation() && (usage.extended() || usage.implemented() || usage.constructed())) {
            return false;
        }
        if (usage.extended() && usage.implemented()) {
            return false;
        }
        if (usage.annotation() && candidate.kind() != TypeKind.ANNOTATION && candidate.kind() != TypeKind.UNKNOWN) {
            return false;
        }
        if (usage.extended() && candidate.kind() != TypeKind.CLASS && candidate.kind() != TypeKind.UNKNOWN) {
            return false;
        }
        if (usage.implemented() && candidate.kind() != TypeKind.INTERFACE && candidate.kind() != TypeKind.UNKNOWN) {
            return false;
        }
        if (usage.constructed()) {
            if (candidate.abstractType()) {
                return false;
            }
            switch (candidate.kind()) {
                case INTERFACE, ANNOTATION, ENUM -> {
                    return false;
                }
                case RECORD -> {
                    if (usage.extended()) {
                        return false;
                    }
                }
                case CLASS, UNKNOWN -> {
                }
            }
        }
        if (usage.requiredTypeArgumentCount() != null
            && candidate.typeParameterCount() != null
            && !usage.requiredTypeArgumentCount().equals(candidate.typeParameterCount())) {
            return false;
        }
        if (!usage.memberNames().isEmpty()
            && candidate.memberNamesKnown()
            && !candidate.memberNames().containsAll(usage.memberNames())) {
            return false;
        }
        return true;
    }

    private static List<Candidate> dedupe(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Candidate> byName = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.qualifiedName() == null || candidate.qualifiedName().isBlank()) {
                continue;
            }
            byName.putIfAbsent(candidate.qualifiedName(), candidate);
        }
        return List.copyOf(byName.values());
    }

    private static List<Candidate> sort(List<Candidate> candidates, FileFit file) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            int byScore = Integer.compare(score(b, file), score(a, file));
            if (byScore != 0) {
                return byScore;
            }
            return a.qualifiedName().compareTo(b.qualifiedName());
        });
        return sorted;
    }

    private static boolean uniqueTop(List<Candidate> candidates, FileFit file) {
        List<Candidate> sorted = sort(candidates, file);
        return score(sorted.get(0), file) > score(sorted.get(1), file);
    }

    /**
     * Own package and an already-imported package are the same strength. Project source is a weaker extra
     * point, so it breaks a tie between two package matches and loses to a package match on its own.
     */
    private static int score(Candidate candidate, FileFit file) {
        int score = 0;
        String pkg = candidate.packageName();
        if (pkg.equals(file.filePackage())) {
            score += 2;
        }
        else if (file.importedPackages().contains(pkg)) {
            score += 2;
        }
        if (candidate.projectSource()) {
            score += 1;
        }
        return score;
    }

    private static String reason(Candidate candidate, FileFit file) {
        if (candidate.packageName().equals(file.filePackage())) {
            return "file's own package";
        }
        if (file.importedPackages().contains(candidate.packageName())) {
            return "package already imported";
        }
        if (candidate.projectSource()) {
            return "project source";
        }
        return null;
    }

    private static String joinRanked(List<Ranked> ranked) {
        List<String> parts = new ArrayList<>();
        for (Ranked one : ranked) {
            if (one.reason() == null || one.reason().isBlank()) {
                parts.add(one.qualifiedName());
            }
            else {
                parts.add(one.qualifiedName() + " (" + one.reason() + ")");
            }
        }
        return String.join(", ", parts);
    }

    /**
     * A type in the file's own package, or in a non-static star import, is already visible. Writing an import
     * for it is a no-op, so it is not reported as added and it is not reported as missing.
     */
    static boolean alreadyInScope(Candidate candidate, FileFit file) {
        if (candidate == null || file == null) {
            return false;
        }
        String pkg = candidate.packageName();
        if (pkg.isEmpty()) {
            return false;
        }
        return pkg.equals(file.filePackage()) || file.onDemandPackages().contains(pkg);
    }

    private static Candidate candidateNamed(List<Candidate> candidates, String qualifiedName) {
        for (Candidate candidate : candidates) {
            if (qualifiedName.equals(candidate.qualifiedName())) {
                return candidate;
            }
        }
        return null;
    }

    private static Decision added(String simpleName, String qualifiedName) {
        return new Decision(simpleName, Status.ADDED, qualifiedName, List.of(), 1, null, null, true);
    }

    private static Decision picked(String simpleName, String qualifiedName, int of) {
        return new Decision(simpleName, Status.PICKED, qualifiedName, List.of(), of, null, null, true);
    }

    private static Decision ambiguous(String simpleName, List<Ranked> ranked, String hint) {
        return new Decision(simpleName, Status.AMBIGUOUS, null, ranked, ranked.size(), hint, null, true);
    }

    private static Decision notNeeded(String simpleName) {
        return new Decision(simpleName, Status.NOT_NEEDED, null, List.of(), 0, null, null, false);
    }

    private static Decision unresolved(String simpleName, String reason, boolean hadCandidates) {
        return new Decision(simpleName, Status.UNRESOLVED, null, List.of(), 0, null, reason, hadCandidates);
    }
}
