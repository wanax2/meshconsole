package meshconsole.ui;

import meshconsole.Version;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Line2D;

/** Startup splash: a drawn mesh-network logo with name, version and copyright. */
public class Splash extends JWindow {
    private final long shownAt = System.currentTimeMillis();

    public Splash() {
        setContentPane(new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g.setPaint(new GradientPaint(0, 0, new Color(18, 32, 56), w, h, new Color(40, 70, 120)));
                g.fillRect(0, 0, w, h);

                // mesh logo: nodes connected by links, one highlighted "me"
                int[][] pts = {{70, 60}, {150, 40}, {210, 95}, {130, 120}, {60, 130}, {175, 165}};
                int[][] links = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {0, 4}, {4, 3}, {3, 5}, {2, 5}};
                g.setStroke(new BasicStroke(2f));
                g.setColor(new Color(120, 170, 255, 130));
                for (int[] l : links) g.draw(new Line2D.Float(pts[l[0]][0], pts[l[0]][1], pts[l[1]][0], pts[l[1]][1]));
                for (int i = 0; i < pts.length; i++) {
                    int r = i == 3 ? 10 : 7;
                    g.setColor(i == 3 ? new Color(255, 200, 60) : new Color(90, 220, 140));
                    g.fillOval(pts[i][0] - r, pts[i][1] - r, 2 * r, 2 * r);
                    g.setColor(Color.WHITE);
                    g.drawOval(pts[i][0] - r, pts[i][1] - r, 2 * r, 2 * r);
                }
                // radio waves from "me"
                g.setColor(new Color(255, 200, 60, 90));
                g.setStroke(new BasicStroke(1.5f));
                for (int r = 22; r <= 58; r += 18) g.drawArc(pts[3][0] - r, pts[3][1] - r, 2 * r, 2 * r, 20, 140);

                g.setColor(Color.WHITE);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
                g.drawString(Version.NAME, 260, 78);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
                g.setColor(new Color(200, 215, 240));
                g.drawString("Meshtastic desktop client  ·  version " + Version.VERSION, 262, 104);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.drawString("Messaging · Nodes & map · Signal · Traceroute · Settings · MQTT · SWR", 262, 132);
                g.setColor(new Color(160, 180, 210));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.drawString(Version.COPYRIGHT + "  ·  MIT License", 262, h - 22);
                g.setColor(new Color(255, 255, 255, 60));
                g.drawRect(0, 0, w - 1, h - 1);
            }
        });
        setSize(760, 210);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
    }

    /** Closes the splash once at least minMillis have elapsed since it was shown. */
    public void closeAfter(long minMillis) {
        long wait = Math.max(0, minMillis - (System.currentTimeMillis() - shownAt));
        Timer t = new Timer((int) wait, e -> dispose());
        t.setRepeats(false);
        t.start();
    }
}
