package meshconsole.ui;

import meshconsole.mesh.CoverageSample;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import meshconsole.mesh.WaypointEntry;

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
    private final Coalesce mapCoalesce = new Coalesce(1000, () -> { MapPanel.this.view.autoCenterOnce(); MapPanel.this.view.repaint(); });
    private static final int TILE = 256;
    private final MeshState state;
    private MeshClient client;
    private final MapView view = new MapView();
    private final JCheckBox showLinks = new JCheckBox("My direct links", true);
    private final JCheckBox showNeighbors = new JCheckBox("Neighbour graph", true);
    private final JCheckBox showTracks = new JCheckBox("Tracks", true);
    private final JCheckBox showWaypoints = new JCheckBox("Waypoints", true);
    private final JCheckBox showCoverage = new JCheckBox("Coverage", false);
    private final JCheckBox showGrid = new JCheckBox("Coverage grid", false);
    private final JCheckBox showNames = new JCheckBox("Names", true);
    // 1.29.4: filters, outline with area, marker shapes by kind
    private final JComboBox<String> hopsFilter = new JComboBox<>(new String[]{"Any hops", "Direct only", "Up to 1 hop", "Up to 2 hops", "Up to 3 hops"});
    private final JComboBox<String> ageFilter = new JComboBox<>(new String[]{"Heard: any time", "Heard: 1 hour", "Heard: 6 hours", "Heard: 24 hours", "Heard: 7 days"});
    private final JCheckBoxMenuItem[] srcItems = {new JCheckBoxMenuItem("Unknown", true), new JCheckBoxMenuItem("Typed in by hand", true), new JCheckBoxMenuItem("Radio's own GPS", true), new JCheckBoxMenuItem("Phone or computer", true)};
    private final JCheckBox showHull = new JCheckBox("Outline & area", false);
    private String hullText = "";
    // "Nearby on MQTT": nodes the public server's map reports place around us, heard by our radio or not
    private final java.util.prefs.Preferences mqttPrefs = java.util.prefs.Preferences.userRoot().node("meshconsole-mqttmap-" + Integer.toHexString(meshconsole.DataDir.get().toString().toLowerCase().hashCode()));
    private final JCheckBox showMqtt = new JCheckBox("Nearby on MQTT", mqttPrefs.getBoolean("on", false));
    private final JSpinner mqttRadius = new JSpinner(new SpinnerNumberModel(mqttPrefs.getInt("radiusKm", 80), 5, 2000, 5));
    private meshconsole.mqtt.MapReportWatcher mqttMap;
    private final JComboBox<meshconsole.mqtt.MapReportWatcher.Source> mqttSource = new JComboBox<>(meshconsole.mqtt.MapReportWatcher.Source.values());
    private final JLabel status = new JLabel(" ");

    MapPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(4, 4));
        JPanel top = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));   // wraps onto a second line on a narrow screen instead of hiding controls
        JButton me = new JButton("Center on me");
        JButton fit = new JButton("Fit all nodes");
        JTextField find = new JTextField(22);
        find.setToolTipText("An address or place (\"123 Main St, Aurora NY\"), or coordinates (\"42.75, -76.70\"); Enter jumps the map there");
        JButton findGo = new JButton("Find");
        Runnable doFind = () -> findPlace(find.getText().trim());
        find.addActionListener(e -> doFind.run()); findGo.addActionListener(e -> doFind.run());
        JButton zoomOut = new JButton("−"), zoomIn = new JButton("+");
        zoomOut.setToolTipText("Zoom out (also: mouse wheel, or the - key)"); zoomIn.setToolTipText("Zoom in (also: mouse wheel, or the + key)");
        zoomOut.addActionListener(e -> view.zoomBy(-1)); zoomIn.addActionListener(e -> view.zoomBy(1));
        top.add(zoomOut); top.add(zoomIn);
        top.add(new JLabel("Go to:")); top.add(find); top.add(findGo);
        top.add(me); top.add(fit); top.add(showLinks); top.add(showNeighbors); top.add(showTracks); top.add(showWaypoints); top.add(showCoverage); top.add(showGrid); top.add(showNames);
        // 1.29.4: which nodes to show, and the outline around them
        JButton srcBtn = new JButton("Positions from ▾");
        JPopupMenu srcMenu = new JPopupMenu();
        for (JCheckBoxMenuItem it : srcItems) { srcMenu.add(it); it.addActionListener(e -> view.repaint()); }
        srcBtn.setToolTipText("Show only nodes whose position came from these sources – hand-typed positions are often wrong");
        srcBtn.addActionListener(e -> srcMenu.show(srcBtn, 0, srcBtn.getHeight()));
        hopsFilter.setToolTipText("Hide nodes further away than this many hops (nodes with unknown hops are hidden when a limit is set)");
        ageFilter.setToolTipText("Hide nodes not heard within this time");
        showHull.setToolTipText("Draw a line around the nodes shown and give the area it encloses – a quick number to compare before and after antenna changes");
        for (JComboBox<String> c : java.util.List.of(hopsFilter, ageFilter)) c.addActionListener(e -> view.repaint());
        showHull.addActionListener(e -> view.repaint());
        top.add(hopsFilter); top.add(ageFilter); top.add(srcBtn); top.add(showHull);
        JButton mqttList = new JButton("Nearby list…");
        showMqtt.setToolTipText("Show the nodes that report themselves to the public MQTT map within the radius – heard by your radio or not (grey squares). Uses this computer's internet; nothing goes through the radio.");
        mqttRadius.setToolTipText("Radius around your position, km");
        ((JSpinner.DefaultEditor) mqttRadius.getEditor()).getTextField().setColumns(4);
        try { mqttSource.setSelectedItem(meshconsole.mqtt.MapReportWatcher.Source.valueOf(mqttPrefs.get("source", "MAP_REPORTS"))); } catch (IllegalArgumentException ignored) { }
        mqttSource.setToolTipText("<html>Map reports: nodes that opted in to the public map (few, each about hourly).<br>Channel positions: any node uplinking positions on the default channel, decrypted with the public key (many more, busier feed).</html>");
        mqttSource.addActionListener(e -> { mqttPrefs.put("source", ((meshconsole.mqtt.MapReportWatcher.Source) mqttSource.getSelectedItem()).name()); if (showMqtt.isSelected()) setMqtt(true); /* start() replaces the running connection */ view.repaint(); });
        top.add(showMqtt); top.add(mqttSource); top.add(mqttRadius); top.add(new JLabel("km")); top.add(mqttList);
        showMqtt.addActionListener(e -> { mqttPrefs.putBoolean("on", showMqtt.isSelected()); setMqtt(showMqtt.isSelected()); view.repaint(); });
        mqttRadius.addChangeListener(e -> { mqttPrefs.putInt("radiusKm", (Integer) mqttRadius.getValue()); view.repaint(); });
        mqttList.addActionListener(e -> showNearbyList());
        if (showMqtt.isSelected()) { javax.swing.Timer t = new javax.swing.Timer(5000, e -> setMqtt(true)); t.setRepeats(false); t.start(); }
        JButton clearCov = new JButton("Clear coverage");
        clearCov.addActionListener(e -> { state.clearCoverage(); view.repaint(); });
        top.add(clearCov);
        top.add(status);
        showNeighbors.setToolTipText("Links reported by nodes running the Neighbor Info module (who hears whom), labelled with SNR");
        showTracks.setToolTipText("Position history of moving nodes");
        showCoverage.setToolTipText("Where this radio was when it heard packets, coloured by RSSI – a drive-test map (needs a GPS position on this node)");
        showGrid.setToolTipText("Coverage points binned into 100 m cells, median RSSI per cell");
        for (JCheckBox c : new JCheckBox[]{showNeighbors, showTracks, showWaypoints, showCoverage, showGrid}) c.addActionListener(e -> view.repaint());
        add(top, BorderLayout.NORTH);
        add(view, BorderLayout.CENTER);
        view.setFocusable(true);   // + / - keys zoom once the map has been clicked
        InputMap im = view.getInputMap(JComponent.WHEN_FOCUSED); ActionMap am = view.getActionMap();
        for (String k : new String[]{"PLUS", "ADD", "EQUALS", "shift EQUALS"}) im.put(KeyStroke.getKeyStroke(k), "zoomIn");
        for (String k : new String[]{"MINUS", "SUBTRACT"}) im.put(KeyStroke.getKeyStroke(k), "zoomOut");
        am.put("zoomIn", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { view.zoomBy(1); } });
        am.put("zoomOut", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { view.zoomBy(-1); } });
        JLabel credit = new JLabel("  Map data © OpenStreetMap contributors  ·  ● client  ▲ router  ■ base  ◆ tracker  pentagon moving  ✈ above 20,000 ft  ○ mute  ·  colour = how recently heard  ·  drag to pan, wheel to zoom, click a marker, right-click for a waypoint");
        credit.setFont(credit.getFont().deriveFont(10f));
        add(credit, BorderLayout.SOUTH);

        me.addActionListener(e -> centerOnMe());
        fit.addActionListener(e -> fitAll());
        showLinks.addActionListener(e -> view.repaint());
        showNames.addActionListener(e -> view.repaint());

        state.addListener(new MeshState.Listener() {
            @Override public void onNodesChanged() { mapCoalesce.request(); }
            @Override public void onWaypointsChanged() { SwingUtilities.invokeLater(view::repaint); }
        });
    }

    void setClient(MeshClient c) { client = c; }

    private void addWaypointAt(double lat, double lon) {
        if (client == null || !client.isConnected()) { status.setText("Connect to a radio to send waypoints"); return; }
        JTextField name = new JTextField(20), desc = new JTextField(20);
        JComboBox<String> expire = new JComboBox<>(new String[]{"Never", "1 hour", "1 day", "1 week"});
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
        form.add(new JLabel("Name:")); form.add(name);
        form.add(new JLabel("Description:")); form.add(desc);
        form.add(new JLabel("Expires:")); form.add(expire);
        form.add(new JLabel("Position:")); form.add(new JLabel(String.format("%.5f, %.5f", lat, lon)));
        if (JOptionPane.showConfirmDialog(this, form, "New waypoint", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (name.getText().isBlank()) return;
        long[] secs = {0, 3600, 86400, 7 * 86400};
        long exp = secs[expire.getSelectedIndex()] == 0 ? 0 : System.currentTimeMillis() / 1000 + secs[expire.getSelectedIndex()];
        try { client.sendWaypoint(name.getText().trim(), desc.getText().trim(), lat, lon, exp); }
        catch (java.io.IOException e) { status.setText("Waypoint failed: " + e.getMessage()); }
    }

    // ---- 1.29.4 helpers -------------------------------------------------------------------------------------------
    /** Passes the hop, last-heard and position-source filters. */
    private boolean visible(NodeEntry n, long now, NodeEntry me) {
        if (me != null && n.num == me.num) return true;
        int hi = hopsFilter.getSelectedIndex();
        if (hi > 0 && (n.hopsAway < 0 || n.hopsAway > hi - 1)) return false;
        long[] ages = {0, 3600_000L, 6 * 3600_000L, 24 * 3600_000L, 7 * 86400_000L};
        int ai = ageFilter.getSelectedIndex();
        if (ai > 0 && now - n.lastHeardMillis() > ages[ai]) return false;
        int src = n.locationSource >= 0 && n.locationSource < srcItems.length ? n.locationSource : 0;
        return srcItems[src].isSelected();
    }

    /** Marker shape by kind; colour says how recently it was heard. */
    private static void drawMarker(Graphics2D g, String kind, Point p, int r, Color fill, Color edge) {
        Shape s;
        switch (kind) {
            case "router" -> { java.awt.geom.Path2D.Double t = new java.awt.geom.Path2D.Double(); t.moveTo(p.x, p.y - r - 2); t.lineTo(p.x + r + 1, p.y + r); t.lineTo(p.x - r - 1, p.y + r); t.closePath(); s = t; }
            case "base" -> s = new Rectangle(p.x - r, p.y - r, 2 * r, 2 * r);
            case "tracker" -> { java.awt.geom.Path2D.Double d = new java.awt.geom.Path2D.Double(); d.moveTo(p.x, p.y - r - 2); d.lineTo(p.x + r + 2, p.y); d.lineTo(p.x, p.y + r + 2); d.lineTo(p.x - r - 2, p.y); d.closePath(); s = d; }
            case "mobile" -> { java.awt.geom.Path2D.Double pe = new java.awt.geom.Path2D.Double(); for (int i = 0; i < 5; i++) { double a = -Math.PI / 2 + i * 2 * Math.PI / 5; double x = p.x + (r + 2) * Math.cos(a), y = p.y + (r + 2) * Math.sin(a); if (i == 0) pe.moveTo(x, y); else pe.lineTo(x, y); } pe.closePath(); s = pe; }
            case "air" -> {
                Font f0 = g.getFont(); g.setFont(f0.deriveFont(Font.PLAIN, 20f));
                g.setColor(edge); g.drawString("✈", p.x - 9, p.y + 8); g.setColor(fill); g.drawString("✈", p.x - 10, p.y + 7);
                g.setFont(f0); return;
            }
            default -> s = new java.awt.geom.Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r);
        }
        if (kind.equals("mute")) { g.setColor(Color.WHITE); g.fill(s); g.setColor(fill); g.setStroke(new BasicStroke(2.5f)); g.draw(s); return; }
        g.setColor(fill); g.fill(s);
        g.setColor(edge); g.setStroke(new BasicStroke(2f)); g.draw(s);
    }

    /** Convex hull of {lat, lon} points (Andrew's monotone chain). */
    static List<double[]> convexHull(List<double[]> in) {
        List<double[]> a = new java.util.ArrayList<>(in);
        if (a.size() < 3) return List.of();
        a.sort((u, v) -> u[1] != v[1] ? Double.compare(u[1], v[1]) : Double.compare(u[0], v[0]));
        java.util.ArrayList<double[]> lo = new java.util.ArrayList<>(), up = new java.util.ArrayList<>();
        for (double[] q : a) { while (lo.size() >= 2 && cross(lo.get(lo.size() - 2), lo.get(lo.size() - 1), q) <= 0) lo.remove(lo.size() - 1); lo.add(q); }
        for (int i = a.size() - 1; i >= 0; i--) { double[] q = a.get(i); while (up.size() >= 2 && cross(up.get(up.size() - 2), up.get(up.size() - 1), q) <= 0) up.remove(up.size() - 1); up.add(q); }
        lo.remove(lo.size() - 1); up.remove(up.size() - 1); lo.addAll(up);
        return lo;
    }
    private static double cross(double[] o, double[] u, double[] v) { return (u[1] - o[1]) * (v[0] - o[0]) - (u[0] - o[0]) * (v[1] - o[1]); }

    /** Area of a {lat, lon} polygon in km², on a local flat projection (good to well under 1% at mesh scale). */
    static double areaKm2(List<double[]> poly) {
        if (poly.size() < 3) return 0;
        double lat0 = 0; for (double[] q : poly) lat0 += q[0]; lat0 = Math.toRadians(lat0 / poly.size());
        double s = 0;
        for (int i = 0; i < poly.size(); i++) {
            double[] u = poly.get(i), v = poly.get((i + 1) % poly.size());
            double x1 = u[1] * 111.32 * Math.cos(lat0), y1 = u[0] * 110.57, x2 = v[1] * 111.32 * Math.cos(lat0), y2 = v[0] * 110.57;
            s += x1 * y2 - x2 * y1;
        }
        return Math.abs(s) / 2;
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

    /** Jumps the map to coordinates or to an address found through OpenStreetMap's Nominatim, and marks the spot. */
    void findPlace(String q) {
        if (q.isEmpty()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").matcher(q);
        if (m.matches()) { double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2)); view.mark(la, lo, String.format("%.5f, %.5f", la, lo)); return; }
        status.setText("Looking up " + q + "…");
        new Thread(() -> {
            try {
                String url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8);
                java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
                java.net.http.HttpResponse<String> r = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("User-Agent", "MeshConsole/" + meshconsole.Version.VERSION + " (desktop Meshtastic client)").timeout(java.time.Duration.ofSeconds(20)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                Object root = meshconsole.analysis.Json.parse(r.body());
                if (!(root instanceof List<?> l) || l.isEmpty()) { SwingUtilities.invokeLater(() -> status.setText("Nothing found for \"" + q + "\" – try adding the town and state, or type coordinates")); return; }
                java.util.Map<?, ?> hit = (java.util.Map<?, ?>) l.get(0);
                double la = Double.parseDouble(String.valueOf(hit.get("lat"))), lo = Double.parseDouble(String.valueOf(hit.get("lon")));
                String name = String.valueOf(hit.get("display_name"));
                SwingUtilities.invokeLater(() -> { view.mark(la, lo, name.length() > 70 ? name.substring(0, 69) + "…" : name); status.setText("Found: " + name + " – right-click the exact roof to set the fixed position"); });
            } catch (Exception ex) { SwingUtilities.invokeLater(() -> status.setText("Address lookup failed: " + ex.getMessage())); }
        }, "find-place").start();
    }

    private void setMqtt(boolean on) {
        if (!on) { if (mqttMap != null) { meshconsole.mqtt.MapReportWatcher w = mqttMap; new Thread(w::close, "mqtt-map-stop").start(); } return; }
        if (mqttMap == null) {
            mqttMap = new meshconsole.mqtt.MapReportWatcher(state, meshconsole.DataDir.get(), msg -> SwingUtilities.invokeLater(() -> status.setText(msg)));
            mqttMap.addListener(() -> SwingUtilities.invokeLater(view::repaint));
        }
        meshconsole.mqtt.MapReportWatcher w = mqttMap;
        meshconsole.mqtt.MapReportWatcher.Source src = (meshconsole.mqtt.MapReportWatcher.Source) mqttSource.getSelectedItem();
        new Thread(() -> { try { w.start(src); } catch (Exception ex) { SwingUtilities.invokeLater(() -> status.setText("Nearby on MQTT: " + ex.getMessage())); } }, "mqtt-map-start").start();
    }

    /** The map-report nodes within the radius of my position (all of them when my position is unknown), nearest first. */
    List<meshconsole.mqtt.MapReportWatcher.Report> nearbyReports() {
        if (mqttMap == null) return List.of();
        NodeEntry me = state.myNode();
        boolean havePos = me != null && me.hasPosition;
        double maxM = (Integer) mqttRadius.getValue() * 1000.0;
        List<meshconsole.mqtt.MapReportWatcher.Report> out = new ArrayList<>();
        for (meshconsole.mqtt.MapReportWatcher.Report r : mqttMap.reports()) {
            if (me != null && r.num == me.num) continue;
            if (r.lat == 0 && r.lon == 0) continue;
            meshconsole.mqtt.MapReportWatcher.Source src = (meshconsole.mqtt.MapReportWatcher.Source) mqttSource.getSelectedItem();
            if (src == meshconsole.mqtt.MapReportWatcher.Source.MAP_REPORTS && !r.viaMap) continue;
            if (src == meshconsole.mqtt.MapReportWatcher.Source.CHANNEL && !r.viaChannel) continue;
            if (havePos && Fmt.distanceM(me.lat, me.lon, r.lat, r.lon) > maxM) continue;
            out.add(r);
        }
        if (havePos) out.sort(Comparator.comparingDouble(r -> Fmt.distanceM(me.lat, me.lon, r.lat, r.lon)));
        return out;
    }

    static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2), x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    String heardText(int num) {
        NodeEntry n = state.node(num);
        return n != null && n.lastHeard > 0 ? "heard by radio " + Fmt.ago(n.lastHeardMillis()) + " ago" : "not heard by radio";
    }

    String describe(meshconsole.mqtt.MapReportWatcher.Report r) {
        NodeEntry me = state.myNode();
        String where = "";
        if (me != null && me.hasPosition) where = String.format("  %s at %.0f°", Fmt.distance(Fmt.distanceM(me.lat, me.lon, r.lat, r.lon)), bearingDeg(me.lat, me.lon, r.lat, r.lon));
        String fuzz = r.fuzzMetres() > 0 ? String.format(" (±%.0f m)", r.fuzzMetres()) : "";
        return (r.longName.isBlank() ? r.idString() : r.longName) + " " + r.idString() + where + fuzz + "  ·  " + r.preset + " " + r.role
                + "  ·  " + r.via() + " " + Fmt.ago(r.lastSeen) + " ago  ·  " + heardText(r.num);
    }

    private void showNearbyList() {
        if (mqttMap == null || !showMqtt.isSelected()) { showMqtt.setSelected(true); mqttPrefs.putBoolean("on", true); setMqtt(true); }
        NodeEntry me = state.myNode();
        boolean havePos = me != null && me.hasPosition;
        List<meshconsole.mqtt.MapReportWatcher.Report> list = nearbyReports();
        String[] cols = {"Name", "Short", "ID", "Distance", "Bearing", "Preset", "Role", "Hardware", "Seen via", "Last seen", "Your radio"};
        Object[][] rows = new Object[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            meshconsole.mqtt.MapReportWatcher.Report r = list.get(i);
            rows[i] = new Object[]{r.longName, r.shortName, r.idString(),
                    havePos ? Fmt.distance(Fmt.distanceM(me.lat, me.lon, r.lat, r.lon)) : "?",
                    havePos ? String.format("%.0f°", bearingDeg(me.lat, me.lon, r.lat, r.lon)) : "?",
                    r.preset, r.role, r.hw, r.via(), Fmt.ago(r.lastSeen) + " ago", heardText(r.num)};
        }
        JTable t = new JTable(rows, cols) { @Override public boolean isCellEditable(int r, int c) { return false; } };
        t.setAutoCreateRowSorter(false);
        t.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && t.getSelectedRow() >= 0) { meshconsole.mqtt.MapReportWatcher.Report r = list.get(t.getSelectedRow()); view.setCenter(r.lat, r.lon, Math.max(view.zoom, 11)); view.repaint(); status.setText(describe(r)); }
            }
        });
        JScrollPane sp = new JScrollPane(t); sp.setPreferredSize(new Dimension(1000, 380));
        String head = list.isEmpty()
                ? (mqttMap != null && mqttMap.isRunning() ? "No map reports within " + mqttRadius.getValue() + " km yet – nodes report about hourly, so give it up to an hour after first switching this on." : "Connecting to the map reports…")
                : list.size() + " node(s) within " + mqttRadius.getValue() + " km (" + mqttSource.getSelectedItem() + "), nearest first" + (havePos ? "" : " (set your position to sort by distance)") + ". Double-click to show one on the map.";
        JPanel p = new JPanel(new BorderLayout(4, 4)); p.add(new JLabel(head), BorderLayout.NORTH); p.add(sp, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, p, "Nearby on MQTT", JOptionPane.PLAIN_MESSAGE);
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
    private static final Path cacheDir = meshconsole.DataDir.file("tilecache");
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

    static Color rssiColor(int rssi, int alpha) {
        // -60 strong (green) … -95 (yellow) … -130 weak (red)
        double t = Math.max(0, Math.min(1, (rssi + 130) / 70.0));
        int r = (int) (255 * (1 - Math.max(0, t - 0.5) * 2));
        int gg = (int) (255 * Math.min(1, t * 2));
        return new Color(r, gg, 40, alpha);
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
        /** Zoom around the centre of the view – for touch screens and remote desktops without a mouse wheel. */
        void zoomBy(int d) { int nz = Math.max(1, Math.min(18, zoom + d)); if (nz != zoom) { zoom = nz; repaint(); } }
        private double markLat = Double.NaN, markLon; private String markLabel = "";
        void mark(double lat, double lon, String label) { markLat = lat; markLon = lon; markLabel = label; setCenter(lat, lon, Math.max(zoom, 17)); repaint(); }
        double centerLat = 20, centerLon = 0;
        int zoom = 2;
        boolean centeredOnce;
        Point dragStart;
        double dragLat, dragLon;
        NodeEntry selected;

        MapView() {
            setPreferredSize(new Dimension(700, 500));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); dragStart = e.getPoint(); dragLat = centerLat; dragLon = centerLon; }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStart == null) return;
                    double cx = lonToX(dragLon, zoom) - (e.getX() - dragStart.x);
                    double cy = latToY(dragLat, zoom) - (e.getY() - dragStart.y);
                    centerLon = xToLon(cx, zoom);
                    centerLat = yToLat(cy, zoom);
                    repaint();
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        double lat = yToLat(latToY(centerLat, zoom) + e.getY() - getHeight() / 2.0, zoom);
                        double lon = xToLon(lonToX(centerLon, zoom) + e.getX() - getWidth() / 2.0, zoom);
                        JPopupMenu menu = new JPopupMenu();
                        JMenuItem wp = new JMenuItem(String.format("Add waypoint here (%.5f, %.5f)", lat, lon));
                        wp.addActionListener(a -> addWaypointAt(lat, lon));
                        JMenuItem fix = new JMenuItem("Set as my fixed position");
                        fix.addActionListener(a -> {
                            if (client == null || !client.isConnected()) { status.setText("Connect to a radio first"); return; }
                            if (JOptionPane.showConfirmDialog(MapPanel.this, String.format("Store %.5f, %.5f on the radio as its fixed position?", lat, lon), "Fixed position", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                            new Thread(() -> {
                                try { client.setFixedPosition(lat, lon, 0); SwingUtilities.invokeLater(() -> { status.setText("Fixed position set"); repaint(); }); }
                                catch (java.io.IOException ex) { SwingUtilities.invokeLater(() -> status.setText("Failed: " + ex.getMessage())); }
                            }, "set-position").start();
                        });
                        menu.add(wp); menu.add(fix);
                        JMenuItem beam = new JMenuItem("Antenna beam overlay…");
                        beam.addActionListener(a -> {
                            java.util.prefs.Preferences pr = beamPrefs();
                            JTextField bearing = new JTextField(pr.get("bearing", "60"), 5), width = new JTextField(pr.get("width", "50"), 5), label = new JTextField(pr.get("label", "yagi"), 10);
                            JCheckBox on = new JCheckBox("Show", pr.getBoolean("on", true));
                            JPanel form = new JPanel(new GridLayout(0, 2, 4, 4)); form.add(new JLabel("Bearing (° true):")); form.add(bearing); form.add(new JLabel("Beam width (°, -3 dB):")); form.add(width); form.add(new JLabel("Label:")); form.add(label); form.add(on);
                            if (JOptionPane.showConfirmDialog(MapPanel.this, form, "Antenna beam overlay (drawn from my position)", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
                            pr.put("bearing", bearing.getText().trim()); pr.put("width", width.getText().trim()); pr.put("label", label.getText().trim()); pr.putBoolean("on", on.isSelected()); repaint();
                        });
                        menu.add(beam);
                        menu.show(MapView.this, e.getX(), e.getY());
                        return;
                    }
                    for (WaypointEntry wp : state.waypoints()) {
                        Point p = toScreen(wp.lat, wp.lon);
                        if (p.distance(e.getPoint()) < 10) {
                            status.setText("Waypoint '" + wp.name + "'" + (wp.description.isEmpty() ? "" : " – " + wp.description) + "  from " + state.nodeName(wp.from)
                                    + (wp.expire == 0 ? "" : "  expires " + Fmt.time(wp.expire * 1000)));
                            repaint();
                            return;
                        }
                    }
                    NodeEntry hit = null;
                    long nowHit = System.currentTimeMillis(); NodeEntry meHit = state.myNode();
                    for (NodeEntry n : state.nodes()) {
                        if (!n.hasPosition || !visible(n, nowHit, meHit)) continue;
                        Point p = toScreen(n.lat, n.lon);
                        if (p.distance(e.getPoint()) < 10) { hit = n; break; }
                    }
                    if (hit == null && showMqtt.isSelected()) {
                        for (meshconsole.mqtt.MapReportWatcher.Report r : nearbyReports()) {
                            Point p = toScreen(r.lat, r.lon);
                            if (p.distance(e.getPoint()) < 10) { status.setText(describe(r)); selected = null; repaint(); return; }
                        }
                    }
                    selected = hit;
                    if (hit != null) {
                        NodeEntry me = state.myNode();
                        String dist = (me != null && me.hasPosition && me.num != hit.num) ? "  " + Fmt.distance(Fmt.distanceM(me.lat, me.lon, hit.lat, hit.lon)) + " away" : "";
                        status.setText(hit.displayName() + " " + hit.idString() + (hit.town.isEmpty() ? "" : "  near " + hit.town) + dist + "  heard " + Fmt.ago(hit.lastHeardMillis()) + " ago"
                                + "  · position: " + hit.locationSourceName() + (hit.altitude != 0 ? String.format("  · %,d ft", Math.round(hit.altitude * 3.281)) : "")
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

        java.util.prefs.Preferences beamPrefs() { return java.util.prefs.Preferences.userRoot().node("meshconsole-beam-" + Integer.toHexString(meshconsole.DataDir.get().toString().toLowerCase().hashCode())); }

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
            if (!Double.isNaN(markLat)) {   // the spot found with "Go to"
                Point mp = toScreen(markLat, markLon);
                g.setColor(new Color(220, 40, 40)); g.setStroke(new BasicStroke(2.5f));
                g.drawLine(mp.x - 9, mp.y, mp.x + 9, mp.y); g.drawLine(mp.x, mp.y - 9, mp.x, mp.y + 9); g.drawOval(mp.x - 6, mp.y - 6, 12, 12);
                g.setColor(new Color(160, 20, 20)); g.drawString(markLabel, mp.x + 10, mp.y - 8);
            }
            long now = System.currentTimeMillis();

            // antenna beam overlay: a wedge from my position along the configured bearing, 15 km long
            java.util.prefs.Preferences bp = beamPrefs();
            if (mePt != null && bp.getBoolean("on", false)) {
                try {
                    double brg = Double.parseDouble(bp.get("bearing", "60")), wid = Double.parseDouble(bp.get("width", "50"));
                    double kmLen = 15; Point far = toScreen(me.lat + kmLen / 111.0 * Math.cos(Math.toRadians(brg)), me.lon + kmLen / (111.0 * Math.cos(Math.toRadians(me.lat))) * Math.sin(Math.toRadians(brg)));
                    double rpx = Math.hypot(far.x - mePt.x, far.y - mePt.y);
                    java.awt.geom.Arc2D wedge = new java.awt.geom.Arc2D.Double(mePt.x - rpx, mePt.y - rpx, 2 * rpx, 2 * rpx, 90 - brg - wid / 2, wid, java.awt.geom.Arc2D.PIE);
                    g.setColor(new Color(60, 120, 220, 40)); g.fill(wedge);
                    g.setColor(new Color(40, 90, 200, 160)); g.setStroke(new BasicStroke(1.5f)); g.draw(wedge);
                    g.drawLine(mePt.x, mePt.y, far.x, far.y);
                    g.setColor(new Color(20, 60, 160)); g.drawString(bp.get("label", "yagi") + " " + (int) brg + "°", far.x + 4, far.y);
                } catch (NumberFormatException ignored) { }
            }

            if (showGrid.isSelected()) {
                for (meshconsole.analysis.Analysis.Cell cell : meshconsole.analysis.Analysis.coverageGrid(state.coverage(), 100)) {
                    Point p = toScreen(cell.lat(), cell.lon());
                    if (p.x < -40 || p.y < -40 || p.x > w + 40 || p.y > h + 40) continue;
                    double mPerPx = 156543.03 * Math.cos(Math.toRadians(cell.lat())) / (1 << zoom);
                    int sz = Math.max(4, (int) (100 / mPerPx));
                    g.setColor(rssiColor((int) cell.medianRssi(), 120));
                    g.fillRect(p.x - sz / 2, p.y - sz / 2, sz, sz);
                    if (sz > 30) { g.setColor(Color.BLACK); g.setFont(g.getFont().deriveFont(9f)); g.drawString(String.valueOf((int) cell.medianRssi()), p.x - 8, p.y + 3); }
                }
            }
            // coverage dots (under everything else)
            if (showCoverage.isSelected()) {
                for (CoverageSample c : state.coverage()) {
                    Point p = toScreen(c.lat(), c.lon());
                    if (p.x < -5 || p.y < -5 || p.x > w + 5 || p.y > h + 5) continue;
                    g.setColor(rssiColor(c.rssi(), 150));
                    g.fillOval(p.x - 5, p.y - 5, 10, 10);
                }
            }
            // tracks
            if (showTracks.isSelected()) {
                g.setStroke(new BasicStroke(2f));
                for (NodeEntry n : nodes) {
                    if (n.track.size() < 2) continue;
                    boolean isMe = me != null && n.num == me.num;
                    g.setColor(isMe ? new Color(30, 100, 220, 150) : new Color(120, 60, 180, 130));
                    Point prev = null;
                    for (double[] t : n.track) {
                        Point p = toScreen(t[1], t[2]);
                        if (prev != null) g.drawLine(prev.x, prev.y, p.x, p.y);
                        prev = p;
                    }
                }
            }
            // neighbour graph
            if (showNeighbors.isSelected()) {
                g.setStroke(new BasicStroke(1.2f));
                g.setFont(g.getFont().deriveFont(9f));
                for (NodeEntry n : nodes) {
                    if (!n.hasPosition || n.neighbors.isEmpty()) continue;
                    Point a = toScreen(n.lat, n.lon);
                    for (Map.Entry<Integer, Float> e : n.neighbors.entrySet()) {
                        NodeEntry o = state.node(e.getKey());
                        if (o == null || !o.hasPosition) continue;
                        Point b = toScreen(o.lat, o.lon);
                        g.setColor(new Color(60, 160, 90, 140));
                        g.drawLine(a.x, a.y, b.x, b.y);
                        g.setColor(new Color(20, 90, 40));
                        g.drawString(String.format("%.0f", e.getValue()), (a.x + b.x) / 2 + 3, (a.y + b.y) / 2 - 2);
                    }
                }
            }

            if (showLinks.isSelected() && mePt != null) {
                g.setColor(new Color(40, 90, 200, 110));
                g.setStroke(new BasicStroke(1.5f));
                for (NodeEntry n : nodes) {
                    if (!n.hasPosition || n.num == me.num || n.hopsAway != 0) continue;
                    Point p = toScreen(n.lat, n.lon);
                    g.drawLine(mePt.x, mePt.y, p.x, p.y);
                }
            }
            // waypoints
            if (showWaypoints.isSelected()) {
                g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
                for (WaypointEntry wp : state.waypoints()) {
                    if (wp.expire != 0 && wp.expire * 1000 < now) continue;
                    Point p = toScreen(wp.lat, wp.lon);
                    if (p.x < -50 || p.y < -50 || p.x > w + 50 || p.y > h + 50) continue;
                    int[] xs = {p.x, p.x + 8, p.x, p.x - 8}, ys = {p.y - 10, p.y, p.y + 10, p.y};
                    g.setColor(new Color(200, 60, 160));
                    g.fillPolygon(xs, ys, 4);
                    g.setColor(Color.WHITE);
                    g.drawPolygon(xs, ys, 4);
                    if (showNames.isSelected()) {
                        int lw = g.getFontMetrics().stringWidth(wp.name);
                        g.setColor(new Color(255, 255, 255, 200));
                        g.fillRoundRect(p.x + 10, p.y - 8, lw + 6, 15, 4, 4);
                        g.setColor(new Color(120, 20, 90));
                        g.drawString(wp.name, p.x + 13, p.y + 4);
                    }
                }
            }
            if (showMqtt.isSelected()) {   // map-report nodes: grey squares (filled = heard by our radio), under our own nodes
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
                for (meshconsole.mqtt.MapReportWatcher.Report r : nearbyReports()) {
                    Point p = toScreen(r.lat, r.lon);
                    if (p.x < -50 || p.y < -50 || p.x > w + 50 || p.y > h + 50) continue;
                    NodeEntry known = state.node(r.num);
                    boolean heard = known != null && known.lastHeard > 0;
                    if (zoom >= 11 && r.fuzzMetres() > 0) {   // the area the reported position stands for
                        double dLat = r.fuzzMetres() / 111_320.0, dLon = dLat / Math.max(0.1, Math.cos(Math.toRadians(r.lat)));
                        Point a = toScreen(r.lat + dLat, r.lon - dLon), b = toScreen(r.lat - dLat, r.lon + dLon);
                        g.setColor(new Color(120, 120, 120, 40)); g.fillRect(a.x, a.y, b.x - a.x, b.y - a.y);
                        g.setColor(new Color(120, 120, 120, 110)); g.drawRect(a.x, a.y, b.x - a.x, b.y - a.y);
                    }
                    g.setStroke(new BasicStroke(1.5f));
                    g.setColor(heard ? new Color(90, 90, 90) : new Color(255, 255, 255, 220)); g.fillRect(p.x - 5, p.y - 5, 10, 10);
                    g.setColor(new Color(80, 80, 80)); g.drawRect(p.x - 5, p.y - 5, 10, 10);
                    if (showNames.isSelected() && (known == null || !known.hasPosition)) {
                        String label = r.label();
                        int lw = g.getFontMetrics().stringWidth(label);
                        g.setColor(new Color(240, 240, 240, 190)); g.fillRoundRect(p.x + 7, p.y - 7, lw + 6, 13, 4, 4);
                        g.setColor(new Color(70, 70, 70)); g.drawString(label, p.x + 10, p.y + 3);
                    }
                }
            }
            // 1.29.4: outline around the nodes shown, with the area it encloses
            hullText = "";
            if (showHull.isSelected()) {
                List<double[]> pts = new java.util.ArrayList<>();
                for (NodeEntry n : nodes) if (n.hasPosition && !(n.lat == 0 && n.lon == 0) && !n.viaMqtt && visible(n, now, me)) pts.add(new double[]{n.lat, n.lon});
                List<double[]> hull = convexHull(pts);
                if (hull.size() >= 3) {
                    java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
                    for (int i = 0; i < hull.size(); i++) { Point hp = toScreen(hull.get(i)[0], hull.get(i)[1]); if (i == 0) path.moveTo(hp.x, hp.y); else path.lineTo(hp.x, hp.y); }
                    path.closePath();
                    g.setColor(new Color(210, 153, 34, 30)); g.fill(path);
                    g.setColor(new Color(190, 130, 20)); g.setStroke(new BasicStroke(1.8f)); g.draw(path);
                    double km2 = areaKm2(hull);
                    hullText = String.format("   outline: %,.0f sq mi (%,.0f km²) around %d nodes", km2 / 2.58999, km2, pts.size());
                } else hullText = "   outline: needs 3 nodes with positions";
            }
            g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
            for (NodeEntry n : nodes) {
                if (!n.hasPosition) continue;
                Point p = toScreen(n.lat, n.lon);
                if (p.x < -50 || p.y < -50 || p.x > w + 50 || p.y > h + 50) continue;
                boolean isMe = me != null && n.num == me.num;
                if (!isMe && !visible(n, now, me)) continue;
                long age = now - n.lastHeardMillis();
                Color c = isMe ? new Color(30, 100, 220) : age < 15 * 60_000 ? new Color(30, 170, 60)
                        : age < 2 * 3600_000 ? new Color(230, 170, 20) : new Color(140, 140, 140);
                int r = isMe ? 8 : 6;
                drawMarker(g, isMe ? "client" : n.kind(now), p, r, c, n == selected ? Color.RED : Color.WHITE);
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
            String cov = showCoverage.isSelected() ? "   coverage: " + state.coverage().size() + " pts (green strong → red weak)" : "";
            g.drawString("z" + zoom + "   " + withPos + "/" + nodes.size() + " nodes have a position" + cov + hullText + (online ? "" : "   (tile download failed – offline?)"), 6, h - 6);
        }
    }
}
