package meshconsole.ui;

import meshconsole.mesh.MeshState;
import meshconsole.mqtt.MqttWitness;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/** "MQTT witness": what public gateways heard, especially from our own node. */
class WitnessPanel extends JPanel {
    private final MeshState state;
    private MqttWitness witness;
    private final Preferences prefs = Preferences.userNodeForPackage(WitnessPanel.class);
    private final JTextField host = new JTextField(prefs.get("host", "mqtt.meshtastic.org"), 16), user = new JTextField(prefs.get("user", "meshdev"), 8), pass = new JTextField(prefs.get("pass", "large4cats"), 8), topic = new JTextField(prefs.get("topic", "msh/US/#"), 14);
    private final JCheckBox tls = new JCheckBox("TLS", prefs.getBoolean("tls", false));
    private final JButton start = new JButton("Start listening");
    private final JLabel status = new JLabel("Stopped"), summary = new JLabel(" ");
    private final SeenModel mine = new SeenModel(), all = new SeenModel();
    private final GwModel gw = new GwModel();

    private static final String[] COLS = {"Time", "Gateway", "Channel", "From", "To", "Port", "Hops", "RSSI@gw", "SNR@gw", "Text"};

    private class SeenModel extends AbstractTableModel {
        List<MqttWitness.Seen> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            MqttWitness.Seen s = rows.get(r);
            return switch (c) { case 0 -> Fmt.time(s.time()); case 1 -> s.gateway(); case 2 -> s.channel(); case 3 -> state.nodeName(s.from()); case 4 -> s.to() == 0xFFFFFFFF ? "Broadcast" : state.nodeName(s.to());
                case 5 -> s.port(); case 6 -> s.hops() < 0 ? "" : String.valueOf(s.hops()); case 7 -> s.rssi() == 0 ? "" : String.valueOf(s.rssi()); case 8 -> s.snr() == 0 ? "" : String.format("%.1f", s.snr()); default -> s.text(); };
        }
    }
    private class GwModel extends AbstractTableModel {
        final String[] cols = {"Gateway", "Packets", "Last"};
        List<Object[]> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
    }

    WitnessPanel(MeshState state) {
        this.state = state;
        this.witness = new MqttWitness(state, s -> SwingUtilities.invokeLater(() -> { status.setText(s); start.setText(witness != null && witness.isRunning() ? "Stop" : "Start listening"); }));
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel r1 = row(); r1.add(new JLabel("Broker:")); r1.add(host); r1.add(tls); r1.add(new JLabel("user:")); r1.add(user); r1.add(new JLabel("pass:")); r1.add(pass); r1.add(new JLabel("topic:")); r1.add(topic); r1.add(start); r1.add(status);
        JPanel r2 = row(); r2.add(summary);
        JPanel r3 = row(); r3.add(new JLabel("<html>Read-only. Every packet a public gateway uploads is decoded (default key + your radio's channel keys). <b>My packets</b> lists ones from your node(s): if they appear, the mesh hears you, whatever your receiver says. Narrow the topic (e.g. msh/US/VA/#) if the feed is busy.</html>"));
        top.add(r1); top.add(r2); top.add(r3);
        add(top, BorderLayout.NORTH);
        JTable tMine = table(mine), tAll = table(all), tGw = table(gw);
        JScrollPane sMine = new JScrollPane(tMine); sMine.setBorder(BorderFactory.createTitledBorder("My packets as heard by gateways"));
        JScrollPane sAll = new JScrollPane(tAll); sAll.setBorder(BorderFactory.createTitledBorder("Everything on the feed (last 300)"));
        JScrollPane sGw = new JScrollPane(tGw); sGw.setBorder(BorderFactory.createTitledBorder("Gateways"));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sAll, sGw); right.setResizeWeight(0.7);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sMine, right); main.setResizeWeight(0.4);
        add(main, BorderLayout.CENTER);
        start.addActionListener(e -> toggle());
        witness.addListener(s -> SwingUtilities.invokeLater(this::refresh));
        new javax.swing.Timer(3000, e -> { if (witness.isRunning()) refresh(); }).start();
        state.addListener(new MeshState.Listener() { @Override public void onStatusChanged() { witness.addMyNode(state.myNodeNum()); } });
    }

    private static JTable table(AbstractTableModel m) { JTable t = new JTable(m); t.setRowHeight(20); return t; }
    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }

    /** Radios from the library count as "mine" too. */
    void addMyNodes(Collection<Integer> nums) { for (int n : nums) witness.addMyNode(n); }

    private void toggle() {
        if (witness.isRunning()) { witness.close(); return; }
        prefs.put("host", host.getText().trim()); prefs.put("user", user.getText().trim()); prefs.put("pass", pass.getText().trim()); prefs.put("topic", topic.getText().trim()); prefs.putBoolean("tls", tls.isSelected());
        witness.addMyNode(state.myNodeNum());
        new Thread(() -> {
            try { witness.start(host.getText().trim(), tls.isSelected() ? 8883 : 1883, tls.isSelected(), user.getText().trim(), pass.getText().trim(), topic.getText().trim()); }
            catch (IOException ex) { SwingUtilities.invokeLater(() -> status.setText("Failed: " + ex.getMessage())); }
        }, "witness").start();
    }

    private long lastRefresh;
    private void refresh() {
        if (System.currentTimeMillis() - lastRefresh < 1000) return;
        lastRefresh = System.currentTimeMillis();
        List<MqttWitness.Seen> r = witness.recent();
        List<MqttWitness.Seen> m = new ArrayList<>(); for (MqttWitness.Seen s : r) if (s.mine()) m.add(s);
        Collections.reverse(m); mine.rows = m; mine.fireTableDataChanged();
        List<MqttWitness.Seen> a = new ArrayList<>(r.subList(Math.max(0, r.size() - 300), r.size())); Collections.reverse(a); all.rows = a; all.fireTableDataChanged();
        List<Object[]> g = new ArrayList<>();
        for (Map.Entry<String, long[]> e : witness.gateways().entrySet()) g.add(new Object[]{e.getKey(), e.getValue()[0], Fmt.ago(e.getValue()[1]) + " ago"});
        g.sort((x, y) -> Long.compare((Long) y[1], (Long) x[1])); gw.rows = g; gw.fireTableDataChanged();
        summary.setText(String.format("%d packets from %d gateways, %d distinct nodes  ·  MY NODE: %d packets heard by gateways%s", witness.total(), witness.gateways().size(), witness.nodesViaMqtt().size(), witness.mine(), witness.lastMine() == 0 ? " (none yet)" : ", last " + Fmt.ago(witness.lastMine()) + " ago"));
    }
}
