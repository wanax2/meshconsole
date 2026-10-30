package meshconsole.mqtt;

import meshconsole.mesh.MeshState;
import org.meshtastic.proto.MQTTProtos.MapReport;
import org.meshtastic.proto.MQTTProtos.ServiceEnvelope;
import org.meshtastic.proto.MeshProtos.MeshPacket;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;
import org.meshtastic.proto.Portnums.PortNum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Listens to the public server's map reports (root/2/map/) on this computer's own internet connection – nothing is
 * sent to or through the radio – so the map can show the nodes around us that report themselves online, even ones
 * our radio has never heard. Reports are kept in the data folder (mqtt_map_reports.json) and expire after 7 days.
 */
public class MapReportWatcher implements AutoCloseable {
    public static final class Report {
        public int num; public String longName = "", shortName = "", role = "", hw = "", firmware = "", preset = "", region = "";
        public double lat, lon; public int precisionBits, onlineLocal; public long lastSeen;
        /** Where this node was seen: "map" (map report), "channel" (position on the default channel) or both. */
        public boolean viaMap, viaChannel;
        public String via() { return viaMap && viaChannel ? "map report + channel" : viaMap ? "map report" : "channel"; }
        public String idString() { return String.format("!%08x", num); }
        public String label() { return !shortName.isBlank() ? shortName : !longName.isBlank() ? longName : idString(); }
        /** Half the side of the area the reported position stands for, in metres (0 = exact). */
        public double fuzzMetres() {
            if (precisionBits <= 0 || precisionBits >= 32) return 0;
            return Math.pow(2, 32 - precisionBits) * 1e-7 * 111_320 / 2;   // kept bits of latitude_i (1e-7 deg) -> half the cell
        }
    }

    /** Which of the public server's feeds to read. */
    public enum Source {
        MAP_REPORTS("Map reports"), BOTH("Map reports + channel"), CHANNEL("Channel positions");
        public final String label;
        Source(String l) { label = l; }
        @Override public String toString() { return label; }
        boolean map() { return this != CHANNEL; }
        boolean channel() { return this != MAP_REPORTS; }
    }

    private static final long KEEP_MS = 7L * 24 * 3600_000;
    private volatile Source source = Source.MAP_REPORTS;
    private String channelName = "LongFast";
    private final MeshState state;
    private final Consumer<String> status;
    private final Map<Integer, Report> reports = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Path file;
    private MqttClient mqtt;
    private long received, lastSave;

    public MapReportWatcher(MeshState state, Path dataDir, Consumer<String> status) {
        this.state = state; this.status = status; this.file = dataDir.resolve("mqtt_map_reports.json");
        load();
    }

    public void addListener(Runnable r) { listeners.add(r); }
    public boolean isRunning() { return mqtt != null; }
    public Collection<Report> reports() { return reports.values(); }
    public Report get(int num) { return reports.get(num); }

    /** Connects with the radio's MQTT settings when they are loaded, else the public defaults. */
    public synchronized void start(Source src) throws IOException {
        close();
        source = src;
        String address = "mqtt.meshtastic.org", user = "meshdev", pass = "large4cats", root = "msh/US"; boolean tls = false;
        ModuleConfig mc = state.moduleConfig(ModuleConfig.PayloadVariantCase.MQTT);
        if (mc != null) {
            ModuleConfig.MQTTConfig c = mc.getMqtt();
            if (!c.getAddress().isEmpty()) address = c.getAddress();
            if (!c.getUsername().isEmpty()) user = c.getUsername();
            if (!c.getPassword().isEmpty()) pass = c.getPassword();
            if (!c.getRoot().isEmpty()) root = c.getRoot();
            tls = c.getTlsEnabled();
        }
        String host = address; int port = tls ? 8883 : 1883;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && colon < address.length() - 1) { host = address.substring(0, colon); try { port = Integer.parseInt(address.substring(colon + 1)); } catch (NumberFormatException ignored) { } }
        channelName = presetChannelName();
        List<String> topics = new ArrayList<>();
        if (src.map()) topics.add(root + "/2/map/#");
        if (src.channel()) topics.add(root + "/2/e/" + channelName + "/#");
        status.accept("Connecting to " + host + "…");
        mqtt = new MqttClient(host, port, tls, user, pass, "meshconsole-map-" + Long.toHexString(System.nanoTime() & 0xFFFFFF), new MqttClient.Listener() {
            @Override public void onMessage(String t, byte[] payload, boolean retained) { if (t.contains("/2/map")) onEnvelope(payload); else onChannel(payload); }
            @Override public void onLog(String line) { state.emitLog("[mqtt-map] " + line); }
            @Override public void onDisconnected(String reason) { status.accept("Map reports: disconnected – " + reason); synchronized (MapReportWatcher.this) { mqtt = null; } }
        });
        mqtt.subscribe(topics.toArray(new String[0]));
        status.accept("Nearby on MQTT: listening on " + String.join(" + ", topics) + (src.map() ? " – map reports arrive about hourly per node" : ""));
        state.emitLog("[mqtt-map] subscribed to " + String.join(", ", topics));
    }

    private void onEnvelope(byte[] payload) {
        try {
            MeshPacket p = ServiceEnvelope.parseFrom(payload).getPacket();
            if (!p.hasDecoded() || p.getDecoded().getPortnum() != PortNum.MAP_REPORT_APP) return;
            MapReport m = MapReport.parseFrom(p.getDecoded().getPayload());
            if (m.getLatitudeI() == 0 && m.getLongitudeI() == 0) return;
            Report r = new Report();
            r.num = p.getFrom(); r.longName = m.getLongName(); r.shortName = m.getShortName();
            r.role = m.getRole().name(); r.hw = m.getHwModel().name(); r.firmware = m.getFirmwareVersion();
            r.preset = m.getModemPreset().name(); r.region = m.getRegion().name();
            r.lat = m.getLatitudeI() * 1e-7; r.lon = m.getLongitudeI() * 1e-7; r.precisionBits = m.getPositionPrecision();
            r.onlineLocal = m.getNumOnlineLocalNodes(); r.lastSeen = System.currentTimeMillis();
            Report old = reports.get(r.num);
            r.viaMap = true; r.viaChannel = old != null && old.viaChannel;
            reports.put(r.num, r);
            received++;
            for (Runnable l : listeners) l.run();
            if (System.currentTimeMillis() - lastSave > 300_000) save();
        } catch (Exception ignored) { }   // anything that isn't a decodable map report
    }

    /** A packet on the default channel: decrypt with the public default key and take positions and node names from it. */
    private void onChannel(byte[] payload) {
        try {
            MeshPacket p = ServiceEnvelope.parseFrom(payload).getPacket();
            org.meshtastic.proto.MeshProtos.Data d;
            if (p.hasDecoded()) d = p.getDecoded();
            else if (!p.getEncrypted().isEmpty()) d = org.meshtastic.proto.MeshProtos.Data.parseFrom(meshconsole.tools.MeshCrypto.decrypt(meshconsole.tools.MeshCrypto.DEFAULT_KEY, p.getId(), p.getFrom(), p.getEncrypted().toByteArray()));
            else return;
            int from = p.getFrom();
            if (d.getPortnum() == PortNum.POSITION_APP) {
                org.meshtastic.proto.MeshProtos.Position pos = org.meshtastic.proto.MeshProtos.Position.parseFrom(d.getPayload());
                if (pos.getLatitudeI() == 0 && pos.getLongitudeI() == 0) return;
                Report r = reports.computeIfAbsent(from, k -> { Report n = new Report(); n.num = k; return n; });
                if (!r.viaMap || System.currentTimeMillis() - r.lastSeen > 2 * 3600_000) {   // a recent map report keeps its own position
                    r.lat = pos.getLatitudeI() * 1e-7; r.lon = pos.getLongitudeI() * 1e-7; r.precisionBits = pos.getPrecisionBits();
                }
                if (r.preset.isEmpty()) r.preset = channelName + " (channel)";
                r.viaChannel = true; r.lastSeen = System.currentTimeMillis();
                received++;
                for (Runnable l : listeners) l.run();
            } else if (d.getPortnum() == PortNum.NODEINFO_APP) {
                org.meshtastic.proto.MeshProtos.User u = org.meshtastic.proto.MeshProtos.User.parseFrom(d.getPayload());
                Report r = reports.computeIfAbsent(from, k -> { Report n = new Report(); n.num = k; return n; });
                r.longName = u.getLongName(); r.shortName = u.getShortName(); r.hw = u.getHwModel().name(); r.role = u.getRole().name();
                if (r.lastSeen == 0) r.lastSeen = System.currentTimeMillis();
            } else return;
            if (System.currentTimeMillis() - lastSave > 300_000) save();
        } catch (Exception ignored) { }   // not decodable with the default key, or not a position / node info
    }

    private String presetChannelName() {
        try {
            org.meshtastic.proto.ConfigProtos.Config lc = state.config(org.meshtastic.proto.ConfigProtos.Config.PayloadVariantCase.LORA);
            if (lc == null) return "LongFast";
            return switch (lc.getLora().getModemPreset()) {
                case LONG_FAST -> "LongFast"; case LONG_SLOW -> "LongSlow"; case LONG_MODERATE -> "LongMod";
                case MEDIUM_FAST -> "MediumFast"; case MEDIUM_SLOW -> "MediumSlow";
                case SHORT_FAST -> "ShortFast"; case SHORT_SLOW -> "ShortSlow"; case SHORT_TURBO -> "ShortTurbo";
                default -> "LongFast";
            };
        } catch (Exception e) { return "LongFast"; }
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            Object root = meshconsole.analysis.Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof List<?> l)) return;
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Report r = new Report();
                r.num = (int) (long) meshconsole.analysis.Json.num(m.get("num"), 0); r.longName = str(m.get("long")); r.shortName = str(m.get("short"));
                r.role = str(m.get("role")); r.hw = str(m.get("hw")); r.firmware = str(m.get("fw")); r.preset = str(m.get("preset")); r.region = str(m.get("region"));
                r.lat = meshconsole.analysis.Json.num(m.get("lat"), 0); r.lon = meshconsole.analysis.Json.num(m.get("lon"), 0);
                r.precisionBits = (int) meshconsole.analysis.Json.num(m.get("prec"), 0); r.onlineLocal = (int) meshconsole.analysis.Json.num(m.get("online"), 0);
                r.lastSeen = (long) meshconsole.analysis.Json.num(m.get("seen"), 0);
                r.viaMap = m.get("vm") == null || Boolean.TRUE.equals(m.get("vm"));   // older files held map reports only
                r.viaChannel = Boolean.TRUE.equals(m.get("vc"));
                if (r.lastSeen >= cutoff && r.num != 0) reports.put(r.num, r);
            }
        } catch (Exception ignored) { }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    public synchronized void save() {
        lastSave = System.currentTimeMillis();
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        StringBuilder sb = new StringBuilder("[\n");
        boolean first = true;
        for (Report r : reports.values()) {
            if (r.lastSeen < cutoff || (r.lat == 0 && r.lon == 0)) continue;
            if (!first) sb.append(",\n"); first = false;
            sb.append(String.format(Locale.ROOT, "{\"num\":%d,\"long\":%s,\"short\":%s,\"role\":%s,\"hw\":%s,\"fw\":%s,\"preset\":%s,\"region\":%s,\"lat\":%.6f,\"lon\":%.6f,\"prec\":%d,\"online\":%d,\"seen\":%d,\"vm\":%b,\"vc\":%b}",
                    r.num & 0xFFFFFFFFL, q(r.longName), q(r.shortName), q(r.role), q(r.hw), q(r.firmware), q(r.preset), q(r.region), r.lat, r.lon, r.precisionBits, r.onlineLocal, r.lastSeen, r.viaMap, r.viaChannel));
        }
        sb.append("\n]\n");
        try { Files.writeString(file, sb.toString(), StandardCharsets.UTF_8); } catch (IOException ignored) { }
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    public long received() { return received; }

    @Override public synchronized void close() {
        if (mqtt != null) { try { mqtt.close(); } catch (Exception ignored) { } mqtt = null; }
        save();
    }
}
