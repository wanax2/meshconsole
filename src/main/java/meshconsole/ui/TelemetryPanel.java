package meshconsole.ui;

import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Environment, device and power telemetry per node. */
class TelemetryPanel extends JPanel {
    private final MeshState state;
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    private static final String[] COLS = {"Node", "Updated", "Battery", "Volts", "Ch util", "Air TX", "Uptime",
            "Temp °C", "Humidity %", "Pressure hPa", "IAQ", "Lux", "Wind", "Power ch1", "Power ch2", "Power ch3", "More"};

    private class Model extends AbstractTableModel {
        List<NodeEntry> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            NodeEntry n = rows.get(r);
            return switch (c) {
                case 0 -> n.displayName();
                case 1 -> Fmt.ago(Math.max(n.deviceMetricsTime, Math.max(n.envTime, n.powerTime))) + " ago";
                case 2 -> n.battery < 0 ? "" : n.battery > 100 ? "ext" : n.battery + "%";
                case 3 -> n.voltage == 0 ? "" : String.format("%.2f", n.voltage);
                case 4 -> n.channelUtil == 0 ? "" : String.format("%.1f%%", n.channelUtil);
                case 5 -> n.airUtilTx == 0 ? "" : String.format("%.2f%%", n.airUtilTx);
                case 6 -> n.uptimeSeconds < 0 ? "" : n.uptimeSeconds / 86400 + "d " + (n.uptimeSeconds % 86400) / 3600 + "h";
                case 7 -> n.temperature == null ? "" : String.format("%.1f", n.temperature);
                case 8 -> n.humidity == null ? "" : String.format("%.0f", n.humidity);
                case 9 -> n.pressure == null ? "" : String.format("%.1f", n.pressure);
                case 10 -> n.iaq == null ? "" : String.valueOf(n.iaq);
                case 11 -> n.lux == null ? "" : String.format("%.0f", n.lux);
                case 12 -> n.windSpeed == null ? "" : String.format("%.1f m/s%s", n.windSpeed, n.windDirection == null ? "" : " @" + n.windDirection + "°");
                case 13, 14, 15 -> power(n, c - 13);
                case 16 -> n.powerChannels > 3 ? (n.powerChannels - 3) + " more ch" : "";
                default -> "";
            };
        }
        private String power(NodeEntry n, int ch) {
            if (ch >= n.powerChannels) return "";
            float v = n.powerVolts[ch], a = n.powerAmps[ch];
            String sv = Float.isNaN(v) ? "" : String.format("%.2fV", v);
            String sa = Float.isNaN(a) ? "" : String.format(" %.0fmA", a);
            return sv + sa;
        }
    }

    TelemetryPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));
        table.setRowHeight(20);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] w = {150, 70, 55, 50, 55, 55, 70, 60, 70, 80, 40, 50, 100, 90, 90, 90, 70};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        add(new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);
        JLabel note = new JLabel("  Device metrics arrive from every node; environment/power values only from nodes with sensors attached (BME280, INA219, …).");
        note.setFont(note.getFont().deriveFont(11f));
        add(note, BorderLayout.SOUTH);
        state.addListener(new MeshState.Listener() {
            @Override public void onTelemetryChanged() { SwingUtilities.invokeLater(TelemetryPanel.this::reload); }
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(TelemetryPanel.this::reload); }
        });
    }

    void reload() {
        List<NodeEntry> all = state.nodes();
        List<NodeEntry> rows = new ArrayList<>();
        for (NodeEntry n : all) if (n.deviceMetricsTime > 0 || n.envTime > 0 || n.powerTime > 0 || n.battery >= 0) rows.add(n);
        model.rows = rows;
        model.fireTableDataChanged();
    }
}
