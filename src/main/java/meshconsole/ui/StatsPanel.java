package meshconsole.ui;

import meshconsole.mesh.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Delivery statistics per destination, plus CSV export of everything the app has collected. */
class StatsPanel extends JPanel {
    private final MeshState state;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel summary = new JLabel(" ");

    record Row(int dest, int sent, int delivered, int failed, int pending, double avgAttempts, double avgAckSec) { }

    private static final String[] COLS = {"Destination", "Messages", "Delivered", "Failed", "Pending", "Success %", "Avg attempts", "Avg time to ack"};

    private class Model extends AbstractTableModel {
        List<Row> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            Row x = rows.get(r);
            return switch (c) {
                case 0 -> state.nodeName(x.dest());
                case 1 -> x.sent();
                case 2 -> x.delivered();
                case 3 -> x.failed();
                case 4 -> x.pending();
                case 5 -> x.sent() == 0 ? "" : Math.round(100.0 * x.delivered() / x.sent()) + "%";
                case 6 -> x.avgAttempts() == 0 ? "" : String.format("%.1f", x.avgAttempts());
                case 7 -> x.avgAckSec() == 0 ? "" : String.format("%.0f s", x.avgAckSec());
                default -> "";
            };
        }
    }

    StatsPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        top.add(new JLabel("<html><b>Delivery statistics</b> (direct messages; a retried message counts once)</html>"));
        add(top, BorderLayout.NORTH);
        table.setRowHeight(20);
        JPanel center = new JPanel(new BorderLayout());
        center.add(new JScrollPane(table), BorderLayout.CENTER);
        center.add(summary, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JPanel export = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        export.setBorder(BorderFactory.createTitledBorder("Export CSV"));
        export.add(button("Nodes", this::exportNodes));
        export.add(button("Signal samples", this::exportSignal));
        export.add(button("Telemetry", this::exportTelemetry));
        export.add(button("Messages", this::exportMessages));
        export.add(button("Coverage points", this::exportCoverage));
        export.add(button("Neighbour links", this::exportNeighbors));
        add(export, BorderLayout.SOUTH);

        state.addListener(new MeshState.Listener() {
            @Override public void onMessage(ChatMessage m, boolean isNew) { SwingUtilities.invokeLater(StatsPanel.this::reload); }
        });
        reload();
    }

    private interface Writer { void write(PrintWriter w); }

    private JButton button(String label, Writer w) {
        JButton b = new JButton(label);
        b.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new java.io.File(label.toLowerCase().replace(' ', '_') + ".csv"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path p = fc.getSelectedFile().toPath();
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
                w.write(pw);
                state.emitLog("Exported " + p);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Export failed", JOptionPane.ERROR_MESSAGE);
            }
        });
        return b;
    }

    private static String csv(Object... f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length; i++) {
            String v = f[i] == null ? "" : String.valueOf(f[i]);
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
            if (i > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    private static String iso(long millis) { return millis <= 0 ? "" : java.time.Instant.ofEpochMilli(millis).toString(); }

    private void exportNodes(PrintWriter w) {
        w.println(csv("id", "long_name", "short_name", "hardware", "role", "favorite", "licensed", "hops", "last_heard", "first_seen", "snr", "rssi", "avg_rssi", "avg_snr", "packets", "direct_pct", "battery", "voltage", "lat", "lon", "alt", "neighbours"));
        for (NodeEntry n : state.nodes())
            w.println(csv(n.idString(), n.longName, n.shortName, n.hwModel, n.role, n.isFavorite, n.isLicensed, n.hopsAway, iso(n.lastHeardMillis()), iso(n.firstSeen),
                    n.snr, n.rssi, n.rssiCount == 0 ? "" : String.format("%.1f", n.avgRssi()), n.snrCount == 0 ? "" : String.format("%.2f", n.avgSnr()),
                    n.packetsSeen, n.directPercent() < 0 ? "" : n.directPercent(), n.battery < 0 ? "" : n.battery, n.voltage,
                    n.hasPosition ? n.lat : "", n.hasPosition ? n.lon : "", n.hasPosition ? n.altitude : "", n.neighbors.size()));
    }

    private void exportSignal(PrintWriter w) {
        w.println(csv("time", "from_id", "from_name", "rssi", "snr", "hops"));
        for (SignalSample s : state.signalHistory())
            w.println(csv(iso(s.time()), String.format("!%08x", s.from()), state.nodeName(s.from()), s.rssi(), s.snr(), s.hops()));
    }

    private void exportTelemetry(PrintWriter w) {
        w.println(csv("id", "name", "updated", "battery", "voltage", "channel_util", "air_util_tx", "uptime_s", "temperature", "humidity", "pressure", "iaq", "lux", "wind_speed", "wind_dir",
                "p1_v", "p1_a", "p2_v", "p2_a", "p3_v", "p3_a"));
        for (NodeEntry n : state.nodes()) {
            if (n.deviceMetricsTime == 0 && n.envTime == 0 && n.powerTime == 0) continue;
            w.println(csv(n.idString(), n.displayName(), iso(Math.max(n.deviceMetricsTime, Math.max(n.envTime, n.powerTime))), n.battery < 0 ? "" : n.battery, n.voltage, n.channelUtil, n.airUtilTx,
                    n.uptimeSeconds < 0 ? "" : n.uptimeSeconds, n.temperature, n.humidity, n.pressure, n.iaq, n.lux, n.windSpeed, n.windDirection,
                    pv(n, 0, true), pv(n, 0, false), pv(n, 1, true), pv(n, 1, false), pv(n, 2, true), pv(n, 2, false)));
        }
    }

    private static String pv(NodeEntry n, int ch, boolean volts) {
        if (ch >= n.powerChannels) return "";
        float v = volts ? n.powerVolts[ch] : n.powerAmps[ch];
        return Float.isNaN(v) ? "" : String.valueOf(v);
    }

    private void exportMessages(PrintWriter w) {
        w.println(csv("time", "direction", "from", "to", "channel", "packet_id", "status", "detail", "attempt", "rssi", "snr", "hops", "reply_to", "text"));
        for (ChatMessage m : state.messages())
            w.println(csv(iso(m.time), m.outgoing ? "out" : "in", state.nodeName(m.from), state.nodeName(m.to), m.channel, String.format("%08x", m.packetId), m.status, m.statusDetail, m.attempt,
                    m.rssi, m.snr, m.hops, m.replyId == 0 ? "" : String.format("%08x", m.replyId), m.text));
    }

    private void exportCoverage(PrintWriter w) {
        w.println(csv("time", "lat", "lon", "from_id", "from_name", "rssi", "snr"));
        for (CoverageSample c : state.coverage())
            w.println(csv(iso(c.time()), c.lat(), c.lon(), String.format("!%08x", c.from()), state.nodeName(c.from()), c.rssi(), c.snr()));
    }

    private void exportNeighbors(PrintWriter w) {
        w.println(csv("node_id", "node_name", "neighbour_id", "neighbour_name", "snr", "reported"));
        for (NodeEntry n : state.nodes())
            for (Map.Entry<Integer, Float> e : n.neighbors.entrySet())
                w.println(csv(n.idString(), n.displayName(), String.format("!%08x", e.getKey()), state.nodeName(e.getKey()), e.getValue(), iso(n.neighborsTime)));
    }

    void reload() {
        Map<Integer, List<ChatMessage>> byDest = new LinkedHashMap<>();
        for (ChatMessage m : state.messages())
            if (m.outgoing && !m.isBroadcast()) byDest.computeIfAbsent(m.to, k -> new ArrayList<>()).add(m);
        List<Row> rows = new ArrayList<>();
        int tSent = 0, tDel = 0;
        for (Map.Entry<Integer, List<ChatMessage>> e : byDest.entrySet()) {
            // group retries of the same text into one logical message
            Map<String, List<ChatMessage>> logical = new LinkedHashMap<>();
            for (ChatMessage m : e.getValue()) logical.computeIfAbsent(m.text + "|" + (m.time / 3_600_000), k -> new ArrayList<>()).add(m);
            int sent = 0, del = 0, fail = 0, pend = 0, attempts = 0; double ackSum = 0; int ackN = 0;
            for (List<ChatMessage> g : logical.values()) {
                sent++;
                ChatMessage first = g.get(0);
                boolean delivered = false, failed = false;
                for (ChatMessage m : g) {
                    if (m.status == ChatMessage.Status.DELIVERED) { delivered = true; if (m.ackTime > 0) { ackSum += (m.ackTime - first.time) / 1000.0; ackN++; } }
                }
                if (!delivered) {
                    ChatMessage last = g.get(g.size() - 1);
                    failed = last.status == ChatMessage.Status.FAILED || last.status == ChatMessage.Status.HISTORY;
                }
                attempts += g.size();
                if (delivered) del++; else if (failed) fail++; else pend++;
            }
            rows.add(new Row(e.getKey(), sent, del, fail, pend, sent == 0 ? 0 : (double) attempts / sent, ackN == 0 ? 0 : ackSum / ackN));
            tSent += sent; tDel += del;
        }
        rows.sort((a, b) -> Integer.compare(b.sent(), a.sent()));
        model.rows = rows;
        model.fireTableDataChanged();
        summary.setText(tSent == 0 ? "  No direct messages sent yet." : String.format("  %d direct messages, %d delivered (%d%%)", tSent, tDel, Math.round(100.0 * tDel / tSent)));
    }
}
