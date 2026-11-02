package meshconsole.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import meshconsole.bbs.BbsEngine;
import meshconsole.bbs.BbsStore;
import meshconsole.mesh.ChatMessage;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Mesh Console in a browser: messages (read and send, channels and DMs), a live node map, the node list with traceroute,
 * the BBS's posts and mail, and the radio's status. Port 3500 (the next free one for a second window on the same
 * computer, remembered per data folder). Everything except the page itself needs the password in web-password.txt
 * (made up on first start and shown in the log), because anyone who can open the page could otherwise send as the BBS.
 */
public final class WebConsole {
    private WebConsole() { }
    private static volatile int port = -1;
    public static int port() { return port; }

    public static void start(MeshClient client, Supplier<BbsEngine> bbs, Path dataDir, java.util.function.Consumer<String> log) {
        MeshState state = client.state();
        String password = password(dataDir, log);
        Path pf = dataDir.resolve("webmap-port.txt");
        int want = 3500;
        try { want = Integer.parseInt(Files.readString(pf).trim()); } catch (Exception ignored) { }
        for (int p = want; p < want + 10; p++) {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(p), 0);
                s.createContext("/", ex -> send(ex, 200, "text/html; charset=utf-8", PAGE));
                s.createContext("/api/", ex -> {
                    if (!authorised(ex, password)) { send(ex, 401, "application/json", "{\"error\":\"password\"}"); return; }
                    String path = ex.getRequestURI().getPath();
                    try {
                        switch (path) {
                            case "/api/nodes" -> send(ex, 200, "application/json", WebMap.json(state));
                            case "/api/messages" -> send(ex, 200, "application/json", messages(state));
                            case "/api/status" -> send(ex, 200, "application/json", status(client, bbs.get()));
                            case "/api/bbs" -> send(ex, 200, "application/json", bbsJson(state, bbs.get()));
                            case "/api/send" -> sendText(ex, client);
                            case "/api/traceroute" -> {
                                Map<String, String> f = form(ex);
                                client.traceroute(nodeNum(f.getOrDefault("to", "")));
                                send(ex, 200, "application/json", "{\"ok\":true}");
                            }
                            default -> send(ex, 404, "application/json", "{\"error\":\"not found\"}");
                        }
                    } catch (Exception e) {
                        send(ex, 500, "application/json", "{\"error\":" + q(String.valueOf(e.getMessage())) + "}");
                    }
                });
                s.setExecutor(Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "webconsole"); t.setDaemon(true); return t; }));
                s.start();
                port = p;
                try { Files.writeString(pf, Integer.toString(p)); } catch (IOException ignored) { }
                log.accept("Web console: http://" + WebMap.host() + ":" + p + "  – password in " + dataDir.resolve("web-password.txt"));
                return;
            } catch (IOException busy) { /* next port */ }
        }
        log.accept("Web console: no free port between " + want + " and " + (want + 9));
    }

    // ------------------------------------------------------------------ password
    private static String password(Path dataDir, java.util.function.Consumer<String> log) {
        Path f = dataDir.resolve("web-password.txt");
        try { String p = Files.readString(f).trim(); if (!p.isEmpty()) return p; } catch (IOException ignored) { }
        String[] words = {"radio", "lake", "mesh", "tower", "signal", "relay", "beacon", "antenna", "harbor", "summit", "valley", "comet"};
        SecureRandom r = new SecureRandom();
        String p = words[r.nextInt(words.length)] + "-" + words[r.nextInt(words.length)] + "-" + (100 + r.nextInt(900));
        try { Files.writeString(f, p + "\n"); } catch (IOException ignored) { }
        log.accept("Web console password (change it by editing web-password.txt): " + p);
        return p;
    }

    private static boolean authorised(HttpExchange ex, String password) {
        String given = Optional.ofNullable(ex.getRequestHeaders().getFirst("X-Pass")).orElse("");
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ data
    private static String nameOf(MeshState st, int num) { return st.nodeName(num); }

    static String messages(MeshState st) {
        List<ChatMessage> all = st.messages();
        List<ChatMessage> last = all.subList(Math.max(0, all.size() - 400), all.size());
        StringBuilder sb = new StringBuilder("{\"my\":").append(q(String.format("!%08x", st.myNodeNum()))).append(",\"channels\":[");
        boolean first = true;
        for (var c : st.channels()) {
            if (!first) sb.append(',');
            first = false;
            String name = c.getSettings().getName();
            if (name.isEmpty()) name = c.getIndex() == 0 ? "Primary" : "Channel " + c.getIndex();
            sb.append("{\"index\":").append(c.getIndex()).append(",\"name\":").append(q(name)).append('}');
        }
        sb.append("],\"messages\":[");
        first = true;
        for (ChatMessage m : last) {
            if (m.emoji) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"time\":").append(m.time)
              .append(",\"from\":").append(q(String.format("!%08x", m.from))).append(",\"fromName\":").append(q(nameOf(st, m.from)))
              .append(",\"to\":").append(q(m.isBroadcast() ? "^all" : String.format("!%08x", m.to))).append(",\"toName\":").append(q(m.isBroadcast() ? "" : nameOf(st, m.to)))
              .append(",\"channel\":").append(m.channel)
              .append(",\"text\":").append(q(m.text))
              .append(",\"out\":").append(m.outgoing)
              .append(",\"status\":").append(q(m.status == null ? "" : m.status.name()))
              .append(",\"rssi\":").append(m.rssi).append(",\"snr\":").append(m.snr)
              .append('}');
        }
        return sb.append("]}").toString();
    }

    static String status(MeshClient client, BbsEngine bbs) {
        MeshState st = client.state();
        NodeEntry me = st.myNode();
        List<NodeEntry> nodes = st.nodes();
        long now = System.currentTimeMillis();
        long hour = nodes.stream().filter(n -> n.lastHeardMillis() > now - 3600_000L).count();
        long day = nodes.stream().filter(n -> n.lastHeardMillis() > now - 86_400_000L).count();
        StringBuilder sb = new StringBuilder("{\"connected\":").append(client.isConnected())
            .append(",\"version\":").append(q(meshconsole.Version.VERSION))
            .append(",\"folder\":").append(q(meshconsole.DataDir.get().getFileName().toString()))
            .append(",\"name\":").append(q(me == null ? "" : me.displayName()))
            .append(",\"id\":").append(q(String.format("!%08x", st.myNodeNum())))
            .append(",\"hw\":").append(q(me == null ? "" : me.hwModel))
            .append(",\"chUtil\":").append(me == null ? 0 : me.channelUtil)
            .append(",\"airTx\":").append(me == null ? 0 : me.airUtilTx)
            .append(",\"uptime\":").append(me == null ? -1 : me.uptimeSeconds)
            .append(",\"nodes\":").append(nodes.size()).append(",\"heardHour\":").append(hour).append(",\"heardDay\":").append(day);
        if (bbs != null) {
            BbsStore s = bbs.store();
            sb.append(",\"bbs\":{\"enabled\":").append(s.enabled).append(",\"posts\":").append(s.posts.size()).append(",\"mail\":").append(s.mail.size())
              .append(",\"callers\":").append(s.callers.size()).append('}');
        }
        return sb.append('}').toString();
    }

    static String bbsJson(MeshState st, BbsEngine bbs) {
        if (bbs == null) return "{\"posts\":[],\"mail\":[]}";
        BbsStore s = bbs.store();
        StringBuilder sb = new StringBuilder("{\"posts\":[");
        List<BbsStore.Post> posts = new ArrayList<>(s.posts);
        Collections.reverse(posts);
        boolean first = true;
        for (BbsStore.Post p : posts.subList(0, Math.min(100, posts.size()))) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(p.id).append(",\"time\":").append(p.time).append(",\"author\":").append(q(p.authorName.isEmpty() ? nameOf(st, p.author) : p.authorName)).append(",\"text\":").append(q(p.text)).append('}');
        }
        sb.append("],\"mail\":[");
        List<BbsStore.Mail> mail = new ArrayList<>(s.mail);
        Collections.reverse(mail);
        first = true;
        for (BbsStore.Mail m : mail.subList(0, Math.min(100, mail.size()))) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"time\":").append(m.time).append(",\"from\":").append(q(m.fromName.isEmpty() ? nameOf(st, m.from) : m.fromName)).append(",\"to\":").append(q(nameOf(st, m.to)))
              .append(",\"delivered\":").append(m.delivered).append(",\"text\":").append(q(m.text)).append('}');
        }
        return sb.append("]}").toString();
    }

    private static void sendText(HttpExchange ex, MeshClient client) throws IOException {
        Map<String, String> f = form(ex);
        String text = f.getOrDefault("text", "").trim();
        if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 200) { send(ex, 400, "application/json", "{\"error\":\"message empty or over 200 bytes\"}"); return; }
        if (!client.isConnected()) { send(ex, 409, "application/json", "{\"error\":\"radio not connected\"}"); return; }
        String to = f.getOrDefault("to", "^all");
        int channel = Integer.parseInt(f.getOrDefault("channel", "0"));
        int dest = to.equals("^all") || to.isEmpty() ? ChatMessage.BROADCAST : nodeNum(to);
        client.sendText(text, dest, channel);
        send(ex, 200, "application/json", "{\"ok\":true}");
    }

    static int nodeNum(String id) {
        String h = id.trim().replaceFirst("^!", "");
        return (int) Long.parseLong(h, 16);
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            m.put(URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8), i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static final String PAGE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mesh Console</title>
<style>
:root { --bg:#111417; --panel:#1b2026; --line:#2c333b; --text:#e6e9ec; --dim:#8b949e; --me:#1f3a5f; --them:#22282f; }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--text); font:14px/1.4 system-ui, -apple-system, "Segoe UI", sans-serif; height:100vh; display:flex; flex-direction:column; }
header { display:flex; flex-wrap:wrap; gap:8px; align-items:center; padding:8px 12px; background:var(--panel); border-bottom:1px solid var(--line); }
header h1 { font-size:16px; margin:0 8px 0 0; }
.tabs button, button { background:#2a3139; color:var(--text); border:1px solid var(--line); border-radius:6px; padding:6px 11px; font-size:14px; cursor:pointer; }
.tabs button.on { background:#1f6feb; border-color:#1f6feb; }
input, select, textarea { background:#0f1316; color:var(--text); border:1px solid var(--line); border-radius:6px; padding:6px 8px; font-size:14px; font-family:inherit; }
main { flex:1; min-height:0; display:flex; flex-direction:column; }
.pane { flex:1; min-height:0; overflow:auto; padding:10px 12px; }
.pane[hidden] { display:none; }
#conn { width:10px; height:10px; border-radius:50%; background:#f85149; display:inline-block; }
#conn.ok { background:#3fb950; }
.dim { color:var(--dim); }
#msgs { flex:1; overflow:auto; padding:6px 2px; }
.msg { max-width:780px; margin:4px 0; padding:6px 9px; border-radius:8px; background:var(--them); }
.msg.out { background:var(--me); margin-left:auto; }
.msg .meta { font-size:11px; color:var(--dim); }
.msg .who { cursor:pointer; text-decoration:underline dotted; }
#pane-messages { display:flex; flex-direction:column; }
#compose { display:flex; gap:6px; flex-wrap:wrap; align-items:center; border-top:1px solid var(--line); padding-top:8px; }
#text { flex:1; min-width:200px; }
table { width:100%; border-collapse:collapse; font-size:13px; }
th, td { text-align:left; padding:5px 6px; border-bottom:1px solid var(--line); vertical-align:top; }
th { color:var(--dim); position:sticky; top:0; background:var(--bg); }
#map { height:100%; min-height:400px; border-radius:8px; }
.card { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:10px 12px; margin-bottom:10px; }
.grid { display:grid; grid-template-columns:repeat(auto-fit, minmax(160px, 1fr)); gap:8px; }
.grid div b { display:block; font-size:20px; }
#login { position:fixed; inset:0; background:rgba(0,0,0,.75); display:flex; align-items:center; justify-content:center; }
#login .card { width:min(360px, 92vw); }
.mini { padding:2px 8px; font-size:12px; }
</style>
</head>
<body>
<header><span id="conn"></span><h1 id="title">Mesh Console</h1><span id="sub" class="dim"></span>
  <span style="flex:1"></span>
  <span class="tabs"><button data-tab="messages" class="on">Messages</button><button data-tab="map">Map</button><button data-tab="nodes">Nodes</button><button data-tab="bbs">BBS</button><button data-tab="status">Status</button></span>
</header>
<main>
  <div class="pane" id="pane-messages">
    <div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center"><span class="dim">Show</span><select id="view"></select><span id="mcount" class="dim"></span></div>
    <div id="msgs"></div>
    <div id="compose"><select id="target"></select><input id="text" maxlength="200" placeholder="Message (200 bytes max)"><button id="sendBtn">Send</button><span id="sendNote" class="dim"></span></div>
  </div>
  <div class="pane" id="pane-map" hidden><div id="map"></div></div>
  <div class="pane" id="pane-nodes" hidden>
    <input type="search" id="nfind" placeholder="Find node by name or ID" style="width:min(360px,100%);margin-bottom:8px">
    <table><thead><tr><th>Node</th><th>Heard</th><th>Hops</th><th>Signal</th><th>Battery</th><th></th></tr></thead><tbody id="nlist"></tbody></table>
  </div>
  <div class="pane" id="pane-bbs" hidden><div class="card"><b>Posts</b><table><tbody id="posts"></tbody></table></div><div class="card"><b>Mail</b><table><tbody id="mail"></tbody></table></div></div>
  <div class="pane" id="pane-status" hidden><div id="statusBox"></div></div>
</main>
<div id="login" hidden><div class="card"><b>Mesh Console</b><p class="dim">Enter the web console password. It's in <code>web-password.txt</code> in this radio's data folder (and in Mesh Console's log at start-up).</p>
  <input type="password" id="pw" style="width:100%" placeholder="password"><p><button id="pwGo">Open</button> <span id="pwNote" class="dim"></span></p></div></div>
<script>
"use strict";
const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const KEY = "mc.pass." + location.port;
let pass = localStorage.getItem(KEY) || "", M = null, N = null, map = null, layer = null, fitted = false;
const ago = ms => { const s = Math.round(ms / 1000); return s < 90 ? s + " s" : s < 5400 ? Math.round(s / 60) + " min" : s < 172800 ? Math.round(s / 3600) + " h" : Math.round(s / 86400) + " d"; };
const when = t => new Date(t).toLocaleString([], {month:"short", day:"numeric", hour:"2-digit", minute:"2-digit"});
async function api(path, body) {
  const r = await fetch(path, body ? { method: "POST", headers: { "X-Pass": pass, "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(body) } : { headers: { "X-Pass": pass } });
  if (r.status === 401) { $("login").hidden = false; throw new Error("password"); }
  return r.json();
}
$("pwGo").onclick = async () => { pass = $("pw").value.trim(); localStorage.setItem(KEY, pass); try { await api("api/status"); $("login").hidden = true; refresh(); } catch (e) { $("pwNote").textContent = "Wrong password."; } };
$("pw").onkeydown = e => { if (e.key === "Enter") $("pwGo").click(); };

// ---------------- messages
function fillSelectors() {
  const v = $("view").value, t = $("target").value;
  if (!M.channels.length) M.channels = [{ index: 0, name: "Primary" }];   // radio not connected yet: channel list unknown
  const chans = M.channels.map(c => `<option value="ch${c.index}">#${c.index} ${esc(c.name)}</option>`).join("");
  $("view").innerHTML = `<option value="all">All messages</option>${chans}<option value="dm">Direct messages</option>`;
  const dmPeers = [...new Map(M.messages.filter(m => m.to !== "^all").map(m => { const peer = m.out ? m.to : m.from; return [peer, m.out ? m.toName : m.fromName]; })).entries()];
  $("target").innerHTML = M.channels.map(c => `<option value="ch${c.index}">To #${c.index} ${esc(c.name)}</option>`).join("") + dmPeers.map(([id, name]) => `<option value="dm${id}">DM ${esc(name || id)}</option>`).join("");
  if (v) $("view").value = v; if (t && [...$("target").options].some(o => o.value === t)) $("target").value = t;
}
function renderMessages() {
  if (!M) return;
  const v = $("view").value || "all";
  const list = M.messages.filter(m => v === "all" || (v === "dm" ? m.to !== "^all" : (m.to === "^all" && "ch" + m.channel === v)));
  const box = $("msgs"), atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  box.innerHTML = list.map(m => {
    const where = m.to === "^all" ? "#" + m.channel : (m.out ? "DM to " + esc(m.toName || m.to) : "DM");
    return `<div class="msg ${m.out ? "out" : ""}"><div class="meta">${m.out ? "me" : `<span class="who" data-dm="${esc(m.from)}" data-name="${esc(m.fromName)}">${esc(m.fromName)}</span>`} · ${where} · ${when(m.time)}${m.out && m.status ? " · " + esc(m.status.toLowerCase()) : ""}${!m.out && m.rssi ? " · " + m.rssi + " dBm" : ""}</div>${esc(m.text)}</div>`;
  }).join("") || '<p class="dim">No messages.</p>';
  $("mcount").textContent = list.length + " message(s)";
  if (atBottom) box.scrollTop = box.scrollHeight;
}
$("view").onchange = renderMessages;
$("msgs").onclick = e => {
  const w = e.target.closest("[data-dm]"); if (!w) return;
  const val = "dm" + w.dataset.dm;
  if (![...$("target").options].some(o => o.value === val)) $("target").insertAdjacentHTML("beforeend", `<option value="${val}">DM ${esc(w.dataset.name)}</option>`);
  $("target").value = val; $("text").focus();
};
$("sendBtn").onclick = async () => {
  const t = $("target").value, text = $("text").value.trim(); if (!text) return;
  const body = t.startsWith("ch") ? { text, to: "^all", channel: t.slice(2) } : { text, to: t.slice(2), channel: 0 };
  try { const r = await api("api/send", body); if (r.ok) { $("text").value = ""; $("sendNote").textContent = "sent"; setTimeout(load, 1500); } else $("sendNote").textContent = r.error || "failed"; }
  catch (e) { $("sendNote").textContent = e.message; }
};
$("text").onkeydown = e => { if (e.key === "Enter") $("sendBtn").click(); };

// ---------------- map and nodes
function loadLeaflet(cb) {
  if (window.L) { cb(); return; }
  const css = document.createElement("link"); css.rel = "stylesheet"; css.href = "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css"; document.head.appendChild(css);
  const js = document.createElement("script"); js.src = "https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"; js.onload = cb; document.head.appendChild(js);
}
function nodeColor(n, now) { if (n.me) return "#ffd84d"; if (n.hops === 0) return "#3fb950"; const a = (now - n.heard) / 3600000; return a < 1 ? "#58a6ff" : a < 6 ? "#8fb8ff" : a < 24 ? "#6e7f99" : "#4a535d"; }
function renderMap() {
  if (!N || !window.L) return;
  if (!map) { map = L.map("map", { preferCanvas: true }).setView([38.9, -77.05], 11); L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "&copy; OpenStreetMap contributors" }).addTo(map); layer = L.layerGroup().addTo(map); }
  map.invalidateSize(); layer.clearLayers();
  const now = N.now, me = N.nodes.find(n => n.me), pts = [];
  for (const n of N.nodes) {
    if (n.lat === undefined || (!n.me && (!n.heard || now - n.heard > 86400000 || n.mqtt))) continue;
    if (me && me.lat !== undefined && n.hops === 0 && !n.me) L.polyline([[me.lat, me.lon], [n.lat, n.lon]], { color: "#3fb950", weight: 1, opacity: 0.45 }).addTo(layer);
    L.circleMarker([n.lat, n.lon], { radius: n.me ? 9 : n.hops === 0 ? 7 : 5, color: nodeColor(n, now), fillColor: nodeColor(n, now), fillOpacity: 0.8, weight: 1 })
      .bindPopup(`<b>${esc(n.long || n.id)}</b> ${esc(n.short)} · ${esc(n.id)}<br>${esc(n.hw)}<br>heard ${ago(now - n.heard)} ago${n.hops >= 0 ? " · " + (n.hops === 0 ? "direct" : n.hops + " hops") : ""}${n.rssi ? "<br>" + n.rssi + " dBm / " + n.snr + " dB" : ""}`)
      .addTo(layer);
    pts.push([n.lat, n.lon]);
  }
  if (!fitted && pts.length) { map.fitBounds(pts, { padding: [20, 20], maxZoom: 12 }); fitted = true; }
}
function renderNodes() {
  if (!N) return;
  const q = $("nfind").value.trim().toLowerCase(), now = N.now;
  const list = N.nodes.filter(n => n.heard && (!q || (n.long + " " + n.short + " " + n.id).toLowerCase().includes(q))).slice(0, 400);
  $("nlist").innerHTML = list.map(n => `<tr><td><b>${esc(n.short || "?")}</b> ${esc(n.long)} <span class="dim">${esc(n.id)}${n.mqtt ? " · MQTT" : ""}</span></td><td>${ago(now - n.heard)}</td><td>${n.hops < 0 ? "" : n.hops === 0 ? "direct" : n.hops}</td><td>${n.rssi ? n.rssi + " / " + n.snr : ""}</td><td>${n.batt < 0 ? "" : n.batt > 100 ? "powered" : n.batt + "%"}</td>
    <td>${n.me ? "" : `<button class="mini" data-dmn="${esc(n.id)}" data-name="${esc(n.long || n.short)}">DM</button> <button class="mini" data-tr="${esc(n.id)}">Trace</button>`}</td></tr>`).join("");
}
$("nfind").oninput = renderNodes;
$("nlist").onclick = async e => {
  const d = e.target.closest("[data-dmn]"), t = e.target.closest("[data-tr]");
  if (d) { const val = "dm" + d.dataset.dmn; if (![...$("target").options].some(o => o.value === val)) $("target").insertAdjacentHTML("beforeend", `<option value="${val}">DM ${esc(d.dataset.name)}</option>`); $("target").value = val; show("messages"); $("text").focus(); }
  if (t) { await api("api/traceroute", { to: t.dataset.tr }); t.textContent = "sent – see the radio's log"; }
};

// ---------------- BBS and status
async function renderBbs() {
  const b = await api("api/bbs");
  $("posts").innerHTML = b.posts.map(p => `<tr><td class="dim">${when(p.time)}</td><td><b>${esc(p.author)}</b></td><td>${esc(p.text)}</td></tr>`).join("") || '<tr><td class="dim">No posts.</td></tr>';
  $("mail").innerHTML = b.mail.map(m => `<tr><td class="dim">${when(m.time)}</td><td>${esc(m.from)} → ${esc(m.to)}</td><td>${esc(m.text)}</td><td class="dim">${m.delivered ? "delivered" : "waiting"}</td></tr>`).join("") || '<tr><td class="dim">No mail.</td></tr>';
}
function renderStatus(s) {
  $("statusBox").innerHTML = `<div class="card"><b>${esc(s.name)}</b> <span class="dim">${esc(s.id)} · ${esc(s.hw)} · data folder ${esc(s.folder)} · Mesh Console ${esc(s.version)}</span></div>
   <div class="grid">
    <div class="card">Radio<b>${s.connected ? "connected" : "NOT connected"}</b></div>
    <div class="card">Channel use<b>${(+s.chUtil).toFixed(1)} %</b><span class="dim">air-time TX ${(+s.airTx).toFixed(2)} %</span></div>
    <div class="card">Nodes heard<b>${s.heardHour}</b><span class="dim">last hour · ${s.heardDay} today · ${s.nodes} known</span></div>
    <div class="card">Radio uptime<b>${s.uptime >= 0 ? Math.floor(s.uptime / 3600) + " h " + Math.floor(s.uptime % 3600 / 60) + " m" : "?"}</b></div>
    ${s.bbs ? `<div class="card">BBS<b>${s.bbs.enabled ? "ON" : "off"}</b><span class="dim">${s.bbs.posts} posts · ${s.bbs.mail} mail · ${s.bbs.callers} callers</span></div>` : ""}
   </div>`;
}

// ---------------- plumbing
let current = "messages";
function show(t) {
  current = t;
  document.querySelectorAll(".tabs button").forEach(b => b.classList.toggle("on", b.dataset.tab === t));
  for (const p of ["messages", "map", "nodes", "bbs", "status"]) $("pane-" + p).hidden = p !== t;
  if (t === "map") loadLeaflet(renderMap);
  if (t === "bbs") renderBbs();
  if (t === "nodes") renderNodes();
}
document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => show(b.dataset.tab));
async function load() {
  try {
    const s = await api("api/status");
    $("conn").classList.toggle("ok", s.connected); $("title").textContent = s.name || "Mesh Console"; document.title = $("title").textContent;
    $("sub").textContent = s.heardHour + " nodes heard in the last hour · channel " + (+s.chUtil).toFixed(1) + " %";
    renderStatus(s);
    M = await api("api/messages"); fillSelectors(); renderMessages();
    N = await api("api/nodes");
    if (current === "map") renderMap(); if (current === "nodes") renderNodes();
  } catch (e) { if (e.message !== "password") $("sub").textContent = "not answering"; }
}
function refresh() { load(); }
if (!pass) $("login").hidden = false; else refresh();
setInterval(() => { if ($("login").hidden) load(); }, 10000);
</script>
</body>
</html>
""";
}
