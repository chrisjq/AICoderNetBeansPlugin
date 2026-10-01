package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Font;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The diff panel's code font comes from the editor's Fonts &amp; Colors default coloring, so a diff reads at
 * the same size as the file does in the editor. These pin how that coloring is turned into a font.
 */
class AiDiffTopComponentFontTest {

    @Test
    void usesTheEditorsFamilyAndSize() {
        SimpleAttributeSet coloring = new SimpleAttributeSet();
        StyleConstants.setFontFamily(coloring, "DejaVu Sans Mono");
        StyleConstants.setFontSize(coloring, 17);

        Font font = AiDiffTopComponent.editorFont(coloring);

        assertEquals("DejaVu Sans Mono", font.getName());
        assertEquals(17, font.getSize());
        assertEquals(Font.PLAIN, font.getStyle());
    }

    @Test
    void noEditorSettings_fallsBackToPlainMonospaced12() {
        Font font = AiDiffTopComponent.editorFont((javax.swing.text.AttributeSet) null);

        assertEquals(Font.MONOSPACED, font.getName());
        assertEquals(12, font.getSize());
    }

    @Test
    void sizeMissing_keepsTheEditorsFamilyAtTheDefaultSize() {
        SimpleAttributeSet coloring = new SimpleAttributeSet();
        StyleConstants.setFontFamily(coloring, "DejaVu Sans Mono");

        Font font = AiDiffTopComponent.editorFont(coloring);

        assertEquals("DejaVu Sans Mono", font.getName());
        assertEquals(12, font.getSize());
    }

    @Test
    void headerText_usesTheIdesLabelFontSize_notAFixedSmallSize() {
        Font label = javax.swing.UIManager.getFont("Label.font");
        javax.swing.UIManager.put("Label.font", new javax.swing.plaf.FontUIResource("Dialog", Font.PLAIN, 19));
        try {
            Font header = AiDiffTopComponent.headerFont();

            assertEquals(19, header.getSize(), "must follow the IDE's label size (NetBeans --fontsize)");
            assertEquals("Dialog", header.getName());
            assertEquals(Font.BOLD, header.getStyle());
        }
        finally {
            javax.swing.UIManager.put("Label.font", label);
        }
    }

    @Test
    void lineNumberGutter_widensForALargeEditorFont_andNeverShrinksBelowTheOldWidth() {
        java.awt.Graphics2D g = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
                .createGraphics();
        try {
            java.awt.FontMetrics large = g.getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 30));
            java.awt.FontMetrics small = g.getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 8));

            assertTrue(AiDiffTopComponent.gutterWidth(large) >= large.stringWidth("99999") + 12,
                    "a five-digit line number plus padding must fit at a large editor font");
            assertEquals(52, AiDiffTopComponent.gutterWidth(small));
        }
        finally {
            g.dispose();
        }
    }

    @Test
    void familyMissing_keepsTheEditorsSizeInMonospaced() {
        SimpleAttributeSet coloring = new SimpleAttributeSet();
        StyleConstants.setFontSize(coloring, 15);

        Font font = AiDiffTopComponent.editorFont(coloring);

        assertEquals(Font.MONOSPACED, font.getName());
        assertEquals(15, font.getSize());
    }
}
