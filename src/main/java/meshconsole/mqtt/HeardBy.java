package meshconsole.mqtt;

import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import meshconsole.tools.MeshCrypto;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.MQTTProtos.MapReport;
import org.meshtastic.proto.MQTTProtos.ServiceEnvelope;
import org.meshtastic.proto.MeshProtos.Data;
import org.meshtastic.proto.MeshProtos.MeshPacket;
import org.meshtastic.proto.MeshProtos.Position;
import org.meshtastic.proto.MeshProtos.User;
import org.meshtastic.proto.Portnums.PortNum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * "Heard by": runs in the background, listening to the public Meshtastic MQTT server (other people's gateways) and,
 * optionally, your own private server, and records every time a gateway hears one of YOUR nodes – which gateway,
 * how many hops, the signal at that gateway. Gateway positions/names come from their own position, node-info and
 * map-report packets. Log: heard_by.csv; gateways: heard_by_gateways.csv; settings: heard-by.properties.
 */
public final class HeardBy {
    public record Sighting(long time, String source, String gateway, int from, String port, int hops, int rssi, float snr, String channel) { }
    public static final class Gw { public String id, name = ""; public double lat, lon; public long last; boolean dirty; }

    private final MeshClient client;
    private final MeshState state;
    private final Path dir, cfgFile, logFile, gwFile;
    private final Properties p = new Properties();
    private final Map<String, MqttClient> conns = new ConcurrentHashMap<>();
    private final Map<String, String> connStatus = new ConcurrentHashMap<>();
    private final Map<String, long[]> connCounts = new ConcurrentHashMap<>();
    private final Map<String, Gw> gws = new ConcurrentHashMap<>();
    private final Deque<Sighting> recent = new ConcurrentLinkedDeque<>();       // last 30 days in memory
    private final Map<String, Long> seenKeys = new ConcurrentHashMap<>();       // packetId|gateway -> time (dedupe)
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "heard-by"); t.setDaemon(true); return t; });

    private static final Map<Path, HeardBy> BY_DIR = new HashMap<>();
    /** One shared service per data folder, so the desktop tab and the web console show the same thing. */
    public static synchronized HeardBy forDir(MeshClient client, Path dataDir) {
        return BY_DIR.computeIfAbsent(dataDir.toAbsolutePath().normalize(), d -> new HeardBy(client, d));
    }

    public HeardBy(MeshClient client, Path dataDir) {
        this.client = client; this.state = client.state(); this.dir = dataDir;
        cfgFile = dataDir.resolve("heard-by.properties"); logFile = dataDir.resolve("heard_by.csv"); gwFile = dataDir.resolve("heard_by_gateways.csv");
        try (var r = Files.newBufferedReader(cfgFile)) { p.load(r); } catch (Exception ignored) { }
        loadGateways(); loadLog();
        ex.scheduleWithFixedDelay(this::tick, 5, 60, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ settings
    public String get(String k, String d) { return p.getProperty(k, d); }
    boolean on(String k) { return Boolean.parseBoolean(get(k, "false")); }
    public synchronized void save(Map<String, String> f) throws IOException {
        for (String k : List.of("enabled", "publicOn", "publicHost", "publicUser", "publicTopic", "privateOn", "privateHost", "privatePort", "privateUser", "privateTopic", "extraNodes"))
            if (f.containsKey(k)) p.setProperty(k, f.get(k).trim());
        for (String k : List.of("publicPass", "privatePass")) if (f.containsKey(k) && !f.get(k).isBlank()) p.setProperty(k, f.get(k).trim());
        try (var w = Files.newBufferedWriter(cfgFile)) { p.store(w, "Mesh Console – Heard by"); }
        for (MqttClient c : conns.values()) c.close();
        conns.clear(); ex.execute(this::tick);
    }

    /** Our own node plus the extra node IDs from settings (the other house, the G2, the T-Beam…). */
    public Set<Integer> myNodes() {
        Set<Integer> s = new LinkedHashSet<>();
        NodeEntry me = state.myNode(); if (me != null) s.add(me.num);
        for (String t : get("extraNodes", "").split("[,\\s]+")) {
            t = t.trim().replaceFirst("^!", ""); if (t.isEmpty()) continue;
            try { s.add((int) Long.parseLong(t, 16)); } catch (NumberFormatException ignored) { }
        }
        return s;
    }

    // ------------------------------------------------------------------ connections
    private void tick() {
        try {
            long cut = System.currentTimeMillis() - 30L * 86_400_000L;
            while (!recent.isEmpty() && recent.peekFirst().time() < cut) recent.pollFirst();
            seenKeys.values().removeIf(t -> t < System.currentTimeMillis() - 15 * 60_000L);
            saveGateways();
            if (!on("enabled")) { for (MqttClient c : conns.values()) c.close(); conns.clear(); connStatus.clear(); return; }
            if (on("publicOn") || !p.containsKey("publicOn")) ensure("public", get("publicHost", "mqtt.meshtastic.org"), 1883, get("publicUser", "meshdev"), get("publicPass", "large4cats"), get("publicTopic", "msh/US/#"));
            else drop("public");
            if (on("privateOn") && !get("privateHost", "").isBlank())
                ensure("private", get("privateHost", ""), Integer.parseInt(get("privatePort", "1883")), get("privateUser", "mesh"), get("privatePass", ""), get("privateTopic", "msh/#"));
            else drop("private");
        } catch (Exception e) { state.emitLog("[heard-by] " + e.getMessage()); }
    }
    private void drop(String name) { MqttClient c = conns.remove(name); if (c != null) c.close(); connStatus.remove(name); }
    private void ensure(String name, String host, int port, String user, String pass, String topic) {
        if (conns.containsKey(name)) return;
        try {
            MqttClient c = new MqttClient(host, port, false, user, pass, "meshconsole-heardby-" + name + "-" + Long.toHexString(System.nanoTime() & 0xFFFFFF), new MqttClient.Listener() {
                @Override public void onMessage(String t, byte[] payload, boolean retained) { onEnvelope(name, payload); }
                @Override public void onLog(String line) { }
                @Override public void onDisconnected(String reason) { conns.remove(name); connStatus.put(name, "disconnected (" + reason + ") – retrying"); }
            });
            c.subscribe(topic);
            conns.put(name, c); connStatus.put(name, "listening on " + topic + " at " + host);
        } catch (Exception e) { connStatus.put(name, "can't connect to " + host + ": " + e.getMessage() + " – retrying every minute"); }
    }

    // ------------------------------------------------------------------ decoding
    void onEnvelope(String source, byte[] payload) {
        ServiceEnvelope env;
        try { env = ServiceEnvelope.parseFrom(payload); } catch (Exception e) { return; }
        if (!env.hasPacket()) return;
        connCounts.computeIfAbsent(source, k -> new long[1])[0]++;
        MeshPacket pk = env.getPacket();
        String gwId = env.getGatewayId();
        Data d = pk.hasDecoded() ? pk.getDecoded() : pk.hasEncrypted() ? tryDecrypt(pk) : null;
        // learn gateways' names/positions from what they send about themselves
        if (d != null) learn(pk.getFrom(), d);
        if (!myNodes().contains(pk.getFrom()) || gwId.isEmpty()) return;
        int gwNum = parseId(gwId);
        if (gwNum == pk.getFrom()) return;                                   // our own node uplinking itself: not "heard by"
        String key = Integer.toUnsignedString(pk.getId()) + "|" + gwId;
        if (seenKeys.putIfAbsent(key, System.currentTimeMillis()) != null) return;   // same packet, same gateway, other source
        String port = d != null ? d.getPortnum().name().replace("_APP", "") : "encrypted";
        int hops = pk.getHopStart() > 0 ? pk.getHopStart() - pk.getHopLimit() : -1;
        Sighting s = new Sighting(System.currentTimeMillis(), source, gwId, pk.getFrom(), port, hops, pk.getRxRssi(), pk.getRxSnr(), env.getChannelId());
        recent.addLast(s);
        gws.computeIfAbsent(gwId, k -> { Gw g = new Gw(); g.id = k; return g; }).last = s.time();
        try {
            Files.writeString(logFile, s.time() + "," + source + "," + gwId + "," + String.format("!%08x", s.from()) + "," + port + "," + hops + "," + s.rssi() + "," + s.snr() + "," + s.channel().replace(",", ";") + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    private void learn(int from, Data d) {
        String id = String.format("!%08x", from);
        try {
            if (d.getPortnum() == PortNum.POSITION_APP) {
                Position pos = Position.parseFrom(d.getPayload());
                if (pos.getLatitudeI() != 0) { Gw g = gws.computeIfAbsent(id, k -> { Gw x = new Gw(); x.id = k; return x; }); g.lat = pos.getLatitudeI() / 1e7; g.lon = pos.getLongitudeI() / 1e7; g.dirty = true; }
            } else if (d.getPortnum() == PortNum.NODEINFO_APP) {
                User u = User.parseFrom(d.getPayload());
                Gw g = gws.computeIfAbsent(id, k -> { Gw x = new Gw(); x.id = k; return x; }); if (!u.getLongName().isBlank()) { g.name = u.getLongName(); g.dirty = true; }
            } else if (d.getPortnum() == PortNum.MAP_REPORT_APP) {
                MapReport m = MapReport.parseFrom(d.getPayload());
                Gw g = gws.computeIfAbsent(id, k -> { Gw x = new Gw(); x.id = k; return x; });
                if (!m.getLongName().isBlank()) g.name = m.getLongName();
                if (m.getLatitudeI() != 0) { g.lat = m.getLatitudeI() / 1e7; g.lon = m.getLongitudeI() / 1e7; }
                g.dirty = true;
            }
        } catch (Exception ignored) { }
        if (gws.size() > 20000) gws.values().removeIf(g -> g.last == 0 && g.lat == 0);    // keep the table bounded
    }

    private Data tryDecrypt(MeshPacket p) {
        List<byte[]> keys = new ArrayList<>(); keys.add(MeshCrypto.DEFAULT_KEY);
        for (Channel c : state.allChannels()) if (!c.getSettings().getPsk().isEmpty()) keys.add(MeshCrypto.expandKey(c.getSettings().getPsk().toByteArray()));
        for (byte[] k : keys) {
            if (k.length != 16 && k.length != 32) continue;
            try { Data d = Data.parseFrom(MeshCrypto.decrypt(k, p.getId(), p.getFrom(), p.getEncrypted().toByteArray())); if (d.getPortnumValue() > 0 && d.getPortnumValue() < 512) return d; } catch (Exception ignored) { }
        }
        return null;
    }
    static int parseId(String s) { try { return (int) Long.parseLong(s.replaceFirst("^!", ""), 16); } catch (Exception e) { return 0; } }

    // ------------------------------------------------------------------ persistence
    private void loadLog() {
        long cut = System.currentTimeMillis() - 30L * 86_400_000L;
        try (var lines = Files.lines(logFile)) {
            lines.forEach(l -> { String[] c = l.split(",", -1); if (c.length < 9) return;
                try { long t = Long.parseLong(c[0]); if (t < cut) return;
                    recent.addLast(new Sighting(t, c[1], c[2], parseId(c[3]), c[4], Integer.parseInt(c[5]), Integer.parseInt(c[6]), Float.parseFloat(c[7]), c[8]));
                    gws.computeIfAbsent(c[2], k -> { Gw g = new Gw(); g.id = k; return g; }).last = Math.max(gws.get(c[2]) == null ? 0 : gws.get(c[2]).last, t);
                } catch (Exception ignored) { } });
        } catch (IOException ignored) { }
    }
    private void loadGateways() {
        try (var lines = Files.lines(gwFile)) {
            lines.forEach(l -> { String[] c = l.split(",", 5); if (c.length < 5) return;
                Gw g = new Gw(); g.id = c[0]; try { g.lat = Double.parseDouble(c[1]); g.lon = Double.parseDouble(c[2]); g.last = Long.parseLong(c[3]); } catch (Exception ignored) { } g.name = c[4]; gws.put(g.id, g); });
        } catch (IOException ignored) { }
    }
    private void saveGateways() {
        // only gateways that have heard us, or that we know a position for and heard us before
        boolean any = false; for (Gw g : gws.values()) if (g.dirty) { any = true; g.dirty = false; }
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (Gw g : gws.values()) if (g.last > 0) sb.append(g.id).append(',').append(g.lat).append(',').append(g.lon).append(',').append(g.last).append(',').append(g.name.replace("\n", " ")).append('\n');
        try { Files.writeString(gwFile, sb.toString()); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ report
    public String json() {
        long now = System.currentTimeMillis(), d1 = now - 86_400_000L, d7 = now - 7 * 86_400_000L;
        StringBuilder sb = new StringBuilder("{\"enabled\":").append(on("enabled"));
        sb.append(",\"settings\":{\"publicOn\":").append(on("publicOn") || !p.containsKey("publicOn")).append(",\"publicHost\":").append(q(get("publicHost", "mqtt.meshtastic.org")))
          .append(",\"publicTopic\":").append(q(get("publicTopic", "msh/US/#"))).append(",\"privateOn\":").append(on("privateOn"))
          .append(",\"privateHost\":").append(q(get("privateHost", ""))).append(",\"privatePort\":").append(q(get("privatePort", "1883")))
          .append(",\"privateUser\":").append(q(get("privateUser", "mesh"))).append(",\"hasPrivatePass\":").append(!get("privatePass", "").isBlank())
          .append(",\"privateTopic\":").append(q(get("privateTopic", "msh/#"))).append(",\"extraNodes\":").append(q(get("extraNodes", ""))).append("}");
        sb.append(",\"sources\":[");
        boolean first = true;
        for (String n : new TreeSet<>(connStatus.keySet())) { if (!first) sb.append(','); first = false;
            sb.append("{\"name\":").append(q(n)).append(",\"status\":").append(q(connStatus.get(n))).append(",\"packets\":").append(connCounts.getOrDefault(n, new long[1])[0]).append('}'); }
        sb.append("],\"nodes\":[");
        first = true;
        for (int num : myNodes()) {
            if (!first) sb.append(','); first = false;
            NodeEntry ne = state.node(num); String id = String.format("!%08x", num);
            Gw selfGw = gws.get(id);
            double lat = ne != null && ne.hasPosition ? ne.lat : selfGw != null ? selfGw.lat : 0, lon = ne != null && ne.hasPosition ? ne.lon : selfGw != null ? selfGw.lon : 0;
            String name = state.nodeName(num); if ((name == null || name.startsWith("!")) && selfGw != null && !selfGw.name.isBlank()) name = selfGw.name;
            Map<String, long[]> per = new HashMap<>();            // gw -> n1, n7, last, minHops, bestSnr*10, bestRssi, direct count
            Map<String, Set<String>> daily = new TreeMap<>(); Map<String, Integer> dailyN = new TreeMap<>();
            for (Sighting s : recent) {
                if (s.from() != num) continue;
                String day = Instant.ofEpochMilli(s.time()).atZone(ZoneId.systemDefault()).toLocalDate().toString();
                daily.computeIfAbsent(day, k -> new HashSet<>()).add(s.gateway()); dailyN.merge(day, 1, Integer::sum);
                if (s.time() < d7) continue;
                long[] a = per.computeIfAbsent(s.gateway(), k -> new long[]{0, 0, 0, 99, -9990, -999, 0});
                a[1]++; if (s.time() >= d1) a[0]++; a[2] = Math.max(a[2], s.time());
                if (s.hops() >= 0) a[3] = Math.min(a[3], s.hops());
                if (s.hops() == 0) { a[6]++; if (s.snr() != 0) a[4] = Math.max(a[4], Math.round(s.snr() * 10)); if (s.rssi() != 0) a[5] = Math.max(a[5], s.rssi()); }
            }
            sb.append("{\"id\":").append(q(id)).append(",\"name\":").append(q(name == null ? id : name)).append(",\"lat\":").append(lat).append(",\"lon\":").append(lon)
              .append(",\"gateways\":[");
            List<Map.Entry<String, long[]>> list = new ArrayList<>(per.entrySet()); list.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
            boolean f2 = true;
            for (var e : list) {
                if (!f2) sb.append(','); f2 = false;
                Gw g = gws.get(e.getKey()); long[] a = e.getValue();
                double km = g != null && g.lat != 0 && lat != 0 ? meshconsole.analysis.Metar.distanceKm(lat, lon, g.lat, g.lon) : -1;
                sb.append("{\"id\":").append(q(e.getKey())).append(",\"name\":").append(q(g == null ? "" : g.name)).append(",\"lat\":").append(g == null ? 0 : g.lat).append(",\"lon\":").append(g == null ? 0 : g.lon)
                  .append(",\"km\":").append(String.format(Locale.ROOT, "%.1f", km)).append(",\"n1\":").append(a[0]).append(",\"n7\":").append(a[1]).append(",\"last\":").append(a[2])
                  .append(",\"minHops\":").append(a[3] == 99 ? -1 : a[3]).append(",\"direct\":").append(a[6])
                  .append(",\"bestSnr\":").append(a[4] == -9990 ? "null" : String.format(Locale.ROOT, "%.1f", a[4] / 10.0)).append(",\"bestRssi\":").append(a[5] == -999 ? "null" : a[5]).append('}');
            }
            sb.append("],\"daily\":[");
            boolean f3 = true;
            for (String day : daily.keySet()) { if (!f3) sb.append(','); f3 = false; sb.append("{\"day\":").append(q(day)).append(",\"gateways\":").append(daily.get(day).size()).append(",\"packets\":").append(dailyN.get(day)).append('}'); }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }
    static String q(String s) { return meshconsole.ui.WebConsole.q(s == null ? "" : s); }
}
