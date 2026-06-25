package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.TransferHandler;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.PromptHistory;

/**
 * Multi-line text input for prompts. Enter = submit, Shift+Enter = newline,
 * Up/Down = prompt history navigation. Dropped files insert @/path references.
 * Pasted images save to a temp PNG and insert @/path.
 */
public class AiInputField extends JTextArea {

    private static final Logger LOG = Logger.getLogger(AiInputField.class.getName());

    private static final String HINT = "Ask AI... (Enter or Send to submit, Shift+Enter for newline)";
    private static final Color HINT_COLOR = new Color(0x58, 0x5b, 0x70);

    private final PromptHistory history;
    private Consumer<String> submitCallback;
    private boolean showingHint = false;
    private boolean canSend = true;
    private final Color normalForeground;

    public AiInputField(PromptHistory history) {
        this.history = history;
        setLineWrap(true);
        setWrapStyleWord(true);
        setRows(3);
        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        normalForeground = getForeground();
        setupHint();
        setupKeyBindings();
        setupDragAndDrop();
        setupImagePaste();
    }

    public void setSubmitCallback(Consumer<String> callback) {
        this.submitCallback = callback;
    }

    /**
     * Controls whether Enter submits. When false, Enter inserts a newline
     * instead.
     */
    public void setCanSend(boolean canSend) {
        this.canSend = canSend;
    }

    /**
     * Returns the current text (empty string if hint is showing).
     */
    public String getPromptText() {
        return showingHint ? "" : getText().strip();
    }

    /**
     * Clear the field. If focused, stay in edit mode; otherwise show hint.
     */
    public void clear() {
        if (isFocusOwner()) {
            showingHint = false;
            setText("");
            setForeground(normalForeground);
        }
        else {
            showingHint = true;
            setText(HINT);
            setForeground(HINT_COLOR);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled && showingHint) {
            setText("Accept or reject the proposed change first…");
        }
        else if (enabled && showingHint) {
            setText(HINT);
        }
    }

    /**
     * -------------------------------------------------------------------------
     */
    private void setupHint() {
        showingHint = true;
        setText(HINT);
        setForeground(HINT_COLOR);

        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (showingHint) {
                    showingHint = false;
                    setText("");
                    setForeground(normalForeground);
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (getText().isBlank()) {
                    showingHint = true;
                    setText(HINT);
                    setForeground(HINT_COLOR);
                }
            }
        });
    }

    private void setupKeyBindings() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // Handle edit shortcuts early so NetBeans IDE-level Ctrl+C/X/A
                // interception doesn't swallow them before the component acts.
                int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
                if ((e.getModifiersEx() & mask) != 0 && !e.isAltDown()) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_C -> {
                            String sel = getSelectedText();
                            if (sel != null && !sel.isEmpty()) {
                                Toolkit.getDefaultToolkit().getSystemClipboard()
                                        .setContents(new StringSelection(sel), null);
                            }
                            e.consume();
                            return;
                        }
                        case KeyEvent.VK_X -> {
                            String sel = getSelectedText();
                            if (sel != null && !sel.isEmpty() && isEnabled()) {
                                Toolkit.getDefaultToolkit().getSystemClipboard()
                                        .setContents(new StringSelection(sel), null);
                                replaceSelection("");
                            }
                            e.consume();
                            return;
                        }
                        case KeyEvent.VK_A -> {
                            selectAll();
                            e.consume();
                            return;
                        }
                    }
                }
                if (!isEnabled()) {
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_ENTER -> {
                        e.consume();
                        if (e.isShiftDown()) {
                            insertAtCursor("\n");
                        }
                        else if (canSend) {
                            handleSubmit();
                        }
                        else {
                            insertAtCursor("\n");
                        }
                    }
                    case KeyEvent.VK_UP -> {
                        if (!e.isShiftDown()) {
                            e.consume();
                            handleHistory(true);
                        }
                    }
                    case KeyEvent.VK_DOWN -> {
                        if (!e.isShiftDown()) {
                            e.consume();
                            handleHistory(false);
                        }
                    }
                }
            }
        });
    }

    private void setupDragAndDrop() {
        TransferHandler existing = getTransferHandler();
        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || (existing != null && existing.canImport(support));
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    try {
                        List<File> files = (List<File>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        StringBuilder sb = new StringBuilder();
                        for (File f : files) {
                            if (sb.length() > 0) {
                                sb.append(" ");
                            }
                            sb.append("@").append(f.getAbsolutePath());
                        }
                        insertAtCursor(sb.append(" ").toString());
                        return true;
                    }
                    catch (Exception ex) {
                        LOG.log(Level.WARNING, "File drop failed", ex);
                        return false;
                    }
                }
                return existing != null && existing.importData(support);
            }
        });
    }

    private void setupImagePaste() {
        javax.swing.Action originalPaste = getActionMap().get(DefaultEditorKit.pasteAction);
        getActionMap().put(DefaultEditorKit.pasteAction, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!tryPasteImage() && originalPaste != null) {
                    originalPaste.actionPerformed(e);
                }
            }
        });
    }

    private boolean tryPasteImage() {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        try {
            if (!cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                return false;
            }
            Image img = (Image) cb.getData(DataFlavor.imageFlavor);
            if (img == null) {
                return false;
            }

            BufferedImage bi;
            if (img instanceof BufferedImage bimg) {
                bi = bimg;
            }
            else {
                int w = img.getWidth(null);
                int h = img.getHeight(null);
                if (w <= 0 || h <= 0) {
                    return false;
                }
                bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = bi.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
            }

            File tmp = new File(System.getProperty("java.io.tmpdir"),
                    "ai-coder-paste-" + UUID.randomUUID() + ".png");
            ImageIO.write(bi, "PNG", tmp);
            insertAtCursor("@" + tmp.getAbsolutePath() + " ");
            return true;
        }
        catch (IOException | UnsupportedOperationException
                | java.awt.datatransfer.UnsupportedFlavorException ex) {
            LOG.log(Level.FINE, "Image paste failed", ex);
            return false;
        }
    }

    /**
     * Insert text at the caret position, clearing hint state if needed.
     */
    void insertAtCursor(String text) {
        if (showingHint) {
            showingHint = false;
            setText("");
            setForeground(normalForeground);
        }
        try {
            getDocument().insertString(getCaretPosition(), text, null);
        }
        catch (BadLocationException ex) {
            append(text);
        }
    }

    private void handleSubmit() {
        String text = getPromptText();
        if (text.isEmpty()) {
            return;
        }
        history.add(text);
        clear();
        if (submitCallback != null) {
            submitCallback.accept(text);
        }
    }

    private void handleHistory(boolean older) {
        String current = showingHint ? "" : getText();
        String next = older ? history.previous(current) : history.next(current);
        showingHint = false;
        setForeground(normalForeground);
        if (next.isEmpty()) {
            showingHint = true;
            setText(HINT);
            setForeground(HINT_COLOR);
        }
        else {
            setText(next);
        }
    }
}
