package meshconsole.ui;

import meshconsole.mesh.ActivityStats;
import meshconsole.mesh.MeshState;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;

/** 1.29.4: the Traffic tab's two charts – packets per minute by kind, and hop usage. */
final class ActivityCharts {
    private ActivityCharts() { }

    static Color colour(int i) { return Color.decode(ActivityStats.COLOURS[i]); }
    private static final java.time.format.DateTimeFormatter HM = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    /** Stacked bars: packets per minute by kind, with the radio's channel utilisation as a line. Hover for the numbers. */
    static final class Timeline extends JComponent {
        private final MeshState state;
        private int minutes = 60;
        private List<ActivityStats.Minute> data = List.of();
        private int hover = -1;

        Timeline(MeshState state) {
            this.state = state;
            setPreferredSize(new Dimension(600, 200));
            ToolTipManager.sharedInstance().registerComponent(this);
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { hover = barAt(e.getX()); repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
            };
            addMouseListener(m); addMouseMotionListener(m);
        }

        void setMinutes(int m) { minutes = m; reload(); }
        void reload() { data = state.activity.minutes(minutes, System.currentTimeMillis()); repaint(); }

        private static final int L = 40, R = 40, T = 24, B = 22;
        private int barAt(int x) {
            int w = getWidth() - L - R;
            if (data.isEmpty() || x < L || x >= L + w) return -1;
            return Math.min(data.size() - 1, (x - L) * data.size() / w);
        }

        @Override public String getToolTipText(MouseEvent e) {
            int i = barAt(e.getX());
            if (i < 0) return null;
            ActivityStats.Minute m = data.get(i);
            StringBuilder s = new StringBuilder("<html><b>").append(HM.format(java.time.Instant.ofEpochMilli(m.startMillis()).atZone(java.time.ZoneId.systemDefault()))).append("</b> – ").append(m.total()).append(" packets");
            for (int k = 0; k < ActivityStats.KINDS.length; k++)
                if (m.counts()[k] > 0) s.append("<br><font color='").append(ActivityStats.COLOURS[k]).append("'>■</font> ").append(ActivityStats.KINDS[k]).append(": ").append(m.counts()[k]);
            if (!Float.isNaN(m.util())) s.append(String.format(Locale.US, "<br>Channel busy: %.1f%%", m.util()));
            return s.append("</html>").toString();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - L - R, h = getHeight() - T - B;
            g.setColor(getBackground() == null ? Color.WHITE : getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
            Color fg = UIManager.getColor("Label.foreground"); if (fg == null) fg = Color.DARK_GRAY;
            Color grid = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 40);
            g.setFont(getFont().deriveFont(10f));
            // legend
            int lx = L;
            for (int k = 0; k < ActivityStats.KINDS.length; k++) {
                g.setColor(colour(k)); g.fillRect(lx, 6, 9, 9);
                g.setColor(fg); g.drawString(ActivityStats.KINDS[k], lx + 12, 15);
                lx += g.getFontMetrics().stringWidth(ActivityStats.KINDS[k]) + 22;
            }
            g.setColor(new Color(200, 160, 0)); g.drawLine(lx, 11, lx + 12, 11); g.setColor(fg); g.drawString("Channel busy %", lx + 16, 15);
            if (data.isEmpty() || w <= 0 || h <= 0) { g.dispose(); return; }
            int max = 1; float maxU = 10;
            for (ActivityStats.Minute m : data) { max = Math.max(max, m.total()); if (!Float.isNaN(m.util())) maxU = Math.max(maxU, m.util()); }
            max = niceCeil(max); maxU = niceCeil((int) Math.ceil(maxU));
            // grid and axes
            for (int i = 0; i <= 4; i++) {
                int y = T + h - h * i / 4;
                g.setColor(grid); g.drawLine(L, y, L + w, y);
                g.setColor(fg);
                String a = String.valueOf(max * i / 4); g.drawString(a, L - 4 - g.getFontMetrics().stringWidth(a), y + 4);
                g.setColor(new Color(170, 130, 0)); g.drawString((int) (maxU * i / 4) + "%", L + w + 4, y + 4);
            }
            double bw = (double) w / data.size();
            for (int i = 0; i < data.size(); i++) {
                ActivityStats.Minute m = data.get(i);
                int x = L + (int) Math.round(i * bw), x2 = L + (int) Math.round((i + 1) * bw), y = T + h;
                int bwi = Math.max(1, x2 - x - (bw > 4 ? 1 : 0));
                for (int k = 0; k < ActivityStats.KINDS.length; k++) {
                    int c = m.counts()[k]; if (c == 0) continue;
                    int bh = (int) Math.round((double) c * h / max);
                    g.setColor(colour(k)); g.fillRect(x, y - bh, bwi, bh); y -= bh;
                }
                if (i == hover) { g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 60)); g.fillRect(x, T, bwi, h); }
            }
            // utilisation line
            g.setColor(new Color(230, 180, 0)); g.setStroke(new BasicStroke(1.6f));
            int px = -1, py = -1;
            for (int i = 0; i < data.size(); i++) {
                float u = data.get(i).util();
                if (Float.isNaN(u)) { px = -1; continue; }
                int x = L + (int) Math.round((i + 0.5) * bw), y = T + h - (int) Math.round(u * h / maxU);
                if (px >= 0) g.drawLine(px, py, x, y);
                px = x; py = y;
            }
            // time labels
            g.setColor(fg);
            int step = Math.max(1, data.size() / 6);
            for (int i = 0; i < data.size(); i += step) {
                String t = HM.format(java.time.Instant.ofEpochMilli(data.get(i).startMillis()).atZone(java.time.ZoneId.systemDefault()));
                g.drawString(t, L + (int) Math.round(i * bw), T + h + 15);
            }
            g.dispose();
        }
    }

    /** Grid: rows = hops a packet was sent with, columns = hops it used to reach this radio. */
    static final class Hops extends JComponent {
        private final MeshState state;
        private long[][] h = new long[ActivityStats.MAX_HOPS + 1][ActivityStats.MAX_HOPS + 1];

        Hops(MeshState state) {
            this.state = state;
            setPreferredSize(new Dimension(330, 200));
            setToolTipText("<html>Each row: packets sent with that many hops. Each column: how many hops they used to reach this radio.<br>"
                    + "Packets far left of the diagonal had hops to spare; on the diagonal they used all of them.</html>");
        }

        void reload() { h = state.activity.hops(); repaint(); }
        long[][] data() { return h; }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color fg = UIManager.getColor("Label.foreground"); if (fg == null) fg = Color.DARK_GRAY;
            g.setColor(getBackground() == null ? Color.WHITE : getBackground()); g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(getFont().deriveFont(10f));
            int n = ActivityStats.MAX_HOPS;
            long max = 1;
            for (int s = 1; s <= n; s++) for (int u = 0; u <= s; u++) max = Math.max(max, h[s][u]);
            int L = 64, T = 30, cw = Math.max(22, (getWidth() - L - 56) / (n + 1)), ch = Math.max(14, (getHeight() - T - 6) / n);
            g.setColor(fg);
            g.drawString("hops used →", L, 12);
            for (int u = 0; u <= n; u++) g.drawString(String.valueOf(u), L + u * cw + cw / 2 - 3, T - 4);
            g.drawString("total", L + (n + 1) * cw + 6, T - 4);
            for (int s = 1; s <= n; s++) {
                int y = T + (s - 1) * ch;
                long row = 0; for (int u = 0; u <= s; u++) row += h[s][u];
                g.setColor(fg); g.drawString("sent " + s, 6, y + ch / 2 + 4);
                for (int u = 0; u <= n; u++) {
                    int x = L + u * cw;
                    if (u > s) { g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 12)); g.fillRect(x, y, cw - 1, ch - 1); continue; }
                    long c = h[s][u];
                    float a = c == 0 ? 0 : 0.15f + 0.85f * (float) Math.sqrt((double) c / max);
                    g.setColor(new Color(62, 142, 208, Math.round(a * 255))); g.fillRect(x, y, cw - 1, ch - 1);
                    if (u == s && c > 0) { g.setColor(new Color(229, 72, 77)); g.drawRect(x, y, cw - 2, ch - 2); }
                    if (c > 0) { String t = c >= 10000 ? (c / 1000) + "k" : String.valueOf(c); g.setColor(a > 0.6f ? Color.WHITE : fg); g.drawString(t, x + (cw - g.getFontMetrics().stringWidth(t)) / 2, y + ch / 2 + 4); }
                }
                g.setColor(fg); g.drawString(String.valueOf(row), L + (n + 1) * cw + 6, y + ch / 2 + 4);
            }
            g.dispose();
        }
    }

    private static int niceCeil(int v) {
        int[] steps = {1, 2, 4, 5, 8, 10};
        int mag = 1;
        while (true) { for (int s : steps) if (s * mag >= v) return s * mag; mag *= 10; }
    }
}
