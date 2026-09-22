package meshconsole.ui;

import meshconsole.mesh.SignalSample;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Strip chart of RSSI (dBm) and SNR (dB) for received packets, optionally filtered to one node. */
class SignalChart extends JComponent {
    private List<SignalSample> samples = List.of();
    private int filterNode = 0;       // 0 = all nodes

    SignalChart() {
        setPreferredSize(new Dimension(500, 180));
        setToolTipText("Blue: RSSI (dBm, left axis).  Orange: SNR (dB, right axis).  One point per received packet.");
    }

    void setData(List<SignalSample> s, int filterNode) {
        this.samples = s;
        this.filterNode = filterNode;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        int left = 44, right = w - 44, top = 12, bottom = h - 22;
        g.setColor(Color.GRAY);
        g.drawRect(left, top, right - left, bottom - top);

        List<SignalSample> pts = samples.stream().filter(s -> filterNode == 0 || s.from() == filterNode).toList();
        int maxPts = Math.max(20, (right - left) / 4);
        if (pts.size() > maxPts) pts = pts.subList(pts.size() - maxPts, pts.size());

        double rMin = -140, rMax = -40, sMin = -20, sMax = 15;
        g.setFont(g.getFont().deriveFont(10f));
        for (int r = -140; r <= -40; r += 20) {
            int y = yFor(r, rMin, rMax, top, bottom);
            g.setColor(new Color(220, 220, 220));
            g.drawLine(left + 1, y, right - 1, y);
            g.setColor(new Color(40, 90, 200));
            g.drawString(String.valueOf(r), 4, y + 4);
        }
        for (int s = -20; s <= 15; s += 5) {
            int y = yFor(s, sMin, sMax, top, bottom);
            g.setColor(new Color(220, 120, 20));
            g.drawString(String.valueOf(s), right + 6, y + 4);
        }
        g.setColor(Color.DARK_GRAY);
        boolean averaged = !pts.isEmpty() && pts.get(0).hops() == -1;
        g.drawString(pts.isEmpty() ? "No packets received yet" : pts.size() + (averaged ? " points, hourly averages then packets (latest at right)" : " packets (latest at right)"), left + 4, bottom + 15);
        if (pts.isEmpty()) return;

        int n = pts.size();
        int[] xs = new int[n], yr = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = left + (int) ((right - left) * (n == 1 ? 0.5 : (double) i / (n - 1)));
            yr[i] = yFor(pts.get(i).rssi(), rMin, rMax, top, bottom);
            ys[i] = yFor(pts.get(i).snr(), sMin, sMax, top, bottom);
        }
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(40, 90, 200));
        g.drawPolyline(xs, yr, n);
        g.setColor(new Color(220, 120, 20));
        g.drawPolyline(xs, ys, n);
        SignalSample last = pts.get(n - 1);
        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.drawString(String.format("RSSI %d dBm   SNR %.2f dB", last.rssi(), last.snr()), left + 6, top + 14);
    }

    private static int yFor(double v, double min, double max, int top, int bottom) {
        double t = (v - min) / (max - min);
        t = Math.max(0, Math.min(1, t));
        return (int) (bottom - t * (bottom - top));
    }
}
