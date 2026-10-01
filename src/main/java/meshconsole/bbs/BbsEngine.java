package meshconsole.bbs;

import meshconsole.analysis.WeatherHistory;
import meshconsole.analysis.WeatherObs;
import meshconsole.mesh.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A tiny bulletin-board bot that answers direct messages sent to this node.
 * Commands (case-insensitive, send "?" for the menu): B [page], R n, P text, D n, M, M !id|name text, N, S, W, I, PING.
 */
public class BbsEngine {
    public interface Listener { void onActivity(String line); default void onCaller(int num, String command) { } default void onStateChanged() { } }

    private static final int MAX_BYTES = 200;
    private final MeshClient client;
    private final MeshState state;
    private final BbsStore store;
    private final Map<Integer, Long> lastReply = new HashMap<>();
    private final Deque<String> activity = new ArrayDeque<>();
    private WeatherHistory weather;
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Listener listener = new Listener() {
        @Override public void onActivity(String line) { for (Listener l : listeners) l.onActivity(line); }
        @Override public void onCaller(int num, String command) { for (Listener l : listeners) l.onCaller(num, command); }
        @Override public void onStateChanged() { for (Listener l : listeners) l.onStateChanged(); }
    };
    private long lastMailSweep;
    private final Map<Integer, Long> pendingTrace = new HashMap<>();
    private final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "bbs-net"); t.setDaemon(true); return t; });

    public BbsEngine(MeshClient client, BbsStore store) {
        this.client = client;
        this.state = client.state();
        this.store = store;
        store.started = System.currentTimeMillis();
        state.addListener(new MeshState.Listener() {
            @Override public void onMessage(ChatMessage m, boolean isNew) { if (isNew && !m.outgoing) handle(m); }
            @Override public void onNodesChanged() { deliverPendingMail(); }
            @Override public void onTracerouteResult(int node, String summary) {
                if (pendingTrace.remove(node) != null) {
                    String s = summary.replace("Traceroute ", "").replace("   |   ", " | ");
                    send(node, s.length() > 190 ? s.substring(0, 187) + "…" : s);
                }
            }
        });
    }

    public BbsStore store() { return store; }

    public void setEnabled(boolean on) {
        store.enabled = on; store.save();
        log(on ? "BBS ON – waiting for callers" : "BBS OFF");
        listener.onStateChanged();
    }

    /** Mirrors a bulletin to the configured Discord/Slack webhook (read-only bridge). */
    public void bridge(String text) {
        if (store.discordWebhook.isBlank()) return;
        bg.submit(() -> { String err = BbsExtras.discord(store.discordWebhook, text); if (err != null) log("bridge failed: " + err); });
    }

    /** Sysop-initiated DM to a caller (bypasses cooldown; counts as a reply). */
    public void sysopChat(int to, String text) {
        send(to, "[sysop] " + text);
        store.save();
    }

    /** Call about once a minute: sends the daily advertisement at the configured time while the board is online. */
    public void tick() {
        if (!store.enabled || !store.advertOn) return;
        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime at; try { at = java.time.LocalTime.parse(store.advertTime.trim()); } catch (Exception e) { return; }
        String today = java.time.LocalDate.now().toString();
        if (now.getHour() == at.getHour() && now.getMinute() == at.getMinute() && !today.equals(store.advertDay)) {
            store.advertDay = today; store.save();
            String text = store.advertText.isBlank() ? store.defaultAdvert() : store.advertText;
            try { client.sendText(text.length() > 190 ? text.substring(0, 190) : text, ChatMessage.BROADCAST, 0); log("advert sent: " + text); }
            catch (IOException e) { log("advert failed: " + e.getMessage()); }
        }
    }

    /** Sends the advertisement now (sysop). */
    public void advertiseNow() {
        String text = store.advertText.isBlank() ? store.defaultAdvert() : store.advertText;
        try { client.sendText(text.length() > 190 ? text.substring(0, 190) : text, ChatMessage.BROADCAST, 0); log("advert sent: " + text); }
        catch (IOException e) { log("advert failed: " + e.getMessage()); }
    }

    public String stateLine() {
        long up = (System.currentTimeMillis() - store.started) / 60000;
        return String.format("%s  |  %s  |  up %dh%02dm  |  calls today %d  |  callers %d  |  posts %d  |  mail %d  |  cmds %d",
                store.name, store.enabled ? "ONLINE" : "OFFLINE", up / 60, up % 60, store.callsToday, store.callers.size(), store.posts.size(), store.mail.size(), store.commandsServed);
    }
    public void setWeather(WeatherHistory w) { weather = w; }
    public void setListener(Listener l) { listeners.add(l); }
    public void addListener(Listener l) { listeners.add(l); }
    public List<String> activity() { synchronized (activity) { return new ArrayList<>(activity); } }

    private void log(String s) {
        String line = meshconsole.ui.FmtBridge.time(System.currentTimeMillis()) + "  " + s;
        synchronized (activity) { activity.addLast(line); while (activity.size() > 500) activity.removeFirst(); }
        listener.onActivity(line);
        state.emitLog("[bbs] " + s);
        try { java.nio.file.Files.writeString(meshconsole.DataDir.file("bbs.log"), line + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (IOException ignored) { }
    }

    // ---- incoming -----------------------------------------------------------

    private void handle(ChatMessage m) {
        if (!store.enabled) return;
        int me = state.myNodeNum();
        if (m.from == me || m.from == 0) return;
        String text = m.text == null ? "" : m.text.trim();
        boolean dm = m.to == me;
        if (!dm) {
            // broadcast: only the optional trigger word gets an answer
            if (!store.channelTrigger || !text.equalsIgnoreCase(store.triggerWord)) return;
        }
        if (store.banned.contains(m.from)) { log("ignored banned " + state.nodeName(m.from)); return; }
        long now = System.currentTimeMillis();
        Long last = lastReply.get(m.from);
        if (last != null && now - last < store.cooldownSec * 1000L) { log("rate-limited " + state.nodeName(m.from)); return; }
        // caller bookkeeping
        BbsStore.Caller cl = store.callers.computeIfAbsent(m.from, k -> { BbsStore.Caller x = new BbsStore.Caller(); x.num = k; x.first = now; return x; });
        cl.name = state.nodeName(m.from); cl.last = now; cl.commands++; cl.lastCommand = text.length() > 40 ? text.substring(0, 40) : text;
        String today = java.time.LocalDate.now().toString();
        if (!today.equals(store.callsDay)) { store.callsDay = today; store.callsToday = 0; }
        store.callsToday++;
        log("← " + cl.name + ": " + text);
        listener.onCaller(m.from, text);
        String reply;
        try { reply = dm ? command(m.from, text) : store.name + " here – DM me ? for commands"; }
        catch (RuntimeException e) { reply = "error: " + e.getMessage(); }
        if (reply == null) { store.commandsServed++; store.save(); return; }   // answered asynchronously
        store.commandsServed++;
        send(m.from, reply);
        store.save();
    }

    private void send(int to, String text) {
        for (String chunk : chunks(text)) {
            try {
                client.sendText(chunk, to, 0);
                store.repliesSent++;
                lastReply.put(to, System.currentTimeMillis());
                log("→ " + state.nodeName(to) + ": " + chunk);
            } catch (IOException e) { log("send failed: " + e.getMessage()); return; }
        }
    }

    /** Splits on line/word boundaries into ≤200-byte pieces, numbered when more than one. */
    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        if (text.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) { out.add(text); return out; }
        StringBuilder cur = new StringBuilder();
        for (String word : text.split("(?<=\\s)")) {
            if ((cur.toString() + word).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES - 8) { out.add(cur.toString().trim()); cur.setLength(0); }
            cur.append(word);
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        for (int i = 0; i < out.size(); i++) out.set(i, "(" + (i + 1) + "/" + out.size() + ") " + out.get(i));
        return out;
    }

    // ---- commands -----------------------------------------------------------

    String command(int from, String text) {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].toUpperCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        String who = state.nodeName(from);
        switch (cmd) {
            case "?", "H", "HELP", "MENU" -> { return store.name + " menu: B bulletins, R n, P text, D n | M mail, M name text | S signal, T trace, N nodes, STATS, TOP | W wx, TAF, METRO | C call, E events | TRIVIA, GUESS, ADV | I, PING (?2 = details)"; }
            case "?2", "HELP2" -> { return "B list (B 2 next page) R 3 read #3 P text post D 3 delete yours | M read mail, M bob hi = mail bob | S signal at BBS T traceroute you N nodes/1h STATS mesh TOP leaderboards | W METAR TAF forecast METRO rail | C N8HM callsign E events | games: TRIVIA, GUESS, ADV"; }
            case "PING" -> { return "PONG from " + store.name; }
            case "I", "INFO" -> {
                long up = (System.currentTimeMillis() - store.started) / 60000;
                return store.name + " on " + state.nodeName(state.myNodeNum()) + ". " + store.posts.size() + " posts, " + store.mail.size() + " mail, " + store.commandsServed + " cmds, up " + up / 60 + "h" + up % 60 + "m. " + store.welcome;
            }
            case "B", "LIST" -> {
                if (store.posts.isEmpty()) return "No bulletins yet. P text to post.";
                int page = 1; try { page = Math.max(1, Integer.parseInt(arg)); } catch (NumberFormatException ignored) { }
                List<BbsStore.Post> ps = new ArrayList<>(store.posts); Collections.reverse(ps);
                int per = 4, start = (page - 1) * per;
                if (start >= ps.size()) return "No page " + page + ".";
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < Math.min(ps.size(), start + per); i++) {
                    BbsStore.Post p = ps.get(i);
                    String t = p.text.length() > 30 ? p.text.substring(0, 30) + "…" : p.text;
                    sb.append('#').append(p.id).append(' ').append(shortName(p.authorName)).append(": ").append(t).append('\n');
                }
                if (start + per < ps.size()) sb.append("B ").append(page + 1).append(" for more");
                return sb.toString().trim();
            }
            case "R", "READ" -> {
                BbsStore.Post p = post(arg);
                if (p == null) return "R n – which post? (B lists them)";
                return "#" + p.id + " " + p.authorName + " " + meshconsole.ui.FmtBridge.ago(p.time) + " ago: " + p.text;
            }
            case "P", "POST" -> {
                if (arg.isEmpty()) return "P text – what do you want to post?";
                BbsStore.Post p = new BbsStore.Post(); p.id = store.nextId(); p.time = System.currentTimeMillis(); p.author = from; p.authorName = who; p.text = arg;
                store.posts.add(p);
                while (store.posts.size() > store.maxPosts) store.posts.remove(0);
                log("post #" + p.id + " by " + who + ": " + arg);
                bridge("📌 " + who + ": " + arg);
                return "Posted #" + p.id + ". B to list.";
            }
            case "D", "DEL", "DELETE" -> {
                if (cmd.equals("D") && arg.isEmpty() && store.advRoom.containsKey(from)) return command(from, "DOWN");
                BbsStore.Post p = post(arg);
                if (p == null) return "D n – which post?";
                if (p.author != from) return "Only the author can delete #" + p.id;
                store.posts.remove(p);
                return "Deleted #" + p.id;
            }
            case "M", "MAIL" -> {
                if (arg.isEmpty()) {
                    List<BbsStore.Mail> mine = new ArrayList<>();
                    for (BbsStore.Mail x : store.mail) if (x.to == from) mine.add(x);
                    if (mine.isEmpty()) return "No mail for you. M name text – leave mail for someone.";
                    StringBuilder sb = new StringBuilder();
                    for (BbsStore.Mail x : mine) { sb.append(shortName(x.fromName)).append(": ").append(x.text).append('\n'); x.delivered = System.currentTimeMillis(); }
                    store.mail.removeIf(x -> x.to == from);
                    return sb.toString().trim();
                }
                String[] mp = arg.split("\\s+", 2);
                if (mp.length < 2) return "M name text – who and what?";
                Integer to = resolve(mp[0]);
                if (to == null) return "Don't know '" + mp[0] + "'. Use !id or a name from N.";
                BbsStore.Mail x = new BbsStore.Mail(); x.id = store.nextId(); x.time = System.currentTimeMillis(); x.from = from; x.fromName = who; x.to = to; x.text = mp[1];
                store.mail.add(x);
                log("mail #" + x.id + " " + who + " → " + state.nodeName(to));
                return "Mail queued for " + state.nodeName(to) + "; delivered when they're heard.";
            }
            case "NODES" -> { return nodesLine(); }
            case "S", "SIG", "SIGNAL" -> {
                NodeEntry n = state.node(from);
                if (n == null || n.rssiCount == 0) return "Haven't measured you yet.";
                String dist = "";
                NodeEntry me = state.myNode();
                if (me != null && me.hasPosition && n.hasPosition) dist = String.format(", %.1f km", meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, n.lat, n.lon));
                return String.format("You at %s: last %d dBm / %.1f dB, %s%s, avg %.0f dBm over %d pkts. T = traceroute you.", store.name, n.rssi, n.snr, n.hopsAway == 0 ? "direct" : n.hopsAway + " hop" + (n.hopsAway == 1 ? "" : "s"), dist, n.avgRssi(), n.rssiCount);
            }
            case "T", "TRACE" -> {
                try { client.traceroute(from); pendingTrace.put(from, System.currentTimeMillis()); return "Tracing the route to you – reply follows in up to a minute."; }
                catch (IOException e) { return "Traceroute failed: " + e.getMessage(); }
            }
            case "STATS", "MESH" -> {
                long cutoff = System.currentTimeMillis() - 3600_000L; int heard = 0, total = 0, direct = 0; NodeEntry far = null; NodeEntry me = state.myNode();
                for (NodeEntry n : state.nodes()) {
                    if (n.num == state.myNodeNum()) continue; total++;
                    if (n.lastHeardMillis() > cutoff) { heard++; if (n.hopsAway == 0) direct++; }
                    if (me != null && me.hasPosition && n.hasPosition && n.lastLocalRx > cutoff * 0 && n.packetsSeen > 0) { if (far == null || meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, n.lat, n.lon) > meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, far.lat, far.lon)) far = n; }
                }
                float util = me == null ? 0 : me.channelUtil;
                String farS = far == null ? "" : String.format(", farthest heard %s %.0f km", far.shortLabel(), meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, far.lat, far.lon));
                return String.format("Mesh via %s: %d nodes/1h (%d direct), %d known, util %.0f%%%s", store.name, heard, direct, total, util, farS);
            }
            case "TOP", "LEADER" -> {
                StringBuilder sb = new StringBuilder("TOP: ");
                NodeEntry me = state.myNode(); NodeEntry far = null; NodeEntry hops = null; Map<Integer, Integer> posts = new HashMap<>();
                for (NodeEntry n : state.nodes()) { if (n.num == state.myNodeNum() || n.packetsSeen == 0) continue; if (me != null && me.hasPosition && n.hasPosition && (far == null || meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, n.lat, n.lon) > meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, far.lat, far.lon))) far = n; if (n.hopsAway >= 0 && (hops == null || n.hopsAway > hops.hopsAway)) hops = n; }
                for (BbsStore.Post p : store.posts) posts.merge(p.author, 1, Integer::sum);
                if (far != null) sb.append("farthest ").append(far.shortLabel()).append(String.format(" %.0f km", meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, far.lat, far.lon))).append("; ");
                if (hops != null) sb.append("most hops ").append(hops.shortLabel()).append(" (").append(hops.hopsAway).append("); ");
                posts.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> sb.append("top poster ").append(shortName(state.nodeName(e.getKey()))).append(" (").append(e.getValue()).append("); "));
                store.trivia.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> sb.append("trivia champ ").append(shortName(state.nodeName(e.getKey()))).append(" (").append(e.getValue()).append(" pts)"));
                return sb.toString();
            }
            case "TAF" -> { bg.submit(() -> send(from, BbsExtras.taf(store.station))); return null; }
            case "METRO", "WMATA" -> { bg.submit(() -> send(from, BbsExtras.metro(store.wmataKey))); return null; }
            case "C", "CALL", "CALLSIGN" -> {
                if (arg.isEmpty()) return "C N8HM – look up a US callsign";
                final String call = arg.split("\\s+")[0];
                bg.submit(() -> send(from, BbsExtras.callsign(call))); return null;
            }
            case "E", "EVENTS" -> {
                if (store.events.isEmpty()) return "No events posted. (Sysop posts them.)";
                StringBuilder sb = new StringBuilder();
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MMM d HH:mm");
                for (BbsStore.Event e : store.events) { String line = (e.when > 0 ? f.format(new Date(e.when)) + " " : "") + e.text + "\n"; if (sb.length() + line.length() > 190) break; sb.append(line); }
                return sb.toString().trim();
            }
            case "TRIVIA", "Q" -> {
                int qi = new Random().nextInt(BbsExtras.TRIVIA.length);
                store.triviaQ.put(from, qi);
                return "Q: " + BbsExtras.TRIVIA[qi].q() + "  (answer with A your-answer)";
            }
            case "A", "ANSWER" -> {
                Integer qi = store.triviaQ.get(from);
                if (qi == null) return "No question pending – send TRIVIA first.";
                BbsExtras.Trivia t = BbsExtras.TRIVIA[qi];
                String ans = arg.trim().toLowerCase().replace(".", "");
                boolean ok = ans.equals(t.a()) || Arrays.asList(t.alts()).contains(ans) || ans.contains(t.a());
                store.triviaQ.remove(from);
                if (ok) { int pts = store.trivia.merge(from, 1, Integer::sum); return "Correct! " + pts + " point" + (pts == 1 ? "" : "s") + ". TRIVIA for another."; }
                return "Nope – it was '" + t.a() + "'. TRIVIA for another.";
            }
            case "GUESS", "G" -> {
                Integer target = store.guessTarget.get(from);
                if (arg.isEmpty() || target == null) {
                    if (target == null) { store.guessTarget.put(from, 1 + new Random().nextInt(100)); store.guessTries.put(from, 0); }
                    return "I'm thinking of a number 1–100. Send G 50 to guess.";
                }
                int g; try { g = Integer.parseInt(arg.trim()); } catch (NumberFormatException e) { return "G followed by a number, e.g. G 42"; }
                int tries = store.guessTries.merge(from, 1, Integer::sum);
                if (g == target) { store.guessTarget.remove(from); return "Yes! " + g + " in " + tries + " tr" + (tries == 1 ? "y" : "ies") + ". GUESS for a new game."; }
                return g < target ? "Higher. (" + tries + ")" : "Lower. (" + tries + ")";
            }
            case "ADV", "LOOK", "N", "NORTH", "SOUTH", "U", "UP", "DOWN", "TAKE" -> {
                // "N" doubles as nodes when no adventure is in progress
                if (cmd.equals("N") && !store.advRoom.containsKey(from)) { return nodesLine(); }
                int room = store.advRoom.getOrDefault(from, -1);
                if (cmd.equals("ADV") || room < 0) { store.advRoom.put(from, 0); store.advHasSa.remove(from); return BbsExtras.ROOMS[0]; }
                switch (cmd) {
                    case "LOOK" -> { return BbsExtras.ROOMS[room]; }
                    case "N", "NORTH" -> { if (room == 0) room = 1; else if (room == 2) room = 0; else return "Can't go north."; }
                    case "SOUTH" -> { if (room == 0) room = 2; else if (room == 1) room = 0; else return "Can't go south."; }
                    case "U", "UP" -> { if (room == 1) { if (!store.advHasSa.contains(from)) return "The roof is noisy – you'd better TAKE SA (the tinySA) first."; room = 3; } else return "Can't go up."; }
                    case "DOWN" -> { if (room == 3) room = 1; else return "Can't go down."; }
                    case "TAKE" -> { if (room == 1 && arg.toUpperCase().startsWith("SA")) { store.advHasSa.add(from); return "Taken. The tinySA reads −124 dBm. Nice."; } if (room == 2 && arg.toUpperCase().startsWith("PSU")) return "Taken. 12 V, ready for a Pi."; return "Nothing like that here."; }
                }
                if (room == 3 && store.advHasSa.contains(from)) { store.advRoom.remove(from); return BbsExtras.ROOMS[4]; }
                store.advRoom.put(from, room);
                return BbsExtras.ROOMS[room];
            }
            case "W", "WX", "WEATHER" -> {
                WeatherObs o = weather == null ? null : weather.latest();
                if (o == null) return "No weather data on this BBS.";
                return String.format("%s %s: %.0f°C/%.0f°F RH %.0f%% wind %s kt %.0f hPa%s", o.station(), meshconsole.ui.FmtBridge.time(o.time()), o.tempC(), o.tempC() * 9 / 5 + 32, o.humidityPct(),
                        Double.isNaN(o.windKt()) ? "?" : String.valueOf((int) o.windKt()), o.pressureHpa(), o.wx().isEmpty() ? "" : " " + o.wx());
            }
            default -> { return "Unknown '" + parts[0] + "'. Send ? for commands."; }
        }
    }

    private String nodesLine() {
        long cutoff = System.currentTimeMillis() - 3600_000L;
        List<String> names = new ArrayList<>();
        for (NodeEntry n : state.nodes()) if (n.num != state.myNodeNum() && n.lastHeardMillis() > cutoff) names.add(n.shortLabel());
        if (names.isEmpty()) return "Nobody heard in the last hour.";
        String s = names.size() + " nodes/1h: " + String.join(" ", names);
        return s.length() > 180 ? s.substring(0, 177) + "…" : s;
    }

    private BbsStore.Post post(String arg) {
        try { int id = Integer.parseInt(arg.replace("#", "").trim()); for (BbsStore.Post p : store.posts) if (p.id == id) return p; } catch (NumberFormatException ignored) { }
        return null;
    }

    private Integer resolve(String who) {
        if (who.startsWith("!")) { try { return (int) Long.parseLong(who.substring(1), 16); } catch (NumberFormatException e) { return null; } }
        for (NodeEntry n : state.nodes()) if (n.shortName.equalsIgnoreCase(who) || n.longName.equalsIgnoreCase(who)) return n.num;
        for (NodeEntry n : state.nodes()) if (n.longName.toLowerCase().startsWith(who.toLowerCase())) return n.num;
        return null;
    }

    private static String shortName(String s) { return s.length() > 12 ? s.substring(0, 12) : s; }

    // ---- mail delivery ------------------------------------------------------

    private void deliverPendingMail() {
        if (!store.enabled || store.mail.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastMailSweep < 30_000) return;
        lastMailSweep = now;
        for (NodeEntry n : state.nodes()) {
            if (now - n.lastLocalRx > 10 * 60_000L || n.num == state.myNodeNum()) continue;
            int count = 0;
            for (BbsStore.Mail x : store.mail) if (x.to == n.num && x.delivered == 0) count++;
            if (count == 0) continue;
            Long last = lastReply.get(n.num);
            if (last != null && now - last < 5 * 60_000L) continue;
            send(n.num, "You have " + count + " mail at " + store.name + ". DM me M to read.");
            for (BbsStore.Mail x : store.mail) if (x.to == n.num && x.delivered == 0) x.delivered = -1;   // notified, not yet read
            store.save();
        }
    }
}
