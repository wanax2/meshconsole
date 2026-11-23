package meshconsole.ui;

import meshconsole.analysis.Json;
import meshconsole.mesh.MeshClient;
import meshconsole.mqtt.HeardBy;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Desktop "Heard by" tab: the same background service and data as the web console's tab (1.26.2). */
class HeardByPanel extends JPanel {
    private final HeardBy hb;
    private final JCheckBox on = new JCheckBox("on"), pub = new JCheckBox("public server"), priv = new JCheckBox("my private server");
    private final JTextField pubHost = new JTextField(16), pubTopic = new JTextField(8), privHost = new JTextField("localhost", 12), privPort = new JTextField(5),
        privUser = new JTextField(7), privTopic = new JTextField(7), extra = new JTextField(30);
    private final JPasswordField privPass = new JPasswordField(10);
    private final JComboBox<String> node = new JComboBox<>();
    private final JLabel status = new JLabel(" "), summary = new JLabel(" "), daily = new JLabel(" ");
    private final Model model = new Model();
    private List<Object> nodes = new ArrayList<>();
    private boolean filling;
    @SuppressWarnings("unchecked") private static Map<String, Object> m(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }
    @SuppressWarnings("unchecked") private static List<Object> l(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }
    private static boolean b(Object o) { return o instanceof Boolean x ? x : Boolean.parseBoolean(String.valueOf(o)); }

    private static final String[] COLS = {"Gateway", "ID", "km", "24 h", "7 days", "Fewest hops", "Direct", "Best SNR", "Best RSSI", "Last"};
    private class Model extends AbstractTableModel {
        List<Map<String, Object>> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            Map<String, Object> g = rows.get(r);
            return switch (c) {
                case 0 -> Json.str(g.get("name")); case 1 -> Json.str(g.get("id"));
                case 2 -> Json.num(g.get("km"), -1) < 0 ? "?" : String.valueOf(Json.num(g.get("km"), 0));
                case 3 -> (long) Json.num(g.get("n1"), 0); case 4 -> (long) Json.num(g.get("n7"), 0);
                case 5 -> Json.num(g.get("minHops"), -1) < 0 ? "?" : String.valueOf((long) Json.num(g.get("minHops"), 0));
                case 6 -> (long) Json.num(g.get("direct"), 0);
                case 7 -> g.get("bestSnr") == null ? "" : String.valueOf(g.get("bestSnr"));
                case 8 -> g.get("bestRssi") == null ? "" : String.valueOf((long) Json.num(g.get("bestRssi"), 0));
                default -> Fmt.time((long) Json.num(g.get("last"), 0));
            };
        }
    }

    HeardByPanel(MeshClient client) {
        super(new BorderLayout(6, 6));
        this.hb = HeardBy.forDir(client, meshconsole.DataDir.get());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel intro = new JLabel("Which other stations' gateways hear your nodes (from MQTT). Same data as the web console's Heard by tab.");
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT)); r1.add(on); r1.add(pub); r1.add(pubHost); r1.add(new JLabel("topic")); r1.add(pubTopic);
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT)); r2.add(priv); r2.add(privHost); r2.add(new JLabel("port")); r2.add(privPort); r2.add(new JLabel("user")); r2.add(privUser);
        r2.add(new JLabel("password")); r2.add(privPass); r2.add(new JLabel("topic")); r2.add(privTopic);
        JButton save = new JButton("Save"), map = new JButton("Open map in browser");
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT)); r3.add(new JLabel("Also track (other house, G2, T-Beam):")); r3.add(extra); r3.add(save); r3.add(map);
        JButton rel = new JButton("Reliability…");
        rel.setToolTipText("What share of this node's packets each gateway heard, with an AI explanation if the local AI is set up");
        rel.addActionListener(e -> showReliability());
        JPanel r4 = new JPanel(new FlowLayout(FlowLayout.LEFT)); r4.add(new JLabel("Node:")); r4.add(node); r4.add(rel); r4.add(summary);
        for (JComponent c : new JComponent[]{intro, r1, r2, r3, r4}) { c.setAlignmentX(LEFT_ALIGNMENT); top.add(c); }
        add(top, BorderLayout.NORTH);
        JTable t = new JTable(model); t.setAutoCreateRowSorter(true); t.setFillsViewportHeight(true);
        add(new JScrollPane(t), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new GridLayout(2, 1)); bottom.add(daily); bottom.add(status);
        add(bottom, BorderLayout.SOUTH);

        save.addActionListener(e -> {
            Map<String, String> f = new HashMap<>();
            f.put("enabled", String.valueOf(on.isSelected())); f.put("publicOn", String.valueOf(pub.isSelected())); f.put("publicHost", pubHost.getText()); f.put("publicTopic", pubTopic.getText());
            f.put("privateOn", String.valueOf(priv.isSelected())); f.put("privateHost", privHost.getText()); f.put("privatePort", privPort.getText()); f.put("privateUser", privUser.getText());
            f.put("privateTopic", privTopic.getText()); f.put("extraNodes", extra.getText());
            if (privPass.getPassword().length > 0) f.put("privatePass", new String(privPass.getPassword()));
            try { hb.save(f); status.setText("Saved – connecting…"); privPass.setText(""); } catch (Exception ex) { status.setText("Couldn't save: " + ex.getMessage()); }
            new javax.swing.Timer(4000, ev -> { ((javax.swing.Timer) ev.getSource()).stop(); refresh(true); }).start();
        });
        map.addActionListener(e -> {
            try { Desktop.getDesktop().browse(new java.net.URI("http://localhost:" + WebConsole.port() + "/#heard")); }
            catch (Exception ex) { status.setText("Open http://localhost:" + WebConsole.port() + "/#heard in a browser"); }
        });
        node.addActionListener(e -> { if (!filling) showNode(); });
        refresh(true);
        new javax.swing.Timer(30_000, e -> refresh(false)).start();
    }

    @SuppressWarnings("unchecked")
    private void refresh(boolean settingsToo) {
        new SwingWorker<Map<String, Object>, Void>() {
            @Override protected Map<String, Object> doInBackground() { return (Map<String, Object>) Json.parse(hb.json()); }
            @Override protected void done() {
                Map<String, Object> d; try { d = get(); } catch (Exception ex) { return; }
                if (settingsToo) {
                    Map<String, Object> s = m(d.get("settings"));
                    on.setSelected(b(d.get("enabled"))); pub.setSelected(b(s.get("publicOn"))); pubHost.setText(Json.str(s.get("publicHost"))); pubTopic.setText(Json.str(s.get("publicTopic")));
                    priv.setSelected(b(s.get("privateOn"))); privHost.setText(Json.str(s.get("privateHost"))); privPort.setText(Json.str(s.get("privatePort")));
                    privUser.setText(Json.str(s.get("privateUser"))); privTopic.setText(Json.str(s.get("privateTopic"))); extra.setText(Json.str(s.get("extraNodes")));
                    privPass.setToolTipText(b(s.get("hasPrivatePass")) ? "saved – leave empty to keep it" : "");
                }
                StringBuilder st = new StringBuilder(b(d.get("enabled")) ? "Running. " : "Off. ");
                for (Object o : l(d.get("sources"))) { Map<String, Object> m = m(o); st.append(Json.str(m.get("name"))).append(": ").append(Json.str(m.get("status"))).append(" · ").append((long) Json.num(m.get("packets"), 0)).append(" packets.  "); }
                status.setText(st.toString());
                nodes = l(d.get("nodes"));
                String keep = (String) node.getSelectedItem();
                filling = true; node.removeAllItems();
                for (Object o : nodes) { Map<String, Object> n = m(o); node.addItem(Json.str(n.get("name")) + " (" + Json.str(n.get("id")) + ")"); }
                if (keep != null) node.setSelectedItem(keep);
                filling = false; showNode();
            }
        }.execute();
    }

    /** 1.29.5: the reliability report for the selected node, in a window of its own. */
    private void showReliability() {
        int i = node.getSelectedIndex();
        if (i < 0 || i >= nodes.size()) return;
        String id = Json.str(m(nodes.get(i)).get("id"));
        int num = (int) Long.parseLong(id.replaceFirst("^!", ""), 16);
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Reliability – " + node.getSelectedItem(), java.awt.Dialog.ModalityType.MODELESS);
        JComboBox<String> span = new JComboBox<>(new String[]{"Last 24 hours", "Last 3 days", "Last 7 days"});
        int[] hrs = {24, 72, 168};
        JTextArea sum = new JTextArea(4, 70); sum.setLineWrap(true); sum.setWrapStyleWord(true); sum.setEditable(false);
        JTextArea ai = new JTextArea(6, 70); ai.setLineWrap(true); ai.setWrapStyleWord(true); ai.setEditable(false);
        String[] cols = {"Gateway", "ID", "Heard", "Share", "Median hops", "Median SNR", "Direct", "km", "Last"};
        javax.swing.table.DefaultTableModel tm = new javax.swing.table.DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
        JTable t = new JTable(tm); t.setAutoCreateRowSorter(true);
        Runnable load = () -> {
            HeardBy.Reliability r = hb.reliability(num, hrs[span.getSelectedIndex()]);
            sum.setText(HeardBy.summary(r));
            tm.setRowCount(0);
            for (HeardBy.GwRel g : r.gateways()) tm.addRow(new Object[]{g.name(), g.id(), g.heard(), String.format("%.0f%%", g.pct()),
                    Double.isNaN(g.medHops()) ? "?" : String.format("%.1f", g.medHops()), Double.isNaN(g.medSnr()) ? "" : String.format("%.1f", g.medSnr()),
                    g.direct(), g.km() < 0 ? "?" : String.format("%.1f", g.km()), Fmt.time(g.last())});
        };
        span.addActionListener(e -> { load.run(); ai.setText(""); });
        JButton explain = new JButton("Explain (AI)");
        meshconsole.bbs.LocalAi local = meshconsole.bbs.LocalAi.forDir(meshconsole.DataDir.get());
        explain.setEnabled(local.enabled());
        if (!local.enabled()) explain.setToolTipText("Set enabled=true and servers in ai.properties in the data folder to use the local AI");
        explain.addActionListener(e -> {
            explain.setEnabled(false); ai.setText("Asking the local AI… (the first answer can take a minute while the model loads)");
            HeardBy.Reliability r = hb.reliability(num, hrs[span.getSelectedIndex()]);
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() { return local.chat(WebConsole.EXPLAIN_SYSTEM, List.<String[]>of(new String[]{"user", WebConsole.explainPrompt(r)}), 450); }
                @Override protected void done() { try { String a = get(); ai.setText(a != null ? a : "No answer: " + local.lastError()); } catch (Exception ex) { ai.setText("No answer: " + ex.getMessage()); } explain.setEnabled(true); }
            }.execute();
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT)); top.add(span); top.add(explain);
        JPanel north = new JPanel(new BorderLayout(4, 4)); north.add(top, BorderLayout.NORTH); north.add(new JScrollPane(sum), BorderLayout.CENTER);
        JPanel c = new JPanel(new BorderLayout(6, 6)); c.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        c.add(north, BorderLayout.NORTH); c.add(new JScrollPane(t), BorderLayout.CENTER);
        JScrollPane aiScroll = new JScrollPane(ai); aiScroll.setBorder(BorderFactory.createTitledBorder("AI explanation")); c.add(aiScroll, BorderLayout.SOUTH);
        d.setContentPane(c); d.setSize(980, 620); d.setLocationRelativeTo(this);
        load.run(); d.setVisible(true);
    }

    private void showNode() {
        int i = node.getSelectedIndex();
        if (i < 0 || i >= nodes.size()) { model.rows = List.of(); model.fireTableDataChanged(); summary.setText(" "); daily.setText(" "); return; }
        Map<String, Object> n = m(nodes.get(i));
        List<Map<String, Object>> rows = new ArrayList<>(); for (Object o : l(n.get("gateways"))) rows.add(m(o));
        model.rows = rows; model.fireTableDataChanged();
        long direct = rows.stream().filter(g -> Json.num(g.get("direct"), 0) > 0).count();
        Map<String, Object> far = rows.stream().filter(g -> Json.num(g.get("km"), -1) >= 0).max(Comparator.comparingDouble(g -> Json.num(g.get("km"), 0))).orElse(null);
        summary.setText(rows.size() + " gateway(s) in 7 days · " + direct + " directly" + (far == null ? "" : " · farthest " + Json.num(far.get("km"), 0) + " km (" + (Json.str(far.get("name")).isBlank() ? Json.str(far.get("id")) : Json.str(far.get("name"))) + ")"));
        StringBuilder dl = new StringBuilder("Gateways per day: ");
        List<Object> days = l(n.get("daily"));
        for (int k = Math.max(0, days.size() - 7); k < days.size(); k++) { Map<String, Object> d = m(days.get(k)); dl.append(Json.str(d.get("day")).substring(5)).append(" ").append((long) Json.num(d.get("gateways"), 0)).append("   "); }
        daily.setText(days.isEmpty() ? "No sightings yet." : dl.toString());
    }
}
