package meshconsole.tools;

import com.sun.net.httpserver.HttpServer;
import meshconsole.mesh.ChatMessage;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Tiny built-in web server: /  dashboard, /api/status, /api/nodes, /api/messages, /report, /map. */
public class WebDashboard implements AutoCloseable {
    private final HttpServer server;
    private final MeshState state;

    public WebDashboard(MeshState state, int port, Supplier<String> reportHtml) throws IOException {
        this.state = state;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> send(ex, "text/html", page()));
        server.createContext("/api/status", ex -> send(ex, "application/json", statusJson()));
        server.createContext("/api/nodes", ex -> send(ex, "application/json", nodesJson()));
        server.createContext("/api/messages", ex -> send(ex, "application/json", messagesJson()));
        server.createContext("/report", ex -> send(ex, "text/html", reportHtml.get()));
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }

    private static void send(com.sun.net.httpserver.HttpExchange ex, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private static String q(String s) { return "\"" + (s == null ? "" : s).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }

    private String statusJson() {
        NodeEntry me = state.myNode();
        return String.format(Locale.ROOT, "{\"connected\":%b,\"node\":%s,\"id\":%s,\"hardware\":%s,\"firmware\":%s,\"nodes\":%d,\"battery\":%d,\"voltage\":%.2f,\"channelUtil\":%.1f,\"airUtilTx\":%.2f,\"noiseFloor\":%d,\"lora\":%s,\"time\":%d}",
                state.configComplete(), q(me == null ? "" : me.displayName()), q(me == null ? "" : me.idString()), q(me == null ? "" : me.hwModel), q(state.firmware()), state.nodes().size(),
                me == null ? -1 : me.battery, me == null ? 0f : me.voltage, me == null ? 0f : me.channelUtil, me == null ? 0f : me.airUtilTx,
                state.localStats() == null ? 0 : state.localStats().getNoiseFloor(), q(MeshState.loraSummary(state.lora())), System.currentTimeMillis());
    }

    private String nodesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (NodeEntry n : state.nodes()) {
            if (!first) sb.append(','); first = false;
            sb.append(String.format(Locale.ROOT, "{\"id\":%s,\"name\":%s,\"short\":%s,\"hw\":%s,\"lastHeard\":%d,\"hops\":%d,\"snr\":%.1f,\"rssi\":%d,\"battery\":%d,\"lat\":%s,\"lon\":%s,\"packets\":%d}",
                    q(n.idString()), q(n.displayName()), q(n.shortName), q(n.hwModel), n.lastHeardMillis(), n.hopsAway, n.snr, n.rssi, n.battery,
                    n.hasPosition ? String.format(Locale.ROOT, "%.6f", n.lat) : "null", n.hasPosition ? String.format(Locale.ROOT, "%.6f", n.lon) : "null", n.packetsSeen));
        }
        return sb.append(']').toString();
    }

    private String messagesJson() {
        List<ChatMessage> l = state.messages();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ChatMessage m : l.subList(Math.max(0, l.size() - 100), l.size())) {
            if (!first) sb.append(','); first = false;
            sb.append(String.format(Locale.ROOT, "{\"time\":%d,\"out\":%b,\"from\":%s,\"to\":%s,\"ch\":%d,\"text\":%s,\"status\":%s,\"rssi\":%d,\"snr\":%.1f}",
                    m.time, m.outgoing, q(state.nodeName(m.from)), q(state.nodeName(m.to)), m.channel, q(m.text), q(m.status.name()), m.rssi, m.snr));
        }
        return sb.append(']').toString();
    }

    private String page() {
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Mesh Console</title>"
             + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'><script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
             + "<style>body{font-family:system-ui,sans-serif;margin:0;background:#111;color:#eee}header{background:#1e3a5f;padding:10px 16px;font-weight:bold}main{padding:12px 16px}#map{height:320px;margin:8px 0;border-radius:6px}"
             + "table{border-collapse:collapse;width:100%;font-size:13px}td,th{padding:3px 6px;border-bottom:1px solid #333;text-align:left}th{color:#9cf}.g{color:#8f8}.y{color:#fd6}.r{color:#f88}small{color:#999}</style></head><body>"
             + "<header>Mesh Console <small id='ver'></small></header><main><div id='status'>loading…</div><div id='map'></div><h3>Nodes</h3><table id='nodes'></table><h3>Recent messages</h3><table id='msgs'></table><p><a href='/report' style='color:#9cf'>Full report</a></p></main>"
             + "<script>let map,markers=[];function ago(t){if(!t)return'never';let s=(Date.now()-t)/1000;return s<60?Math.round(s)+'s':s<3600?Math.round(s/60)+'m':s<86400?Math.round(s/3600)+'h':Math.round(s/86400)+'d'}"
             + "async function refresh(){let s=await(await fetch('/api/status')).json();document.getElementById('status').innerHTML=`<b>${s.node}</b> ${s.id} · ${s.hardware} · fw ${s.firmware} · ${s.nodes} nodes · util ${s.channelUtil.toFixed(1)}% · noise ${s.noiseFloor} dBm · ${s.lora}`;"
             + "let n=await(await fetch('/api/nodes')).json();let now=Date.now();document.getElementById('nodes').innerHTML='<tr><th>Node</th><th>Heard</th><th>Hops</th><th>SNR</th><th>RSSI</th><th>Batt</th></tr>'+n.map(x=>`<tr><td>${x.name}</td><td class='${now-x.lastHeard<900000?'g':now-x.lastHeard<7200000?'y':'r'}'>${ago(x.lastHeard)}</td><td>${x.hops<0?'?':x.hops}</td><td>${x.snr?x.snr.toFixed(1):''}</td><td>${x.rssi||''}</td><td>${x.battery<0?'':x.battery>100?'ext':x.battery+'%'}</td></tr>`).join('');"
             + "if(!map){map=L.map('map');L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'© OpenStreetMap'}).addTo(map);}markers.forEach(m=>map.removeLayer(m));markers=[];let pts=[];n.forEach(x=>{if(x.lat==null)return;let m=L.circleMarker([x.lat,x.lon],{radius:6,color:now-x.lastHeard<7200000?'#3c3':'#888'}).bindPopup(x.name+'<br>'+ago(x.lastHeard)+' ago').addTo(map);markers.push(m);pts.push([x.lat,x.lon])});if(pts.length&&!map._fitted){map.fitBounds(pts,{padding:[20,20]});map._fitted=true}"
             + "let m=await(await fetch('/api/messages')).json();document.getElementById('msgs').innerHTML='<tr><th>Time</th><th>From</th><th>To</th><th>Message</th><th>Status</th></tr>'+m.reverse().map(x=>`<tr><td>${new Date(x.time).toLocaleTimeString()}</td><td>${x.from}</td><td>${x.to}</td><td>${x.text}</td><td>${x.status}</td></tr>`).join('');}"
             + "refresh();setInterval(refresh,15000);</script></body></html>";
    }
}
