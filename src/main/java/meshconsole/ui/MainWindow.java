package meshconsole.ui;

import com.fazecast.jSerialComm.SerialPort;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

public class MainWindow extends JFrame {
    private final MeshClient client;
    private final MeshState state;
    private final JComboBox<PortItem> ports = new JComboBox<>();
    private final JButton connect = new JButton("Connect to device");
    private final JLabel status = new JLabel("Not connected");
    private final JCheckBox autoReconnect = new JCheckBox("Auto-reconnect", true);
    private String lastPortName;
    private Timer reconnectTimer;
    private final JTabbedPane tabs = new JTabbedPane();
    private final StatusPanel statusPanel;
    private final MessagesPanel messagesPanel;
    private final NodesPanel nodesPanel;
    private final MapPanel mapPanel;
    private final SwrPanel swrPanel;
    private final SettingsPanel settingsPanel;
    private final TelemetryPanel telemetryPanel;
    private final StatsPanel statsPanel;
    private final TrafficPanel trafficPanel;
    private final AlertsPanel alertsPanel;
    private final meshconsole.mesh.AlertEngine alerts;
    private AnalysisPanel analysisPanel;
    private WeatherPanel weatherPanel;
    private long connectedSince; private int connectedNum; private boolean registered;

    record PortItem(SerialPort port) {
        @Override public String toString() { return port.getSystemPortName() + "  —  " + port.getDescriptivePortName(); }
    }

    public MainWindow(MeshClient client) {
        super(meshconsole.Version.NAME + " " + meshconsole.Version.VERSION);
        this.client = client;
        this.state = client.state();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { client.disconnect(); client.saveDbQuietly(); dispose(); System.exit(0); }
        });

        JMenuBar menu = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem dataDir = new JMenuItem("Data folder…");
        dataDir.addActionListener(e -> chooseDataFolder());
        JMenuItem openDir = new JMenuItem("Open data folder");
        openDir.addActionListener(e -> { try { Desktop.getDesktop().open(meshconsole.DataDir.get().toFile()); } catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); } });
        JMenuItem export = new JMenuItem("Export all logs as zip…");
        export.addActionListener(e -> exportLogs());
        file.add(dataDir); file.add(openDir); file.addSeparator(); file.add(export);
        menu.add(file);
        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About " + meshconsole.Version.NAME + "…");
        about.addActionListener(e -> showAbout());
        help.add(about);
        menu.add(help);
        setJMenuBar(menu);

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(new JLabel(" Radio port: "));
        bar.add(ports);
        JButton refresh = new JButton("↻");
        refresh.setToolTipText("Rescan serial ports");
        refresh.addActionListener(e -> refreshPorts());
        bar.add(refresh);
        connect.setFont(connect.getFont().deriveFont(Font.BOLD, 15f));
        connect.setPreferredSize(new Dimension(200, 36));
        connect.setMaximumSize(new Dimension(200, 36));
        connect.setBackground(new Color(40, 120, 70));
        connect.setForeground(Color.WHITE);
        connect.setOpaque(true);
        connect.setBorderPainted(false);
        connect.setFocusPainted(false);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(connect);
        bar.add(Box.createHorizontalStrut(8));
        JButton tcp = new JButton("TCP…");
        tcp.setToolTipText("Connect to a WiFi/Ethernet node (port 4403) instead of USB");
        tcp.addActionListener(e -> connectTcp());
        bar.add(tcp);
        autoReconnect.setToolTipText("ESP32 boards (T-Deck, Heltec, Station G2…) drop their USB port whenever they reboot, e.g. after saving settings. Reconnect automatically when it comes back.");
        bar.add(autoReconnect);
        bar.addSeparator();
        bar.add(status);
        add(bar, BorderLayout.NORTH);

        statusPanel = new StatusPanel(state);
        statusPanel.setClient(client);
        messagesPanel = new MessagesPanel(client);
        nodesPanel = new NodesPanel(client);
        mapPanel = new MapPanel(state);
        swrPanel = new SwrPanel(state);
        settingsPanel = new SettingsPanel(client);
        telemetryPanel = new TelemetryPanel(state);
        statsPanel = new StatsPanel(state);
        trafficPanel = new TrafficPanel(state);
        alerts = new meshconsole.mesh.AlertEngine(state);
        alertsPanel = new AlertsPanel(state, alerts);
        mapPanel.setClient(client);
        tabs.addTab("Status & signal", statusPanel);
        tabs.addTab("Messages", messagesPanel);
        tabs.addTab("Nodes", nodesPanel);
        tabs.addTab("Map", mapPanel);
        tabs.addTab("Telemetry", telemetryPanel);
        tabs.addTab("Traffic", trafficPanel);
        tabs.addTab("Stats & export", statsPanel);
        tabs.addTab("Alerts", alertsPanel);
        // Analysis + Weather are added once the histories are known (setHistories)
        tabs.addTab("Antenna SWR", swrPanel);
        tabs.addTab("Settings & MQTT", settingsPanel);
        add(tabs, BorderLayout.CENTER);
        JLabel footer = new JLabel(" " + meshconsole.Version.NAME + " " + meshconsole.Version.VERSION + "  ·  " + meshconsole.Version.COPYRIGHT + "  ·  MIT License");
        footer.setFont(footer.getFont().deriveFont(10f));
        footer.setForeground(java.awt.Color.DARK_GRAY);
        add(footer, BorderLayout.SOUTH);

        nodesPanel.setOnMessageNode(num -> { messagesPanel.selectDestination(num); tabs.setSelectedComponent(messagesPanel); });
        nodesPanel.setOnShowOnMap(num -> { mapPanel.centerOn(num); tabs.setSelectedComponent(mapPanel); });

        connect.addActionListener(e -> toggleConnect());
        client.setConnectionListener((c, d) -> SwingUtilities.invokeLater(() -> {
            connect.setText(c ? "Disconnect" : "Connect to device");
            connect.setBackground(c ? new Color(160, 60, 50) : new Color(40, 120, 70));
            ports.setEnabled(!c);
            if (!c) {
                if (connectedSince > 0 && connectedNum != 0 && analysisPanel != null) analysisPanel.addConnectedTime(connectedNum, System.currentTimeMillis() - connectedSince);
                connectedSince = 0; connectedNum = 0; registered = false;
                status.setText(d.isEmpty() ? "Not connected" : "Disconnected: " + d);
                settingsPanel.onDisconnected();
                if (!d.isEmpty() && autoReconnect.isSelected() && lastPortName != null) startReconnectWatch();
            } else {
                connectedSince = System.currentTimeMillis(); registered = false;
                status.setText("Connected on " + d + " – loading config…");
                if (reconnectTimer != null) { reconnectTimer.stop(); reconnectTimer = null; }
            }
        }));
        state.addListener(new MeshState.Listener() {
            @Override public void onStatusChanged() { SwingUtilities.invokeLater(MainWindow.this::refreshStatusLine); }
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(MainWindow.this::refreshStatusLine); }
        });

        new Timer(2000, e -> { nodesPanel.tick(); statusPanel.refreshChart(); statusPanel.tickCapture(); }).start();
        new Timer(60_000, e -> alerts.check()).start();
        new Timer(5 * 60_000, e -> { if (client.isConnected()) client.saveDbQuietly(); }).start();

        refreshPorts();
        setSize(1150, 760);
        setLocationRelativeTo(null);
    }

    public void setSignalHistory(meshconsole.mesh.SignalHistory h) { statusPanel.setHistory(h); }

    public void setHistories(meshconsole.mesh.SignalHistory sig, meshconsole.analysis.UtilHistory util) {
        statusPanel.setHistory(sig);
        analysisPanel = new AnalysisPanel(state, sig, util);
        weatherPanel = new WeatherPanel(state, sig);
        analysisPanel.setWeatherPanel(weatherPanel);
        swrPanel.setAntennaDb(() -> analysisPanel.antennaDb());
        tabs.insertTab("Analysis", null, analysisPanel, null, tabs.indexOfComponent(alertsPanel));
        tabs.insertTab("Weather", null, weatherPanel, null, tabs.indexOfComponent(alertsPanel));
    }

    private void refreshStatusLine() {
        if (!client.isConnected()) return;
        if (state.configComplete() && !registered && state.myNodeNum() != 0 && analysisPanel != null) {
            registered = true; connectedNum = state.myNodeNum();
            analysisPanel.registerConnectedRadio();
        }
        NodeEntry me = state.myNode();
        StringBuilder sb = new StringBuilder("Connected");
        if (me != null) sb.append(" as ").append(me.displayName()).append(" ").append(me.idString());
        sb.append("  ·  ").append(state.nodes().size()).append(" nodes");
        if (me != null && me.battery >= 0) sb.append("  ·  batt ").append(me.battery > 100 ? "ext" : me.battery + "%");
        if (me != null) sb.append(String.format("  ·  ch util %.1f%%", me.channelUtil));
        if (!state.configComplete()) sb.append("  ·  loading config…");
        status.setText(sb.toString());
    }

    void refreshPorts() {
        ports.removeAllItems();
        for (SerialPort p : SerialPort.getCommPorts()) ports.addItem(new PortItem(p));
        int pick = -1;
        for (int i = 0; i < ports.getItemCount(); i++) {
            String d = ports.getItemAt(i).port().getDescriptivePortName().toLowerCase();
            if (d.contains("rak") || d.contains("meshtastic") || d.contains("wisblock") || d.contains("usb serial")) { pick = i; break; }
            // built-in / virtual ports that are never a radio
            if (pick < 0 && !d.contains("active management") && !d.contains("sol") && !d.contains("communications port") && !d.contains("bluetooth")) pick = i;
        }
        if (pick >= 0) ports.setSelectedIndex(pick);
        if (ports.getItemCount() == 0) status.setText("No serial ports found – plug in the radio and press ↻");
    }

    /** Poll for the last-used serial port to reappear (device rebooted) and reconnect to it. */
    private void startReconnectWatch() {
        if (reconnectTimer != null) reconnectTimer.stop();
        final long deadline = System.currentTimeMillis() + 60_000;
        final String want = lastPortName;
        reconnectTimer = new Timer(1000, null);
        reconnectTimer.addActionListener(e -> {
            if (client.isConnected() || System.currentTimeMillis() > deadline) { reconnectTimer.stop(); reconnectTimer = null; return; }
            for (SerialPort p : SerialPort.getCommPorts()) {
                if (p.getSystemPortName().equals(want)) {
                    status.setText("Port " + want + " is back – reconnecting…");
                    reconnectTimer.stop(); reconnectTimer = null;
                    try {
                        Thread.sleep(1500);            // let the firmware finish booting
                        client.connect(p);
                        refreshPorts();
                    } catch (Exception ex) {
                        status.setText("Reconnect failed: " + ex.getMessage());
                    }
                    return;
                }
            }
            long left = (deadline - System.currentTimeMillis()) / 1000;
            status.setText("Disconnected – waiting for " + want + " to come back (" + left + " s)");
        });
        reconnectTimer.start();
    }

    private void exportLogs() {
        JFileChooser fc = new JFileChooser(meshconsole.DataDir.get().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder to save the export zip in");
        fc.setApproveButtonText("Export here");
        JCheckBox caps = new JCheckBox("Include packet captures (*.mcap)", false);
        fc.setAccessory(caps);
        if (fc.showDialog(this, "Export here") != JFileChooser.APPROVE_OPTION) return;
        try {
            statusPanel.flushLog();
            java.nio.file.Path zip = meshconsole.DataDir.exportZip(fc.getSelectedFile().toPath(), caps.isSelected());
            state.emitLog("Exported logs to " + zip);
            int r = JOptionPane.showOptionDialog(this, "Saved " + zip.getFileName() + " (" + java.nio.file.Files.size(zip) / 1024 + " KB)", "Export",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, new Object[]{"Show in folder", "OK"}, "OK");
            if (r == 0 && Desktop.isDesktopSupported()) Desktop.getDesktop().open(zip.getParent().toFile());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void chooseDataFolder() {
        JFileChooser fc = new JFileChooser(meshconsole.DataDir.get().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder for logs, node database and history (current: " + meshconsole.DataDir.get() + ")");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.nio.file.Path chosen = fc.getSelectedFile().toPath().toAbsolutePath();
        if (chosen.equals(meshconsole.DataDir.get())) return;
        Object[] opts = {"Copy existing data there", "Start empty there", "Cancel"};
        int r = JOptionPane.showOptionDialog(this, "<html>Use <b>" + chosen + "</b> for all data files from the next start?<br><br>"
                + "Copy the current files there so history continues, or start with an empty folder (current files stay where they are).</html>",
                "Data folder", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (r == 2 || r < 0) return;
        try {
            if (r == 0) { int n = meshconsole.DataDir.copyData(chosen, false); state.emitLog("Copied " + n + " data files to " + chosen); }
            meshconsole.DataDir.set(chosen);
            JOptionPane.showMessageDialog(this, "Data folder set to " + chosen + ".\nRestart Mesh Console for it to take effect.", "Data folder", JOptionPane.INFORMATION_MESSAGE);
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Data folder", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showAbout() {
        String html = "<html><div style='width:420px;font-family:sans-serif'>"
                + "<h2 style='margin:0'>" + meshconsole.Version.NAME + " " + meshconsole.Version.VERSION + "</h2>"
                + "<p>Desktop client for Meshtastic radios: messaging, node map, live signal, traceroute, "
                + "device settings, MQTT client proxy and NanoVNA antenna SWR.</p>"
                + "<p><b>" + meshconsole.Version.COPYRIGHT + "</b><br>" + meshconsole.Version.LICENSE + "<br>"
                + "<a href='" + meshconsole.Version.HOMEPAGE + "'>" + meshconsole.Version.HOMEPAGE + "</a></p>"
                + "<p style='color:#555;font-size:90%'>Built with jSerialComm (LGPL/Apache), Google Protocol Buffers (BSD) and the "
                + "Meshtastic protobuf definitions (GPL-3.0, fetched at build time). Map tiles © OpenStreetMap contributors (ODbL). "
                + "Meshtastic is a registered trademark of Meshtastic LLC; this program is not affiliated with or endorsed by Meshtastic.</p>"
                + "<p style='color:#555;font-size:90%'>Java " + System.getProperty("java.version") + " on " + System.getProperty("os.name") + "</p>"
                + "</div></html>";
        JOptionPane.showMessageDialog(this, html, "About " + meshconsole.Version.NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    private void connectTcp() {
        if (client.isConnected()) client.disconnect();
        String s = JOptionPane.showInputDialog(this, "Host or IP (optionally :port, default 4403):", "meshtastic.local:4403");
        if (s == null || s.isBlank()) return;
        String host = s.trim();
        int port = 4403;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(host.substring(colon + 1)); host = host.substring(0, colon); } catch (NumberFormatException ignored) { }
        }
        try {
            client.connectTcp(host, port);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "TCP connect failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void toggleConnect() {
        if (client.isConnected()) { lastPortName = null; client.disconnect(); return; }
        PortItem pi = (PortItem) ports.getSelectedItem();
        if (pi == null) { refreshPorts(); return; }
        try {
            lastPortName = pi.port().getSystemPortName();
            client.connect(pi.port());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Connect failed", JOptionPane.ERROR_MESSAGE);
        }
    }
}
