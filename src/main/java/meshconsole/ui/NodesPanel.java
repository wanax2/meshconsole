package meshconsole.ui;

import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

class NodesPanel extends JPanel {
    private final MeshClient client;
    private final MeshState state;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextArea results = new JTextArea(5, 40);
    private IntConsumer onMessageNode = n -> { };
    private IntConsumer onShowOnMap = n -> { };

    private static final String[] COLS = {"Name", "Short", "ID", "Last heard", "Hops", "SNR", "RSSI", "Battery", "Distance", "Position", "Hardware", "Pkts"};

    private class Model extends AbstractTableModel {
        List<NodeEntry> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            NodeEntry n = rows.get(r);
            NodeEntry me = state.myNode();
            boolean isMe = n.num == state.myNodeNum();
            return switch (c) {
                case 0 -> (isMe ? "★ " : "") + n.displayName();
                case 1 -> n.shortName;
                case 2 -> n.idString();
                case 3 -> isMe ? "(this radio)" : Fmt.ago(n.lastHeardMillis());
                case 4 -> isMe ? "" : n.hopsAway < 0 ? "?" : n.hopsAway == 0 ? "direct" : String.valueOf(n.hopsAway);
                case 5 -> isMe || n.snr == 0 ? "" : String.format("%.1f dB", n.snr);
                case 6 -> isMe || n.rssi == 0 ? "" : n.rssi + " dBm";
                case 7 -> n.battery < 0 ? "" : n.battery > 100 ? "ext" : n.battery + "%";
                case 8 -> (me != null && me.hasPosition && n.hasPosition && !isMe)
                        ? Fmt.distance(Fmt.distanceM(me.lat, me.lon, n.lat, n.lon)) : "";
                case 9 -> n.hasPosition ? String.format("%.4f, %.4f", n.lat, n.lon) : "";
                case 10 -> n.hwModel;
                case 11 -> isMe ? "" : String.valueOf(n.packetsSeen);
                default -> "";
            };
        }
    }

    NodesPanel(MeshClient client) {
        this.client = client;
        this.state = client.state();
        setLayout(new BorderLayout(6, 6));

        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        int[] widths = {160, 50, 90, 90, 50, 70, 70, 60, 80, 150, 140, 40};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel && row < model.rows.size()) {
                    long age = System.currentTimeMillis() - model.rows.get(row).lastHeardMillis();
                    c.setForeground(age < 15 * 60_000 ? new Color(0, 130, 0) : age < 2 * 3600_000 ? new Color(180, 120, 0) : Color.GRAY);
                }
                return c;
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton dm = new JButton("Message node");
        JButton tr = new JButton("Traceroute");
        JButton pos = new JButton("Request position");
        JButton map = new JButton("Show on map");
        buttons.add(dm); buttons.add(tr); buttons.add(pos); buttons.add(map);
        south.add(buttons, BorderLayout.NORTH);
        results.setEditable(false);
        results.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        results.setLineWrap(true);
        JScrollPane rs = new JScrollPane(results);
        rs.setBorder(BorderFactory.createTitledBorder("Traceroute / neighbor results"));
        south.add(rs, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        dm.addActionListener(e -> withSelected(n -> onMessageNode.accept(n.num)));
        map.addActionListener(e -> withSelected(n -> onShowOnMap.accept(n.num)));
        tr.addActionListener(e -> withSelected(n -> {
            try { client.traceroute(n.num); } catch (IOException ex) { error(ex); }
        }));
        pos.addActionListener(e -> withSelected(n -> {
            try { client.requestPosition(n.num); } catch (IOException ex) { error(ex); }
        }));

        state.addListener(new MeshState.Listener() {
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(NodesPanel.this::reload); }
            @Override public void onTraceroute(String summary) {
                SwingUtilities.invokeLater(() -> results.append(Fmt.time(System.currentTimeMillis()) + "  " + summary + "\n"));
            }
            @Override public void onLog(String line) {
                if (line.startsWith("Neighbors of"))
                    SwingUtilities.invokeLater(() -> results.append(Fmt.time(System.currentTimeMillis()) + "  " + line + "\n"));
            }
        });
    }

    void setOnMessageNode(IntConsumer c) { onMessageNode = c; }
    void setOnShowOnMap(IntConsumer c) { onShowOnMap = c; }

    private void withSelected(java.util.function.Consumer<NodeEntry> action) {
        int r = table.getSelectedRow();
        if (r < 0 || r >= model.rows.size()) {
            JOptionPane.showMessageDialog(this, "Select a node first.", "No node selected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        NodeEntry n = model.rows.get(r);
        if (n.num == state.myNodeNum()) {
            JOptionPane.showMessageDialog(this, "That's this radio.", "Own node", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        action.accept(n);
    }

    private void error(Exception ex) {
        JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    void reload() {
        int selNum = 0;
        int r = table.getSelectedRow();
        if (r >= 0 && r < model.rows.size()) selNum = model.rows.get(r).num;
        model.rows = state.nodes();
        model.fireTableDataChanged();
        for (int i = 0; i < model.rows.size(); i++) {
            if (model.rows.get(i).num == selNum) { table.setRowSelectionInterval(i, i); break; }
        }
    }

    /** Cheap periodic refresh so the "last heard" column keeps ticking. */
    void tick() {
        if (model.rows.isEmpty()) return;
        model.fireTableRowsUpdated(0, model.rows.size() - 1);
    }
}
