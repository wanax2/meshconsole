package meshconsole.ui;

import meshconsole.mesh.Airtime;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Who and what is using the channel: airtime by node, by app (port) and by channel. */
class TrafficPanel extends JPanel {
    private final MeshState state;
    private final JLabel summary = new JLabel(" ");
    private final NodeModel byNode = new NodeModel();
    private final KeyModel byPort = new KeyModel("App (port)");
    private final KeyModel byChannel = new KeyModel("Channel");

    record KeyRow(String key, MeshState.Traffic t) { }

    private class NodeModel extends AbstractTableModel {
        final String[] cols = {"Node", "Packets", "Air bytes", "Airtime", "Share", "Last relay via"};
        List<NodeEntry> rows = new ArrayList<>();
        long total = 1;
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            NodeEntry n = rows.get(r);
            return switch (c) {
                case 0 -> n.displayName();
                case 1 -> n.packetsSeen;
                case 2 -> n.airBytes;
                case 3 -> String.format("%.1f s", n.airtimeMs / 1000.0);
                case 4 -> String.format("%.1f%%", 100.0 * n.airtimeMs / total);
                case 5 -> n.lastRelayNode < 0 ? "" : relayName(n.lastRelayNode);
                default -> "";
            };
        }
    }

    private class KeyModel extends AbstractTableModel {
        final String[] cols;
        List<KeyRow> rows = new ArrayList<>();
        long total = 1;
        KeyModel(String first) { cols = new String[]{first, "Packets", "Air bytes", "Airtime", "Share"}; }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            KeyRow x = rows.get(r);
            return switch (c) {
                case 0 -> x.key();
                case 1 -> x.t().packets;
                case 2 -> x.t().bytes;
                case 3 -> String.format("%.1f s", x.t().airtimeMs / 1000.0);
                case 4 -> String.format("%.1f%%", 100.0 * x.t().airtimeMs / total);
                default -> "";
            };
        }
    }

    /** relay_node is only the low byte of the relaying node's number; match it against known nodes. */
    private String relayName(int lowByte) {
        List<String> matches = new ArrayList<>();
        for (NodeEntry n : state.nodes()) if ((n.num & 0xFF) == lowByte) matches.add(n.shortLabel());
        return matches.isEmpty() ? String.format("..%02x", lowByte) : String.join("/", matches);
    }

    TrafficPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        JButton reset = new JButton("Reset counters");
        reset.addActionListener(e -> state.resetTraffic());
        top.add(reset);
        top.add(summary);
        add(top, BorderLayout.NORTH);
        JPanel grid = new JPanel(new GridLayout(1, 3, 6, 6));
        grid.add(titled("By node", byNode));
        grid.add(titled("By app", byPort));
        grid.add(titled("By channel", byChannel));
        add(grid, BorderLayout.CENTER);
        JLabel note = new JLabel("  Airtime is computed from packet size and this radio's modem preset (Semtech LoRa formula); only packets this radio heard are counted, so it's a lower bound for the whole mesh.");
        note.setFont(note.getFont().deriveFont(11f));
        add(note, BorderLayout.SOUTH);
        state.addListener(new MeshState.Listener() {
            @Override public void onTrafficChanged() { SwingUtilities.invokeLater(TrafficPanel.this::reload); }
        });
    }

    private static JComponent titled(String t, AbstractTableModel m) {
        JTable tbl = new JTable(m);
        tbl.setRowHeight(20);
        tbl.setAutoCreateRowSorter(true);
        JScrollPane sp = new JScrollPane(tbl);
        sp.setBorder(BorderFactory.createTitledBorder(t));
        return sp;
    }

    private long lastReload;

    void reload() {
        long now = System.currentTimeMillis();
        if (now - lastReload < 1000) return;      // packets can arrive in bursts
        lastReload = now;
        MeshState.Traffic total = state.trafficTotal();
        long tot = Math.max(1, total.airtimeMs);
        List<NodeEntry> nodes = new ArrayList<>();
        for (NodeEntry n : state.nodes()) if (n.airtimeMs > 0) nodes.add(n);
        nodes.sort((a, b) -> Long.compare(b.airtimeMs, a.airtimeMs));
        byNode.rows = nodes; byNode.total = tot; byNode.fireTableDataChanged();
        List<KeyRow> ports = new ArrayList<>();
        state.trafficByPort().forEach((k, v) -> ports.add(new KeyRow(k, v)));
        ports.sort((a, b) -> Long.compare(b.t().airtimeMs, a.t().airtimeMs));
        byPort.rows = ports; byPort.total = tot; byPort.fireTableDataChanged();
        List<KeyRow> chs = new ArrayList<>();
        state.trafficByChannel().forEach((k, v) -> chs.add(new KeyRow(String.valueOf(k), v)));
        byChannel.rows = chs; byChannel.total = tot; byChannel.fireTableDataChanged();
        long elapsed = Math.max(1, now - state.trafficSince());
        summary.setText(String.format("  Since %s: %d packets, %.1f s of airtime = %.2f%% of elapsed time   ·   modem %s%s",
                Fmt.time(state.trafficSince()), total.packets, total.airtimeMs / 1000.0, Math.min(100.0, 100.0 * total.airtimeMs / elapsed), Airtime.presetName(state.lora()),
                state.localStats() != null && state.localStats().getNumRxDupe() > 0 ? "   ·   radio dropped " + state.localStats().getNumRxDupe() + " duplicate packets" : ""));
    }
}
