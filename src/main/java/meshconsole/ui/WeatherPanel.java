package meshconsole.ui;

import meshconsole.analysis.*;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import meshconsole.mesh.SignalHistory;
import meshconsole.mesh.SignalSample;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;

/** METAR weather fetching (aviationweather.gov) and signal-vs-weather correlation. */
class WeatherPanel extends JPanel {
    private final MeshState state;
    private final SignalHistory history;
    private final WeatherHistory weather = new WeatherHistory(Path.of("weather_history.csv"));
    private final Preferences prefs = Preferences.userNodeForPackage(WeatherPanel.class);
    private final JTextField station = new JTextField(prefs.get("station", "KDCA"), 6);
    private final JCheckBox auto = new JCheckBox("Auto: nearest to my node", prefs.getBoolean("auto", false));
    private final JCheckBox enabled = new JCheckBox("Fetch every 30 min", prefs.getBoolean("enabled", true));
    private final JLabel current = new JLabel("No observation yet.");
    private final JLabel status = new JLabel(" ");
    private final CorrModel corr = new CorrModel();
    private final JTable corrTable = new JTable(corr);
    private final JComboBox<String> scatterVar = new JComboBox<>(new String[]{"Temperature °C", "Humidity %", "Wind kt", "Pressure hPa"});
    private final Scatter scatter = new Scatter();
    private long lastFetch;

    private static final String[] COLS = {"Node", "Hours", "r temp", "r humidity", "r wind", "r pressure", "Dry RSSI", "Wet RSSI", "Wet hours", "Rain effect"};

    private class CorrModel extends AbstractTableModel {
        List<Analysis.WeatherCorr> rows = List.of();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            Analysis.WeatherCorr w = rows.get(r);
            return switch (c) {
                case 0 -> state.nodeName(w.node());
                case 1 -> w.hours();
                case 2 -> fr(w.rTemp()); case 3 -> fr(w.rHumidity()); case 4 -> fr(w.rWind()); case 5 -> fr(w.rPressure());
                case 6 -> Double.isNaN(w.dryRssi()) ? "" : String.format("%.1f", w.dryRssi());
                case 7 -> Double.isNaN(w.wetRssi()) ? "" : String.format("%.1f", w.wetRssi());
                case 8 -> w.wetHours();
                case 9 -> Double.isNaN(w.wetRssi()) || Double.isNaN(w.dryRssi()) ? "" : String.format("%+.1f dB", w.wetRssi() - w.dryRssi());
                default -> "";
            };
        }
        private String fr(double r) { return Double.isNaN(r) ? "" : String.format("%+.2f", r); }
    }

    WeatherPanel(MeshState state, SignalHistory history) {
        this.state = state;
        this.history = history;
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel r1 = row();
        r1.add(new JLabel("METAR station:")); r1.add(station);
        JButton find = new JButton("Find nearest…");
        find.setToolTipText("List METAR stations near this node's position (needs a position on the radio)");
        find.addActionListener(e -> findNearest());
        r1.add(find); r1.add(auto); r1.add(enabled);
        JButton fetch = new JButton("Fetch now");
        fetch.addActionListener(e -> fetch(true));
        r1.add(fetch);
        JButton backfill = new JButton("Backfill 48 h");
        backfill.setToolTipText("Download the last two days of observations to correlate against existing signal history");
        backfill.addActionListener(e -> backfill());
        r1.add(backfill);
        top.add(r1);
        JPanel r2 = row(); r2.add(current); top.add(r2);
        JPanel r3 = row(); r3.add(status); top.add(r3);
        add(top, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        corrTable.setRowHeight(20);
        corrTable.setAutoCreateRowSorter(true);
        corrTable.getSelectionModel().addListSelectionListener(e -> updateScatter());
        JScrollPane sp = new JScrollPane(corrTable);
        sp.setBorder(BorderFactory.createTitledBorder("Correlation of hourly average RSSI with weather (Pearson r; needs ≥ 12 matched hours per node)"));
        split.setTopComponent(sp);
        JPanel sc = new JPanel(new BorderLayout());
        JPanel scTop = row(); scTop.add(new JLabel("Scatter for selected node:")); scTop.add(scatterVar);
        scatterVar.addActionListener(e -> updateScatter());
        sc.add(scTop, BorderLayout.NORTH);
        sc.add(scatter, BorderLayout.CENTER);
        split.setBottomComponent(sc);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);
        JLabel note = new JLabel("  Source: NOAA aviationweather.gov METARs. r near ±1 = strong relation, near 0 = none; a negative r for humidity means the link weakens as it gets damp (wet foliage). Rain effect = wet-hour RSSI minus dry-hour RSSI.");
        note.setFont(note.getFont().deriveFont(11f));
        add(note, BorderLayout.SOUTH);

        station.addActionListener(e -> prefs.put("station", station.getText().trim().toUpperCase()));
        auto.addActionListener(e -> prefs.putBoolean("auto", auto.isSelected()));
        enabled.addActionListener(e -> prefs.putBoolean("enabled", enabled.isSelected()));
        showLatest();
        recompute();
        new Timer(60_000, e -> { if (enabled.isSelected() && System.currentTimeMillis() - lastFetch > 30 * 60_000L) fetch(false); }).start();
    }

    WeatherHistory history() { return weather; }
    String stationId() { return station.getText().trim().toUpperCase(); }

    private void showLatest() {
        WeatherObs o = weather.latest();
        if (o == null) { current.setText("No observation yet."); return; }
        current.setText(String.format("<html><b>%s</b> %s: %.1f °C, dewpoint %.1f, RH %.0f%%, wind %s kt%s, %.1f hPa, vis %s mi%s &nbsp; <i>%s</i></html>",
                o.station(), Fmt.time(o.time()), o.tempC(), o.dewpointC(), o.humidityPct(), Double.isNaN(o.windKt()) ? "?" : String.valueOf((int) o.windKt()),
                Double.isNaN(o.gustKt()) ? "" : " gust " + (int) o.gustKt(), o.pressureHpa(), Double.isNaN(o.visibilityMi()) ? "?" : String.valueOf((int) o.visibilityMi()),
                o.wx().isEmpty() ? "" : ", " + o.wx(), o.raw()));
    }

    private void fetch(boolean manual) {
        lastFetch = System.currentTimeMillis();
        String id = stationId();
        new Thread(() -> {
            try {
                String use = id;
                if (auto.isSelected()) {
                    NodeEntry me = state.myNode();
                    if (me != null && me.hasPosition) {
                        List<Metar.Station> near = Metar.nearest(me.lat, me.lon);
                        if (!near.isEmpty()) { use = near.get(0).id(); final String u = use; SwingUtilities.invokeLater(() -> { station.setText(u); prefs.put("station", u); }); }
                    }
                }
                WeatherObs o = Metar.latest(use);
                boolean added = weather.add(o);
                SwingUtilities.invokeLater(() -> { showLatest(); status.setText((added ? "New observation " : "Already had ") + Fmt.time(o.time()) + " from " + o.station() + "  ·  " + weather.size() + " observations stored"); recompute(); });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> status.setText("Weather fetch failed: " + ex.getMessage()));
            }
        }, "metar").start();
    }

    private void backfill() {
        String id = stationId();
        new Thread(() -> {
            try {
                int n = 0;
                for (WeatherObs o : Metar.history(id, 48)) if (weather.add(o)) n++;
                final int added = n;
                SwingUtilities.invokeLater(() -> { showLatest(); status.setText("Backfill: " + added + " new observations from " + id); recompute(); });
            } catch (IOException ex) { SwingUtilities.invokeLater(() -> status.setText("Backfill failed: " + ex.getMessage())); }
        }, "metar-backfill").start();
    }

    private void findNearest() {
        NodeEntry me = state.myNode();
        if (me == null || !me.hasPosition) { status.setText("This radio has no position yet – set one in the phone app or wait for GPS"); return; }
        new Thread(() -> {
            try {
                List<Metar.Station> near = Metar.nearest(me.lat, me.lon);
                SwingUtilities.invokeLater(() -> {
                    if (near.isEmpty()) { status.setText("No METAR stations found within ~100 km"); return; }
                    Metar.Station[] arr = near.subList(0, Math.min(12, near.size())).toArray(new Metar.Station[0]);
                    JComboBox<String> box = new JComboBox<>();
                    for (Metar.Station st : arr) box.addItem(String.format("%s  %s  (%.0f km)", st.id(), st.name(), st.distanceKm()));
                    if (JOptionPane.showConfirmDialog(this, box, "Nearest METAR stations", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                        station.setText(arr[box.getSelectedIndex()].id());
                        prefs.put("station", arr[box.getSelectedIndex()].id());
                        fetch(true);
                    }
                });
            } catch (IOException ex) { SwingUtilities.invokeLater(() -> status.setText("Station lookup failed: " + ex.getMessage())); }
        }, "metar-stations").start();
    }

    void recompute() {
        if (history == null) return;
        List<SignalSample> s = history.since(System.currentTimeMillis() - 365L * 86400_000L);
        corr.rows = Analysis.weatherCorrelation(s, weather, 12);
        corr.fireTableDataChanged();
        updateScatter();
    }

    private void updateScatter() {
        int r = corrTable.getSelectedRow();
        if (r < 0 || r >= corr.rows.size()) { scatter.set(List.of(), ""); return; }
        int node = corr.rows.get(corrTable.convertRowIndexToModel(r)).node();
        int var = scatterVar.getSelectedIndex();
        java.util.Map<Long, double[]> hourly = new java.util.HashMap<>();
        for (SignalSample s : history.since(System.currentTimeMillis() - 365L * 86400_000L)) {
            if (s.from() != node) continue;
            double[] a = hourly.computeIfAbsent(s.time() / 3_600_000L, k -> new double[2]); a[0]++; a[1] += s.rssi();
        }
        List<double[]> pts = new java.util.ArrayList<>();
        for (var e : hourly.entrySet()) {
            WeatherObs w = weather.at(e.getKey() * 3_600_000L + 1_800_000L);
            if (w == null) continue;
            double x = switch (var) { case 0 -> w.tempC(); case 1 -> w.humidityPct(); case 2 -> w.windKt(); default -> w.pressureHpa(); };
            if (!Double.isNaN(x)) pts.add(new double[]{x, e.getValue()[1] / e.getValue()[0], w.precip() ? 1 : 0});
        }
        scatter.set(pts, state.nodeName(node) + " – RSSI vs " + scatterVar.getSelectedItem());
    }

    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }

    /** Simple scatter: x = weather variable, y = hourly RSSI; rain hours in blue. */
    static class Scatter extends JComponent {
        private List<double[]> pts = List.of(); private String title = "";
        void set(List<double[]> p, String t) { pts = p; title = t; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
            int left = 50, right = w - 15, top = 20, bottom = h - 28;
            g.setColor(Color.GRAY); g.drawRect(left, top, right - left, bottom - top);
            g.setColor(Color.BLACK); g.setFont(g.getFont().deriveFont(11f)); g.drawString(title, left + 4, 14);
            if (pts.isEmpty()) { g.drawString("Select a node above (needs weather observations matching signal history hours)", left + 8, top + 20); return; }
            double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE, ymin = Double.MAX_VALUE, ymax = -Double.MAX_VALUE;
            for (double[] p : pts) { xmin = Math.min(xmin, p[0]); xmax = Math.max(xmax, p[0]); ymin = Math.min(ymin, p[1]); ymax = Math.max(ymax, p[1]); }
            if (xmax == xmin) { xmax += 1; xmin -= 1; } if (ymax == ymin) { ymax += 1; ymin -= 1; }
            g.setFont(g.getFont().deriveFont(10f)); g.setColor(Color.DARK_GRAY);
            g.drawString(String.format("%.1f", xmin), left, bottom + 14); g.drawString(String.format("%.1f", xmax), right - 30, bottom + 14);
            g.drawString(String.format("%.0f", ymax), 4, top + 10); g.drawString(String.format("%.0f", ymin), 4, bottom);
            for (double[] p : pts) {
                int x = left + (int) ((p[0] - xmin) / (xmax - xmin) * (right - left)), y = bottom - (int) ((p[1] - ymin) / (ymax - ymin) * (bottom - top));
                g.setColor(p[2] > 0 ? new Color(40, 90, 200, 160) : new Color(220, 120, 20, 160));
                g.fillOval(x - 3, y - 3, 6, 6);
            }
            g.setColor(Color.DARK_GRAY);
            g.drawString(pts.size() + " hours   orange = dry, blue = precipitation   (y = hourly avg RSSI dBm)", left + 4, bottom + 26);
        }
    }
}
