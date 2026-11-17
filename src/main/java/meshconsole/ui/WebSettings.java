package meshconsole.ui;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import meshconsole.mesh.ChatMessage;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;

import static meshconsole.ui.WebConsole.q;

/**
 * The web console's Settings tab (owner, role, LoRa, position, WiFi, channels, MQTT, mesh alerts) and the read-only
 * "peer" feed that the PiHealth dashboard (both-houses page, daily digest) and the other Mesh Console window on the same
 * computer read without the password – but only from this computer or the dashboard's Pi.
 */
final class WebSettings {
    private WebSettings() { }
    /** Computers (names or IP addresses) allowed to read the password-free peer feed, besides this one. */
    static final List<String> DEFAULT_DASHBOARD_HOSTS = List.of("arlmesh", "arlmesh.local");
    static final String DASHBOARD_HOSTS_FILE = "dashboard-hosts.txt";
    private static volatile Path dataDir;
    private static volatile List<String> dashboardHosts = DEFAULT_DASHBOARD_HOSTS;
    private static volatile long hostsReadAt;
    static void dataDir(Path dir) { dataDir = dir; hostsReadAt = 0; }

    /** 1.29.1: the list comes from dashboard-hosts.txt in the data folder (one name or address per line or comma-separated,
     *  # starts a comment); re-read at most once a minute, so an edit needs no restart. No file = the built-in default. */
    static List<String> dashboardHosts() {
        long now = System.currentTimeMillis();
        Path dir = dataDir;
        if (dir == null || now - hostsReadAt < 60_000L) return dashboardHosts;
        hostsReadAt = now;
        try {
            Path f = dir.resolve(DASHBOARD_HOSTS_FILE);
            if (!java.nio.file.Files.exists(f)) return dashboardHosts = DEFAULT_DASHBOARD_HOSTS;
            dashboardHosts = parseHosts(java.nio.file.Files.readString(f));
        } catch (Exception ignored) { }
        return dashboardHosts;
    }
    static List<String> parseHosts(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            int c = line.indexOf('#');
            if (c >= 0) line = line.substring(0, c);
            for (String h : line.split("[,\\s]+")) if (!h.isBlank() && !out.contains(h.trim())) out.add(h.trim());
        }
        return List.copyOf(out);
    }
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(4)).build();

    // ------------------------------------------------------------------ settings as JSON
    static String json(MeshClient client) {
        MeshState st = client.state();
        NodeEntry me = st.myNode();
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"long\":").append(q(me == null ? "" : me.longName)).append(",\"short\":").append(q(me == null ? "" : me.shortName));
        Config dc = st.config(Config.PayloadVariantCase.DEVICE);
        sb.append(",\"role\":").append(q(dc == null ? "" : dc.getDevice().getRole().name()));
        sb.append(",\"roles\":").append(enumList(Config.DeviceConfig.Role.values()));
        Config lc = st.config(Config.PayloadVariantCase.LORA);
        if (lc != null) {
            var l = lc.getLora();
            sb.append(",\"lora\":{\"region\":").append(q(l.getRegion().name())).append(",\"preset\":").append(q(l.getModemPreset().name()))
              .append(",\"hops\":").append(l.getHopLimit()).append(",\"rxBoost\":").append(l.getSx126XRxBoostedGain())
              .append(",\"okToMqtt\":").append(l.getConfigOkToMqtt()).append(",\"txPower\":").append(l.getTxPower()).append('}');
        }
        sb.append(",\"regions\":").append(enumList(Config.LoRaConfig.RegionCode.values())).append(",\"presets\":").append(enumList(Config.LoRaConfig.ModemPreset.values()));
        Config pc = st.config(Config.PayloadVariantCase.POSITION);
        sb.append(",\"fixed\":").append(pc != null && pc.getPosition().getFixedPosition());
        if (me != null && me.hasPosition) sb.append(",\"lat\":").append(me.lat).append(",\"lon\":").append(me.lon).append(",\"alt\":").append(me.altitude);
        Config nc = st.config(Config.PayloadVariantCase.NETWORK);
        if (nc != null) sb.append(",\"wifi\":{\"on\":").append(nc.getNetwork().getWifiEnabled()).append(",\"ssid\":").append(q(nc.getNetwork().getWifiSsid())).append('}');
        ModuleConfig mc = st.moduleConfig(ModuleConfig.PayloadVariantCase.MQTT);
        if (mc != null) {
            var m = mc.getMqtt();
            sb.append(",\"mqtt\":{\"on\":").append(m.getEnabled()).append(",\"address\":").append(q(m.getAddress())).append(",\"user\":").append(q(m.getUsername()))
              .append(",\"encryption\":").append(m.getEncryptionEnabled()).append(",\"json\":").append(m.getJsonEnabled()).append(",\"tls\":").append(m.getTlsEnabled())
              .append(",\"root\":").append(q(m.getRoot())).append(",\"map\":").append(m.getMapReportingEnabled()).append(",\"proxy\":").append(m.getProxyToClientEnabled()).append('}');
        }
        sb.append(",\"channels\":").append(channelsJson(st));
        return sb.append('}').toString();
    }

    static String channelsJson(MeshState st) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Channel c : st.channels()) {
            if (!first) sb.append(',');
            first = false;
            var s = c.getSettings();
            sb.append("{\"index\":").append(c.getIndex()).append(",\"role\":").append(q(c.getRole().name())).append(",\"name\":").append(q(s.getName()))
              .append(",\"psk\":").append(q(Base64.getEncoder().encodeToString(s.getPsk().toByteArray())))
              .append(",\"up\":").append(s.getUplinkEnabled()).append(",\"down\":").append(s.getDownlinkEnabled()).append('}');
        }
        return sb.append(']').toString();
    }

    private static String enumList(Enum<?>[] vals) {
        StringBuilder sb = new StringBuilder("[");
        for (Enum<?> e : vals) if (!e.name().equals("UNRECOGNIZED")) { if (sb.length() > 1) sb.append(','); sb.append(q(e.name())); }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ saving
    static String save(MeshClient client, String what, Map<String, String> f, int myPort) throws Exception {
        if (!client.isConnected()) return "radio not connected";
        MeshState st = client.state();
        switch (what) {
            case "owner" -> {
                String l = f.getOrDefault("long", "").trim(), s = f.getOrDefault("short", "").trim();
                if (l.isEmpty() || s.isEmpty() || s.length() > 4) return "need a long name and a short name of up to 4 characters";
                client.setOwner(l, s);
                return "owner saved";
            }
            case "role" -> {
                Config dc = st.config(Config.PayloadVariantCase.DEVICE);
                if (dc == null) return "device settings not loaded yet";
                client.setConfig(Config.newBuilder().setDevice(dc.getDevice().toBuilder().setRole(Config.DeviceConfig.Role.valueOf(f.get("role")))).build());
                return "role saved – the radio restarts";
            }
            case "lora" -> {
                Config lc = st.config(Config.PayloadVariantCase.LORA);
                if (lc == null) return "LoRa settings not loaded yet";
                var b = lc.getLora().toBuilder()
                    .setRegion(Config.LoRaConfig.RegionCode.valueOf(f.get("region")))
                    .setModemPreset(Config.LoRaConfig.ModemPreset.valueOf(f.get("preset"))).setUsePreset(true)
                    .setHopLimit(Math.max(1, Math.min(7, Integer.parseInt(f.getOrDefault("hops", "3")))))
                    .setSx126XRxBoostedGain(Boolean.parseBoolean(f.get("rxBoost")))
                    .setConfigOkToMqtt(Boolean.parseBoolean(f.get("okToMqtt")));
                client.setConfig(Config.newBuilder().setLora(b).build());
                return "LoRa saved – the radio restarts (transmit power is set on the Station G2 tab of the desktop window)";
            }
            case "position" -> {
                double lat = Double.parseDouble(f.get("lat")), lon = Double.parseDouble(f.get("lon"));
                int alt = (int) Math.round(Double.parseDouble(f.getOrDefault("alt", "0")));
                if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return "latitude/longitude out of range";
                client.setFixedPosition(lat, lon, alt);
                return "fixed position set";
            }
            case "wifi" -> {
                Config nc = st.config(Config.PayloadVariantCase.NETWORK);
                if (nc == null) return "network settings not loaded yet";
                var b = nc.getNetwork().toBuilder().setWifiEnabled(Boolean.parseBoolean(f.get("on"))).setWifiSsid(f.getOrDefault("ssid", ""));
                String psk = f.getOrDefault("psk", "");
                if (!psk.isEmpty()) b.setWifiPsk(psk);
                client.setConfig(Config.newBuilder().setNetwork(b).build());
                return "WiFi saved – the radio restarts (if the SSID or password is wrong, it drops off the network: fix it over USB)";
            }
            case "channel" -> {
                int idx = Integer.parseInt(f.get("index"));
                if (idx < 0 || idx > 7) return "channel 0-7";
                byte[] psk = Base64.getDecoder().decode(f.getOrDefault("psk", "").trim());
                if (!(psk.length == 0 || psk.length == 1 || psk.length == 16 || psk.length == 32)) return "the key must be empty, 1, 16 or 32 bytes (Base64)";
                Channel.Role role = Channel.Role.valueOf(f.getOrDefault("role", "SECONDARY"));
                if (idx == 0 && role != Channel.Role.PRIMARY) return "channel 0 is always the primary";
                ChannelSettings.Builder s = ChannelSettings.newBuilder().setName(f.getOrDefault("name", "").trim()).setPsk(ByteString.copyFrom(psk))
                    .setUplinkEnabled(Boolean.parseBoolean(f.get("up"))).setDownlinkEnabled(Boolean.parseBoolean(f.get("down")));
                for (Channel c : st.channels()) if (c.getIndex() == idx) s.setModuleSettings(c.getSettings().getModuleSettings());   // keep position precision etc.
                client.setChannel(Channel.newBuilder().setIndex(idx).setRole(role).setSettings(s).build());
                return "channel " + idx + " saved";
            }
            case "copy-channels" -> {
                int from = Integer.parseInt(f.get("from"));
                if (from == myPort) return "that's this window";
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + from + "/api/peer/channels")).timeout(java.time.Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return "the other window didn't answer (port " + from + ")";
                List<String> done = new ArrayList<>();
                for (Object o : (List<?>) meshconsole.analysis.Json.parse(r.body())) {
                    Map<?, ?> c = (Map<?, ?>) o;
                    int idx = (int) meshconsole.analysis.Json.num(c.get("index"), -1);
                    String role = String.valueOf(c.get("role"));
                    if (idx <= 0 || role.equals("DISABLED")) continue;   // the primary stays as it is
                    Map<String, String> ch = new HashMap<>();
                    ch.put("index", String.valueOf(idx)); ch.put("role", role); ch.put("name", String.valueOf(c.get("name")));
                    ch.put("psk", String.valueOf(c.get("psk"))); ch.put("up", String.valueOf(c.get("up"))); ch.put("down", String.valueOf(c.get("down")));
                    save(client, "channel", ch, myPort);
                    done.add(idx + " " + c.get("name"));
                    Thread.sleep(400);
                }
                return done.isEmpty() ? "the other window has no secondary channels" : "copied channels: " + String.join(", ", done);
            }
            case "mqtt" -> {
                ModuleConfig mc = st.moduleConfig(ModuleConfig.PayloadVariantCase.MQTT);
                if (mc == null) return "MQTT settings not loaded yet";
                var b = mc.getMqtt().toBuilder().setEnabled(Boolean.parseBoolean(f.get("on"))).setAddress(f.getOrDefault("address", "mqtt.meshtastic.org"))
                    .setUsername(f.getOrDefault("user", "meshdev")).setEncryptionEnabled(Boolean.parseBoolean(f.get("encryption")))
                    .setJsonEnabled(Boolean.parseBoolean(f.get("json"))).setTlsEnabled(Boolean.parseBoolean(f.get("tls")))
                    .setRoot(f.getOrDefault("root", "msh/US")).setMapReportingEnabled(Boolean.parseBoolean(f.get("map")))
                    .setProxyToClientEnabled(Boolean.parseBoolean(f.get("proxy")));
                String pw = f.getOrDefault("password", "");
                if (!pw.isEmpty()) b.setPassword(pw);
                client.setModuleConfig(ModuleConfig.newBuilder().setMqtt(b).build());
                return "MQTT saved – the radio restarts";
            }
            default -> { return "unknown setting"; }
        }
    }

    /** Other Mesh Console windows on this computer (for copying channels). */
    static String otherWindows(int myPort) {
        StringBuilder sb = new StringBuilder("[");
        for (int p = 3500; p < 3510; p++) {
            if (p == myPort) continue;
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + "/api/peer/summary")).timeout(java.time.Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) continue;
                Map<?, ?> m = (Map<?, ?>) meshconsole.analysis.Json.parse(r.body());
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"port\":").append(p).append(",\"name\":").append(q(String.valueOf(m.get("name")))).append('}');
            } catch (Exception ignored) { }
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ peer feed (no password: this computer / dashboard Pi only)
    static boolean peerAllowed(HttpExchange ex, boolean loopbackOnly) {
        InetAddress a = ex.getRemoteAddress().getAddress();
        if (a.isLoopbackAddress()) return true;
        if (loopbackOnly) return false;
        for (String h : dashboardHosts()) {
            try { for (InetAddress x : InetAddress.getAllByName(h)) if (x.equals(a)) return true; } catch (Exception ignored) { }
        }
        return false;
    }

    /** Health and activity summary for the dashboard's both-houses page and daily digest. */
    static String summary(MeshClient client, meshconsole.bbs.BbsEngine bbs, MeshAlerts alerts) {
        MeshState st = client.state();
        NodeEntry me = st.myNode();
        long now = System.currentTimeMillis();
        List<NodeEntry> nodes = st.nodes();
        long hour = nodes.stream().filter(n -> n.lastHeardMillis() > now - 3600_000L).count();
        long day = nodes.stream().filter(n -> n.lastHeardMillis() > now - 86_400_000L).count();
        long direct = nodes.stream().filter(n -> n.hopsAway == 0 && n.lastHeardMillis() > now - 86_400_000L).count();
        List<ChatMessage> msgs = st.messages();
        long msgsDay = msgs.stream().filter(m -> m.time > now - 86_400_000L).count();
        StringBuilder sb = new StringBuilder("{\"name\":").append(q(me == null ? "" : me.displayName()))
            .append(",\"id\":").append(q(String.format("!%08x", st.myNodeNum()))).append(",\"connected\":").append(client.isConnected())
            .append(",\"version\":").append(q(meshconsole.Version.VERSION)).append(",\"folder\":").append(q(meshconsole.DataDir.get().getFileName().toString()))
            .append(",\"hw\":").append(q(me == null ? "" : me.hwModel)).append(",\"uptime\":").append(me == null ? -1 : me.uptimeSeconds)
            .append(",\"chUtil\":").append(me == null ? 0 : me.channelUtil).append(",\"airTx\":").append(me == null ? 0 : me.airUtilTx)
            .append(",\"nodes\":").append(nodes.size()).append(",\"heardHour\":").append(hour).append(",\"heardDay\":").append(day).append(",\"directDay\":").append(direct)
            .append(",\"messagesDay\":").append(msgsDay).append(",\"reconnectsDay\":").append(alerts == null ? 0 : alerts.reconnectsLastDay());
        if (me != null && me.hasPosition) sb.append(",\"lat\":").append(me.lat).append(",\"lon\":").append(me.lon);
        if (bbs != null) {
            var s = bbs.store();
            long callersDay = s.callers.values().stream().filter(c -> c.last > now - 86_400_000L).count();
            long newDay = s.callers.values().stream().filter(c -> c.first > now - 86_400_000L).count();
            sb.append(",\"bbs\":{\"enabled\":").append(s.enabled).append(",\"posts\":").append(s.posts.size()).append(",\"mail\":").append(s.mail.size())
              .append(",\"callers\":").append(s.callers.size()).append(",\"callersDay\":").append(callersDay).append(",\"newCallersDay\":").append(newDay)
              .append(",\"repliesSent\":").append(s.repliesSent).append(",\"repliesAcked\":").append(s.repliesAcked).append('}');
        }
        return sb.append('}').toString();
    }

    /** Recent public-channel messages (no DMs) for the both-houses page. */
    static String publicMessages(MeshState st) {
        List<ChatMessage> all = st.messages();
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (int i = all.size() - 1; i >= 0 && n < 80; i--) {
            ChatMessage m = all.get(i);
            if (!m.isBroadcast() || m.emoji) continue;
            if (n++ > 0) sb.append(',');
            sb.append("{\"time\":").append(m.time).append(",\"from\":").append(q(st.nodeName(m.from))).append(",\"channel\":").append(m.channel)
              .append(",\"text\":").append(q(m.text)).append(",\"out\":").append(m.outgoing).append('}');
        }
        return sb.append(']').toString();
    }

    static Path dataDir() { return meshconsole.DataDir.get(); }
}
