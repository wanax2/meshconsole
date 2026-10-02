package meshconsole.ui;

import javax.swing.*;

/** Runs a UI refresh at most once per interval, however often it is requested. Keeps 800-node tables from rebuilding on every packet. */
final class Coalesce {
    private final Timer timer;
    private volatile boolean pending;

    Coalesce(int delayMs, Runnable r) {
        timer = new Timer(delayMs, e -> { pending = false; r.run(); });
        timer.setRepeats(false);
    }

    /** Call from any thread. */
    void request() {
        if (pending) return;
        pending = true;
        SwingUtilities.invokeLater(() -> { if (!timer.isRunning()) timer.start(); });
    }
}
