package meshconsole.ui;

import meshconsole.bbs.BbsEngine;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Mesh alerts to the phone (ntfy, optionally e-mail): watched nodes not heard for N hours (and when they're back), a new
 * node heard directly, someone using the BBS for the first time (or any check-in by a watched node), and the radio
 * itself dropping off for 10 minutes. Settings in mesh-alerts.properties in the data folder, editable in the web console.
 */
final class MeshAlerts {
    final Properties p = new Properties();
    private final MeshClient client;
    private final Supplier<BbsEngine> bbs;
    private final Path file, sessions;
    private final Set<Integer> knownDirect = ConcurrentHashMap.newKeySet(), knownCallers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> down = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> lastCheckin = new ConcurrentHashMap<>();
    private boolean primed = false, radioDownSent = false;
    private long radioLostSince = 0;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();

    MeshAlerts(MeshClient client, Supplier<BbsEngine> bbs, Path dataDir) {
        this.client = client; this.bbs = bbs;
        this.file = dataDir.resolve("mesh-alerts.properties"); this.sessions = dataDir.resolve("sessions.log");
        try (var r = Files.newBufferedReader(file)) { p.load(r); } catch (Exception ignored) { }
        Thread t = new Thread(() -> { while (true) { try { Thread.sleep(60_000); check(); } catch (InterruptedException e) { return; } catch (Exception ignored) { } } }, "mesh-alerts");
        t.setDaemon(true); t.start();
    }

    String get(String k, String d) { return p.getProperty(k, d); }

    void save(Map<String, String> f) throws java.io.IOException {
        for (String k : List.of("ntfy", "email", "watch", "newDirect", "newCallers", "radioDown")) if (f.containsKey(k)) p.setProperty(k, f.get(k).trim());
        try (var w = Files.newBufferedWriter(file)) { p.store(w, "Mesh Console mesh alerts"); }
    }

    String json() {
        return "{\"ntfy\":" + WebConsole.q(get("ntfy", "")) + ",\"email\":" + WebConsole.q(get("email", "")) + ",\"watch\":" + WebConsole.q(get("watch", ""))
            + ",\"newDirect\":" + get("newDirect", "false") + ",\"newCallers\":" + get("newCallers", "true") + ",\"radioDown\":" + get("radioDown", "true") + "}";
    }

    /** watch = "!a1b2c3d4=6, !12345678=24" (node, hours) */
    private Map<Integer, Integer> watched() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (String part : get("watch", "").split("[,;\\n]")) {
            String[] kv = part.trim().split("=");
            if (kv[0].isEmpty()) continue;
            try { m.put(WebConsole.nodeNum(kv[0]), kv.length > 1 ? Integer.parseInt(kv[1].trim()) : 6); } catch (Exception ignored) { }
        }
        return m;
    }

    void check() {
        MeshState st = client.state();
        long now = System.currentTimeMillis();
        String name = st.myNode() == null ? "Mesh Console" : st.myNode().displayName();
        // the radio itself
        if (!client.isConnected()) {
            if (radioLostSince == 0) radioLostSince = now;
            if (!radioDownSent && now - radioLostSince > 10 * 60_000L && Boolean.parseBoolean(get("radioDown", "true"))) {
                send(name + ": radio not connected", "Mesh Console has had no connection to the radio for 10 minutes.", "high"); radioDownSent = true;
            }
            return;
        }
        if (radioDownSent) send(name + ": radio connected again", "The radio is back.", "default");
        radioLostSince = 0; radioDownSent = false;
        // watched nodes
        for (var e : watched().entrySet()) {
            NodeEntry n = st.node(e.getKey());
            if (n == null) continue;
            long ageH = (now - n.lastHeardMillis()) / 3600_000L;
            if (n.lastHeardMillis() > 0 && ageH >= e.getValue()) {
                if (down.add(e.getKey())) send("Not heard: " + n.displayName(), n.displayName() + " hasn't been heard by " + name + " for " + ageH + " hours.", "default");
            } else if (down.remove(e.getKey())) send("Back: " + n.displayName(), n.displayName() + " is being heard again by " + name + ".", "default");
        }
        // new nodes heard directly
        for (NodeEntry n : st.nodes()) {
            if (n.hopsAway != 0 || n.num == st.myNodeNum() || n.lastHeardMillis() < now - 3600_000L) continue;
            if (knownDirect.add(n.num) && primed && Boolean.parseBoolean(get("newDirect", "false")))
                send("New direct neighbour", name + " now hears " + n.displayName() + " (" + String.format("!%08x", n.num) + ") directly" + (n.rssi != 0 ? ", " + n.rssi + " dBm" : "") + ".", "low");
        }
        // BBS callers
        BbsEngine b = bbs.get();
        if (b != null) {
            Set<Integer> w = watched().keySet();
            for (var c : new ArrayList<>(b.store().callers.values())) {
                boolean isNew = knownCallers.add(c.num);
                Long prev = lastCheckin.put(c.num, c.last);
                if (!primed) continue;
                if (isNew && Boolean.parseBoolean(get("newCallers", "true")))
                    send("New BBS caller", (c.name.isEmpty() ? String.format("!%08x", c.num) : c.name) + " used " + name + "'s BBS for the first time (" + c.lastCommand + ").", "default");
                else if (w.contains(c.num) && prev != null && c.last > prev)
                    send("BBS check-in", (c.name.isEmpty() ? String.format("!%08x", c.num) : c.name) + " checked in on " + name + " (" + c.lastCommand + ").", "low");
            }
        }
        primed = true;   // first pass only learns what's already there
    }

    int reconnectsLastDay() {
        long from = System.currentTimeMillis() - 86_400_000L;
        try {
            return (int) Files.readAllLines(sessions, StandardCharsets.UTF_8).stream().filter(l -> l.contains("connection lost")).filter(l -> {
                try { return OffsetDateTime.parse(l.substring(0, l.indexOf("  "))).toInstant().toEpochMilli() > from; } catch (Exception e) { return false; }
            }).count();
        } catch (Exception e) { return 0; }
    }

    void send(String title, String body, String priority) {
        String topic = get("ntfy", "");
        if (topic.isEmpty()) return;
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("https://ntfy.sh/" + topic)).timeout(java.time.Duration.ofSeconds(15))
                .header("Title", title).header("Priority", priority).header("Tags", "satellite_antenna").POST(HttpRequest.BodyPublishers.ofString(body));
            String email = get("email", "");
            if (!email.isEmpty() && !priority.equals("low")) r.header("Email", email);
            HTTP.send(r.build(), HttpResponse.BodyHandlers.discarding());
            client.state().emitLog("[alert] " + title + " – " + body);
        } catch (Exception e) { client.state().emitLog("[alert] couldn't send: " + e.getMessage()); }
    }
}
