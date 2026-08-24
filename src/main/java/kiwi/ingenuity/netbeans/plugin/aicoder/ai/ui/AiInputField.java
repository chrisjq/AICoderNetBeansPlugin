package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.PromptHistory;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TempFile;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TempFileRegistry;

/**
 * Multi-line text input for prompts. Enter = submit, Shift+Enter = newline, Up/Down = prompt history navigation.
 * Dropped files insert @/path references. Pasted images are saved through {@link TempFileRegistry} into the owning
 * session's registry-owned temp directory and inserted as the short marker @tmp.&lt;filename&gt; — expanded back to the
 * absolute path only in what is actually sent to the agent (see
 * {@link kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TmpMarkerExpander}), never in what the user sees.
 */
public class AiInputField extends JTextArea {

    private static final Logger LOG = Logger.getLogger(AiInputField.class.getName());

    private static final String HINT = "Ask AI... (Enter or Send to submit, Shift+Enter for newline)";
    private static final Color HINT_COLOR = new Color(0x58, 0x5b, 0x70);

    private final PromptHistory history;
    /**
     * The session this input field belongs to; pasted-image temp files are minted against it so they live in that
     * session's own temp directory and die with it. May be null while the panel is still being wired up — pastes are
     * refused gracefully until a session arrives.
     */
    private final AiSession session;
    private Consumer<String> submitCallback;
    /**
     * Notified on the EDT when a pasted image could not be saved (e.g. no temp storage available for this session, or
     * the PNG write failed). Optional — a paste failure is otherwise only logged.
     */
    private Consumer<String> pasteErrorCallback;
    private boolean showingHint = false;
    private boolean canSend = true;
    private final Color normalForeground;

    public AiInputField(PromptHistory history, AiSession session) {
        this.history = history;
        this.session = session;
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
     * See {@link #pasteErrorCallback}.
     */
    public void setPasteErrorCallback(Consumer<String> callback) {
        this.pasteErrorCallback = callback;
    }

    /**
     * Controls whether Enter submits. When false, Enter inserts a newline instead.
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

    /**
     * Decides synchronously whether the clipboard holds a pasteable image (the paste Action needs the answer
     * immediately to know whether to fall through to a normal text paste), then hands the actual encode+write off the
     * EDT. A 4K screenshot is tens of MB of ARGB pixels — allocating a {@link BufferedImage}, drawing into it and
     * PNG-encoding it are all too slow to run on the calling thread without freezing the UI, so only cheap checks
     * (clipboard flavor, image presence, and — for a non-{@link BufferedImage} {@link Image} — its reported dimensions)
     * happen here.
     */
    private boolean tryPasteImage() {
        if (session == null) {
            // Panel not fully wired to a session yet; refusing beats minting
            // into nowhere (or an NPE) on the EDT during a paste.
            return false;
        }
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        try {
            if (!cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                return false;
            }
            Image img = (Image) cb.getData(DataFlavor.imageFlavor);
            if (img == null) {
                return false;
            }
            if (!(img instanceof BufferedImage) && (img.getWidth(null) <= 0 || img.getHeight(null) <= 0)) {
                return false;
            }
            pasteImageAsync(img);
            return true;
        }
        catch (IOException | UnsupportedOperationException | UnsupportedFlavorException ex) {
            LOG.log(Level.FINE, "Image paste failed", ex);
            return false;
        }
    }

    /**
     * Runs the encode+write on a background thread so the EDT is never held for the duration. Package-private (not
     * private) purely so tests can drive it directly without going through the real system clipboard.
     */
    void pasteImageAsync(Image img) {
        Thread worker = new Thread(() -> encodeAndSavePastedImage(img), "ai-coder-image-paste");
        worker.setDaemon(true);
        worker.start();
    }

    private void encodeAndSavePastedImage(Image img) {
        BufferedImage bi;
        if (img instanceof BufferedImage bimg) {
            bi = bimg;
        }
        else {
            int w = img.getWidth(null);
            int h = img.getHeight(null);
            bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = bi.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }

        TempFile tmp = createPasteTempFile(session.id());
        if (tmp == null) {
            reportPasteFailure("Could not save the pasted image — temp storage isn't available for this session");
            return;
        }
        try {
            writePastedImage(bi, tmp.path().toFile());
        }
        catch (IOException ex) {
            LOG.log(Level.FINE, "Image paste failed", ex);
            // createPasteTempFile already registered (and created) the file; a write
            // failure must not leave that empty .png tracked until the age sweep gets
            // to it hours later.
            cleanupFailedPasteTempFile(tmp);
            reportPasteFailure("Could not save the pasted image");
            return;
        }
        // Insert on the EDT, and only after the write actually succeeded. insertAtCursor
        // reads the caret position fresh when it runs rather than one captured up front,
        // so this is correct even though the user may have kept typing (moving the caret)
        // while the write was in flight.
        String marker = "@tmp." + tmp.path().getFileName() + " ";
        SwingUtilities.invokeLater(() -> insertAtCursor(marker));
    }

    /**
     * Creates the temp file the pasted image is written into. Package-private so tests can substitute a fake without a
     * live MCP server — {@link TempFileRegistry#createTempFile} needs the plugin's session-config-dir resolution, which
     * a plain unit test does not have.
     */
    TempFile createPasteTempFile(String sessionId) {
        return TempFileRegistry.createTempFile(sessionId, "ai-coder-paste", ".png");
    }

    /**
     * Encodes and writes the pasted image. Package-private so tests can force a failure deterministically instead of
     * needing a genuinely corrupt image or a full disk.
     */
    void writePastedImage(BufferedImage image, File target) throws IOException {
        ImageIO.write(image, "PNG", target);
    }

    /**
     * Removes a temp file left behind by a failed image-paste write. Package-private so tests can observe the call —
     * {@link TempFileRegistry#deleteTempFile} is a silent no-op for a {@link TempFile} it never tracked (e.g. one a
     * test fabricates directly), so asserting on this method is how the cleanup itself gets verified.
     */
    void cleanupFailedPasteTempFile(TempFile tempFile) {
        TempFileRegistry.deleteTempFile(tempFile);
    }

    /**
     * Reports an image-paste failure to {@link #pasteErrorCallback} on the EDT, if one is registered. Always safe to
     * call from a background thread.
     */
    private void reportPasteFailure(String message) {
        Consumer<String> callback = pasteErrorCallback;
        if (callback == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> callback.accept(message));
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
        setText(next);
    }
}
