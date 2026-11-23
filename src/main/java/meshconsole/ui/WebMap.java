package meshconsole.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * A read-only live map of every node this radio knows, served as a web page (default port 3500, the next free one for
 * a second window on the same computer, remembered per data folder), so it can be viewed from any PC or phone over the
 * home network or Tailscale instead of through screen sharing.
 */
public final class WebMap {
    private WebMap() { }
    private static volatile int port = -1;

    public static int port() { return port; }

    public static void start(MeshState state, Path dataDir, java.util.function.Consumer<String> log) {
        Path pf = dataDir.resolve("webmap-port.txt");
        int want = 3500;
        try { want = Integer.parseInt(Files.readString(pf).trim()); } catch (Exception ignored) { }
        for (int p = want; p < want + 10; p++) {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(p), 0);
                s.createContext("/api/nodes", ex -> send(ex, "application/json", json(state)));
                s.createContext("/", ex -> send(ex, "text/html; charset=utf-8", PAGE));
                s.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "webmap"); t.setDaemon(true); return t; }));
                s.start();
                port = p;
                try { Files.writeString(pf, Integer.toString(p)); } catch (IOException ignored) { }
                log.accept("Web map: http://" + host() + ":" + p + "  (open it from any computer or phone on the network / Tailscale)");
                return;
            } catch (IOException busy) { /* try the next port */ }
        }
        log.accept("Web map: no free port between " + want + " and " + (want + 9));
    }

    static String host() { try { return java.net.InetAddress.getLocalHost().getHostName().toLowerCase(); } catch (Exception e) { return "this-computer"; } }

    private static void send(HttpExchange ex, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static String json(MeshState st) {
        List<NodeEntry> nodes = st.nodes();
        int my = st.myNodeNum();
        NodeEntry me = st.myNode();
        StringBuilder sb = new StringBuilder("{\"now\":").append(System.currentTimeMillis()).append(",\"my\":").append(Integer.toUnsignedLong(my));
        sb.append(",\"myName\":").append(q(me == null ? "" : me.displayName()));
        sb.append(",\"nodes\":[");
        boolean first = true;
        for (NodeEntry n : nodes) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(q(String.format("!%08x", n.num)))
              .append(",\"num\":").append(Integer.toUnsignedLong(n.num))
              .append(",\"long\":").append(q(n.longName)).append(",\"short\":").append(q(n.shortName))
              .append(",\"hw\":").append(q(n.hwModel)).append(",\"role\":").append(q(n.role))
              .append(",\"heard\":").append(n.lastHeardMillis())
              .append(",\"hops\":").append(n.hopsAway)
              .append(",\"snr\":").append(String.format(Locale.ROOT, "%.2f", n.snr))
              .append(",\"rssi\":").append(n.rssi)
              .append(",\"batt\":").append(n.battery)
              .append(",\"mqtt\":").append(n.viaMqtt)
              .append(",\"pkts\":").append(n.packetsSeen)
              .append(",\"direct\":").append(n.directPackets)
              .append(",\"me\":").append(n.num == my)
              .append(",\"kind\":").append(q(n.kind(System.currentTimeMillis())))   // 1.29.4: marker icon
              .append(",\"locsrc\":").append(n.locationSource)
              .append(",\"town\":").append(q(n.town));
            if (n.hasPosition && !(n.lat == 0 && n.lon == 0))
                sb.append(",\"lat\":").append(String.format(Locale.ROOT, "%.6f", n.lat)).append(",\"lon\":").append(String.format(Locale.ROOT, "%.6f", n.lon)).append(",\"alt\":").append(n.altitude);
            if (n.temperature != null) sb.append(",\"temp\":").append(String.format(Locale.ROOT, "%.1f", n.temperature));
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    static final String PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mesh map</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<style>
:root { --bg:#111417; --panel:#1b2026; --line:#2c333b; --text:#e6e9ec; --dim:#8b949e; }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:14px/1.35 system-ui, -apple-system, "Segoe UI", sans-serif; display:flex; flex-direction:column; height:100vh; }
header { display:flex; flex-wrap:wrap; gap:10px; align-items:center; padding:8px 12px; background:var(--panel); border-bottom:1px solid var(--line); }
header h1 { font-size:16px; margin:0 6px 0 0; }
select, input { background:#0f1316; color:var(--text); border:1px solid var(--line); border-radius:6px; padding:5px 7px; font-size:14px; }
label { color:var(--dim); font-size:13px; }
#wrap { flex:1; display:flex; min-height:0; }
#map { flex:1; }
#side { width:340px; max-width:45vw; overflow:auto; border-left:1px solid var(--line); background:var(--panel); }
#side table { width:100%; border-collapse:collapse; font-size:13px; }
#side td { padding:5px 7px; border-bottom:1px solid var(--line); cursor:pointer; }
#side tr:hover td { background:#22303f; }
.dim { color:var(--dim); }
.dot { display:inline-block; width:9px; height:9px; border-radius:50%; margin-right:6px; }
@media (max-width:700px) { #wrap { flex-direction:column; } #side { width:auto; max-width:none; height:40vh; border-left:none; border-top:1px solid var(--line); } }
</style>
</head>
<body>
<header><h1 id="title">Mesh map</h1><span id="stats" class="dim"></span>
  <label>Heard within <select id="age"><option value="1">1 hour</option><option value="6">6 hours</option><option value="24" selected>24 hours</option><option value="168">7 days</option><option value="100000">ever</option></select></label>
  <label><input type="checkbox" id="mqtt"> include MQTT-only</label>
  <label><input type="checkbox" id="links" checked> lines to nodes heard directly</label>
  <input type="search" id="find" placeholder="Find node…" size="14">
</header>
<div id="wrap"><div id="map"></div><div id="side"><table><tbody id="list"></tbody></table></div></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script>
"use strict";
const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const map = L.map("map", { preferCanvas: true }).setView([38.9, -77.05], 11);
L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "&copy; OpenStreetMap contributors" }).addTo(map);
const layer = L.layerGroup().addTo(map);
let data = null, fitted = false, markers = {};
const ago = ms => { const s = Math.round(ms / 1000); return s < 90 ? s + " s" : s < 5400 ? Math.round(s / 60) + " min" : s < 172800 ? Math.round(s / 3600) + " h" : Math.round(s / 86400) + " d"; };
function color(n, now) {
  if (n.me) return "#ffd84d";
  const a = (now - n.heard) / 3600000;
  if (n.hops === 0) return "#3fb950";            // heard directly
  return a < 1 ? "#58a6ff" : a < 6 ? "#8fb8ff" : a < 24 ? "#6e7f99" : "#4a535d";
}
function popup(n, now) {
  return `<b>${esc(n.long || n.id)}</b> <span style="color:#888">${esc(n.short)} · ${esc(n.id)}</span><br>` +
    `${esc(n.hw)}${n.role ? " · " + esc(n.role) : ""}<br>` +
    `heard ${n.heard ? ago(now - n.heard) + " ago" : "never"}` + (n.hops >= 0 ? ` · ${n.hops === 0 ? "direct" : n.hops + " hop(s)"}` : "") +
    (n.rssi ? `<br>last ${n.rssi} dBm / ${n.snr} dB SNR` : "") +
    (n.batt >= 0 ? `<br>battery ${n.batt > 100 ? "powered" : n.batt + " %"}` : "") +
    (n.temp !== undefined ? ` · ${n.temp} °C` : "") + (n.alt ? `<br>altitude ${n.alt} m` : "") +
    (n.pkts ? `<br>${n.pkts} packets (${n.direct} direct)` : "") + (n.mqtt ? "<br><i>via MQTT</i>" : "");
}
function render() {
  if (!data) return;
  const now = data.now, maxAge = +$("age").value * 3600000, q = $("find").value.trim().toLowerCase();
  const me = data.nodes.find(n => n.me);
  const shown = data.nodes.filter(n => (n.me || (n.heard && now - n.heard <= maxAge)) && ($("mqtt").checked || !n.mqtt || n.me) &&
    (!q || (n.long + " " + n.short + " " + n.id).toLowerCase().includes(q)));
  layer.clearLayers(); markers = {};
  const pts = [];
  for (const n of shown) {
    if (n.lat === undefined) continue;
    if ($("links").checked && me && me.lat !== undefined && n.hops === 0 && !n.me)
      L.polyline([[me.lat, me.lon], [n.lat, n.lon]], { color: "#3fb950", weight: 1, opacity: 0.45 }).addTo(layer);
    const m = L.circleMarker([n.lat, n.lon], { radius: n.me ? 9 : n.hops === 0 ? 7 : 5, color: color(n, now), fillColor: color(n, now), fillOpacity: 0.8, weight: 1 })
      .bindPopup(popup(n, now)).bindTooltip(esc(n.short || n.long), { direction: "top" });
    m.addTo(layer); markers[n.id] = m; pts.push([n.lat, n.lon]);
  }
  if (!fitted && pts.length) { map.fitBounds(pts, { padding: [20, 20], maxZoom: 12 }); fitted = true; }
  const placed = shown.filter(n => n.lat !== undefined).length;
  $("stats").textContent = `${shown.length} nodes (${placed} on the map) · ${data.nodes.filter(n => n.heard && now - n.heard < 3600000).length} heard in the last hour · ${data.nodes.length} known`;
  $("list").innerHTML = shown.slice(0, 600).map(n => `<tr data-id="${esc(n.id)}"><td><span class="dot" style="background:${color(n, now)}"></span><b>${esc(n.short || "?")}</b> ${esc(n.long)}<br><span class="dim">${n.heard ? ago(now - n.heard) + " ago" : "never"}${n.hops >= 0 ? " · " + (n.hops === 0 ? "direct" : n.hops + " hops") : ""}${n.rssi ? " · " + n.rssi + " dBm" : ""}${n.lat === undefined ? " · no position" : ""}</span></td></tr>`).join("");
}
async function load() {
  try { data = await (await fetch("api/nodes")).json(); $("title").textContent = (data.myName || "Mesh") + " – map"; document.title = $("title").textContent; render(); }
  catch (e) { $("stats").textContent = "not answering"; }
}
for (const id of ["age", "mqtt", "links", "find"]) $(id).addEventListener("input", render);
$("list").addEventListener("click", e => { const tr = e.target.closest("tr[data-id]"); const m = tr && markers[tr.dataset.id]; if (m) { map.setView(m.getLatLng(), Math.max(map.getZoom(), 14)); m.openPopup(); } });
load(); setInterval(load, 30000);
</script>
</body>
</html>
""";
}
