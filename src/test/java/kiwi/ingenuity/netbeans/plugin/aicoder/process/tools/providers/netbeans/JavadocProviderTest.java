package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class JavadocProviderTest {

    @Test
    void missingMemberMessageNamesRequestedAndAvailableMembers() {
        String result = JavadocProvider.memberNotFoundMessage("example.Type", "missingMember",
                                                              List.of("presentMember", "otherMember"), new HashSet<>());

        assertEquals("No member matching 'missingMember' found on example.Type\n"
                + "Available public/protected members: presentMember, otherMember", result);
    }

    @Test
    void matchingOrBlankMemberDoesNotProduceMissingMessage() {
        assertTrue(JavadocProvider.hasMemberMatch(List.of("getSessionId"), "Session"));
        assertNull(JavadocProvider.memberNotFoundMessage("example.Type", "", List.of("presentMember"), new HashSet<>()));
        assertNull(JavadocProvider.memberNotFoundMessage("example.Type", null, List.of("presentMember"), new HashSet<>()));
    }

    @Test
    void availableMemberListIsCappedAtFifty() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            if (names.size() < 50) {
                names.add("member" + i);
            }
        }
        String result = JavadocProvider.memberNotFoundMessage("example.Type", "missing", names, new HashSet<>());

        assertEquals(50, result.substring(result.indexOf(": ") + 2).split(", ").length);
        assertFalse(result.contains("member50"));
    }

    @Test
    void missingMemberMessageIncludesSearchedTypes() {
        Set<String> declaredTypes = new HashSet<>();
        declaredTypes.add("example.Type");
        declaredTypes.add("example.BaseType");
        String result = JavadocProvider.memberNotFoundMessage("example.Type", "missingMember",
                                                              List.of("presentMember"), declaredTypes);

        assertTrue(result.contains("No member matching 'missingMember' found on example.Type"));
        assertTrue(result.contains("Searched types:"));
        assertTrue(result.contains("example.Type"));
        assertTrue(result.contains("example.BaseType"));
        assertTrue(result.contains("Available public/protected members: presentMember"));
    }

    @Test
    void withoutMemberNameOnlyOwnMembersAreListed() {
        // Live v1.4.8: GetJavadoc javax.swing.JPanel with no memberName listed ~350 inherited members before its own.
        assertTrue(JavadocProvider.isListed(true, null, "updateUI"));
        assertFalse(JavadocProvider.isListed(false, null, "getName"));
        assertFalse(JavadocProvider.isListed(false, "  ", "getName"));
    }

    @Test
    void memberSearchIncludesInheritedMembers() {
        assertTrue(JavadocProvider.isListed(false, "stream", "stream"));
        assertTrue(JavadocProvider.isListed(true, "UI", "updateUI"));
        assertFalse(JavadocProvider.isListed(true, "stream", "size"));
    }

    @Test
    void inheritedMembersSummaryTotalsAndNamesTheTypes() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("javax.swing.JComponent", 300);
        counts.put("java.awt.Container", 40);

        assertEquals("Plus 340 inherited public/protected member(s) from javax.swing.JComponent, java.awt.Container"
                + " — pass memberName to search them.", JavadocProvider.inheritedMembersSummary(counts));
        assertNull(JavadocProvider.inheritedMembersSummary(new LinkedHashMap<>()));
    }

    @Test
    void availableMemberNamesPutOwnFirstWithoutDuplicatesAndCapAtFifty() {
        Set<String> own = new LinkedHashSet<>(List.of("getUI", "setUI"));
        Set<String> inherited = new LinkedHashSet<>();
        inherited.add("getUI");
        for (int i = 0; i < 100; i++) {
            inherited.add("inherited" + i);
        }

        List<String> names = JavadocProvider.availableMemberNames(own, inherited);

        assertEquals(List.of("getUI", "setUI"), names.subList(0, 2));
        assertEquals(1, Collections.frequency(names, "getUI"));
        assertEquals(50, names.size());
    }

    @Test
    void constructorsAreShownUnderTheClassNameNotInit() {
        // Live v1.4.9: JPanel's constructors printed as "public <init>(...)".
        assertEquals("JPanel", JavadocProvider.displayNameOf(javax.lang.model.element.ElementKind.CONSTRUCTOR, "<init>", "JPanel"));
        assertEquals("updateUI", JavadocProvider.displayNameOf(javax.lang.model.element.ElementKind.METHOD, "updateUI", "JPanel"));
    }

    @Test
    void typeParameterClauseOmitsImplicitObjectBound() {
        // Live v1.4.9: ApprovalDeadline.arm printed "void arm(CompletableFuture<T> ...)" with no <T>.
        Map<String, List<String>> simple = new LinkedHashMap<>();
        simple.put("T", List.of("java.lang.Object"));
        assertEquals("<T>", JavadocProvider.formatTypeParameters(simple));

        Map<String, List<String>> bounded = new LinkedHashMap<>();
        bounded.put("K", List.of("java.lang.Object"));
        bounded.put("V", List.of("java.lang.Number", "java.lang.Comparable<V>"));
        assertEquals("<K, V extends java.lang.Number & java.lang.Comparable<V>>", JavadocProvider.formatTypeParameters(bounded));

        assertEquals("", JavadocProvider.formatTypeParameters(new LinkedHashMap<>()));
    }

    @Test
    void signatureHeaderIsStrippedAndHtmlBecomesPlainText() {
        String html = "<font size='+0'><b>kiwi.&#x200B;Example</b></font><pre>public <b>toIdePath</b>(File f)</pre>"
                + "<p>Returns the IDE&nbsp;spelling &amp; never <code>null</code>.</p>"
                + "<p><b>Parameters:</b><blockquote><code>f</code> - inbound file</blockquote>"
                + "<ul><li>first</li><li>a &lt;T&gt; value</li></ul>";

        assertEquals("Returns the IDE spelling & never null.\n\nParameters:\nf - inbound file\n\n- first\n- a <T> value",
                     JavadocProvider.htmlToText(JavadocProvider.stripSignatureHeader(html)));
        assertEquals("no header", JavadocProvider.stripSignatureHeader("no header"));
    }

    @Test
    void docPlaceholdersAreFilledIndentedOrRemoved() {
        String text = "sig0" + JavadocProvider.docPlaceholder(0) + "\n\nsig1" + JavadocProvider.docPlaceholder(1)
                + "\n\nsig10" + JavadocProvider.docPlaceholder(10);
        List<String> docs = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            docs.add(null);
        }
        docs.set(0, "line one\n\nline two");
        docs.set(10, "ten");

        // Live v1.4.10: blank lines inside an indented doc carried the two-space indent as trailing whitespace.
        assertEquals("sig0\n  line one\n\n  line two\n\nsig1\n\nsig10\n  ten", JavadocProvider.fillDocPlaceholders(text, docs));
    }

    // Fixture shapes below are copied from the live JDK 21 page
    // https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/JPanel.html (fetched 2026-09-15).
    private static final String MEMBER_DETAILS = "<ul class=\"member-list\">\n<li>\n"
            + "<section class=\"detail\" id=\"&lt;init&gt;(boolean)\">\n<h3>JPanel</h3>\n"
            + "<div class=\"member-signature\"><span class=\"modifiers\">public</span>&nbsp;"
            + "<span class=\"element-name\">JPanel</span>(boolean&nbsp;isDoubleBuffered)</div>\n"
            + "<div class=\"block\">Creates a new <code>JPanel</code> with <code>FlowLayout</code>.</div>\n"
            + "</section>\n</li>\n<li>\n"
            + "<section class=\"detail\" id=\"updateUI()\">\n<h3>updateUI</h3>\n"
            + "<div class=\"member-signature\"><span class=\"modifiers\">public</span>&nbsp;"
            + "<span class=\"return-type\">void</span>&nbsp;<span class=\"element-name\">updateUI</span>()</div>\n"
            + "<div class=\"block\">Resets the UI property with a value from the current look and feel.</div>\n"
            + "<dl class=\"notes\">\n<dt>Overrides:</dt>\n<dd><code><a href=\"JComponent.html#updateUI()\">updateUI</a></code>"
            + "&nbsp;in class&nbsp;<code><a href=\"JComponent.html\">JComponent</a></code></dd>\n</dl>\n"
            + "</section>\n</li>\n<li>\n"
            + "<section class=\"detail\" id=\"getUI()\">\n<h3>getUI</h3>\n"
            + "<div class=\"block\">Returns the look and feel (L&amp;F) object that renders this component.</div>\n"
            + "</section>\n</li>\n</ul>";

    @Test
    void memberAnchorMatchesJavadocSectionIds() {
        // Live v1.4.14: JDK Javadoc is fetched directly, so section ids must match javadoc's own spelling exactly.
        assertEquals("updateUI()", JavadocProvider.memberAnchor("updateUI", List.of(), false));
        assertEquals("setUI(javax.swing.plaf.PanelUI)",
                     JavadocProvider.memberAnchor("setUI", List.of("javax.swing.plaf.PanelUI"), false));
        assertEquals("<init>(java.awt.LayoutManager,boolean)",
                     JavadocProvider.memberAnchor("<init>", List.of("java.awt.LayoutManager", "boolean"), false));
        assertEquals("format(java.lang.String,java.lang.Object...)",
                     JavadocProvider.memberAnchor("format", List.of("java.lang.String", "java.lang.Object[]"), true));
        assertEquals("copyOf(int[],int)", JavadocProvider.memberAnchor("copyOf", List.of("int[]", "int"), false));
    }

    @Test
    void jdkPageUrlPrefixesTheModuleAndUsesDottedNestedClassNames() {
        String root = "https://docs.oracle.com/en/java/javase/21/docs/api/";
        assertEquals(root + "java.desktop/javax/swing/JPanel.html",
                     JavadocProvider.jdkPageUrl(root, "java.desktop", "javax.swing", "javax.swing.JPanel"));
        assertEquals(root + "java.desktop/javax/swing/JSpinner.DefaultEditor.html",
                     JavadocProvider.jdkPageUrl(root, "java.desktop", "javax.swing", "javax.swing.JSpinner$DefaultEditor"));
        assertEquals(root + "com/example/Widget.html",
                     JavadocProvider.jdkPageUrl(root.substring(0, root.length() - 1), null, "com.example", "com.example.Widget"));
    }

    @Test
    void moduleNameComesFromAJdkModuleRootOnly() {
        assertEquals("java.desktop", JavadocProvider.moduleFromRoot("nbjrt:file:/usr/lib/jvm/default/!/modules/java.desktop/"));
        assertEquals("java.base", JavadocProvider.moduleFromRoot("nbjrt:file:/usr/lib/jvm/default/!/modules/java.base"));
        assertNull(JavadocProvider.moduleFromRoot("jar:file:/home/chris/.m2/gson-2.14.0.jar!/"));
    }

    @Test
    void notFoundJavadocIsRecognisedByItsMarkupNotItsText() {
        assertTrue(JavadocProvider.isNotFoundJavadoc(null));
        assertTrue(JavadocProvider.isNotFoundJavadoc("<pre>public void <b>updateUI</b>()</pre><p id=\"not-found\">"
                + "<font color=\"#7c0000\">Javadoc not found.</font> Either Javadoc documentation ..."));
        assertFalse(JavadocProvider.isNotFoundJavadoc("<pre>x</pre><p>Resets the UI property."));
    }

    @Test
    void memberDetailDropsHeadingAndSignatureAndStopsAtItsOwnSection() {
        String text = JavadocProvider.htmlToText(JavadocProvider.extractMemberDetail(MEMBER_DETAILS, "updateUI()"));

        // Live v1.4.15: a blank line sat between "Overrides:" and its text.
        assertEquals("Resets the UI property with a value from the current look and feel.\n\n"
                + "Overrides:\nupdateUI in class JComponent", text);
        assertFalse(text.contains("void"), text);
        assertFalse(text.contains("Returns the look and feel"), text);
        assertEquals("Creates a new JPanel with FlowLayout.",
                     JavadocProvider.htmlToText(JavadocProvider.extractMemberDetail(MEMBER_DETAILS, "<init>(boolean)")));
        assertNull(JavadocProvider.extractMemberDetail(MEMBER_DETAILS, "noSuchMember()"));
    }

    @Test
    void classDescriptionIsTheFirstBlockOfItsSection() {
        String page = "<section class=\"class-description\" id=\"class-description\">\n"
                + "<dl class=\"notes\">\n<dt>All Implemented Interfaces:</dt>\n<dd>Accessible</dd>\n</dl>\n<hr>\n"
                + "<div class=\"type-signature\">public class JPanel</div>\n"
                + "<div class=\"block\"><code>JPanel</code> is a generic <div class=\"inner\">lightweight</div> container.</div>\n"
                + "</section>\n<section class=\"summary\" id=\"summary\">\n<div class=\"block\">not this</div>\n</section>";

        assertEquals("JPanel is a generic lightweight container.",
                     JavadocProvider.htmlToText(JavadocProvider.extractClassDescription(page)));
        assertNull(JavadocProvider.extractClassDescription("<section class=\"class-description\" id=\"class-description\">"
                + "<hr></section>\n<section class=\"summary\" id=\"summary\"><div class=\"block\">x</div></section>"));
    }

    @Test
    void numericEntitiesAreDecoded() {
        assertEquals("AB & C", JavadocProvider.decodeNumericEntities("&#65;&#x42; &#38; C"));
        assertEquals("&#xZZ; stays", JavadocProvider.decodeNumericEntities("&#xZZ; stays"));
        assertEquals("L&F A", JavadocProvider.htmlToText("L&amp;F &#65;"));
    }

    @Test
    void superscriptsAndWrappedLineIndentsAreReadable() {
        // Live v1.4.15: Integer.MAX_VALUE read "231-1", and wrapped Oracle lines kept a leading space.
        assertEquals("A constant holding the maximum value, 2^31-1.",
                     JavadocProvider.htmlToText("<div class=\"block\">A constant holding the maximum value,"
                             + " 2<sup>31</sup>-1.</div>"));
        assertEquals("Returns the\nvalue.\n  indented code",
                     JavadocProvider.htmlToText("<div class=\"block\">Returns the\n value.\n  indented code</div>"));
    }

    @Test
    void notFoundJavadocBecomesAShortNote() {
        assertNull(JavadocProvider.docHtmlToText(null));
        assertEquals("No Javadoc found.", JavadocProvider.docHtmlToText("<pre>public void <b>x</b>()</pre>"
                     + "<p id=\"not-found\"><font color=\"#7c0000\">Javadoc not found.</font> Either Javadoc documentation"
                     + " for this item does not exist or ... <a href=\"x\">Attach Javadoc...</a>"));
        assertEquals("Doc.", JavadocProvider.docHtmlToText("<pre>sig</pre><p>Doc."));
    }

    @Test
    void typeAnnotationsAreWrittenBeforeTheTypeWithSimpleNames() {
        // Live v1.4.15: String.format showed "java.lang.@org.jspecify.annotations.Nullable Object[] os".
        assertEquals("@Nullable java.lang.Object",
                     JavadocProvider.readableType("java.lang.@org.jspecify.annotations.Nullable Object"));
        assertEquals("java.util.Map<java.lang.String, @Nullable java.lang.Object>",
                     JavadocProvider.readableType("java.util.Map<java.lang.String, java.lang.@org.jspecify.annotations.Nullable Object>"));
        assertEquals("@A @B(1) java.util.Map.Entry",
                     JavadocProvider.readableType("java.util.Map.@a.A @b.B(1) Entry"));
        assertEquals("java.util.List<T>", JavadocProvider.readableType("java.util.List<T>"));
        assertEquals("java.lang.Object[]",
                     JavadocProvider.stripTypeAnnotations("java.lang.@org.jspecify.annotations.Nullable Object[]"));
    }

    @Test
    void varargsParameterIsWrittenWithEllipsis() {
        assertEquals("java.lang.String format, @Nullable java.lang.Object... args",
                     JavadocProvider.formatParameters(List.of("java.lang.String", "@Nullable java.lang.Object[]"),
                                                      List.of("format", "args"), true));
        assertEquals("int[] a, int n", JavadocProvider.formatParameters(List.of("int[]", "int"), List.of("a", "n"), false));
        assertEquals("", JavadocProvider.formatParameters(List.of(), List.of(), true));
    }
}
