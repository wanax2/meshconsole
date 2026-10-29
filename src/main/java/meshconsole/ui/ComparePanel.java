package meshconsole.ui;

import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Radio vs radio by bearing: for every node heard directly (or at one hop) by BOTH radios in the window, the SNR
 * difference, aggregated into compass sectors. Radio A is this instance's history; radio B is this instance's other
 * radio or another instance's data folder (its signal_history.csv + nodes.json). Built to answer "is the yagi
 * pointing the right way?" in an hour instead of a day.
 */
public class ComparePanel extends JPanel {
    private final MeshState state;
    private final JComboBox<String> radioA = new JComboBox<>(), radioB = new JComboBox<>();
    private final JComboBox<String> window = new JComboBox<>(new String[]{"last 6 h", "last 24 h", "last 3 days", "last 7 days", "all"});
    private final JComboBox<String> hops = new JComboBox<>(new String[]{"direct only", "direct or 1 hop", "any hops"});
    private final JTextField otherFolder = new JTextField(28);
    private final Rose rose = new Rose();
    private final DefaultTableModel tm = new DefaultTableModel(new Object[]{"Node", "Bearing", "km", "A SNR", "B SNR", "B − A dB", "n A", "n B"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
    private final JLabel summary = new JLabel(" ");
    private Map<Integer, String> namesA = new HashMap<>(), namesB = new HashMap<>();
    private Map<Integer, double[]> posA = new HashMap<>(), posB = new HashMap<>();

    public ComparePanel(MeshState state) {
        super(new BorderLayout(6, 6));
        this.state = state;
        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Radio A:")); top.add(radioA);
        top.add(new JLabel("Radio B:")); top.add(radioB);
        top.add(new JLabel("or other instance's data folder:")); top.add(otherFolder);
        JButton browse = new JButton("Browse…"); browse.addActionListener(e -> { Path p = NativeFiles.folder(this, "Other instance's data folder", meshconsole.DataDir.get().getParent()); if (p != null) otherFolder.setText(p.toString()); }); top.add(browse);
        top.add(window); top.add(hops);
        JButton run = new JButton("Compare"); run.addActionListener(e -> compute()); top.add(run);
        add(top, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout());
        rose.setPreferredSize(new Dimension(420, 420));
        JPanel roseBox = new JPanel(new BorderLayout()); roseBox.setBorder(BorderFactory.createTitledBorder("B minus A, by 30° sector (dB; outward = B better)")); roseBox.add(rose, BorderLayout.CENTER);
        JTable table = new JTable(tm); table.setAutoCreateRowSorter(true);
        JScrollPane sp = new JScrollPane(table); sp.setBorder(BorderFactory.createTitledBorder("Nodes heard by both"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, roseBox, sp); split.setResizeWeight(0.45);
        mid.add(split, BorderLayout.CENTER);
        add(mid, BorderLayout.CENTER);
        summary.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        add(summary, BorderLayout.SOUTH);
        refreshRadios();
    }

    void refreshRadios() {
        Set<Integer> rx = new LinkedHashSet<>();
        try { for (String l : Files.readAllLines(meshconsole.DataDir.file("signal_history.csv"))) { String[] f = l.split(","); if (f.length >= 7) try { rx.add((int) Long.parseLong(f[6])); } catch (NumberFormatException ignored) { } } } catch (java.io.IOException ignored) { }
        Object a = radioA.getSelectedItem(), b = radioB.getSelectedItem();
        radioA.removeAllItems(); radioB.removeAllItems();
        for (int n : rx) { String s = state.nodeName(n) + " (" + String.format("!%08x", n) + ")"; radioA.addItem(s); radioB.addItem(s); }
        if (a != null) radioA.setSelectedItem(a); if (b != null) radioB.setSelectedItem(b);
    }

    private static int numOf(Object item) { if (item == null) return 0; String s = item.toString(); int i = s.lastIndexOf("(!"); return i < 0 ? 0 : (int) Long.parseLong(s.substring(i + 2, s.length() - 1), 16); }

    private record Sample(int node, double snr, int hops, long time) { }

    private static List<Sample> read(Path hist, int rx, long since, int maxHops, Set<Integer> excl) {
        List<Sample> out = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(hist)) {
                String[] f = l.split(",");
                if (f.length < 7) continue;
                try {
                    long t = Long.parseLong(f[0]); int node = (int) Long.parseLong(f[1]); int h = Integer.parseInt(f[4]); int rxn = (int) Long.parseLong(f[6]);
                    if (t < since || h < 0 || h > maxHops || (rx != 0 && rxn != rx) || excl.contains(node)) continue;
                    out.add(new Sample(node, Double.parseDouble(f[3]), h, t));
                } catch (NumberFormatException ignored) { }
            }
        } catch (java.io.IOException ignored) { }
        return out;
    }

    private static void loadNodes(Path dir, Map<Integer, String> names, Map<Integer, double[]> pos) {
        try {
            Object root = meshconsole.analysis.Json.parse(Files.readString(dir.resolve("nodes.json")));
            if (root instanceof List<?> arr) for (Object o : arr) if (o instanceof Map<?, ?> m) {
                int num = (int) (long) meshconsole.analysis.Json.num(m.get("num"), 0);
                names.put(num, meshconsole.analysis.Json.str(m.get("long")));
                if (m.get("lat") != null && m.get("lon") != null) pos.put(num, new double[]{meshconsole.analysis.Json.num(m.get("lat"), 0), meshconsole.analysis.Json.num(m.get("lon"), 0)});
            }
        } catch (Exception ignored) { }
    }

    private void compute() {
        long since = switch (window.getSelectedIndex()) { case 0 -> System.currentTimeMillis() - 6 * 3600_000L; case 1 -> System.currentTimeMillis() - 86400_000L; case 2 -> System.currentTimeMillis() - 3 * 86400_000L; case 3 -> System.currentTimeMillis() - 7 * 86400_000L; default -> 0; };
        int maxHops = switch (hops.getSelectedIndex()) { case 0 -> 0; case 1 -> 1; default -> 99; };
        NodeEntry me = state.myNode();
        Set<Integer> excl = new HashSet<>(MeshState.myRadios); if (me != null) excl.add(me.num);
        namesA.clear(); posA.clear(); loadNodes(meshconsole.DataDir.get(), namesA, posA);
        List<Sample> a = read(meshconsole.DataDir.file("signal_history.csv"), numOf(radioA.getSelectedItem()), since, maxHops, excl);
        List<Sample> b;
        String bName;
        if (!otherFolder.getText().isBlank()) {
            Path other = Path.of(otherFolder.getText().trim());
            namesB.clear(); posB.clear(); loadNodes(other, namesB, posB);
            b = read(other.resolve("signal_history.csv"), 0, since, maxHops, excl);
            bName = other.getFileName().toString();
        } else {
            namesB = namesA; posB = posA;
            b = read(meshconsole.DataDir.file("signal_history.csv"), numOf(radioB.getSelectedItem()), since, maxHops, excl);
            bName = String.valueOf(radioB.getSelectedItem());
        }
        double myLat = me != null && me.hasPosition ? me.lat : 0, myLon = me != null && me.hasPosition ? me.lon : 0;
        Map<Integer, List<Double>> sa = new HashMap<>(), sb = new HashMap<>();
        for (Sample s : a) sa.computeIfAbsent(s.node, k -> new ArrayList<>()).add(s.snr);
        for (Sample s : b) sb.computeIfAbsent(s.node, k -> new ArrayList<>()).add(s.snr);
        tm.setRowCount(0);
        double[] sectorSum = new double[12]; int[] sectorN = new int[12];
        int common = 0; double total = 0;
        for (int node : sa.keySet()) {
            if (!sb.containsKey(node) || sa.get(node).size() < 2 || sb.get(node).size() < 2) continue;
            double[] p = posA.getOrDefault(node, posB.get(node));
            if (p == null || myLat == 0) continue;
            if (Math.abs(p[0] - myLat) < 0.001 && Math.abs(p[1] - myLon) < 0.001) continue;   // in the house
            double bearing = bearing(myLat, myLon, p[0], p[1]), km = meshconsole.analysis.Metar.distanceKm(myLat, myLon, p[0], p[1]);
            double ma = median(sa.get(node)), mb = median(sb.get(node)), d = mb - ma;
            int sec = (int) (bearing / 30) % 12; sectorSum[sec] += d; sectorN[sec]++; common++; total += d;
            String name = namesA.getOrDefault(node, namesB.getOrDefault(node, String.format("!%08x", node)));
            tm.addRow(new Object[]{name, String.format("%.0f°", bearing), String.format("%.1f", km), String.format("%.1f", ma), String.format("%.1f", mb), String.format("%+.1f", d), sa.get(node).size(), sb.get(node).size()});
        }
        double[] sectors = new double[12]; for (int i = 0; i < 12; i++) sectors[i] = sectorN[i] == 0 ? Double.NaN : sectorSum[i] / sectorN[i];
        rose.set(sectors, sectorN);
        int best = -1; for (int i = 0; i < 12; i++) if (sectorN[i] > 0 && (best < 0 || sectors[i] > sectors[best])) best = i;
        summary.setText(common == 0 ? "No positioned nodes heard by both radios in that window (try more hops or a longer window)."
                : String.format("%d nodes heard by both. B (%s) vs A: mean %+.1f dB overall; best sector %d–%d° (%+.1f dB, %d nodes). A directional antenna on B should show one strong sector and negatives behind it.",
                  common, bName, total / common, best * 30, best * 30 + 30, sectors[best], sectorN[best]));
    }

    private static double median(List<Double> v) { List<Double> s = new ArrayList<>(v); Collections.sort(s); return s.get(s.size() / 2); }

    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double y = Math.sin(Math.toRadians(lon2 - lon1)) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2)) - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(lon2 - lon1));
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Compass rose: 12 sectors, radius = dB difference (centre ring = 0, outward = B better). */
    static class Rose extends JComponent {
        private double[] v = new double[12]; private int[] n = new int[12];
        void set(double[] v, int[] n) { this.v = v; this.n = n; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0; g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(); int cx = w / 2, cy = h / 2; int R = Math.min(w, h) / 2 - 24; int r0 = R / 2;   // zero ring at half radius, ±20 dB span
            g.setColor(getBackground()); g.fillRect(0, 0, w, h);
            g.setColor(new Color(200, 200, 200));
            for (int ring = -20; ring <= 20; ring += 10) { int rr = r0 + ring * r0 / 20; g.draw(new Ellipse2D.Double(cx - rr, cy - rr, 2 * rr, 2 * rr)); g.drawString((ring > 0 ? "+" : "") + ring, cx + rr + 2, cy - 2); }
            g.setColor(new Color(120, 120, 120)); g.draw(new Ellipse2D.Double(cx - r0, cy - r0, 2 * r0, 2 * r0));
            for (int i = 0; i < 12; i++) {
                double a0 = Math.toRadians(90 - i * 30), a1 = Math.toRadians(90 - (i + 1) * 30);
                if (!Double.isNaN(v[i])) {
                    double d = Math.max(-20, Math.min(20, v[i])); int rr = (int) (r0 + d * r0 / 20);
                    Arc2D arc = new Arc2D.Double(cx - rr, cy - rr, 2 * rr, 2 * rr, Math.toDegrees(a1), 30, Arc2D.PIE);
                    g.setColor(d >= 0 ? new Color(60, 140, 60, 120) : new Color(190, 60, 60, 120)); g.fill(arc);
                    g.setColor(d >= 0 ? new Color(30, 100, 30) : new Color(140, 30, 30)); g.draw(arc);
                    double am = Math.toRadians(90 - i * 30 - 15); int lr = R + 10;
                    g.setColor(Color.DARK_GRAY); g.drawString(String.format("%+.0f (%d)", v[i], n[i]), (int) (cx + lr * Math.cos(am)) - 18, (int) (cy - lr * Math.sin(am)) + 4);
                }
            }
            g.setColor(Color.DARK_GRAY);
            g.drawString("N", cx - 4, cy - R - 8); g.drawString("E", cx + R + 8, cy + 4); g.drawString("S", cx - 4, cy + R + 18); g.drawString("W", cx - R - 18, cy + 4);
        }
    }
}
