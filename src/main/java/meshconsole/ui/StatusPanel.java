package meshconsole.ui;

import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import meshconsole.mesh.SignalSample;
import meshconsole.mesh.UtilSample;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.TelemetryProtos.LocalStats;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

class StatusPanel extends JPanel {
    private final MeshState state;
    private final JTextArea info = new JTextArea(12, 40);
    private final SignalChart chart = new SignalChart();
    private final UtilChart utilChart = new UtilChart();
    private final JComboBox<NodeChoice> chartNode = new JComboBox<>();
    private final JComboBox<String> chartRange = new JComboBox<>(new String[]{"Live (this session)", "Last hour", "Last 24 h", "Last 7 days", "Last 30 days (hourly avg)", "Last year (hourly avg)"});
    private meshconsole.mesh.SignalHistory history;
    private final JButton captureBtn = new JButton("● Record packets");
    private final JButton replayBtn = new JButton("Replay capture…");
    private final JLabel captureInfo = new JLabel(" ");
    private final JTextArea log = new JTextArea();
    private final JLabel lastSignal = new JLabel(" ");
    private final JCheckBox verbose = new JCheckBox("Verbose");
    private final JCheckBox trace = new JCheckBox("Trace frames");
    private final JCheckBox toFile = new JCheckBox("Write meshconsole.log", true);
    private java.io.PrintWriter logFile;
    private meshconsole.mesh.MeshClient client;

    record NodeChoice(int num, String label) {
        @Override public String toString() { return label; }
    }

    StatusPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));

        info.setEditable(false);
        info.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane infoScroll = new JScrollPane(info);
        infoScroll.setBorder(BorderFactory.createTitledBorder("Radio"));

        JPanel chartBox = new JPanel(new BorderLayout(4, 4));
        chartBox.setBorder(BorderFactory.createTitledBorder("Live signal (received packets)"));
        JPanel chartTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        chartTop.add(new JLabel("Show:"));
        chartNode.addItem(new NodeChoice(0, "All nodes"));
        chartNode.addActionListener(e -> refreshChart());
        chartTop.add(chartNode);
        chartTop.add(new JLabel("Range:"));
        chartRange.addActionListener(e -> refreshChart());
        chartTop.add(chartRange);
        chartTop.add(lastSignal);
        chartBox.add(chartTop, BorderLayout.NORTH);
        chartBox.add(chart, BorderLayout.CENTER);
        utilChart.setPreferredSize(new Dimension(500, 110));
        JPanel utilBox = new JPanel(new BorderLayout());
        utilBox.setBorder(BorderFactory.createTitledBorder("Channel utilisation (blue) and air-time TX (orange), this radio, %"));
        utilBox.add(utilChart, BorderLayout.CENTER);
        JPanel charts = new JPanel(new BorderLayout());
        charts.add(chartBox, BorderLayout.CENTER);
        charts.add(utilBox, BorderLayout.SOUTH);

        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, infoScroll, charts);
        top.setResizeWeight(0.4);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(log);
        JPanel logBox = new JPanel(new BorderLayout());
        logBox.setBorder(BorderFactory.createTitledBorder("Device / app log"));
        JPanel logTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        verbose.setToolTipText("Log every packet (from/to, port, hops, RSSI/SNR), config item, admin message and MQTT proxy transfer");
        trace.setToolTipText("Also log the full protobuf content of every frame sent to and received from the radio (very chatty)");
        toFile.setToolTipText("Append everything shown here to meshconsole.log in the working directory");
        JButton clear = new JButton("Clear");
        JButton copy = new JButton("Copy");
        logTop.add(verbose); logTop.add(trace); logTop.add(toFile); logTop.add(clear); logTop.add(copy);
        logTop.add(new JSeparator(SwingConstants.VERTICAL));
        captureBtn.setToolTipText("Save every frame from the radio to a .mcap file; replay it later (or send it with a bug report)");
        replayBtn.setToolTipText("Feed a recorded .mcap file through the app as if it were live traffic");
        logTop.add(captureBtn); logTop.add(replayBtn); logTop.add(captureInfo);
        captureBtn.addActionListener(e -> toggleCapture());
        replayBtn.addActionListener(e -> replay());
        logBox.add(logTop, BorderLayout.NORTH);
        logBox.add(logScroll, BorderLayout.CENTER);
        verbose.addActionListener(e -> state.setVerbose(verbose.isSelected()));
        trace.addActionListener(e -> { if (client != null) client.setTraceFrames(trace.isSelected()); });
        clear.addActionListener(e -> log.setText(""));
        copy.addActionListener(e -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(log.getText()), null));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, logBox);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);

        state.addListener(new MeshState.Listener() {
            @Override public void onStatusChanged() { SwingUtilities.invokeLater(StatusPanel.this::refreshInfo); }
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(StatusPanel.this::refreshAll); }
            @Override public void onLog(String line) { SwingUtilities.invokeLater(() -> appendLog(line)); }
            @Override public void onTelemetryChanged() { SwingUtilities.invokeLater(() -> utilChart.setData(state.utilHistory())); }
        });
    }

    void setClient(meshconsole.mesh.MeshClient c) { client = c; }
    void setHistory(meshconsole.mesh.SignalHistory h) { history = h; }

    private void toggleCapture() {
        if (client == null) return;
        if (client.isCapturing()) { client.stopCapture(); captureBtn.setText("● Record packets"); captureInfo.setText(" "); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(meshconsole.DataDir.file("capture-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".mcap").toFile());
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            client.startCapture(fc.getSelectedFile().toPath());
            captureBtn.setText("■ Stop recording");
        } catch (java.io.IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "Capture", JOptionPane.ERROR_MESSAGE); }
    }

    private void replay() {
        if (client == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Mesh Console capture (*.mcap)", "mcap"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        String[] speeds = {"As fast as possible", "Real time", "10× speed"};
        int sp = JOptionPane.showOptionDialog(this, "Replay speed:", "Replay", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, speeds, speeds[0]);
        if (sp < 0) return;
        double speed = sp == 0 ? 0 : sp == 1 ? 1 : 10;
        final boolean[] cancel = {false};
        replayBtn.setText("Stop replay");
        for (java.awt.event.ActionListener l : replayBtn.getActionListeners()) replayBtn.removeActionListener(l);
        replayBtn.addActionListener(e -> cancel[0] = true);
        new Thread(() -> {
            try { long n = client.replay(fc.getSelectedFile().toPath(), speed, () -> cancel[0]); SwingUtilities.invokeLater(() -> captureInfo.setText(n + " frames replayed")); }
            catch (java.io.IOException ex) { SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, ex.getMessage(), "Replay", JOptionPane.ERROR_MESSAGE)); }
            finally { SwingUtilities.invokeLater(() -> { replayBtn.setText("Replay capture…"); for (java.awt.event.ActionListener l : replayBtn.getActionListeners()) replayBtn.removeActionListener(l); replayBtn.addActionListener(e -> replay()); }); }
        }, "replay").start();
    }

    void flushLog() { if (logFile != null) logFile.flush(); }

    void tickCapture() {
        if (client != null && client.isCapturing()) captureInfo.setText("recording: " + client.captureFrames() + " frames");
    }

    void appendLog(String line) {
        String stamped = Fmt.time(System.currentTimeMillis()) + "  " + line;
        if (toFile.isSelected()) {
            try {
                if (logFile == null) logFile = new java.io.PrintWriter(new java.io.FileWriter(meshconsole.DataDir.file("meshconsole.log").toFile(), true), true);
                logFile.println(stamped);
            } catch (java.io.IOException ignored) { }
        }
        log.append(stamped + "\n");
        if (log.getDocument().getLength() > 200_000) {
            try { log.getDocument().remove(0, 50_000); } catch (Exception ignored) { }
        }
        log.setCaretPosition(log.getDocument().getLength());
    }

    void refreshAll() {
        refreshInfo();
        refreshNodeChoices();
        refreshChart();
    }

    private void refreshNodeChoices() {
        NodeChoice sel = (NodeChoice) chartNode.getSelectedItem();
        int selNum = sel == null ? 0 : sel.num();
        List<NodeEntry> nodes = state.nodes();
        List<NodeChoice> choices = new ArrayList<>();
        choices.add(new NodeChoice(0, "All nodes"));
        int my = state.myNodeNum();
        for (NodeEntry n : nodes) if (n.num != my) choices.add(new NodeChoice(n.num, n.displayName()));
        boolean same = choices.size() == chartNode.getItemCount();
        if (same) for (int i = 0; i < choices.size(); i++) if (!choices.get(i).equals(chartNode.getItemAt(i))) { same = false; break; }
        if (same) return;
        chartNode.removeAllItems();
        for (NodeChoice c : choices) {
            chartNode.addItem(c);
            if (c.num() == selNum) chartNode.setSelectedItem(c);
        }
    }

    void refreshChart() {
        NodeChoice sel = (NodeChoice) chartNode.getSelectedItem();
        int range = chartRange.getSelectedIndex();
        List<SignalSample> s;
        if (range <= 0 || history == null) s = state.signalHistory();
        else {
            long[] ms = {0, 3600_000L, 24 * 3600_000L, 7 * 24 * 3600_000L, 30L * 24 * 3600_000L, 365L * 24 * 3600_000L};
            s = history.since(System.currentTimeMillis() - ms[range]);
        }
        chart.setData(s, sel == null ? 0 : sel.num());
        SignalSample last = null;
        for (int i = s.size() - 1; i >= 0; i--) {
            if (sel == null || sel.num() == 0 || s.get(i).from() == sel.num()) { last = s.get(i); break; }
        }
        if (last == null) lastSignal.setText(" ");
        else lastSignal.setText(String.format("  last: %s  %d dBm / %.2f dB  (%s)",
                state.nodeName(last.from()), last.rssi(), last.snr(), Fmt.ago(last.time())));
    }

    private String firmwareNote = "";
    private long firmwareChecked;

    private void checkFirmware() {
        if (System.currentTimeMillis() - firmwareChecked < 6 * 3600_000L || state.firmware().isEmpty()) return;
        firmwareChecked = System.currentTimeMillis();
        new Thread(() -> {
            try {
                meshconsole.tools.FirmwareCheck.Result r = meshconsole.tools.FirmwareCheck.latest();
                int cmp = meshconsole.tools.FirmwareCheck.compare(state.firmware(), r.latest());
                firmwareNote = cmp < 0 ? "  (latest stable " + r.latest() + " – update via https://flasher.meshtastic.org)" : cmp == 0 ? "  (latest stable)" : "  (newer than stable " + r.latest() + ")";
            } catch (Exception e) { firmwareNote = ""; }
            SwingUtilities.invokeLater(this::refreshInfo);
        }, "firmware-check").start();
    }

    void refreshInfo() {
        checkFirmware();
        StringBuilder sb = new StringBuilder();
        NodeEntry me = state.myNode();
        if (state.myNodeNum() == 0) {
            sb.append("Not connected / waiting for radio…\n");
        } else {
            sb.append("My node:      ").append(me != null ? me.displayName() : "").append("  ")
              .append(String.format("!%08x", state.myNodeNum())).append('\n');
            if (me != null) {
                sb.append("Hardware:     ").append(me.hwModel).append('\n');
                if (me.battery >= 0) {
                    sb.append("Battery:      ").append(me.battery > 100 ? "external power" : me.battery + " %")
                      .append(String.format("  (%.2f V)", me.voltage)).append('\n');
                }
                sb.append("Channel util: ").append(String.format("%.1f %%   air-util TX %.2f %%", me.channelUtil, me.airUtilTx)).append('\n');
                if (me.hasPosition) sb.append("Position:     ").append(String.format("%.5f, %.5f  alt %d m", me.lat, me.lon, me.altitude)).append('\n');
            }
            sb.append("Firmware:     ").append(state.firmware()).append(firmwareNote).append('\n');
            Config.LoRaConfig lora = state.lora();
            if (lora != null) {
                sb.append("LoRa:         ").append(lora.getRegion()).append("  ")
                  .append(lora.getUsePreset() ? lora.getModemPreset().name()
                          : "BW " + lora.getBandwidth() + " SF" + lora.getSpreadFactor() + " CR" + lora.getCodingRate())
                  .append("  txpwr ").append(lora.getTxPower()).append(" dBm  hops ").append(lora.getHopLimit()).append('\n');
                if (lora.getOverrideFrequency() != 0) sb.append("Frequency:    override ").append(lora.getOverrideFrequency()).append(" MHz\n");
                if (lora.getChannelNum() != 0) sb.append("Slot:         channel_num ").append(lora.getChannelNum()).append('\n');
            }
            List<Channel> chs = state.channels();
            if (!chs.isEmpty()) {
                sb.append("Channels:     ");
                for (Channel c : chs) {
                    String name = c.getSettings().getName().isEmpty() ? (c.getIndex() == 0 ? "(default)" : "?") : c.getSettings().getName();
                    sb.append(c.getIndex()).append('=').append(name).append(c.getRole() == Channel.Role.PRIMARY ? "*" : "").append("  ");
                }
                sb.append('\n');
            }
            LocalStats ls = state.localStats();
            if (ls != null) {
                sb.append(String.format("Local stats:  rx %d (bad %d, dupe %d)  tx %d  relayed %d  online %d/%d nodes\n",
                        ls.getNumPacketsRx(), ls.getNumPacketsRxBad(), ls.getNumRxDupe(), ls.getNumPacketsTx(),
                        ls.getNumTxRelay(), ls.getNumOnlineNodes(), ls.getNumTotalNodes()));
                if (ls.getNoiseFloor() != 0) sb.append("Noise floor:  ").append(ls.getNoiseFloor()).append(" dBm\n");
                if (ls.getHeapTotalBytes() != 0) sb.append(String.format("Memory:       %d / %d KB free\n", ls.getHeapFreeBytes() / 1024, ls.getHeapTotalBytes() / 1024));
                if (ls.getNumTxRelayCanceled() != 0) sb.append("Relays cancelled: ").append(ls.getNumTxRelayCanceled()).append('\n');
                sb.append("Uptime:       ").append(ls.getUptimeSeconds() / 3600).append(" h ").append((ls.getUptimeSeconds() % 3600) / 60).append(" m\n");
            }
            if (state.queueFree() >= 0) sb.append("TX queue:     ").append(state.queueFree()).append(" free slots\n");
            long drift = state.clockDriftMs();
            if (drift != Long.MIN_VALUE) {
                long abs = Math.abs(drift) / 1000;
                sb.append("Radio clock:  ").append(abs < 2 ? "in sync with PC" : abs > 365L * 86400 ? "not set (no GPS/phone time source)" : String.format("%s%d s vs PC", drift > 0 ? "+" : "-", abs)).append('\n');
            }
            sb.append("Nodes known:  ").append(state.nodes().size()).append('\n');
            if (state.encryptedSeen() > 0) sb.append("Undecodable:  ").append(state.encryptedSeen()).append(" encrypted packets\n");
            sb.append(state.configComplete() ? "" : "\n(config still loading…)\n");
        }
        info.setText(sb.toString());
    }
}

/** Small strip chart of channel utilisation and air-time TX percentage. */
class UtilChart extends JComponent {
    private List<UtilSample> data = List.of();

    void setData(List<UtilSample> d) { data = d; repaint(); }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        int left = 34, right = w - 10, top = 8, bottom = h - 18;
        g.setColor(Color.GRAY);
        g.drawRect(left, top, right - left, bottom - top);
        g.setFont(g.getFont().deriveFont(10f));
        for (int v : new int[]{0, 25, 50, 75, 100}) {
            int y = bottom - (bottom - top) * v / 100;
            g.setColor(v == 25 ? new Color(230, 160, 120) : new Color(225, 225, 225));
            g.drawLine(left + 1, y, right - 1, y);
            g.setColor(Color.DARK_GRAY);
            g.drawString(String.valueOf(v), 6, y + 4);
        }
        if (data.isEmpty()) { g.setColor(Color.DARK_GRAY); g.drawString("Waiting for device telemetry (every few minutes)", left + 6, top + 14); return; }
        List<UtilSample> pts = data.size() > (right - left) / 3 ? data.subList(data.size() - (right - left) / 3, data.size()) : data;
        int n = pts.size();
        int[] xs = new int[n], yc = new int[n], ya = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = left + (int) ((right - left) * (n == 1 ? 0.5 : (double) i / (n - 1)));
            yc[i] = bottom - (int) ((bottom - top) * Math.min(100, pts.get(i).channelUtil()) / 100);
            ya[i] = bottom - (int) ((bottom - top) * Math.min(100, pts.get(i).airUtilTx()) / 100);
        }
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(40, 90, 200)); g.drawPolyline(xs, yc, n);
        g.setColor(new Color(220, 120, 20)); g.drawPolyline(xs, ya, n);
        UtilSample last = pts.get(n - 1);
        g.setColor(Color.BLACK);
        g.drawString(String.format("now: util %.1f %%  air-tx %.2f %%   (orange line at 25 %% = congested)", last.channelUtil(), last.airUtilTx()), left + 6, bottom + 13);
    }
}
