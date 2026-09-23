package meshconsole;

import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.MessageLog;
import meshconsole.ui.MainWindow;
import meshconsole.ui.Splash;

import javax.swing.*;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        if (args.length > 0) DataDir.set(Path.of(args[0]));
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
