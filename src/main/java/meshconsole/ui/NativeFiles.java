package meshconsole.ui;

import java.awt.*;
import java.nio.file.Path;

/** Native file dialogs. Swing's JFileChooser on Windows asks the shell for drive icons synchronously and can hang for minutes on an offline mapped drive. */
final class NativeFiles {
    private NativeFiles() { }

    static Path save(Component parent, String title, Path suggested) {
        Window w = parent == null ? null : SwingWindow(parent);
        FileDialog fd = w instanceof Frame f ? new FileDialog(f, title, FileDialog.SAVE) : new FileDialog((Dialog) null, title, FileDialog.SAVE);
        if (suggested != null) { if (suggested.getParent() != null) fd.setDirectory(suggested.getParent().toString()); fd.setFile(suggested.getFileName().toString()); }
        fd.setVisible(true);
        return fd.getFile() == null ? null : Path.of(fd.getDirectory(), fd.getFile());
    }

    static Path open(Component parent, String title, String extension) {
        Window w = parent == null ? null : SwingWindow(parent);
        FileDialog fd = w instanceof Frame f ? new FileDialog(f, title, FileDialog.LOAD) : new FileDialog((Dialog) null, title, FileDialog.LOAD);
        fd.setDirectory(meshconsole.DataDir.get().toString());
        if (extension != null) fd.setFile("*." + extension);
        fd.setVisible(true);
        return fd.getFile() == null ? null : Path.of(fd.getDirectory(), fd.getFile());
    }

    /** A folder: the native dialog has no folder mode, so ask for any file inside it (or a name) and take the directory. */
    static Path folder(Component parent, String title, Path start) {
        Window w = parent == null ? null : SwingWindow(parent);
        FileDialog fd = w instanceof Frame f ? new FileDialog(f, title + " (pick any file in the folder, or type a name)", FileDialog.SAVE) : new FileDialog((Dialog) null, title, FileDialog.SAVE);
        if (start != null) fd.setDirectory(start.toString());
        fd.setFile("select-this-folder");
        fd.setVisible(true);
        return fd.getDirectory() == null ? null : Path.of(fd.getDirectory());
    }

    private static Window SwingWindow(Component c) { return javax.swing.SwingUtilities.getWindowAncestor(c); }
}
