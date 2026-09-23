package meshconsole.ui;

import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import meshconsole.tools.Elevation;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Terrain profile, line of sight and Fresnel clearance between two nodes. */
class PathPanel extends JPanel {
    private final MeshState state;
    private final JComboBox<Item> a = new JComboBox<>(), b = new JComboBox<>();
    private final JSpinner ha = new JSpinner(new SpinnerNumberModel(2.0, 0, 300, 0.5)), hb = new JSpinner(new SpinnerNumberModel(2.0, 0, 300, 0.5));
    private final JTextField freq = new JTextField("906.875", 7);
    private final JLabel result = new JLabel(" ");
    private final ProfileChart chart = new ProfileChart();
    private Elevation.Profile profile; private Elevation.Los los;

    record Item(int num, String label, double lat, double lon) { @Override public String toString() { return label; } }

    PathPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel r1 = row(); r1.add(new JLabel("From:")); r1.add(a); r1.add(new JLabel("antenna height m:")); r1.add(ha); r1.add(new JLabel("   To:")); r1.add(b); r1.add(new JLabel("height m:")); r1.add(hb);
        JPanel r2 = row(); r2.add(new JLabel("Frequency MHz:")); r2.add(freq);
        JButton go = new JButton("Compute path"); go.addActionListener(e -> compute()); r2.add(go);
        JButton refresh = new JButton("↻ nodes"); refresh.addActionListener(e -> reloadNodes()); r2.add(refresh);
        r2.add(result);
        JPanel r3 = row(); r3.add(new JLabel("<html>Terrain from SRTM (opentopodata.org, ~30 m), 4/3-earth curvature, 60 % first-Fresnel-zone clearance at the chosen frequency. Green = clear; red = obstructed, with the antenna height that would clear it. Only nodes with positions are listed; heights default to 2 m – use the setup log's height for your own node.</html>"));
        top.add(r1); top.add(r2); top.add(r3);
        add(top, BorderLayout.NORTH);
        add(chart, BorderLayout.CENTER);
        reloadNodes();
        state.addListener(new MeshState.Listener() { @Override public void onNodesChanged() { SwingUtilities.invokeLater(PathPanel.this::reloadNodes); } });
    }

    private void reloadNodes() {
        Object sa = a.getSelectedItem(), sb = b.getSelectedItem();
        a.removeAllItems(); b.removeAllItems();
        int me = state.myNodeNum();
        for (NodeEntry n : state.nodes()) if (n.hasPosition) { Item it = new Item(n.num, (n.num == me ? "★ " : "") + n.displayName(), n.lat, n.lon); a.addItem(it); b.addItem(it); }
        if (sa != null) a.setSelectedItem(sa); else if (a.getItemCount() > 0) a.setSelectedIndex(0);
        if (sb != null) b.setSelectedItem(sb); else if (b.getItemCount() > 1) b.setSelectedIndex(1);
    }

    private void compute() {
        Item x = (Item) a.getSelectedItem(), y = (Item) b.getSelectedItem();
        if (x == null || y == null || x.num() == y.num()) { result.setText("Pick two different nodes with positions."); return; }
        double f; try { f = Double.parseDouble(freq.getText().trim()); } catch (NumberFormatException e) { f = 915; }
        final double ff = f, h1 = ((Number) ha.getValue()).doubleValue(), h2 = ((Number) hb.getValue()).doubleValue();
        result.setText("Fetching terrain…");
        new Thread(() -> {
            try {
                Elevation.Profile p = Elevation.profile(x.lat(), x.lon(), y.lat(), y.lon(), 100);
                Elevation.Los l = Elevation.analyse(p, h1, h2, ff);
                SwingUtilities.invokeLater(() -> {
                    profile = p; los = l; chart.set(p, l, h1, h2, x.label(), y.label()); chart.repaint();
                    String extra = l.clear() ? "" : String.format(" Raising the '%s' antenna to about %.0f m would clear it.", x.label(), h1 + l.neededHeightM());
                    result.setText(String.format("%.1f km. %s Worst clearance %+.0f m at %.1f km (60%% Fresnel needs %.0f m at mid-path).%s", p.totalKm(), l.clear() ? "LINE OF SIGHT CLEAR." : "OBSTRUCTED.", l.worstClearanceM(), l.worstAtKm(), 0.6 * l.fresnelRadiusMidM(), extra));
                    result.setForeground(l.clear() ? new Color(0, 130, 0) : Color.RED);
                });
            } catch (Exception ex) { SwingUtilities.invokeLater(() -> { result.setText("Terrain lookup failed: " + ex.getMessage()); result.setForeground(Color.BLACK); }); }
        }, "los").start();
    }

    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }

    static class ProfileChart extends JComponent {
        Elevation.Profile p; Elevation.Los l; double h1, h2; String na = "", nb = "";
        void set(Elevation.Profile p, Elevation.Los l, double h1, double h2, String na, String nb) { this.p = p; this.l = l; this.h1 = h1; this.h2 = h2; this.na = na; this.nb = nb; }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0; g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(); g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
            if (p == null) { g.setColor(Color.DARK_GRAY); g.drawString("Pick two nodes and press Compute path", 20, 30); return; }
            int left = 50, right = w - 20, top = 30, bottom = h - 30, n = p.distKm().length;
            double D = p.totalKm(), emin = Double.MAX_VALUE, emax = -Double.MAX_VALUE;
            double e1 = p.elevM()[0] + h1, e2 = p.elevM()[n - 1] + h2;
            double[] terrainWithBulge = new double[n];
            for (int i = 0; i < n; i++) { double d1 = p.distKm()[i], d2 = D - d1; terrainWithBulge[i] = p.elevM()[i] + (d1 * d2) / (2 * 4.0 / 3.0 * 6371.0) * 1000.0; emin = Math.min(emin, terrainWithBulge[i]); emax = Math.max(emax, Math.max(terrainWithBulge[i], Math.max(e1, e2))); }
            final double lo = emin - 10, hi = emax + 20;
            java.util.function.DoubleUnaryOperator Y = v -> bottom - (v - lo) / (hi - lo) * (bottom - top);
            java.util.function.DoubleUnaryOperator X = d -> left + d / D * (right - left);
            // terrain
            Polygon poly = new Polygon();
            for (int i = 0; i < n; i++) poly.addPoint((int) X.applyAsDouble(p.distKm()[i]), (int) Y.applyAsDouble(terrainWithBulge[i]));
            poly.addPoint(right, bottom); poly.addPoint(left, bottom);
            g.setColor(new Color(140, 180, 120)); g.fillPolygon(poly); g.setColor(new Color(60, 100, 50)); g.drawPolygon(poly);
            // fresnel zone (60%)
            double lambda = 300.0 / 915;
            Polygon fz = new Polygon();
            for (int i = 0; i < n; i++) { double d1 = p.distKm()[i], d2 = D - d1; double ray = e1 + (e2 - e1) * (d1 / D); double r = 0.6 * Math.sqrt(lambda * d1 * 1000 * d2 * 1000 / (D * 1000)); fz.addPoint((int) X.applyAsDouble(d1), (int) Y.applyAsDouble(ray + r)); }
            for (int i = n - 1; i >= 0; i--) { double d1 = p.distKm()[i], d2 = D - d1; double ray = e1 + (e2 - e1) * (d1 / D); double r = 0.6 * Math.sqrt(lambda * d1 * 1000 * d2 * 1000 / (D * 1000)); fz.addPoint((int) X.applyAsDouble(d1), (int) Y.applyAsDouble(ray - r)); }
            g.setColor(new Color(l != null && l.clear() ? 60 : 220, l != null && l.clear() ? 200 : 60, 60, 70)); g.fillPolygon(fz);
            // ray
            g.setColor(l != null && l.clear() ? new Color(0, 130, 0) : Color.RED); g.setStroke(new BasicStroke(2f));
            g.drawLine((int) X.applyAsDouble(0), (int) Y.applyAsDouble(e1), (int) X.applyAsDouble(D), (int) Y.applyAsDouble(e2));
            g.setColor(Color.BLACK); g.setFont(g.getFont().deriveFont(11f));
            g.drawString(na + String.format(" (%.0f m + %.0f m ant)", p.elevM()[0], h1), left, top - 8);
            String rb = nb + String.format(" (%.0f m + %.0f m ant)", p.elevM()[n - 1], h2); g.drawString(rb, right - g.getFontMetrics().stringWidth(rb), top - 8);
            g.setColor(Color.DARK_GRAY);
            for (int i = 0; i <= 5; i++) { double d = D * i / 5; int x = (int) X.applyAsDouble(d); g.drawString(String.format("%.1f km", d), x - 15, bottom + 14); }
            g.drawString(String.format("%.0f m", hi), 4, top + 4); g.drawString(String.format("%.0f m", lo), 4, bottom);
        }
    }
}
