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
    private final JComboBox<Object> ports = new JComboBox<>();
    private final JButton connect = new JButton("Connect to device");
    private final JLabel status = new JLabel("Not connected");
    private final JCheckBox autoReconnect = new JCheckBox("Auto-reconnect", true);
    private String lastPortName;
    /** Set once the user connects or disconnects by hand, so a pending start-up auto-connect stops looking. */
    private volatile boolean userTookOver;
    private Timer reconnectTimer;
    private TcpItem lastTcp;
    private final JTabbedPane tabs = new JTabbedPane();
    private final StatusPanel statusPanel;
    private final MessagesPanel messagesPanel;
    private final NodesPanel nodesPanel;
    private final MapPanel mapPanel;
    private final SwrPanel swrPanel;
    private final SettingsPanel settingsPanel;
    private RadioPanel radioPanel;
    private final TelemetryPanel telemetryPanel;
    private final StatsPanel statsPanel;
    private final TrafficPanel trafficPanel;
    private final AlertsPanel alertsPanel;
    private final meshconsole.mesh.AlertEngine alerts;
    private AnalysisPanel analysisPanel;
    private WeatherPanel weatherPanel;
    private BbsPanel bbsPanel;
    private WitnessPanel witnessPanel;
    private SchedulePanel schedulePanel;
    private meshconsole.tools.Scheduler scheduler;
    private SecondRadioWindow second;
    private meshconsole.tools.WebDashboard web;
    private meshconsole.mesh.SignalHistory signalHistory;
    private SysopWindow sysop;
    private meshconsole.bbs.BbsEngine bbs;
    private final JToggleButton bbsToggle = new JToggleButton("BBS: OFF");
    private JButton sysopBtn;

    private void applyBbsVisibility(boolean show) {
        bbsToggle.setVisible(show);
        if (sysopBtn != null) sysopBtn.setVisible(show);
        if (bbsPanel != null) {
            int idx = tabs.indexOfComponent(bbsPanel);
            if (show && idx < 0) tabs.insertTab("BBS", null, bbsPanel, null, tabs.indexOfComponent(alertsPanel));
            if (!show && idx >= 0) tabs.removeTabAt(idx);
        }
        if (!show && bbs != null && bbs.store().enabled) bbs.setEnabled(false);
    }
    private long connectedSince; private int connectedNum; private boolean registered;

    record TcpItem(String host, int port) { @Override public String toString() { return "TCP  " + host + (port == 4403 ? "" : ":" + port); } }
    static final Object ADD_TCP = new Object() { @Override public String toString() { return "TCP host… (add)"; } };
    /** Per-data-folder preferences (last radio, TCP hosts) so two instances on different folders don't share them. */
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(MainWindow.class).node("data-" + Integer.toHexString(meshconsole.DataDir.get().toString().toLowerCase().hashCode()));

    record PortItem(SerialPort port) {
        @Override public String toString() { return port.getSystemPortName() + "  —  " + port.getDescriptivePortName(); }
    }

    public MainWindow(MeshClient client) {
        super(meshconsole.Version.NAME + " " + meshconsole.Version.VERSION + " – " + meshconsole.DataDir.get().getFileName());   // the data folder in the title tells two windows apart
        this.client = client;
        this.state = client.state();
        WebMap.start(state, meshconsole.DataDir.get(), msg -> state.emitLog(msg));   // live map in any browser (port 3500, 3501 …)
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { client.disconnect("window closing"); client.saveDbQuietly(); dispose(); System.exit(0); }
        });

        JMenuBar menu = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem dataDir = new JMenuItem("Data folder…");
        dataDir.addActionListener(e -> chooseDataFolder());
        JMenuItem openDir = new JMenuItem("Open data folder");
        openDir.addActionListener(e -> { try { Desktop.getDesktop().open(meshconsole.DataDir.get().toFile()); } catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); } });
        JMenuItem exportCfg = new JMenuItem("Export settings (folder, label)…"); exportCfg.addActionListener(e -> exportSettings()); file.add(exportCfg);
        JMenuItem export = new JMenuItem("Export all logs as zip…");
        export.addActionListener(e -> exportLogs());
        file.add(dataDir); file.add(openDir); file.addSeparator(); file.add(export);
        menu.add(file);
        JMenu tools = new JMenu("Tools");
        JMenuItem secondRadio = new JMenuItem("Second radio window…");
        secondRadio.addActionListener(e -> { if (second == null && signalHistory != null) second = new SecondRadioWindow(state, signalHistory); if (second != null) { second.setVisible(true); second.toFront(); } });
        JCheckBoxMenuItem webItem = new JCheckBoxMenuItem("Web dashboard on port 8080");
        webItem.addActionListener(e -> toggleWeb(webItem));
        JMenuItem importUrl = new JMenuItem("Import channel URL / QR link…");
        importUrl.addActionListener(e -> importChannelUrl());
        JMenuItem exportUrl = new JMenuItem("Export channel URL…");
        exportUrl.addActionListener(e -> exportChannelUrl());
        tools.add(secondRadio); tools.add(webItem); tools.addSeparator(); tools.add(importUrl); tools.add(exportUrl);
        menu.add(tools);
        JMenu view = new JMenu("View");
        JCheckBoxMenuItem showBbs = new JCheckBoxMenuItem("Show BBS features", java.util.prefs.Preferences.userNodeForPackage(MainWindow.class).getBoolean("showBbs", false));
        showBbs.addActionListener(e -> { java.util.prefs.Preferences.userNodeForPackage(MainWindow.class).putBoolean("showBbs", showBbs.isSelected()); applyBbsVisibility(showBbs.isSelected()); });
        view.add(showBbs);
        JCheckBoxMenuItem autoStats = new JCheckBoxMenuItem("Auto-refresh radio stats every 5 min", java.util.prefs.Preferences.userNodeForPackage(StatusPanel.class).getBoolean("autoStats", false));
        autoStats.setToolTipText("Sends a telemetry request to the radio itself. Off by default: it coincided with reboots on a T-Deck.");
        autoStats.addActionListener(e -> { java.util.prefs.Preferences.userNodeForPackage(StatusPanel.class).putBoolean("autoStats", autoStats.isSelected()); StatusPanel sp = (StatusPanel) tabs.getComponentAt(0); sp.autoStats = autoStats.isSelected(); });
        view.add(autoStats);
        JCheckBoxMenuItem ignoreMine = new JCheckBoxMenuItem("Ignore my other radios in signal stats and analysis", meshconsole.mesh.MeshState.ignoreMyRadios);
        ignoreMine.setToolTipText("Packets from nodes listed on My radios are kept out of the live chart, signal history, coverage and Analysis (they still appear on Nodes and Map). Untick for an A/B test between your own radios.");
        ignoreMine.addActionListener(e -> { meshconsole.mesh.MeshState.ignoreMyRadios = ignoreMine.isSelected(); java.util.prefs.Preferences.userNodeForPackage(meshconsole.mesh.MeshState.class).putBoolean("ignoreMyRadios", ignoreMine.isSelected()); });
        view.add(ignoreMine);
        menu.add(view);
        JMenuItem fw = new JMenuItem("Firmware update…");
        fw.addActionListener(e -> new FirmwareDialog(this, client, lastPortName).setVisible(true));
        tools.addSeparator(); tools.add(fw);
        JMenu help = new JMenu("Help");
        JMenuItem upd = new JMenuItem("Updates…");
        upd.addActionListener(e -> updateSettings());
        help.add(upd);
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
        ports.addActionListener(e -> { if (ports.getSelectedItem() == ADD_TCP && !client.isConnected()) SwingUtilities.invokeLater(this::connectTcp); });
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
        bbsToggle.setToolTipText("Turn the bulletin-board bot on or off");
        bbsToggle.addActionListener(e -> { if (bbs != null) bbs.setEnabled(bbsToggle.isSelected()); });
        sysopBtn = new JButton("Sysop screen");
        sysopBtn.addActionListener(e -> { if (sysop != null) { sysop.setVisible(true); sysop.toFront(); } });
        bar.add(bbsToggle); bar.add(sysopBtn);
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
        radioPanel = new RadioPanel(client, () -> new FirmwareDialog(this, client, lastPortName).setVisible(true));
        radioPanel.afterSave = settingsPanel::refreshFromRadio;
        tabs.addTab("This radio", radioPanel);
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

        connect.addActionListener(e -> { userTookOver = true; toggleConnect(); });
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
                else if (!d.isEmpty() && autoReconnect.isSelected() && lastTcp != null) startTcpReconnectWatch();
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

        meshconsole.tools.AppUpdater.start(state::emitLog, () -> { client.disconnect("update"); client.saveDbQuietly(); }, meshconsole.Main.ARGS);
        refreshPorts();
        // fit the usable screen (a headless Pi under screen sharing may be only 1280×720): a window taller than the
        // screen puts its title bar out of reach, so it can't be moved, and its dialogs can end up off-screen
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1150, scr.width), Math.min(760, scr.height));
        setLocationRelativeTo(null);
        if (getY() < scr.y) setLocation(getX(), scr.y);
        // Auto-connect at startup to the radio used last time, when auto-reconnect is on and it is present.
        String last = PREFS.get("lastRadio", "");
        if (autoReconnect.isSelected() && !last.isEmpty()) {
            // At boot the radio's USB port can appear a few seconds after the app starts, or under another name
            // (ttyACM1 instead of ttyACM0): keep looking every 10 s, matching the port name first and then the
            // radio's USB identity, until it is found or the user connects by hand.
            String lastId = PREFS.get("lastRadioUsb", "");
            Timer t = new Timer(2500, null);
            t.addActionListener(e -> {
                if (client.isConnected() || userTookOver) { t.stop(); return; }
                refreshPorts();
                int found = -1;
                for (int i = 0; i < ports.getItemCount() && found < 0; i++) {
                    Object it = ports.getItemAt(i);
                    if ((it instanceof PortItem pi && last.equals("serial:" + pi.port().getSystemPortName()))
                            || (it instanceof TcpItem ti && last.equals("tcp:" + ti.host() + ":" + ti.port()))) found = i;
                }
                if (found < 0 && !lastId.isEmpty() && last.startsWith("serial:"))
                    for (int i = 0; i < ports.getItemCount() && found < 0; i++)
                        if (ports.getItemAt(i) instanceof PortItem pi && lastId.equals(usbIdentity(pi.port()))) found = i;
                if (found >= 0) { t.stop(); ports.setSelectedIndex(found); status.setText("Auto-connecting to " + ports.getItemAt(found) + "…"); toggleConnect(); return; }
                status.setText("Waiting for the last radio (" + last.substring(last.indexOf(':') + 1) + ") – still looking every 10 s, or pick a port and Connect");
                t.setDelay(10_000);
            });
            t.setInitialDelay(2500); t.start();
        }
    }

    public void setSignalHistory(meshconsole.mesh.SignalHistory h) { statusPanel.setHistory(h); }

    private void toggleWeb(JCheckBoxMenuItem item) {
        try {
            if (web != null) { web.close(); web = null; state.emitLog("Web dashboard stopped"); return; }
            web = new meshconsole.tools.WebDashboard(state, 8080, () -> Report.html(state, signalHistory, analysisPanel == null ? null : analysisPanel.utilHistory(), weatherPanel == null ? null : weatherPanel.history(), null, meshconsole.DataDir.file("alerts.log")));
            state.emitLog("Web dashboard at http://localhost:8080/ (and this PC's IP on the LAN)");
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI.create("http://localhost:8080/"));
        } catch (Exception ex) { item.setSelected(false); JOptionPane.showMessageDialog(this, ex.getMessage(), "Web dashboard", JOptionPane.ERROR_MESSAGE); }
    }

    private void importChannelUrl() {
        String url = JOptionPane.showInputDialog(this, "Paste a meshtastic.org/e/#… channel link:");
        if (url == null || url.isBlank()) return;
        try {
            org.meshtastic.proto.AppOnlyProtos.ChannelSet cs = meshconsole.tools.ChannelUrl.parse(url);
            String desc = meshconsole.tools.ChannelUrl.describe(cs);
            if (JOptionPane.showConfirmDialog(this, "<html>This link contains:<pre>" + desc + "</pre>Write these channels" + (cs.hasLoraConfig() ? " and LoRa settings" : "") + " to the radio? (replaces channels 0.." + (cs.getSettingsCount() - 1) + ")</html>", "Import channels", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            new Thread(() -> {
                try {
                    for (int i = 0; i < cs.getSettingsCount(); i++) {
                        org.meshtastic.proto.ChannelProtos.Channel ch = org.meshtastic.proto.ChannelProtos.Channel.newBuilder().setIndex(i).setRole(i == 0 ? org.meshtastic.proto.ChannelProtos.Channel.Role.PRIMARY : org.meshtastic.proto.ChannelProtos.Channel.Role.SECONDARY).setSettings(cs.getSettings(i)).build();
                        client.setChannel(ch);
                    }
                    if (cs.hasLoraConfig()) {
                        org.meshtastic.proto.ConfigProtos.Config base = state.config(org.meshtastic.proto.ConfigProtos.Config.PayloadVariantCase.LORA);
                        org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.Builder b = base == null ? org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.newBuilder() : base.getLora().toBuilder();
                        org.meshtastic.proto.ConfigProtos.Config.LoRaConfig l = cs.getLoraConfig();
                        b.setUsePreset(l.getUsePreset()).setModemPreset(l.getModemPreset()).setRegion(l.getRegion() == org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode.UNSET ? b.getRegion() : l.getRegion()).setChannelNum(l.getChannelNum()).setHopLimit(l.getHopLimit() == 0 ? b.getHopLimit() : l.getHopLimit());
                        if (!l.getUsePreset()) b.setBandwidth(l.getBandwidth()).setSpreadFactor(l.getSpreadFactor()).setCodingRate(l.getCodingRate());
                        client.setConfig(org.meshtastic.proto.ConfigProtos.Config.newBuilder().setLora(b).build());
                    }
                    state.emitLog("Channel URL imported");
                } catch (Exception ex) { SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, ex.getMessage(), "Import failed", JOptionPane.ERROR_MESSAGE)); }
            }, "import-url").start();
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Not a valid channel link: " + ex.getMessage(), "Import", JOptionPane.ERROR_MESSAGE); }
    }

    private void exportChannelUrl() {
        String url = meshconsole.tools.ChannelUrl.build(state.allChannels(), state.lora());
        JTextArea a = new JTextArea(url, 4, 60); a.setLineWrap(true); a.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(a), "Channel URL (paste into the phone app, or share it – it contains your channel keys)", JOptionPane.PLAIN_MESSAGE);
    }

    public void setHistories(meshconsole.mesh.SignalHistory sig, meshconsole.analysis.UtilHistory util) {
        signalHistory = sig;
        statusPanel.setHistory(sig);
        analysisPanel = new AnalysisPanel(state, sig, util);
        weatherPanel = new WeatherPanel(state, sig);
        analysisPanel.setWeatherPanel(weatherPanel);
        swrPanel.setAntennaDb(() -> analysisPanel.antennaDb());
        tabs.insertTab("Analysis", null, analysisPanel, null, tabs.indexOfComponent(alertsPanel));
        tabs.insertTab("Weather", null, weatherPanel, null, tabs.indexOfComponent(alertsPanel));
        bbs = new meshconsole.bbs.BbsEngine(client, new meshconsole.bbs.BbsStore(meshconsole.DataDir.file("bbs.json")));
        bbs.setWeather(weatherPanel.history());
        bbsPanel = new BbsPanel(bbs, state);
        tabs.insertTab("BBS", null, bbsPanel, null, tabs.indexOfComponent(alertsPanel));
        witnessPanel = new WitnessPanel(state);
        tabs.insertTab("MQTT witness", null, witnessPanel, null, tabs.indexOfComponent(alertsPanel));
        scheduler = new meshconsole.tools.Scheduler(client, () -> Report.html(state, signalHistory, util, weatherPanel.history(), null, meshconsole.DataDir.file("alerts.log")));
        schedulePanel = new SchedulePanel(scheduler);
        tabs.insertTab("Schedule", null, schedulePanel, null, tabs.indexOfComponent(alertsPanel));
        new Timer(60_000, e -> { scheduler.tick(); if (bbs != null) bbs.tick(); }).start();
        java.util.List<Integer> mine = new java.util.ArrayList<>(); for (meshconsole.analysis.RadioDb.Radio r : analysisPanel.radioDb().all()) mine.add(r.num); witnessPanel.addMyNodes(mine);
        nodesPanel.onMarkMine = (n, isMine) -> {
            try {
                if (isMine) analysisPanel.radioDb().touch(n.num, n.displayName(), n.hwModel, "", 0, "");
                else analysisPanel.radioDb().remove(n.num);
                state.notifyNodesChanged();
            } catch (java.io.IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "My radios", JOptionPane.ERROR_MESSAGE); }
        };
        sysop = new SysopWindow(bbs, state);
        applyBbsVisibility(java.util.prefs.Preferences.userNodeForPackage(MainWindow.class).getBoolean("showBbs", false));
        Runnable syncToggle = () -> { bbsToggle.setSelected(bbs.store().enabled); bbsToggle.setText(bbs.store().enabled ? "BBS: ON" : "BBS: OFF"); bbsToggle.setBackground(bbs.store().enabled ? new Color(40, 120, 70) : null); bbsToggle.setForeground(bbs.store().enabled ? Color.WHITE : null); bbsToggle.setOpaque(bbs.store().enabled); };
        syncToggle.run();
        bbs.addListener(new meshconsole.bbs.BbsEngine.Listener() {
            @Override public void onActivity(String line) { }
            @Override public void onStateChanged() { SwingUtilities.invokeLater(() -> { syncToggle.run(); bbsPanel.reload(); }); }
        });
    }

    private void refreshStatusLine() {
        if (radioPanel != null) { int i = tabs.indexOfComponent(radioPanel); if (i >= 0) { String t = radioPanel.tabTitle(); if (!t.equals(tabs.getTitleAt(i))) tabs.setTitleAt(i, t); } }
        if (!client.isConnected()) return;
        if (state.configComplete() && !registered && state.myNodeNum() != 0 && analysisPanel != null) {
            registered = true; connectedNum = state.myNodeNum();
            analysisPanel.registerConnectedRadio();
            // like every other client: give the radio the time on connect (a radio with no GPS/phone/NTP may be months off)
            new Thread(() -> { try { Thread.sleep(2000); client.setRadioTime(); } catch (Exception ignored) { } }, "set-time").start();
        }
        NodeEntry me = state.myNode();
        StringBuilder sb = new StringBuilder("Connected");
        if (me != null) sb.append(" as ").append(me.displayName()).append(" ").append(me.idString());
        sb.append("  ·  ").append(state.nodes().size()).append(" nodes");
        if (me != null && me.battery >= 0) sb.append("  ·  batt ").append(me.battery > 100 ? "ext" : me.battery + "%");
        if (me != null) sb.append(String.format("  ·  ch util %.1f%%", me.channelUtil));
        if (!state.wifiIp().isEmpty()) sb.append("  ·  IP ").append(state.wifiIp());
        if (!state.configComplete()) sb.append("  ·  loading config…");
        status.setText(sb.toString());
    }

    private java.util.List<TcpItem> savedTcp() {
        java.util.List<TcpItem> l = new java.util.ArrayList<>();
        for (String s : PREFS.get("tcpHosts", "").split(";")) { if (s.isBlank()) continue; String h = s; int p = 4403; int c = s.lastIndexOf(':'); if (c > 0) { try { p = Integer.parseInt(s.substring(c + 1)); h = s.substring(0, c); } catch (NumberFormatException ignored) { } } l.add(new TcpItem(h, p)); }
        return l;
    }
    private void rememberTcp(TcpItem t) {
        java.util.List<TcpItem> l = savedTcp(); l.removeIf(x -> x.host().equalsIgnoreCase(t.host()) && x.port() == t.port()); l.add(0, t);
        while (l.size() > 6) l.remove(l.size() - 1);
        StringBuilder sb = new StringBuilder(); for (TcpItem x : l) sb.append(x.host()).append(':').append(x.port()).append(';');
        PREFS.put("tcpHosts", sb.toString());
    }

    /** The computer's own UARTs – a Raspberry Pi's ttyAMA* / ttyS* – which a USB radio never appears as. */
    static boolean isBuiltInUart(SerialPort p) {
        String n = p.getSystemPortName();
        return n.startsWith("ttyAMA") || n.startsWith("ttyS") || n.startsWith("serial");
    }

    /** "vid:pid:serial" of a USB serial port, to find the same radio again if its name changes (ttyACM0 to ttyACM1). */
    static String usbIdentity(SerialPort p) {
        try { if (p.getVendorID() <= 0) return ""; return String.format("%04x:%04x:%s", p.getVendorID(), p.getProductID(), p.getSerialNumber() == null ? "" : p.getSerialNumber()); }
        catch (Throwable t) { return ""; }
    }

    void refreshPorts() {
        Object sel = ports.getSelectedItem();
        ports.removeAllItems();
        for (SerialPort p : SerialPort.getCommPorts()) ports.addItem(new PortItem(p));
        for (TcpItem t : savedTcp()) ports.addItem(t);
        ports.addItem(ADD_TCP);
        int pick = -1;
        if (sel instanceof TcpItem) { ports.setSelectedItem(sel); return; }
        for (int i = 0; i < ports.getItemCount(); i++) {
            if (!(ports.getItemAt(i) instanceof PortItem pi)) continue;
            String d = pi.port().getDescriptivePortName().toLowerCase();
            if (d.contains("active management") || d.contains("amt")) continue;   // the laptop's own Intel management port is never a radio
            if (isBuiltInUart(pi.port())) continue;                              // a Pi's own serial pins / debug UART (ttyAMA*, ttyS*) are never a radio
            if (d.contains("rak") || d.contains("meshtastic") || d.contains("wisblock") || d.contains("usb serial") || d.contains("jtag") || d.contains("cp210") || d.contains("ch340") || d.contains("ch910")) { pick = i; break; }
            // built-in / virtual ports that are never a radio
            if (pick < 0 && !d.contains("active management") && !d.contains("sol") && !d.contains("communications port") && !d.contains("bluetooth")) pick = i;
        }
        if (pick >= 0) ports.setSelectedIndex(pick);
        else if (!savedTcp().isEmpty()) ports.setSelectedIndex(ports.getItemCount() - 2);   // last-used TCP host when no radio is on USB
        if (SerialPort.getCommPorts().length == 0 && savedTcp().isEmpty()) status.setText("No serial ports found – plug in the radio and press ↻, or add a TCP host");
    }

    /** Retry a lost TCP connection every 2 s for 30 minutes (the radio rebooted, WiFi dropped). */
    private void startTcpReconnectWatch() {
        if (reconnectTimer != null) reconnectTimer.stop();
        final long deadline = System.currentTimeMillis() + 30 * 60_000;
        final TcpItem want = lastTcp;
        reconnectTimer = new Timer(2000, null);
        reconnectTimer.addActionListener(e -> {
            if (client.isConnected() || System.currentTimeMillis() > deadline || want == null) { reconnectTimer.stop(); reconnectTimer = null; return; }
            status.setText("Reconnecting to " + want.host() + "…");
            try { client.connectTcp(want.host(), want.port()); reconnectTimer.stop(); reconnectTimer = null; }
            catch (IOException ex) { status.setText("Waiting for " + want.host() + " (" + ex.getMessage() + ")"); }
        });
        reconnectTimer.start();
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
        // No JFileChooser here: on Windows it enumerates every mapped drive and can block for minutes if one is offline.
        Object[] opts = {"Export to " + meshconsole.DataDir.exportDir(), "Export settings…", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "<html>Zip all logs (message log, node DB, histories, alerts, app log, crash/hang logs).<br>Packet captures (.mcap) are not included.<br>File name: meshconsole-<b>" + meshconsole.DataDir.exportLabel() + "</b>-&lt;date&gt;.zip</html>", "Export all logs",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (choice == 2 || choice < 0) return;
        if (choice == 1) { exportSettings(); return; }
        java.nio.file.Path target = meshconsole.DataDir.exportDir();
        statusPanel.flushLog();
        final java.nio.file.Path dir = target;
        JDialog progress = new JDialog(this, "Exporting…", false);
        JLabel pl = new JLabel("Compressing the data files – the app stays usable meanwhile.  ");
        pl.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        progress.add(pl); progress.pack(); progress.setLocationRelativeTo(this); progress.setVisible(true);
        new Thread(() -> {
            try {
                java.nio.file.Path zip = meshconsole.DataDir.exportZip(dir, false);
                state.emitLog("Exported logs to " + zip);
                SwingUtilities.invokeLater(() -> {
                    progress.dispose();
                    try {
                        int r = JOptionPane.showOptionDialog(this, "Saved " + zip.getFileName() + " (" + java.nio.file.Files.size(zip) / 1024 + " KB) in " + zip.getParent(), "Export",
                                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, new Object[]{"Show in folder", "OK"}, "OK");
                        if (r == 0 && Desktop.isDesktopSupported()) Desktop.getDesktop().open(zip.getParent().toFile());
                    } catch (java.io.IOException ignored) { }
                });
            } catch (java.io.IOException ex) {
                SwingUtilities.invokeLater(() -> { progress.dispose(); JOptionPane.showMessageDialog(this, ex.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE); });
            }
        }, "export").start();
    }

    /** Self-update source (a folder such as a mounted NAS share, or a GitHub owner/repo), shared by every instance. */
    private void updateSettings() {
        JCheckBox on = new JCheckBox("Check daily and update automatically", meshconsole.tools.AppUpdater.enabled());
        JTextField src = new JTextField(meshconsole.tools.AppUpdater.source(), 32), at = new JTextField(meshconsole.tools.AppUpdater.time(), 5);
        JPanel form = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(3, 4, 3, 4); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; form.add(new JLabel("<html>Source: a folder holding meshconsole-<i>x.y.z</i>-ready-to-run.zip files (e.g. a NAS share mounted on this machine),<br>or a GitHub repository as owner/repo (newest release). Installed version: " + meshconsole.Version.VERSION + "</html>"), c);
        c.gridwidth = 1; c.gridy = 1; form.add(new JLabel("Source:"), c); c.gridx = 1; form.add(src, c);
        c.gridx = 0; c.gridy = 2; form.add(new JLabel("Check at:"), c); c.gridx = 1; form.add(at, c);
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2; form.add(on, c);
        Object[] opts = {"Save", "Save and check now", "Cancel"};
        int r = JOptionPane.showOptionDialog(this, form, "Mesh Console updates", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
        if (r == 2 || r < 0) return;
        meshconsole.tools.AppUpdater.configure(on.isSelected(), src.getText(), at.getText());
        if (r == 1) new Thread(() -> {
            try { String res = meshconsole.tools.AppUpdater.checkAndApply(state::emitLog, () -> { client.disconnect("update"); client.saveDbQuietly(); }, meshconsole.Main.ARGS, true); state.emitLog("[update] " + res); SwingUtilities.invokeLater(() -> notice("Mesh Console updates", res)); }
            catch (Exception ex) { state.emitLog("[update] check failed: " + ex.getMessage()); SwingUtilities.invokeLater(() -> notice("Mesh Console updates", "Update check failed: " + ex.getMessage())); }
        }, "update-now").start();
    }

    /**
     * A result message that can never freeze the window: not modal, always on top and centred on the screen. A modal
     * message box that the window manager places behind the main window or off-screen (seen on the Pi's Wayland
     * desktop when the main window is larger than the screen) blocks every click while being invisible.
     */
    void notice(String title, String text) {
        JOptionPane pane = new JOptionPane(text, JOptionPane.INFORMATION_MESSAGE);
        JDialog d = pane.createDialog(null, title);
        d.setModal(false);
        d.setAlwaysOnTop(true);
        d.setLocationRelativeTo(null);
        pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, e -> d.dispose());
        d.setVisible(true);
    }

    /** Shared export folder (all instances) and this instance's label for the file name. */
    private void exportSettings() {
        JTextField dir = new JTextField(meshconsole.DataDir.exportDir().toString(), 36);
        JTextField label = new JTextField(meshconsole.DataDir.exportLabel(), 16);
        JButton pick = new JButton("Browse…");
        pick.addActionListener(e -> { java.nio.file.Path f = NativeFiles.folder(this, "Export folder", meshconsole.DataDir.exportDir()); if (f != null) dir.setText(f.toString()); });
        JPanel form = new JPanel(new GridBagLayout()); GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(3, 4, 3, 4); c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0; form.add(new JLabel("Export folder (shared by every Mesh Console instance):"), c);
        c.gridx = 1; form.add(dir, c); c.gridx = 2; form.add(pick, c);
        c.gridx = 0; c.gridy = 1; form.add(new JLabel("Label for this instance's files (meshconsole-<label>-<date>.zip):"), c);
        c.gridx = 1; form.add(label, c);
        if (JOptionPane.showConfirmDialog(this, form, "Export settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        meshconsole.DataDir.setExportDir(dir.getText().isBlank() ? null : java.nio.file.Path.of(dir.getText().trim()));
        meshconsole.DataDir.setExportLabel(label.getText());
    }

    private void chooseDataFolder() {
        String typed = JOptionPane.showInputDialog(this, "Folder for logs, node database and history:", meshconsole.DataDir.get().toString());
        if (typed == null || typed.isBlank()) return;
        java.nio.file.Path chosen = java.nio.file.Path.of(typed.trim()).toAbsolutePath();
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
        String s = JOptionPane.showInputDialog(this, "Host or IP (optionally :port, default 4403):", "meshtastic.local:4403");
        if (s == null || s.isBlank()) return;
        String host = s.trim();
        int port = 4403;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(host.substring(colon + 1)); host = host.substring(0, colon); } catch (NumberFormatException ignored) { }
        }
        TcpItem t = new TcpItem(host, port);
        rememberTcp(t);
        refreshPorts();
        ports.setSelectedItem(t);
        connectTcp(t);
    }

    private void connectTcp(TcpItem t) {
        if (client.isConnected()) client.disconnect("switching to TCP " + t.host());
        try { client.connectTcp(t.host(), t.port()); lastPortName = null; lastTcp = t; rememberTcp(t); PREFS.put("lastRadio", "tcp:" + t.host() + ":" + t.port()); }
        catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "TCP connect failed", JOptionPane.ERROR_MESSAGE); }
    }

    private void toggleConnect() {
        if (client.isConnected()) { lastPortName = null; lastTcp = null; client.disconnect("Disconnect button"); return; }
        Object sel = ports.getSelectedItem();
        if (sel == ADD_TCP) { connectTcp(); return; }
        if (sel instanceof TcpItem t) { connectTcp(t); return; }
        if (!(sel instanceof PortItem pi)) { refreshPorts(); return; }
        try {
            lastPortName = pi.port().getSystemPortName();
            PREFS.put("lastRadio", "serial:" + lastPortName);
            PREFS.put("lastRadioUsb", usbIdentity(pi.port()));
            client.connect(pi.port());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Connect failed", JOptionPane.ERROR_MESSAGE);
        }
    }
}
