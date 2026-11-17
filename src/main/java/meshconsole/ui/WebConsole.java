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
    private static volatile String updateResult = "";
    private static final Map<String, Long> exportTokens = new java.util.concurrent.ConcurrentHashMap<>();   // one-use download links
    private static final Map<String, Long> sessions = new java.util.concurrent.ConcurrentHashMap<>();       // live-update tokens (24 h)
    public static int port() { return port; }

    public static void start(MeshClient client, Supplier<BbsEngine> bbs, Path dataDir, java.util.function.Consumer<String> log) {
        MeshState state = client.state();
        String password = password(dataDir, log);
        WebSettings.dataDir(dataDir);
        MeshAlerts alerts = new MeshAlerts(client, bbs, dataDir);
        DiscordBridge discord = new DiscordBridge(client, dataDir);
        meshconsole.mqtt.HeardBy heardBy = meshconsole.mqtt.HeardBy.forDir(client, dataDir);
        meshconsole.meshcore.McService.start(dataDir, log);   // MeshCore radio, if meshcore.properties names one
        Path pf = dataDir.resolve("webmap-port.txt");
        int want = 3500;
        try { want = Integer.parseInt(Files.readString(pf).trim()); } catch (Exception ignored) { }
        for (int p = want; p < want + 10; p++) {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(p), 0);
                final int myPort = p;
                s.createContext("/", ex -> send(ex, 200, "text/html; charset=utf-8", PAGE));
                s.createContext("/chat", ex -> send(ex, 200, "text/html; charset=utf-8", ChatPage.HTML));
                s.createContext("/meshcore", ex -> send(ex, 200, "text/html; charset=utf-8", MeshCorePage.HTML));
                s.createContext("/api/meshcore/", ex -> {
                    if (!authorised(ex, password)) { send(ex, 401, "application/json", "{\"error\":\"password\"}"); return; }
                    String path = ex.getRequestURI().getPath(), qs = Optional.ofNullable(ex.getRequestURI().getQuery()).orElse("");
                    try {
                        switch (path) {
                            case "/api/meshcore/state" -> send(ex, 200, "application/json", meshconsole.meshcore.McWeb.state());
                            case "/api/meshcore/messages" -> send(ex, 200, "application/json", meshconsole.meshcore.McWeb.messages(qs.startsWith("since=") ? Long.parseLong(qs.substring(6)) : 0));
                            case "/api/meshcore/signal" -> send(ex, 200, "application/json", meshconsole.meshcore.McWeb.signal());
                            case "/api/meshcore/send" -> send(ex, 200, "application/json", "{\"result\":" + q(meshconsole.meshcore.McWeb.send(form(ex))) + "}");
                            case "/api/meshcore/advert" -> {
                                var c = meshconsole.meshcore.McService.client();
                                String r = c == null || !c.isConnected() ? "not connected" : c.advert(!"0".equals(form(ex).get("flood")));
                                send(ex, 200, "application/json", "{\"result\":" + q(r) + "}");
                            }
                            case "/api/meshcore/position" -> {
                                var c = meshconsole.meshcore.McService.client(); var f = form(ex); String r;
                                if (c == null || !c.isConnected()) r = "not connected";
                                else { try { r = c.setPosition(Double.parseDouble(f.getOrDefault("lat", "x").trim()), Double.parseDouble(f.getOrDefault("lon", "x").trim()), !"0".equals(f.get("share"))); }
                                       catch (NumberFormatException e) { r = "latitude and longitude must be numbers, e.g. 38.8895 and -77.0353"; } }
                                send(ex, 200, "application/json", "{\"result\":" + q(r) + "}");
                            }
                            case "/api/meshcore/config" -> send(ex, 200, "application/json", "{\"connect\":" + q(meshconsole.meshcore.McService.spec()) + "}");
                            case "/api/meshcore/config/save" -> send(ex, 200, "application/json", "{\"result\":" + q(meshconsole.meshcore.McService.configure(form(ex).get("connect"))) + "}");
                            default -> send(ex, 404, "application/json", "{\"error\":\"unknown\"}");
                        }
                    } catch (Exception e) { send(ex, 500, "application/json", "{\"error\":" + q(String.valueOf(e.getMessage())) + "}"); }
                });
                s.createContext("/api/peer/", ex -> {   // read-only feed: this computer and the dashboard's Pi, no password
                    String path = ex.getRequestURI().getPath();
                    boolean keys = path.equals("/api/peer/channels");
                    if (!WebSettings.peerAllowed(ex, keys)) { send(ex, 403, "application/json", "{\"error\":\"not from here\"}"); return; }
                    switch (path) {
                        case "/api/peer/summary" -> send(ex, 200, "application/json", WebSettings.summary(client, bbs.get(), alerts));
                        case "/api/peer/nodes" -> send(ex, 200, "application/json", WebMap.json(state));
                        case "/api/peer/messages" -> send(ex, 200, "application/json", WebSettings.publicMessages(state));
                        case "/api/peer/channels" -> send(ex, 200, "application/json", WebSettings.channelsJson(state));
                        default -> send(ex, 404, "application/json", "{}");
                    }
                });
                s.createContext("/api/", ex -> {
                    if (ex.getRequestURI().getPath().equals("/api/export")) {   // a plain browser download: one-use token instead of the password header
                        String q = Optional.ofNullable(ex.getRequestURI().getQuery()).orElse("");
                        String tok = q.startsWith("token=") ? q.substring(6) : "";
                        Long exp = exportTokens.remove(tok);
                        if (exp != null && exp > System.currentTimeMillis()) { try { exportZip(ex, dataDir); } catch (Exception e) { ex.close(); } return; }
                    }
                    if (!authorised(ex, password)) { send(ex, 401, "application/json", "{\"error\":\"password\"}"); return; }
                    String path = ex.getRequestURI().getPath();
                    try {
                        switch (path) {
                            case "/api/nodes" -> send(ex, 200, "application/json", WebMap.json(state));
                            case "/api/messages" -> send(ex, 200, "application/json", messages(state));
                            case "/api/status" -> send(ex, 200, "application/json", status(client, bbs.get()));
                            case "/api/bbs" -> send(ex, 200, "application/json", bbsJson(state, bbs.get()));
                            case "/api/heardby" -> send(ex, 200, "application/json", heardBy.json());
                            case "/api/send" -> sendText(ex, client);
                            case "/api/export" -> exportZip(ex, dataDir);
                            case "/api/export-token" -> {
                                String tok = Long.toHexString(new SecureRandom().nextLong()) + Long.toHexString(System.nanoTime());
                                exportTokens.put(tok, System.currentTimeMillis() + 60_000L);
                                send(ex, 200, "application/json", "{\"token\":" + q(tok) + "}");
                            }
                            case "/api/signal" -> send(ex, 200, "application/json", signal(state, dataDir, ex.getRequestURI().getRawQuery()));
                            case "/api/update-check" -> {
                                if (meshconsole.tools.AppUpdater.source().isBlank()) { send(ex, 200, "application/json", "{\"result\":\"No update source set – Help → Updates… in the window first.\"}"); break; }
                                updateResult = "checking…";
                                Thread t = new Thread(() -> {
                                    try {
                                        String res = meshconsole.tools.AppUpdater.checkAndApply(state::emitLog, () -> { client.disconnect("update"); client.saveDbQuietly(); }, meshconsole.Main.ARGS, true);
                                        updateResult = res; state.emitLog("[update] " + res);
                                    } catch (Exception e) { updateResult = "update check failed: " + e.getMessage(); }
                                }, "web-update");
                                t.setDaemon(true); t.start();
                                send(ex, 200, "application/json", "{\"result\":\"Checking – if there's a newer version it installs it and this window restarts in about 10 seconds.\"}");
                            }
                            case "/api/session" -> {
                                String tok = Long.toHexString(new SecureRandom().nextLong()) + Long.toHexString(new SecureRandom().nextLong());
                                sessions.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis());
                                sessions.put(tok, System.currentTimeMillis() + 24 * 3600_000L);
                                send(ex, 200, "application/json", "{\"session\":" + q(tok) + "}");
                            }
                            case "/api/events" -> { liveEvents(ex, client); return; }
                            case "/api/retry" -> {
                                long id = Long.parseLong(form(ex).getOrDefault("id", "0"));
                                ChatMessage old = null;
                                for (ChatMessage m : state.messages()) if (m.outgoing && Integer.toUnsignedLong(m.packetId) == id) old = m;
                                if (old == null) { send(ex, 404, "application/json", "{\"error\":\"message not found\"}"); break; }
                                if (!client.isConnected()) { send(ex, 409, "application/json", "{\"error\":\"radio not connected\"}"); break; }
                                client.sendText(old.text, old.to, old.channel, old.replyId, old.emoji);
                                send(ex, 200, "application/json", "{\"ok\":true}");
                            }
                            case "/api/settings" -> send(ex, 200, "application/json",
                                    "{\"radio\":" + WebSettings.json(client) + ",\"alerts\":" + alerts.json() + ",\"discord\":" + discord.json() + ",\"bbsExtras\":" + (bbs.get() == null ? "null" : bbs.get().community().json()) + ",\"others\":" + WebSettings.otherWindows(myPort) + "}");
                            case "/api/settings/save" -> {
                                Map<String, String> f = form(ex);
                                send(ex, 200, "application/json", "{\"result\":" + q(WebSettings.save(client, f.getOrDefault("what", ""), f, myPort)) + "}");
                            }
                            case "/api/settings/alerts" -> { alerts.save(form(ex)); send(ex, 200, "application/json", "{\"result\":\"alert settings saved\"}"); }
                            case "/api/settings/bbs-extras" -> {
                                BbsEngine b = bbs.get();
                                if (b == null) { send(ex, 200, "application/json", "{\"result\":\"BBS not running\"}"); break; }
                                b.community().save(form(ex)); send(ex, 200, "application/json", "{\"result\":\"BBS extras saved\"}");
                            }
                            case "/api/settings/heardby" -> { heardBy.save(form(ex)); send(ex, 200, "application/json", "{\"result\":\"Heard-by settings saved – connecting…\"}"); }
                            case "/api/settings/discord" -> { discord.save(form(ex)); send(ex, 200, "application/json", "{\"result\":\"Discord bridge settings saved\"}"); }
                            case "/api/settings/discord-test" -> {
                                discord.fromMesh(testMessage());
                                send(ex, 200, "application/json", "{\"result\":\"test message queued for Discord – check the channel\"}");
                            }
                            case "/api/settings/test-alert" -> { alerts.send("Mesh Console test", "If you see this, mesh alerts work.", "default"); send(ex, 200, "application/json", "{\"result\":\"test sent\"}"); }
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
                s.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "webconsole"); t.setDaemon(true); return t; }));
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
        String qs = Optional.ofNullable(ex.getRequestURI().getQuery()).orElse("");
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("(?:^|&)session=([0-9a-f]+)").matcher(qs);
        if (tm.find()) { Long exp = sessions.get(tm.group(1)); if (exp != null && exp > System.currentTimeMillis()) return true; }
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
            if (!first) sb.append(',');
            first = false;
            sb.append(msgJson(st, m));
        }
        return sb.append("]}").toString();
    }

    static String msgJson(MeshState st, ChatMessage m) {
        NodeEntry fn = st.node(m.from);
        return "{\"time\":" + m.time + ",\"id\":" + Integer.toUnsignedLong(m.packetId)
            + ",\"from\":" + q(String.format("!%08x", m.from)) + ",\"fromName\":" + q(nameOf(st, m.from)) + ",\"fromShort\":" + q(fn == null ? "" : fn.shortName)
            + ",\"to\":" + q(m.isBroadcast() ? "^all" : String.format("!%08x", m.to)) + ",\"toName\":" + q(m.isBroadcast() ? "" : nameOf(st, m.to))
            + ",\"channel\":" + m.channel + ",\"text\":" + q(m.text) + ",\"out\":" + m.outgoing
            + ",\"status\":" + q(m.status == null ? "" : m.status.name()) + ",\"detail\":" + q(m.statusDetail == null ? "" : m.statusDetail)
            + ",\"rssi\":" + m.rssi + ",\"snr\":" + m.snr + ",\"hops\":" + m.hops + ",\"attempt\":" + m.attempt
            + ",\"replyId\":" + Integer.toUnsignedLong(m.replyId) + ",\"emoji\":" + m.emoji + ",\"ackTime\":" + m.ackTime + "}";
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
            .append(",\"updateSource\":").append(q(meshconsole.tools.AppUpdater.source()))
            .append(",\"updateAuto\":").append(meshconsole.tools.AppUpdater.enabled())
            .append(",\"updateTime\":").append(q(meshconsole.tools.AppUpdater.time()))
            .append(",\"updateResult\":").append(q(updateResult))
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
        sb.append("],\"advertReport\":").append(q(bbs.community() == null ? "" : bbs.community().advertReport(14)))
          .append(",\"community\":").append(bbs.community() == null ? "{}" : bbs.community().json());
        return sb.append("}").toString();
    }

    private static void sendText(HttpExchange ex, MeshClient client) throws IOException {
        Map<String, String> f = form(ex);
        String text = f.getOrDefault("text", "").trim();
        if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 200) { send(ex, 400, "application/json", "{\"error\":\"message empty or over 200 bytes\"}"); return; }
        if (!client.isConnected()) { send(ex, 409, "application/json", "{\"error\":\"radio not connected\"}"); return; }
        String to = f.getOrDefault("to", "^all");
        int channel = Integer.parseInt(f.getOrDefault("channel", "0"));
        int dest = to.equals("^all") || to.isEmpty() ? ChatMessage.BROADCAST : nodeNum(to);
        int replyTo = (int) Long.parseLong(f.getOrDefault("replyTo", "0"));
        boolean emoji = Boolean.parseBoolean(f.getOrDefault("emoji", "false"));
        ChatMessage m = client.sendText(text, dest, channel, replyTo, emoji);
        send(ex, 200, "application/json", "{\"ok\":true,\"id\":" + (m == null ? 0 : Integer.toUnsignedLong(m.packetId)) + "}");
    }

    /** The data folder as one zip (like Mesh Console's own export), minus the web password. */
    private static void exportZip(HttpExchange ex, Path dir) throws IOException {
        String name = "meshconsole-" + dir.getFileName() + "-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
        ex.getResponseHeaders().set("Content-Type", "application/zip");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        ex.sendResponseHeaders(200, 0);
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(ex.getResponseBody());
             var files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(f)) continue;
                String rel = dir.relativize(f).toString().replace('\\', '/');
                if (rel.equals("web-password.txt") || rel.endsWith(".lock") || rel.contains("@eaDir")) continue;
                z.putNextEntry(new java.util.zip.ZipEntry(rel));
                try { Files.copy(f, z); } catch (IOException ignored) { }
                z.closeEntry();
            }
            z.putNextEntry(new java.util.zip.ZipEntry("export_info.txt"));
            z.write(("Mesh Console " + meshconsole.Version.VERSION + "\nexported " + java.time.ZonedDateTime.now() + " (web console)\nfrom " + dir + "\n").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

    /** Received-signal history (median RSSI/SNR and packet counts per time slot), channel use, and one node's packets. */
    static String signal(MeshState st, Path dir, String rawQuery) throws IOException {
        Map<String, String> p = new HashMap<>();
        for (String kv : Optional.ofNullable(rawQuery).orElse("").split("&")) { int i = kv.indexOf('='); if (i > 0) p.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)); }
        int hours = Math.max(1, Math.min(24 * 14, Integer.parseInt(p.getOrDefault("hours", "24"))));
        long now = System.currentTimeMillis(), from = now - hours * 3600_000L;
        long slot = hours <= 6 ? 300_000L : hours <= 48 ? 900_000L : 3600_000L;
        long my = Integer.toUnsignedLong(st.myNodeNum());
        String want = p.getOrDefault("node", "");
        long wantNum = want.isEmpty() ? -1 : Integer.toUnsignedLong(nodeNum(want));
        TreeMap<Long, List<double[]>> buckets = new TreeMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        List<String> points = new ArrayList<>();
        Path sh = dir.resolve("signal_history.csv");
        if (Files.exists(sh)) try (var lines = Files.lines(sh)) {
            lines.forEach(l -> {
                String[] c = l.trim().split(",");
                if (c.length < 5) return;
                try {
                    long t = Long.parseLong(c[0]);
                    if (t < from) return;
                    if (c.length >= 7 && !c[6].isEmpty() && my != 0 && Long.parseLong(c[6]) != my) return;   // only what this radio heard
                    long n = Long.parseLong(c[1]); double rssi = Double.parseDouble(c[2]), snr = Double.parseDouble(c[3]);
                    buckets.computeIfAbsent(t / slot * slot, k -> new ArrayList<>()).add(new double[]{rssi, snr});
                    counts.merge(n, 1, Integer::sum);
                    if (n == wantNum) points.add("[" + t + "," + (int) rssi + "," + snr + "]");
                } catch (NumberFormatException ignored) { }
            });
        }
        StringBuilder sb = new StringBuilder("{\"slotMin\":").append(slot / 60000).append(",\"buckets\":[");
        boolean first = true;
        for (var e : buckets.entrySet()) {
            List<double[]> v = e.getValue();
            double[] r = v.stream().mapToDouble(x -> x[0]).sorted().toArray(), sn = v.stream().mapToDouble(x -> x[1]).sorted().toArray();
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(e.getKey()).append(',').append(v.size()).append(',').append(r[r.length / 2]).append(',').append(sn[sn.length / 2]).append(']');
        }
        sb.append("],\"util\":[");
        List<String> util = new ArrayList<>();
        Path uh = dir.resolve("util_history.csv");
        if (Files.exists(uh)) try (var lines = Files.lines(uh)) {
            for (String l : (Iterable<String>) lines::iterator) {
                String[] c = l.split(",");
                if (c.length < 4 || !c[0].equals("U")) continue;
                try {
                    long t = Long.parseLong(c[1]);
                    if (t < from) continue;
                    util.add("[" + t + "," + String.format(Locale.ROOT, "%.2f", Double.parseDouble(c[2])) + "," + String.format(Locale.ROOT, "%.3f", Double.parseDouble(c[3])) + "]");
                } catch (NumberFormatException ignored) { }
            }
        }
        int step = Math.max(1, util.size() / 1500);   // keep the chart light: at most ~1500 points
        first = true;
        for (int i = 0; i < util.size(); i += step) { if (!first) sb.append(','); first = false; sb.append(util.get(i)); }
        sb.append("],\"nodes\":[");
        first = true;
        List<Map.Entry<Long, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        for (var e : top.subList(0, Math.min(60, top.size()))) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(q(String.format("!%08x", e.getKey()))).append(",\"name\":").append(q(st.nodeName((int) (long) e.getKey()))).append(",\"count\":").append(e.getValue()).append('}');
        }
        return sb.append("],\"points\":[").append(String.join(",", points)).append("]}").toString();
    }

    /** Server-sent events: new or updated messages, node-list changes (at most every 15 s), status, traceroute results. */
    private static void liveEvents(HttpExchange ex, MeshClient client) throws IOException {
        MeshState st = client.state();
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        java.util.concurrent.LinkedBlockingQueue<String> q = new java.util.concurrent.LinkedBlockingQueue<>(500);
        long[] lastNodes = {0};
        MeshState.Listener l = new MeshState.Listener() {
            @Override public void onMessage(ChatMessage m, boolean isNew) { q.offer("event: message\ndata: " + msgJson(st, m) + "\n\n"); }
            @Override public void onNodesChanged() {
                long now = System.currentTimeMillis();
                if (now - lastNodes[0] > 15_000) { lastNodes[0] = now; q.offer("event: nodes\ndata: {}\n\n"); }
            }
            @Override public void onStatusChanged() { q.offer("event: status\ndata: {}\n\n"); }
            @Override public void onTracerouteResult(int node, String summary) {
                q.offer("event: trace\ndata: {\"node\":" + WebConsole.q(String.format("!%08x", node)) + ",\"text\":" + WebConsole.q(summary) + "}\n\n");
            }
        };
        st.addListener(l);
        try {
            out.write("retry: 3000\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
            while (true) {
                String e = q.poll(20, java.util.concurrent.TimeUnit.SECONDS);
                out.write((e == null ? ": ping\n\n" : e).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception gone) {
            // browser went away
        } finally { st.removeListener(l); try { out.close(); } catch (IOException ignored) { } }
    }

    private static ChatMessage testMessage() {
        ChatMessage m = new ChatMessage();
        m.time = System.currentTimeMillis(); m.from = 0; m.to = ChatMessage.BROADCAST; m.channel = 0;
        m.text = "Test from Mesh Console: the mesh → Discord bridge works."; m.outgoing = true;
        return m;
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

    public static String q(String s) {
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
.pane[hidden], #pane-messages[hidden] { display:none !important; }
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
#login[hidden] { display:none; }
#login .card { width:min(360px, 92vw); }
.mini { padding:2px 8px; font-size:12px; }
</style>
</head>
<body>
<header><span id="conn"></span><h1 id="title">Mesh Console</h1><span id="sub" class="dim"></span>
  <span style="flex:1"></span>
  <a href="chat" style="color:#58a6ff;margin-right:8px;font-weight:600">💬 Chat</a><a href="meshcore" style="color:#d29922;margin-right:8px;font-weight:600">📡 MeshCore</a><span class="tabs"><button data-tab="messages" class="on">Messages</button><button data-tab="map">Map</button><button data-tab="nodes">Nodes</button><button data-tab="signal">Signal</button><button data-tab="bbs">BBS</button><button data-tab="heard">Heard by</button><button data-tab="status">Status</button><button data-tab="settings">Settings</button></span>
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
  <div class="pane" id="pane-signal" hidden>
    <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:8px"><span class="dim">Last</span>
      <select id="sigHours"><option value="6">6 hours</option><option value="24" selected>24 hours</option><option value="72">3 days</option><option value="168">7 days</option><option value="336">14 days</option></select>
      <span class="dim">Node</span><select id="sigNode"><option value="">all nodes (medians)</option></select><span id="sigNote" class="dim"></span></div>
    <div class="card"><b>Received signal</b> <span class="dim">median RSSI (dBm) and SNR (dB), packets per slot</span><div style="height:300px"><canvas id="sigChart"></canvas></div></div>
    <div class="card"><b>Channel use</b> <span class="dim">channel utilisation and this radio's air-time, %</span><div style="height:220px"><canvas id="utilChart"></canvas></div></div>
  </div>
  <div class="pane" id="pane-bbs" hidden><div class="card" id="bbsExtra"></div><div class="card"><b>Posts</b><table><tbody id="posts"></tbody></table></div><div class="card"><b>Mail</b><table><tbody id="mail"></tbody></table></div></div>
  <div class="pane" id="pane-heard" hidden><div class="card" id="hbSet"></div><div class="card"><b>Who hears my nodes</b> <select id="hbNode"></select> <span class="dim" id="hbSum"></span><div id="hbMap" style="height:380px;margin:8px 0"></div><table id="hbTable"></table></div><div class="card"><b>Gateways per day</b><div id="hbDaily"></div></div></div>
  <div class="pane" id="pane-status" hidden><div id="statusBox"></div></div>
  <div class="pane" id="pane-settings" hidden><div id="setBox"><p class="dim">Loading…</p></div></div>
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
  const v = $("view").value, t = $("target").value, tLabel = $("target").selectedOptions[0]?.textContent || "";
  if (!M.channels.length) M.channels = [{ index: 0, name: "Primary" }];   // radio not connected yet: channel list unknown
  const chans = M.channels.map(c => `<option value="ch${c.index}">#${c.index} ${esc(c.name)}</option>`).join("");
  $("view").innerHTML = `<option value="all">All messages</option>${chans}<option value="dm">Direct messages</option>`;
  const dmPeers = [...new Map(M.messages.filter(m => m.to !== "^all").map(m => { const peer = m.out ? m.to : m.from; return [peer, m.out ? m.toName : m.fromName]; })).entries()];
  $("target").innerHTML = M.channels.map(c => `<option value="ch${c.index}">To #${c.index} ${esc(c.name)}</option>`).join("") + dmPeers.map(([id, name]) => `<option value="dm${id}">DM ${esc(name || id)}</option>`).join("");
  if (v) $("view").value = v; if (t) {
    // 1.29.1: a person picked for a first DM has no history yet, so the rebuilt list doesn't contain them – put them back
    // instead of silently falling back to the first entry (the public channel)
    if (![...$("target").options].some(o => o.value === t) && t.startsWith("dm")) $("target").insertAdjacentHTML("beforeend", `<option value="${esc(t)}">${esc(tLabel || "DM " + t.slice(2))}</option>`);
    if ([...$("target").options].some(o => o.value === t)) $("target").value = t;
  }
}
function renderMessages() {
  if (!M) return;
  const v = $("view").value || "all";
  const list = M.messages.filter(m => !m.emoji).filter(m => v === "all" || (v === "dm" ? m.to !== "^all" : (m.to === "^all" && "ch" + m.channel === v)));
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
$("statusBox").addEventListener("click", async e => {
  if (e.target.id === "expBtn") {
    try {
      const t = await api("api/export-token", {});
      const a = document.createElement("a"); a.href = "api/export?token=" + encodeURIComponent(t.token); a.download = "";
      document.body.appendChild(a); a.click(); a.remove();
      $("expNote").textContent = "download started – your browser shows its progress (the file can be tens of MB)";
    } catch (err) { $("expNote").textContent = "failed: " + err.message; }
    return;
  }
  if (e.target.id !== "updBtn") return;
  e.target.disabled = true;
  try { const r = await api("api/update-check", {}); $("updNote").textContent = r.result || r.error || ""; } catch (err) { $("updNote").textContent = err.message; }
  setTimeout(() => { const b = $("updBtn"); if (b) b.disabled = false; }, 15000);
});

// ---------------- map and nodes
let HB = null, hbMap = null, hbLayer = null;
async function renderHeard() {
  HB = await api("api/heardby");
  const st = HB.settings;
  $("hbSet").innerHTML = `<b>Heard by</b> – other stations' gateways that heard your nodes, from MQTT. ${HB.enabled ? "<span class='dim'>running</span>" : "<b style='color:#d29922'>off</b>"}
   <p><label><input type="checkbox" id="hbOn" ${HB.enabled ? "checked" : ""}> on</label> ·
      <label><input type="checkbox" id="hbPub" ${st.publicOn ? "checked" : ""}> public server</label> <input id="hbPubHost" value="${esc(st.publicHost)}" size="20"> topic <input id="hbPubTopic" value="${esc(st.publicTopic)}" size="10"></p>
   <p><label><input type="checkbox" id="hbPriv" ${st.privateOn ? "checked" : ""}> my private server</label> <input id="hbPrivHost" value="${esc(st.privateHost)}" placeholder="e.g. localhost" size="14"> port <input id="hbPrivPort" value="${esc(st.privatePort)}" size="5">
      user <input id="hbPrivUser" value="${esc(st.privateUser)}" size="8"> password <input id="hbPrivPass" type="password" placeholder="${st.hasPrivatePass ? "(saved)" : ""}" size="12"> topic <input id="hbPrivTopic" value="${esc(st.privateTopic)}" size="8"></p>
   <p>Also track these nodes (other house, G2, T-Beam): <input id="hbExtra" value="${esc(st.extraNodes)}" placeholder="type IDs, e.g. !a1b2c3d4, !12345678" size="40"> <button id="hbSave">Save</button> <span id="hbNote" class="dim"></span></p>
   <div class="dim">${HB.sources.map(s => esc(s.name) + ": " + esc(s.status) + " · " + s.packets + " packets").join("<br>")}</div>`;
  $("hbSave").onclick = async () => {
    const body = { enabled: $("hbOn").checked, publicOn: $("hbPub").checked, publicHost: $("hbPubHost").value, publicTopic: $("hbPubTopic").value,
      privateOn: $("hbPriv").checked, privateHost: $("hbPrivHost").value, privatePort: $("hbPrivPort").value, privateUser: $("hbPrivUser").value, privateTopic: $("hbPrivTopic").value, extraNodes: $("hbExtra").value };
    if ($("hbPrivPass").value) body.privatePass = $("hbPrivPass").value;
    $("hbNote").textContent = (await api("api/settings/heardby", body)).result; setTimeout(renderHeard, 4000);
  };
  const sel = $("hbNode"), keep = sel.value;
  sel.innerHTML = HB.nodes.map(n => `<option value="${n.id}">${esc(n.name)} (${n.id})</option>`).join("");
  if (keep && HB.nodes.some(n => n.id === keep)) sel.value = keep;
  sel.onchange = drawHeard; drawHeard();
}
function drawHeard() {
  const n = HB.nodes.find(x => x.id === $("hbNode").value) || HB.nodes[0];
  if (!n) { $("hbTable").innerHTML = "<tr><td>No nodes yet – is the radio connected?</td></tr>"; return; }
  const g = n.gateways, direct = g.filter(x => x.direct > 0).length, far = g.filter(x => x.km >= 0).sort((a, b) => b.km - a.km)[0];
  $("hbSum").textContent = `${g.length} gateway(s) heard it in 7 days · ${direct} directly` + (far ? ` · farthest ${far.km} km (${far.name || far.id})` : "");
  $("hbTable").innerHTML = "<tr><th>Gateway</th><th>km</th><th>24 h</th><th>7 days</th><th>fewest hops</th><th>direct</th><th>best SNR</th><th>best RSSI</th><th>last</th></tr>" + g.map(x =>
    `<tr><td>${esc(x.name || "")} <span class="dim">${x.id}</span></td><td>${x.km >= 0 ? x.km : "?"}</td><td>${x.n1}</td><td>${x.n7}</td><td>${x.minHops < 0 ? "?" : x.minHops}</td><td>${x.direct}</td><td>${x.bestSnr ?? ""}</td><td>${x.bestRssi ?? ""}</td><td>${new Date(x.last).toLocaleString()}</td></tr>`).join("");
  $("hbDaily").innerHTML = n.daily.length ? `<table><tr><th>day</th><th>gateways</th><th>sightings</th></tr>` + n.daily.slice(-14).reverse().map(d => `<tr><td>${d.day}</td><td>${d.gateways}</td><td>${d.packets}</td></tr>`).join("") + "</table>" : "<span class='dim'>No data yet.</span>";
  if (!hbMap) { hbMap = L.map("hbMap", { preferCanvas: true }).setView([n.lat || 38.9, n.lon || -77.05], 10); L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "&copy; OpenStreetMap contributors" }).addTo(hbMap); hbLayer = L.layerGroup().addTo(hbMap); }
  hbLayer.clearLayers(); const pts = [];
  if (n.lat) { L.circleMarker([n.lat, n.lon], { radius: 8, color: "#58a6ff", fillOpacity: .9 }).bindTooltip(n.name).addTo(hbLayer); pts.push([n.lat, n.lon]); }
  for (const x of g) if (x.lat) {
    const col = x.direct > 0 ? "#3fb950" : "#d29922";
    L.circleMarker([x.lat, x.lon], { radius: 6, color: col, fillOpacity: .8 }).bindTooltip(`${x.name || x.id}: ${x.n7} in 7 days, ${x.direct ? "direct, best SNR " + x.bestSnr : "via " + x.minHops + " hop(s)"}`).addTo(hbLayer);
    if (n.lat) L.polyline([[n.lat, n.lon], [x.lat, x.lon]], { color: col, weight: 1, opacity: .5 }).addTo(hbLayer);
    pts.push([x.lat, x.lon]);
  }
  // the map may have been created while its tab was hidden: let it measure itself first, then frame the points
  setTimeout(() => {
    hbMap.invalidateSize();
    if (pts.length > 1) hbMap.fitBounds(pts, { padding: [20, 20], maxZoom: 12 }); else if (pts.length) hbMap.setView(pts[0], 11);
  }, 150);
}
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

// ---------------- signal charts
let sigChart = null, utilChart = null;
function loadChart(cb) {
  if (window.Chart) { cb(); return; }
  const js = document.createElement("script"); js.src = "https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js"; js.onload = cb;
  js.onerror = () => { $("sigNote").textContent = "the charts need an internet connection (Chart.js)"; }; document.head.appendChild(js);
}
async function renderSignal() {
  $("sigNote").textContent = "loading…";
  const node = $("sigNode").value, d = await api("api/signal?hours=" + $("sigHours").value + (node ? "&node=" + encodeURIComponent(node) : ""));
  const keep = $("sigNode").value;
  $("sigNode").innerHTML = '<option value="">all nodes (medians)</option>' + d.nodes.map(n => `<option value="${esc(n.id)}">${esc(n.name)} (${n.count})</option>`).join("");
  $("sigNode").value = keep;
  const opts = (y1, y2) => ({ responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: "index", intersect: false },
    scales: { x: { type: "linear", ticks: { color: "#8b949e", callback: v => new Date(v).toLocaleString([], { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }) }, grid: { color: "#2c333b" } },
      y: { title: { display: true, text: y1, color: "#8b949e" }, ticks: { color: "#8b949e" }, grid: { color: "#2c333b" } },
      ...(y2 ? { y2: { position: "right", title: { display: true, text: y2, color: "#8b949e" }, ticks: { color: "#8b949e" }, grid: { display: false } } } : {}) },
    plugins: { legend: { labels: { color: "#e6e9ec" } }, tooltip: { callbacks: { title: it => new Date(it[0].parsed.x).toLocaleString() } } } });
  let sets;
  if (node) {
    sets = [{ label: "RSSI (dBm)", data: d.points.map(p => ({ x: p[0], y: p[1] })), borderColor: "#58a6ff", backgroundColor: "#58a6ff", showLine: false, pointRadius: 3, yAxisID: "y" },
            { label: "SNR (dB)", data: d.points.map(p => ({ x: p[0], y: p[2] })), borderColor: "#f0883e", backgroundColor: "#f0883e", showLine: false, pointRadius: 3, yAxisID: "y2" }];
    $("sigNote").textContent = d.points.length + " packets from this node";
  } else {
    sets = [{ label: "median RSSI (dBm)", data: d.buckets.map(b => ({ x: b[0], y: b[2] })), borderColor: "#58a6ff", pointRadius: 0, borderWidth: 1.5, yAxisID: "y" },
            { label: "median SNR (dB)", data: d.buckets.map(b => ({ x: b[0], y: b[3] })), borderColor: "#f0883e", pointRadius: 0, borderWidth: 1.5, yAxisID: "y2" },
            { label: "packets per " + d.slotMin + " min", type: "bar", data: d.buckets.map(b => ({ x: b[0], y: b[1] })), backgroundColor: "rgba(63,185,80,.35)", yAxisID: "y3", barThickness: 3 }];
    $("sigNote").textContent = d.buckets.reduce((a, b) => a + b[1], 0) + " packets";
  }
  const o = opts("RSSI dBm", "SNR dB");
  if (!node) o.scales.y3 = { display: false, position: "right", beginAtZero: true };
  if (sigChart) sigChart.destroy();
  sigChart = new Chart($("sigChart"), { type: "line", data: { datasets: sets }, options: o });
  if (utilChart) utilChart.destroy();
  utilChart = new Chart($("utilChart"), { type: "line", data: { datasets: [
      { label: "channel utilisation %", data: d.util.map(u => ({ x: u[0], y: u[1] })), borderColor: "#58a6ff", pointRadius: 0, borderWidth: 1.2 },
      { label: "air-time TX %", data: d.util.map(u => ({ x: u[0], y: u[2] })), borderColor: "#f0883e", pointRadius: 0, borderWidth: 1.2 }] }, options: opts("%", null) });
}
$("sigHours").onchange = renderSignal; $("sigNode").onchange = renderSignal;

// ---------------- settings
const opt = (list, cur) => list.map(v => `<option${v === cur ? " selected" : ""}>${esc(v)}</option>`).join("");
const chk = (id, on, label) => `<label><input type="checkbox" id="${id}"${on ? " checked" : ""}> ${label}</label>`;
async function loadSettings() {
  const d = await api("api/settings"), r = d.radio, a = d.alerts, l = r.lora || {}, w = r.wifi || {}, m = r.mqtt || {};
  const chRows = r.channels.map(c => `<tr data-idx="${c.index}"><td>${c.index}</td>
    <td><select class="cRole">${opt(c.index === 0 ? ["PRIMARY"] : ["SECONDARY", "DISABLED"], c.role)}</select></td>
    <td><input class="cName" value="${esc(c.name)}" size="10"></td><td><input class="cPsk" value="${esc(c.psk)}" size="46"></td>
    <td><input type="checkbox" class="cUp"${c.up ? " checked" : ""}></td><td><input type="checkbox" class="cDown"${c.down ? " checked" : ""}></td>
    <td><button class="mini cSave">Save</button></td></tr>`).join("");
  $("setBox").innerHTML = `
  <p class="dim">Changes go straight to the radio, like the desktop window. LoRa, role, WiFi and MQTT changes restart the radio (it reconnects by itself in about 30 s).</p>
  <div class="card"><b>Owner</b><p><input id="sLong" value="${esc(r.long)}" size="28"> <input id="sShort" value="${esc(r.short)}" size="4" maxlength="4"> <button data-save="owner">Save</button></p>
    <b>Role</b><p><select id="sRole">${opt(r.roles, r.role)}</select> <button data-save="role">Save</button></p></div>
  <div class="card"><b>LoRa</b><p>Region <select id="sRegion">${opt(r.regions, l.region)}</select> Preset <select id="sPreset">${opt(r.presets, l.preset)}</select>
    Hops <input id="sHops" type="number" min="1" max="7" value="${l.hops || 3}" style="width:4em"> ${chk("sRx", l.rxBoost, "RX boost")} ${chk("sOk", l.okToMqtt, "OK to MQTT")} <button data-save="lora">Save</button></p>
    <p class="dim">Transmit power: ${l.txPower ?? "?"} – set it on the desktop window's Station G2 tab, which checks it against your antenna.</p></div>
  <div class="card"><b>Fixed position</b><p><input id="sLat" value="${r.lat ?? ""}" size="10"> <input id="sLon" value="${r.lon ?? ""}" size="10"> altitude m <input id="sAlt" value="${r.alt ?? 0}" size="5"> <button data-save="position">Set</button> <span class="dim">${r.fixed ? "fixed position on" : "no fixed position"}</span></p></div>
  <div class="card"><b>WiFi</b><p>${chk("sWifi", w.on, "on")} SSID <input id="sSsid" value="${esc(w.ssid || "")}" size="14"> password <input id="sPsk" type="password" placeholder="(unchanged)" size="14"> <button data-save="wifi">Save</button></p></div>
  <div class="card"><b>Channels</b> ${d.others.length ? `<span class="dim">copy channels 1–7 from</span> <select id="sFrom">${d.others.map(o => `<option value="${o.port}">${esc(o.name)} (:${o.port})</option>`).join("")}</select> <button id="sCopy">Copy</button>` : ""}
    <table><thead><tr><th>#</th><th>Role</th><th>Name</th><th>Key (Base64)</th><th>Up</th><th>Down</th><th></th></tr></thead><tbody id="chRows">${chRows}</tbody></table></div>
  <div class="card"><b>MQTT</b><p>${chk("sMq", m.on, "enabled")} server <input id="sMqAddr" value="${esc(m.address || "mqtt.meshtastic.org")}" size="20"> user <input id="sMqUser" value="${esc(m.user || "meshdev")}" size="8">
    password <input id="sMqPw" type="password" placeholder="(unchanged)" size="8"> root <input id="sMqRoot" value="${esc(m.root || "msh/US")}" size="8"></p>
    <p>${chk("sMqEnc", m.encryption, "encryption")} ${chk("sMqJson", m.json, "JSON")} ${chk("sMqTls", m.tls, "TLS")} ${chk("sMqMap", m.map, "map reporting")} ${chk("sMqProxy", m.proxy, "proxy to client")} <button data-save="mqtt">Save</button></p></div>
  <div class="card"><b>Mesh alerts</b> <span class="dim">to your phone with ntfy (and e-mail for the important ones)</span>
    <p>ntfy topic <input id="aNtfy" value="${esc(a.ntfy)}" size="22"> e-mail <input id="aEmail" value="${esc(a.email)}" size="22"></p>
    <p>Watch nodes <input id="aWatch" value="${esc(a.watch)}" size="60" placeholder="!a1b2c3d4=2, !12345678=12   (node=hours not heard)"></p>
    <p>${chk("aDirect", a.newDirect, "new node heard directly")} ${chk("aCallers", a.newCallers, "first-time BBS callers (watched nodes: every check-in)")} ${chk("aRadio", a.radioDown, "radio not connected for 10 min")}
      <button id="aSave">Save alerts</button> <button id="aTest">Send test</button></p></div>
  ${d.bbsExtras ? `<div class="card"><b>BBS extras</b> <span class="dim">net: ${esc(d.bbsExtras.netStatus)}</span>
    <p>${chk("xNet", d.bbsExtras.netOn, "weekly check-in net")} on <select id="xDay">${opt(["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"], d.bbsExtras.netDay)}</select> at <input id="xTime" value="${esc(d.bbsExtras.netTime)}" size="5">
      <span class="dim">(announced 30 min before; CHECKIN accepted until 60 min after; roll call broadcast + posted)</span></p>
    <p>N2YO key for SAT passes <input id="xN2yo" type="password" placeholder="${d.bbsExtras.hasN2yoKey ? "(saved – unchanged)" : "free key from n2yo.com"}" size="24"></p>
    <p>Welcome for first-time callers <input id="xWelcome" value="${esc(d.bbsExtras.welcome)}" size="70"></p>
    <p><button id="xSave">Save BBS extras</button></p></div>` : ""}
  <div class="card"><b>Discord bridge</b> <span class="dim">one mesh channel ↔ one Discord channel · status: ${esc(d.discord.status)} · ${d.discord.toDiscordCount} to Discord, ${d.discord.toMeshCount} to the mesh${d.discord.heldBack ? ", " + d.discord.heldBack + " held back (airtime limit)" : ""}</span>
    <p>${chk("dOn", d.discord.enabled, "bridge on")} mesh channel <select id="dChan">${r.channels.filter(c => c.role !== "DISABLED").map(c => `<option value="${c.index}"${c.index === d.discord.meshChannel ? " selected" : ""}>#${c.index} ${esc(c.name || "Primary")}</option>`).join("")}</select>
      ${chk("dToD", d.discord.toDiscord, "mesh → Discord")} ${chk("dSig", d.discord.showSignal, "show hops/signal")}</p>
    <p>Webhook URL (Discord channel → Integrations → Webhooks) <input id="dHook" value="${esc(d.discord.webhook)}" size="50"></p>
    <p>${chk("dToM", d.discord.toMesh, "Discord → mesh")} bot token <input id="dTok" type="password" placeholder="${d.discord.hasBotToken ? "(saved – unchanged)" : "bot token"}" size="22">
      channel ID <input id="dCid" value="${esc(d.discord.channelId)}" size="20"> at most <input id="dMax" type="number" min="1" max="60" value="${d.discord.maxPerHour}" style="width:4em"> an hour</p>
    <p><button id="dSave">Save bridge</button> <button id="dTest">Send test to Discord</button>
      <span class="dim">Discord messages go out as "[D] name: text", one a minute per person, never from bots – the channel is shared airtime.</span></p></div>
  <p id="setNote" class="dim"></p>`;
}
async function saveSetting(what, body) {
  if (!confirm("Save " + what + " to the radio?")) return;
  const r = await api("api/settings/save", { what, ...body }); $("setNote").textContent = r.result || r.error; alert(r.result || r.error);
}
$("setBox").addEventListener("click", async e => {
  const t = e.target, b = t.dataset.save, v = id => $(id).value, c = id => $(id).checked;
  if (b === "owner") saveSetting("owner", { long: v("sLong"), short: v("sShort") });
  if (b === "role") saveSetting("role", { role: v("sRole") });
  if (b === "lora") saveSetting("lora", { region: v("sRegion"), preset: v("sPreset"), hops: v("sHops"), rxBoost: c("sRx"), okToMqtt: c("sOk") });
  if (b === "position") saveSetting("position", { lat: v("sLat"), lon: v("sLon"), alt: v("sAlt") });
  if (b === "wifi") saveSetting("wifi", { on: c("sWifi"), ssid: v("sSsid"), psk: v("sPsk") });
  if (b === "mqtt") saveSetting("mqtt", { on: c("sMq"), address: v("sMqAddr"), user: v("sMqUser"), password: v("sMqPw"), root: v("sMqRoot"), encryption: c("sMqEnc"), json: c("sMqJson"), tls: c("sMqTls"), map: c("sMqMap"), proxy: c("sMqProxy") });
  if (t.classList.contains("cSave")) {
    const tr = t.closest("tr");
    saveSetting("channel " + tr.dataset.idx, { index: tr.dataset.idx, role: tr.querySelector(".cRole").value, name: tr.querySelector(".cName").value, psk: tr.querySelector(".cPsk").value, up: tr.querySelector(".cUp").checked, down: tr.querySelector(".cDown").checked });
  }
  if (t.id === "sCopy") { if (!confirm("Copy channels 1–7 (names, keys, uplink/downlink) from the other window to this radio?")) return; const r = await api("api/settings/save", { what: "copy-channels", from: v("sFrom") }); alert(r.result); loadSettings(); }
  if (t.id === "aSave") { const r = await api("api/settings/alerts", { ntfy: v("aNtfy"), email: v("aEmail"), watch: v("aWatch"), newDirect: c("aDirect"), newCallers: c("aCallers"), radioDown: c("aRadio") }); $("setNote").textContent = r.result; }
  if (t.id === "xSave") { const body = { netOn: c("xNet"), netDay: v("xDay"), netTime: v("xTime"), welcome: v("xWelcome") }; if (v("xN2yo")) body.n2yoKey = v("xN2yo"); const r = await api("api/settings/bbs-extras", body); $("setNote").textContent = r.result; loadSettings(); }
  if (t.id === "dSave") { const r = await api("api/settings/discord", { enabled: c("dOn"), meshChannel: v("dChan"), toDiscord: c("dToD"), showSignal: c("dSig"), webhook: v("dHook"), toMesh: c("dToM"), botToken: v("dTok"), channelId: v("dCid"), maxPerHour: v("dMax") }); $("setNote").textContent = r.result; loadSettings(); }
  if (t.id === "dTest") { const r = await api("api/settings/discord-test", {}); $("setNote").textContent = r.result; }
  if (t.id === "aTest") { const r = await api("api/settings/test-alert", {}); $("setNote").textContent = r.result; }
});

// ---------------- BBS and status
async function renderBbs() {
  const b = await api("api/bbs");
  $("bbsExtra").innerHTML = `<b>Weekly net:</b> ${esc(b.community ? b.community.netStatus : "–")}<br><b>Which adverts bring callers</b><pre style="white-space:pre-wrap;margin:4px 0 0">${esc(b.advertReport || "")}</pre>`;
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
   </div>
   <div class="card"><b>Mesh Console ${esc(s.version)}</b> <span class="dim">updates from ${esc(s.updateSource || "(no source set)")}${s.updateAuto ? " · daily at " + esc(s.updateTime) : " · automatic updates off"}</span>
     <p><button id="updBtn">Check for update now</button> <span id="updNote" class="dim">${esc(s.updateResult || "")}</span></p></div>
   <div class="card"><b>Logs</b> <span class="dim">everything in this radio's data folder (signal history, nodes, messages, BBS, antenna log …) as one zip</span>
     <p><button id="expBtn">Download logs (zip)</button> <span id="expNote" class="dim"></span></p></div>`;
}

// ---------------- plumbing
let current = "messages";
function show(t) {
  current = t;
  document.querySelectorAll(".tabs button").forEach(b => b.classList.toggle("on", b.dataset.tab === t));
  for (const p of ["messages", "map", "nodes", "signal", "bbs", "heard", "status", "settings"]) $("pane-" + p).hidden = p !== t;
  if (t === "heard") loadLeaflet(renderHeard);
  if (t === "settings") loadSettings();
  if (t === "signal") loadChart(renderSignal);
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
if (location.hash === "#heard") { const tryShow = () => { if ($("login").hidden) show("heard"); else setTimeout(tryShow, 1000); }; setTimeout(tryShow, 800); }
</script>
</body>
</html>
""";
}
