package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.MarkdownLinkRouter;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.MarkdownLinkRouter.Action;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class MarkdownLinkRouterTest {

    @Test
    void isWebUrl_trueForHttpAndHttps() {
        assertTrue(MarkdownLinkRouter.isWebUrl("http://example.com"));
        assertTrue(MarkdownLinkRouter.isWebUrl("https://example.com/x"));
        assertTrue(MarkdownLinkRouter.isWebUrl("HTTPS://EXAMPLE.COM"));
    }

    @Test
    void isWebUrl_falseForFileAndPathAndMailto() {
        assertFalse(MarkdownLinkRouter.isWebUrl("file:///home/x/Foo.java"));
        assertFalse(MarkdownLinkRouter.isWebUrl("/home/x/Foo.java"));
        assertFalse(MarkdownLinkRouter.isWebUrl("mailto:a@b.com"));
    }

    @Test
    void localFilePath_stripsFileSchemeAndLineSuffix() {
        assertEquals("/home/x/Foo.java",
                MarkdownLinkRouter.localFilePath("file:///home/x/Foo.java:42"));
    }

    @Test
    void localFilePath_handlesPlainAbsolutePathWithLine() {
        assertEquals("/home/x/Foo.java",
                MarkdownLinkRouter.localFilePath("/home/x/Foo.java:42"));
    }

    @Test
    void localFilePath_handlesSingleSlashFileScheme() {
        // java.net.URL.toString() normalises file URLs to a single slash
        // (e.g. from HyperlinkEvent.getURL()); this must still resolve.
        assertEquals("/home/x/Foo.java",
                MarkdownLinkRouter.localFilePath("file:/home/x/Foo.java:42"));
    }

    @Test
    void localFilePath_handlesFileSchemeWithHost() {
        assertEquals("/home/x/Foo.java",
                MarkdownLinkRouter.localFilePath("file://localhost/home/x/Foo.java"));
    }

    @Test
    void localFilePath_decodesPercentEncodingInFileUri() {
        assertEquals("/home/x/My Doc.pdf",
                MarkdownLinkRouter.localFilePath("file:///home/x/My%20Doc.pdf"));
    }

    @Test
    void localFilePath_nullForWebUrlAndMailto() {
        assertNull(MarkdownLinkRouter.localFilePath("https://example.com"));
        assertNull(MarkdownLinkRouter.localFilePath("mailto:a@b.com"));
    }

    @Test
    void route_webGoesToBrowser() {
        assertEquals(Action.BROWSER,
                MarkdownLinkRouter.route("https://example.com", false, false));
    }

    @Test
    void route_existingProjectFileGoesToEditor() {
        assertEquals(Action.EDITOR,
                MarkdownLinkRouter.route("/proj/src/Foo.java:10", true, true));
    }

    @Test
    void route_existingNonProjectFileGoesToDefaultViewer() {
        assertEquals(Action.DEFAULT_VIEWER,
                MarkdownLinkRouter.route("/home/x/report.pdf", true, false));
    }

    @Test
    void route_missingLocalFileFallsBackToBrowser() {
        assertEquals(Action.BROWSER,
                MarkdownLinkRouter.route("/home/x/gone.pdf", false, false));
    }

    @Test
    void route_mailtoGoesToBrowser() {
        assertEquals(Action.BROWSER,
                MarkdownLinkRouter.route("mailto:a@b.com", false, false));
    }
}
