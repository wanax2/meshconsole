package meshconsole;

import com.fazecast.jSerialComm.SerialPort;
import meshconsole.analysis.UtilHistory;
import meshconsole.bbs.BbsEngine;
import meshconsole.bbs.BbsStore;
import meshconsole.mesh.*;

import java.nio.file.Path;

/**
 * No-GUI mode for a Raspberry Pi or server: connects, logs everything the GUI would, runs the BBS
 * and alerts, saves the node DB, and reconnects forever.
 *
 *   java -cp ... meshconsole.Main --headless --port /dev/ttyACM0 [--data /home/pi/meshdata] [--bbs]
 *   java -cp ... meshconsole.Main --headless --tcp meshpi.local:4403
 */
public final class Headless {
    private Headless() { }

    public static void run(String[] args) throws Exception {
        String port = null, tcp = null; boolean bbs = false; int webPort = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = args[++i];
                case "--tcp" -> tcp = args[++i];
                case "--data" -> DataDir.set(Path.of(args[++i]));
                case "--bbs" -> bbs = true;
                case "--web" -> webPort = Integer.parseInt(args[++i]);
                default -> { }
            }
        }
        if (port == null && tcp == null) { System.err.println("usage: --headless (--port <serial> | --tcp host[:4403]) [--data <folder>] [--bbs]"); System.exit(2); }
        String note = DataDir.checkVersion();
        MeshState state = new MeshState(new MessageLog(DataDir.file("messages.log")), new NodeDb(DataDir.file("nodes.json")));
        state.setSignalHistory(new meshconsole.mesh.SignalHistory(DataDir.file("signal_history.csv")));
        UtilHistory util = new UtilHistory(DataDir.file("util_history.csv"));
        state.setUtilHistory(util);
        MeshClient client = new MeshClient(state);
        java.io.PrintWriter log = new java.io.PrintWriter(new java.io.FileWriter(DataDir.file("meshconsole.log").toFile(), true), true);
        state.addListener(new MeshState.Listener() {
            @Override public void onLog(String line) { String l = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) + "  " + line; System.out.println(l); log.println(l); }
            @Override public void onAlert(String kind, String text) { onLog("ALERT " + kind + ": " + text); try { java.nio.file.Files.writeString(DataDir.file("alerts.log"), java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) + "  " + kind + "  " + text + "\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (java.io.IOException ignored) { } }
            @Override public void onMessage(ChatMessage m, boolean isNew) { if (isNew && !m.outgoing) onLog("MSG " + state.nodeName(m.from) + " → " + state.nodeName(m.to) + ": " + m.text); }
        });
        if (note != null) state.emitLog(note);
        state.emitLog(Version.NAME + " " + Version.VERSION + " headless, data folder " + DataDir.get());
        AlertEngine alerts = new AlertEngine(state);
        BbsStore store = new BbsStore(DataDir.file("bbs.json"));
        if (bbs) { store.enabled = true; store.save(); }
        BbsEngine engine = new BbsEngine(client, store);
        state.emitLog("BBS " + (store.enabled ? "enabled as '" + store.name + "'" : "disabled (use --bbs or enable in bbs.json)"));
        engine.setWeather(new meshconsole.analysis.WeatherHistory(DataDir.file("weather_history.csv")));

        meshconsole.tools.Scheduler scheduler = new meshconsole.tools.Scheduler(client, () -> "<html><body>Report generation is available in the GUI.</body></html>");
        if (webPort > 0) { new meshconsole.tools.WebDashboard(state, webPort, () -> "<html><body>Use the GUI for the full report.</body></html>"); state.emitLog("Web dashboard on port " + webPort); }
        final String fPort = port, fTcp = tcp;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { client.disconnect(); client.saveDbQuietly(); }));
        long lastSave = System.currentTimeMillis(), lastAlert = lastSave;
        while (true) {
            if (!client.isConnected()) {
                try {
                    if (fTcp != null) {
                        String host = fTcp; int p = 4403; int c = fTcp.lastIndexOf(':');
                        if (c > 0) { host = fTcp.substring(0, c); p = Integer.parseInt(fTcp.substring(c + 1)); }
                        client.connectTcp(host, p);
                    } else {
                        SerialPort found = null;
                        for (SerialPort sp : SerialPort.getCommPorts()) if (sp.getSystemPortName().equals(fPort) || sp.getSystemPortPath().equals(fPort)) found = sp;
                        if (found == null) { state.emitLog("Waiting for " + fPort + "…"); Thread.sleep(5000); continue; }
                        client.connect(found);
                    }
                } catch (Exception e) {
                    state.emitLog("Connect failed: " + e.getMessage() + " – retrying in 10 s");
                    Thread.sleep(10_000);
                    continue;
                }
            }
            Thread.sleep(5000);
            long now = System.currentTimeMillis();
            if (now - lastAlert > 60_000) { alerts.check(); scheduler.tick(); lastAlert = now; }
            if (now - lastSave > 5 * 60_000) { client.saveDbQuietly(); lastSave = now; }
        }
    }
}
