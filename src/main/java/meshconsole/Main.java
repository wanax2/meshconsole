package meshconsole;

import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.MessageLog;
import meshconsole.ui.MainWindow;
import meshconsole.ui.Splash;

import javax.swing.*;
import java.nio.file.Path;

public class Main {
    /** Writes hang.log with the UI thread's stack if the UI stops responding for more than 8 s (helps diagnose freezes). */
    private static void startUiWatchdog() {
        Thread t = new Thread(() -> {
            long lastSeen = System.currentTimeMillis();
            final long[] pong = {lastSeen};
            boolean reported = false;
            while (true) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                javax.swing.SwingUtilities.invokeLater(() -> pong[0] = System.currentTimeMillis());
                long stall = System.currentTimeMillis() - pong[0];
                if (stall > 8000 && !reported) {
                    reported = true;
                    try {
                        StringBuilder sb = new StringBuilder(java.time.ZonedDateTime.now() + "  UI thread unresponsive for " + stall / 1000 + " s\n");
                        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                            if (!e.getKey().getName().startsWith("AWT-EventQueue")) continue;
                            sb.append("Thread ").append(e.getKey().getName()).append(":\n");
                            for (StackTraceElement el : e.getValue()) sb.append("    at ").append(el).append('\n');
                        }
                        java.nio.file.Files.writeString(DataDir.file("hang.log"), sb + "\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (Exception ignored) { }
                } else if (stall < 2000) reported = false;
            }
        }, "ui-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** The command-line arguments, kept so the self-updater can restart with them. */
    public static String[] ARGS = new String[0];

    public static void main(String[] args) throws Exception {
        ARGS = args.clone();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter(); e.printStackTrace(new java.io.PrintWriter(sw));
                String rec = java.time.ZonedDateTime.now() + "  " + Version.NAME + " " + Version.VERSION + "  thread " + t.getName() + "\n" + sw + "\n";
                java.nio.file.Files.writeString(DataDir.file("crash.log"), rec, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                System.err.print(rec);
                if (!java.awt.GraphicsEnvironment.isHeadless()) {
                    String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    javax.swing.SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(null, "Something went wrong in " + t.getName() + ":\n" + msg + "\n\nDetails were written to crash.log in the data folder. The app keeps running.", "Mesh Console error", javax.swing.JOptionPane.ERROR_MESSAGE));
                }
            } catch (Exception ignored) { }
        });
        for (String a : args) if (a.equals("--headless")) { Headless.run(args); return; }
        if (args.length > 0 && !args[0].startsWith("--")) DataDir.set(Path.of(args[0]));
        if (!DataDir.lockInstance()) {
            javax.swing.JOptionPane.showMessageDialog(null, "Mesh Console is already running with the data folder\n" + DataDir.get() + "\n\nTwo copies would fight over the radio and the log files. Use the running one, or start this one with a different data folder.", "Already running", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        startUiWatchdog();
        if (args.length > 0 && !args[0].startsWith("--")) DataDir.set(Path.of(args[0]));
        Path logFile = DataDir.file("messages.log");
        String upgradeNote = DataDir.checkVersion();
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> {
            Splash splash = new Splash();
            splash.setVisible(true);
            // build the app while the splash is up (message log load can take a moment)
            new Thread(() -> {
                MeshState state = new MeshState(new MessageLog(logFile), new meshconsole.mesh.NodeDb(DataDir.file("nodes.json")));
                meshconsole.mesh.SignalHistory history = new meshconsole.mesh.SignalHistory(DataDir.file("signal_history.csv"));
                state.setSignalHistory(history);
                meshconsole.analysis.UtilHistory utilHistory = new meshconsole.analysis.UtilHistory(DataDir.file("util_history.csv"));
                state.setUtilHistory(utilHistory);
                MeshClient client = new MeshClient(state);
                SwingUtilities.invokeLater(() -> {
                    MainWindow w = new MainWindow(client);
                    w.setHistories(history, utilHistory);
                    if (upgradeNote != null) state.emitLog(upgradeNote);
                    state.emitLog("Data folder: " + DataDir.get());
                    w.setVisible(true);
                    splash.closeAfter(1500);
                });
            }, "startup").start();
        });
    }
}
