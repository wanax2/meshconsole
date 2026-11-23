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
    /** pid: the packet's id (0 in sightings logged before 1.29.5). */
    public record Sighting(long time, String source, String gateway, int from, String port, int hops, int rssi, float snr, String channel, int pid) { }
    /** 1.29.5: a packet one of our nodes uplinked to MQTT itself – the "sent" side of the reliability report. */
    public record Own(long time, int from, int pid, String port) { }
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
    private final Deque<Own> own = new ConcurrentLinkedDeque<>();               // 1.29.5: our own uplinks, last 30 days
    private final Map<String, Long> ownKeys = new ConcurrentHashMap<>();
    private final Path ownFile;
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
        ownFile = dataDir.resolve("heard_by_sent.csv");
        try (var r = Files.newBufferedReader(cfgFile)) { p.load(r); } catch (Exception ignored) { }
        loadGateways(); loadLog(); loadOwn();
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
            while (!own.isEmpty() && own.peekFirst().time() < cut) own.pollFirst();
            ownKeys.values().removeIf(t -> t < System.currentTimeMillis() - 15 * 60_000L);
            seenKeys.values().removeIf(t -> t < System.currentTimeMillis() - 15 * 60_000L);
            saveGateways();
            if (!on("enabled")) { for (MqttClient c : conns.values()) c.close(); conns.clear(); connStatus.clear(); return; }
            if (on("publicOn") || !p.containsKey("publicOn")) ensure("public", get("publicHost", "mqtt.meshtastic.org"), 1883, get("publicUser", "meshdev"), get("publicPass", "large4cats"), get("publicTopic", "msh/US/#"));
            else drop("public");
            if (on("privateOn") && !get("privateHost", "localhost").isBlank())
                ensure("private", get("privateHost", "localhost"), Integer.parseInt(get("privatePort", "1883")), get("privateUser", "mesh"), get("privatePass", ""), get("privateTopic", "msh/#"));
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
        if (gwNum == pk.getFrom()) {                                        // our own node uplinking itself: not "heard by", but it counts as "sent"
            if (pk.getId() != 0 && ownKeys.putIfAbsent(pk.getFrom() + "|" + Integer.toUnsignedString(pk.getId()), System.currentTimeMillis()) == null) {
                Own o = new Own(System.currentTimeMillis(), pk.getFrom(), pk.getId(), d != null ? d.getPortnum().name().replace("_APP", "") : "encrypted");
                own.addLast(o);
                try { Files.writeString(ownFile, o.time() + "," + String.format("!%08x", o.from()) + "," + Integer.toUnsignedString(o.pid()) + "," + o.port() + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { }
            }
            return;
        }
        String key = Integer.toUnsignedString(pk.getId()) + "|" + gwId;
        if (seenKeys.putIfAbsent(key, System.currentTimeMillis()) != null) return;   // same packet, same gateway, other source
        String port = d != null ? d.getPortnum().name().replace("_APP", "") : "encrypted";
        int hops = pk.getHopStart() > 0 ? pk.getHopStart() - pk.getHopLimit() : -1;
        Sighting s = new Sighting(System.currentTimeMillis(), source, gwId, pk.getFrom(), port, hops, pk.getRxRssi(), pk.getRxSnr(), env.getChannelId(), pk.getId());
        recent.addLast(s);
        gws.computeIfAbsent(gwId, k -> { Gw g = new Gw(); g.id = k; return g; }).last = s.time();
        try {
            Files.writeString(logFile, s.time() + "," + source + "," + gwId + "," + String.format("!%08x", s.from()) + "," + port + "," + hops + "," + s.rssi() + "," + s.snr() + "," + s.channel().replace(",", ";") + "," + Integer.toUnsignedString(s.pid()) + "\n",
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
                    int pid = 0; if (c.length > 9 && !c[9].isEmpty()) try { pid = Integer.parseUnsignedInt(c[9]); } catch (NumberFormatException ignored) { }
                    recent.addLast(new Sighting(t, c[1], c[2], parseId(c[3]), c[4], Integer.parseInt(c[5]), Integer.parseInt(c[6]), Float.parseFloat(c[7]), c[8], pid));
                    gws.computeIfAbsent(c[2], k -> { Gw g = new Gw(); g.id = k; return g; }).last = Math.max(gws.get(c[2]) == null ? 0 : gws.get(c[2]).last, t);
                } catch (Exception ignored) { } });
        } catch (IOException ignored) { }
    }
    private void loadOwn() {
        long cut = System.currentTimeMillis() - 30L * 86_400_000L;
        try (var lines = Files.lines(ownFile)) {
            lines.forEach(l -> { String[] c = l.split(",", -1); if (c.length < 4) return;
                try { long t = Long.parseLong(c[0]); if (t >= cut) own.addLast(new Own(t, parseId(c[1]), Integer.parseUnsignedInt(c[2]), c[3])); } catch (Exception ignored) { } });
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
          .append(",\"privateHost\":").append(q(get("privateHost", "localhost"))).append(",\"privatePort\":").append(q(get("privatePort", "1883")))
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
    // ------------------------------------------------------------------ 1.29.5: reliability report
    public record GwRel(String id, String name, double km, int heard, double pct, double medHops, double medSnr, int direct, long last) { }
    public record PortRel(String port, int total, int heardAny) { }
    /** total = every packet we know the node sent (its own uplinks plus anything a gateway heard). */
    public record Reliability(int node, String name, int hours, int total, int ownUplinks, int heardAny, int heardByNone, long longestGapMin,
                              List<GwRel> gateways, List<PortRel> ports) {
        public double pctAny() { return total == 0 ? 0 : 100.0 * heardAny / total; }
    }

    /** How consistently other gateways heard one of our nodes in the last N hours. */
    public Reliability reliability(int num, int hours) {
        long now = System.currentTimeMillis(), from = now - hours * 3_600_000L;
        Map<String, Set<Integer>> byGw = new HashMap<>();
        Map<String, List<Integer>> hopsBy = new HashMap<>(); Map<String, List<Float>> snrBy = new HashMap<>();
        Map<String, int[]> directLast = new HashMap<>(); Map<String, Long> lastBy = new HashMap<>();
        Set<Integer> heard = new HashSet<>(); Map<Integer, String> portOf = new HashMap<>();
        List<Long> heardTimes = new ArrayList<>();
        for (Sighting x : recent) {
            if (x.from() != num || x.time() < from || x.pid() == 0) continue;
            byGw.computeIfAbsent(x.gateway(), k -> new HashSet<>()).add(x.pid());
            if (x.hops() >= 0) hopsBy.computeIfAbsent(x.gateway(), k -> new ArrayList<>()).add(x.hops());
            if (x.snr() != 0) snrBy.computeIfAbsent(x.gateway(), k -> new ArrayList<>()).add(x.snr());
            if (x.hops() == 0) directLast.computeIfAbsent(x.gateway(), k -> new int[1])[0]++;
            lastBy.merge(x.gateway(), x.time(), Math::max);
            if (heard.add(x.pid())) heardTimes.add(x.time());
            portOf.putIfAbsent(x.pid(), x.port());
        }
        Set<Integer> sent = new HashSet<>();
        for (Own o : own) if (o.from() == num && o.time() >= from) { sent.add(o.pid()); portOf.put(o.pid(), o.port()); }
        Set<Integer> all = new HashSet<>(sent); all.addAll(heard);
        int none = 0; for (int id : sent) if (!heard.contains(id)) none++;
        // longest stretch with no other gateway hearing anything from this node (within the window)
        Collections.sort(heardTimes);
        long gap = 0, prev = from;
        for (long t : heardTimes) { gap = Math.max(gap, t - prev); prev = t; }
        gap = Math.max(gap, now - prev);
        NodeEntry ne = state.node(num); String id = String.format("!%08x", num);
        double lat = ne != null && ne.hasPosition ? ne.lat : 0, lon = ne != null && ne.hasPosition ? ne.lon : 0;
        Gw selfGw = gws.get(id); if (lat == 0 && selfGw != null) { lat = selfGw.lat; lon = selfGw.lon; }
        String name = state.nodeName(num); if ((name == null || name.startsWith("!")) && selfGw != null && !selfGw.name.isBlank()) name = selfGw.name;
        List<GwRel> gl = new ArrayList<>();
        int total = all.size();
        for (var e : byGw.entrySet()) {
            Gw g = gws.get(e.getKey());
            double km = g != null && g.lat != 0 && lat != 0 ? meshconsole.analysis.Metar.distanceKm(lat, lon, g.lat, g.lon) : -1;
            gl.add(new GwRel(e.getKey(), g == null ? "" : g.name, km, e.getValue().size(), total == 0 ? 0 : 100.0 * e.getValue().size() / total,
                    medianI(hopsBy.get(e.getKey())), medianF(snrBy.get(e.getKey())), directLast.getOrDefault(e.getKey(), new int[1])[0], lastBy.getOrDefault(e.getKey(), 0L)));
        }
        gl.sort((a, b) -> Integer.compare(b.heard(), a.heard()));
        Map<String, int[]> pp = new TreeMap<>();
        for (int pid : all) { int[] c = pp.computeIfAbsent(portOf.getOrDefault(pid, "?"), k -> new int[2]); c[0]++; if (heard.contains(pid)) c[1]++; }
        List<PortRel> pl = new ArrayList<>();
        pp.forEach((k, v) -> pl.add(new PortRel(k, v[0], v[1])));
        pl.sort((a, b) -> Integer.compare(b.total(), a.total()));
        return new Reliability(num, name == null ? id : name, hours, total, sent.size(), heard.size(), none, gap / 60_000L, gl, pl);
    }

    private static double medianI(List<Integer> l) { if (l == null || l.isEmpty()) return Double.NaN; List<Integer> c = new ArrayList<>(l); Collections.sort(c); return c.size() % 2 == 1 ? c.get(c.size() / 2) : (c.get(c.size() / 2 - 1) + c.get(c.size() / 2)) / 2.0; }
    private static double medianF(List<Float> l) { if (l == null || l.isEmpty()) return Double.NaN; List<Float> c = new ArrayList<>(l); Collections.sort(c); return c.size() % 2 == 1 ? c.get(c.size() / 2) : (c.get(c.size() / 2 - 1) + c.get(c.size() / 2)) / 2.0; }

    /** The report in plain words: shown under the table, and handed to the AI for "Explain". */
    public static String summary(Reliability r) {
        String span = r.hours() <= 24 ? "the last " + r.hours() + " hours" : "the last " + (r.hours() / 24) + " days";
        if (r.total() == 0) return "Nothing from " + r.name() + " in " + span + ": no gateway heard it and it didn't uplink anything itself.";
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "In %s, %,d packets from %s are known", span, r.total(), r.name()));
        if (r.ownUplinks() > 0) b.append(String.format(Locale.US, " (%,d uplinked by the node itself)", r.ownUplinks()));
        b.append(String.format(Locale.US, ". %.0f%% were heard by at least one other gateway", r.pctAny()));
        if (r.ownUplinks() > 0) b.append(String.format(Locale.US, "; %,d reached no one else", r.heardByNone()));
        b.append(String.format(Locale.US, ". %d gateway%s heard it.", r.gateways().size(), r.gateways().size() == 1 ? "" : "s"));
        for (int i = 0; i < Math.min(3, r.gateways().size()); i++) {
            GwRel g = r.gateways().get(i);
            b.append(String.format(Locale.US, " %s: %.0f%%%s%s%s.", g.name().isBlank() ? g.id() : g.name(), g.pct(),
                    Double.isNaN(g.medHops()) ? "" : String.format(Locale.US, ", usually %s hop%s", fmtHalf(g.medHops()), g.medHops() == 1 ? "" : "s"),
                    Double.isNaN(g.medSnr()) ? "" : String.format(Locale.US, ", SNR %.1f dB", g.medSnr()),
                    g.km() < 0 ? "" : String.format(Locale.US, ", %.0f km away", g.km())));
        }
        if (!r.ports().isEmpty()) {
            b.append(" By kind:");
            for (int i = 0; i < Math.min(4, r.ports().size()); i++) { PortRel p = r.ports().get(i); b.append(String.format(Locale.US, " %s %d of %d heard%s", p.port().toLowerCase(Locale.ROOT).replace('_', ' '), p.heardAny(), p.total(), i < Math.min(4, r.ports().size()) - 1 ? "," : ".")); }
        }
        if (r.longestGapMin() >= 60) b.append(String.format(Locale.US, " Longest stretch with no gateway hearing it: %s.", r.longestGapMin() >= 1440 ? (r.longestGapMin() / 60) + " h" : (r.longestGapMin() / 60) + " h " + (r.longestGapMin() % 60) + " min"));
        if (r.ownUplinks() == 0) b.append(" This node doesn't uplink to MQTT itself, so packets no gateway heard can't be counted: the percentages are shares of what at least one gateway heard.");
        return b.toString();
    }
    private static String fmtHalf(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v); }

    public static String json(Reliability r) {
        StringBuilder b = new StringBuilder("{\"node\":").append(q(String.format("!%08x", r.node()))).append(",\"name\":").append(q(r.name()))
            .append(",\"hours\":").append(r.hours()).append(",\"total\":").append(r.total()).append(",\"own\":").append(r.ownUplinks())
            .append(",\"heardAny\":").append(r.heardAny()).append(",\"none\":").append(r.heardByNone()).append(",\"gapMin\":").append(r.longestGapMin())
            .append(",\"pctAny\":").append(String.format(Locale.ROOT, "%.1f", r.pctAny())).append(",\"gateways\":[");
        for (int i = 0; i < r.gateways().size(); i++) {
            GwRel g = r.gateways().get(i);
            b.append(i > 0 ? "," : "").append("{\"id\":").append(q(g.id())).append(",\"name\":").append(q(g.name()))
             .append(",\"km\":").append(String.format(Locale.ROOT, "%.1f", g.km())).append(",\"heard\":").append(g.heard())
             .append(",\"pct\":").append(String.format(Locale.ROOT, "%.1f", g.pct()))
             .append(",\"hops\":").append(Double.isNaN(g.medHops()) ? "null" : String.format(Locale.ROOT, "%.1f", g.medHops()))
             .append(",\"snr\":").append(Double.isNaN(g.medSnr()) ? "null" : String.format(Locale.ROOT, "%.1f", g.medSnr()))
             .append(",\"direct\":").append(g.direct()).append(",\"last\":").append(g.last()).append('}');
        }
        b.append("],\"ports\":[");
        for (int i = 0; i < r.ports().size(); i++) { PortRel p = r.ports().get(i); b.append(i > 0 ? "," : "").append("{\"port\":").append(q(p.port())).append(",\"total\":").append(p.total()).append(",\"heard\":").append(p.heardAny()).append('}'); }
        return b.append("],\"summary\":").append(q(summary(r))).append('}').toString();
    }

    static String q(String s) { return meshconsole.ui.WebConsole.q(s == null ? "" : s); }

    /** For tests: feed sightings and own uplinks without MQTT. */
    public void addForTest(Sighting x) { recent.addLast(x); }
    public void addForTest(Own o) { own.addLast(o); }
}
