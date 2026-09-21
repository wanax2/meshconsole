package meshconsole.ui;

import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Node map on OpenStreetMap tiles. Tiles are fetched on demand (needs internet the
 * first time) and cached under ./tilecache so the map still works offline later.
 */
class MapPanel extends JPanel {
    private static final int TILE = 256;
    private final MeshState state;
    private final MapView view = new MapView();
    private final JCheckBox showLinks = new JCheckBox("Lines to direct neighbours", true);
    private final JCheckBox showNames = new JCheckBox("Names", true);
    private final JLabel status = new JLabel(" ");

    MapPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(4, 4));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton me = new JButton("Center on me");
        JButton fit = new JButton("Fit all nodes");
        top.add(me); top.add(fit); top.add(showLinks); top.add(showNames); top.add(status);
        add(top, BorderLayout.NORTH);
        add(view, BorderLayout.CENTER);
        JLabel credit = new JLabel("  Map data © OpenStreetMap contributors  ·  drag to pan, wheel to zoom, click a marker for details");
        credit.setFont(credit.getFont().deriveFont(10f));
        add(credit, BorderLayout.SOUTH);

        me.addActionListener(e -> centerOnMe());
        fit.addActionListener(e -> fitAll());
        showLinks.addActionListener(e -> view.repaint());
        showNames.addActionListener(e -> view.repaint());

        state.addListener(new MeshState.Listener() {
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(() -> { view.autoCenterOnce(); view.repaint(); }); }
        });
    }

    void centerOnMe() {
        NodeEntry m = state.myNode();
        if (m != null && m.hasPosition) view.setCenter(m.lat, m.lon, Math.max(view.zoom, 12));
        else status.setText("This radio has no position yet");
    }

    void centerOn(int nodeNum) {
        NodeEntry n = state.node(nodeNum);
        if (n != null && n.hasPosition) view.setCenter(n.lat, n.lon, Math.max(view.zoom, 13));
        else status.setText("That node has no position");
    }

    void fitAll() {
        List<NodeEntry> ns = state.nodes().stream().filter(n -> n.hasPosition).toList();
        if (ns.isEmpty()) { status.setText("No nodes with a position yet"); return; }
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (NodeEntry n : ns) {
            minLat = Math.min(minLat, n.lat); maxLat = Math.max(maxLat, n.lat);
            minLon = Math.min(minLon, n.lon); maxLon = Math.max(maxLon, n.lon);
        }
        view.fit(minLat, maxLat, minLon, maxLon);
    }

    // ---- tile cache ---------------------------------------------------------

    private static final Map<String, BufferedImage> tiles = new HashMap<>();
    private static final Set<String> loading = new HashSet<>();
    private static final ExecutorService pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "tile-fetch"); t.setDaemon(true); return t; });
    private static final Path cacheDir = Path.of("tilecache");
    private static volatile boolean online = true;

    private BufferedImage tile(int z, int x, int y) {
        int n = 1 << z;
        if (y < 0 || y >= n) return null;
        x = ((x % n) + n) % n;
        String key = z + "/" + x + "/" + y;
        synchronized (tiles) {
            BufferedImage img = tiles.get(key);
            if (img != null) return img;
            if (loading.contains(key)) return null;
            loading.add(key);
        }
        final int fx = x;
        pool.submit(() -> {
            BufferedImage img = null;
            Path p = cacheDir.resolve(z + "/" + fx + "/" + y + ".png");
            try {
                if (Files.exists(p)) img = ImageIO.read(p.toFile());
            } catch (IOException ignored) { }
            if (img == null) {
                try {
                    HttpURLConnection c = (HttpURLConnection) URI.create("https://tile.openstreetmap.org/" + z + "/" + fx + "/" + y + ".png").toURL().openConnection();
                    c.setRequestProperty("User-Agent", "MeshConsole/1.0 (Meshtastic desktop client; personal use)");
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(10000);
                    try (InputStream in = c.getInputStream()) {
                        img = ImageIO.read(in);
                    }
                    if (img != null) {
                        Files.createDirectories(p.getParent());
                        ImageIO.write(img, "png", p.toFile());
                    }
                    online = true;
                } catch (IOException e) {
                    online = false;
                }
            }
            synchronized (tiles) {
                loading.remove(key);
                if (img != null) tiles.put(key, img);
            }
            if (img != null) SwingUtilities.invokeLater(view::repaint);
        });
        return null;
    }

    // ---- projection ---------------------------------------------------------

    static double lonToX(double lon, int z) { return (lon + 180) / 360 * (1 << z) * TILE; }
    static double latToY(double lat, int z) {
        double r = Math.toRadians(Math.max(-85.05, Math.min(85.05, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z) * TILE;
    }
    static double xToLon(double x, int z) { return x / ((1 << z) * TILE) * 360 - 180; }
    static double yToLat(double y, int z) {
        double n = Math.PI - 2 * Math.PI * y / ((1 << z) * TILE);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    // ---- view ---------------------------------------------------------------

    private class MapView extends JComponent {
        double centerLat = 20, centerLon = 0;
        int zoom = 2;
        boolean centeredOnce;
        Point dragStart;
        double dragLat, dragLon;
        NodeEntry selected;

        MapView() {
            setPreferredSize(new Dimension(700, 500));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { dragStart = e.getPoint(); dragLat = centerLat; dragLon = centerLon; }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStart == null) return;
                    double cx = lonToX(dragLon, zoom) - (e.getX() - dragStart.x);
                    double cy = latToY(dragLat, zoom) - (e.getY() - dragStart.y);
                    centerLon = xToLon(cx, zoom);
                    centerLat = yToLat(cy, zoom);
                    repaint();
                }
                @Override public void mouseClicked(MouseEvent e) {
                    NodeEntry hit = null;
                    for (NodeEntry n : state.nodes()) {
                        if (!n.hasPosition) continue;
                        Point p = toScreen(n.lat, n.lon);
                        if (p.distance(e.getPoint()) < 10) { hit = n; break; }
                    }
                    selected = hit;
                    if (hit != null) {
                        NodeEntry me = state.myNode();
                        String dist = (me != null && me.hasPosition && me.num != hit.num) ? "  " + Fmt.distance(Fmt.distanceM(me.lat, me.lon, hit.lat, hit.lon)) + " away" : "";
                        status.setText(hit.displayName() + " " + hit.idString() + dist + "  heard " + Fmt.ago(hit.lastHeardMillis()) + " ago"
                                + (hit.snr != 0 ? String.format("  SNR %.1f", hit.snr) : "") + (hit.rssi != 0 ? "  RSSI " + hit.rssi : ""));
                    }
                    repaint();
                }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    int nz = Math.max(1, Math.min(18, zoom - e.getWheelRotation()));
                    if (nz == zoom) return;
                    // zoom around cursor
                    double lat = yToLat(latToY(centerLat, zoom) + e.getY() - getHeight() / 2.0, zoom);
                    double lon = xToLon(lonToX(centerLon, zoom) + e.getX() - getWidth() / 2.0, zoom);
                    zoom = nz;
                    double cx = lonToX(lon, zoom) - (e.getX() - getWidth() / 2.0);
                    double cy = latToY(lat, zoom) - (e.getY() - getHeight() / 2.0);
                    centerLon = xToLon(cx, zoom);
                    centerLat = yToLat(cy, zoom);
                    repaint();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        void autoCenterOnce() {
            if (centeredOnce) return;
            NodeEntry me = state.myNode();
            if (me != null && me.hasPosition) { setCenter(me.lat, me.lon, 12); centeredOnce = true; return; }
            for (NodeEntry n : state.nodes()) if (n.hasPosition) { setCenter(n.lat, n.lon, 10); centeredOnce = true; return; }
        }

        void setCenter(double lat, double lon, int z) {
            centerLat = lat; centerLon = lon; zoom = z; repaint();
        }

        void fit(double minLat, double maxLat, double minLon, double maxLon) {
            int w = Math.max(getWidth(), 200), h = Math.max(getHeight(), 200);
            int z = 18;
            for (; z > 1; z--) {
                double dx = lonToX(maxLon, z) - lonToX(minLon, z);
                double dy = latToY(minLat, z) - latToY(maxLat, z);
                if (dx < w - 80 && dy < h - 80) break;
            }
            centerLat = yToLat((latToY(minLat, z) + latToY(maxLat, z)) / 2, z);
            centerLon = (minLon + maxLon) / 2;
            zoom = z;
            repaint();
        }

        Point toScreen(double lat, double lon) {
            double cx = lonToX(centerLon, zoom), cy = latToY(centerLat, zoom);
            return new Point((int) Math.round(lonToX(lon, zoom) - cx + getWidth() / 2.0),
                             (int) Math.round(latToY(lat, zoom) - cy + getHeight() / 2.0));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(225, 230, 235));
            g.fillRect(0, 0, w, h);
            double cx = lonToX(centerLon, zoom), cy = latToY(centerLat, zoom);
            double left = cx - w / 2.0, top = cy - h / 2.0;
            int tx0 = (int) Math.floor(left / TILE), ty0 = (int) Math.floor(top / TILE);
            int tx1 = (int) Math.floor((left + w) / TILE), ty1 = (int) Math.floor((top + h) / TILE);
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int ty = ty0; ty <= ty1; ty++) {
                    int sx = (int) Math.round(tx * TILE - left), sy = (int) Math.round(ty * TILE - top);
                    BufferedImage img = tile(zoom, tx, ty);
                    if (img != null) g.drawImage(img, sx, sy, null);
                    else { g.setColor(new Color(210, 215, 220)); g.drawRect(sx, sy, TILE, TILE); }
                }
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            List<NodeEntry> nodes = state.nodes();
            NodeEntry me = state.myNode();
            Point mePt = (me != null && me.hasPosition) ? toScreen(me.lat, me.lon) : null;

            if (showLinks.isSelected() && mePt != null) {
                g.setColor(new Color(40, 90, 200, 110));
                g.setStroke(new BasicStroke(1.5f));
                for (NodeEntry n : nodes) {
                    if (!n.hasPosition || n.num == me.num || n.hopsAway != 0) continue;
                    Point p = toScreen(n.lat, n.lon);
                    g.drawLine(mePt.x, mePt.y, p.x, p.y);
                }
            }
            long now = System.currentTimeMillis();
            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            for (NodeEntry n : nodes) {
                if (!n.hasPosition) continue;
                Point p = toScreen(n.lat, n.lon);
                if (p.x < -50 || p.y < -50 || p.x > w + 50 || p.y > h + 50) continue;
                boolean isMe = me != null && n.num == me.num;
                long age = now - n.lastHeardMillis();
                Color c = isMe ? new Color(30, 100, 220) : age < 15 * 60_000 ? new Color(30, 170, 60)
                        : age < 2 * 3600_000 ? new Color(230, 170, 20) : new Color(140, 140, 140);
                int r = isMe ? 8 : 6;
                g.setColor(c);
                g.fillOval(p.x - r, p.y - r, 2 * r, 2 * r);
                g.setColor(n == selected ? Color.RED : Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawOval(p.x - r, p.y - r, 2 * r, 2 * r);
                if (showNames.isSelected()) {
                    String label = isMe ? "ME" : n.shortLabel();
                    int lw = g.getFontMetrics().stringWidth(label);
                    g.setColor(new Color(255, 255, 255, 200));
                    g.fillRoundRect(p.x + r + 2, p.y - 8, lw + 6, 15, 4, 4);
                    g.setColor(Color.BLACK);
                    g.drawString(label, p.x + r + 5, p.y + 4);
                }
            }
            g.setColor(Color.DARK_GRAY);
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
            long withPos = nodes.stream().filter(n -> n.hasPosition).count();
            g.drawString("z" + zoom + "   " + withPos + "/" + nodes.size() + " nodes have a position" + (online ? "" : "   (tile download failed – offline?)"), 6, h - 6);
        }
    }
}
