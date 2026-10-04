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
        if (!store.enabled) return;
        String today = java.time.LocalDate.now().toString();
        java.time.LocalTime now = java.time.LocalTime.now();
        if (due(now, store.digestTime) && !today.equals(store.digestDay) && !store.subs.isEmpty()) { store.digestDay = today; store.save(); sendDigest(); }
        if (store.netOn && due(now, store.netTime) && !today.equals(store.netDay) && !store.peers.isEmpty()) { store.netDay = today; store.save(); int n = netExchangeNow(); log("MeshNet daily exchange: " + n + " post(s) sent"); }
        if (System.currentTimeMillis() - lastDiscordPoll > 5 * 60_000L) { lastDiscordPoll = System.currentTimeMillis(); pollDiscord(); }
        if (!store.advertOn) return;
        if (due(now, store.advertTime) && !today.equals(store.advertDay)) {
            store.advertDay = today; store.save();
            String text = store.advertText.isBlank() ? store.defaultAdvert() : store.advertText;
            try { client.sendText(text.length() > 190 ? text.substring(0, 190) : text, ChatMessage.BROADCAST, 0); log("advert sent: " + text); }
            catch (IOException e) { log("advert failed: " + e.getMessage()); }
        }
    }

    private static boolean due(java.time.LocalTime now, String hhmm) {
        try { java.time.LocalTime t = java.time.LocalTime.parse(hhmm.trim()); return now.getHour() == t.getHour() && now.getMinute() == t.getMinute(); } catch (Exception e) { return false; }
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
        // MeshNet traffic from a configured peer board is protocol, not a caller
        if (dm && text.startsWith("NET ") && isPeer(m.from)) { handleNet(m.from, text.substring(4)); return; }
        BbsStore.Caller cl = store.callers.computeIfAbsent(m.from, k -> { BbsStore.Caller x = new BbsStore.Caller(); x.num = k; x.first = now; return x; });
        cl.name = state.nodeName(m.from); cl.last = now; cl.commands++; cl.lastCommand = text.length() > 40 ? text.substring(0, 40) : text;
        String today = java.time.LocalDate.now().toString();
        if (!today.equals(store.callsDay)) { store.callsDay = today; store.callsToday = 0; }
        store.callsToday++;
        store.callsPerDay.merge(today, 1, Integer::sum);
        store.cmdCounts.merge(text.split("\\s+")[0].toUpperCase(Locale.ROOT), 1, Integer::sum);
        store.hourCounts[java.time.LocalTime.now().getHour()]++;
        log("← " + cl.name + ": " + text);
        listener.onCaller(m.from, text);
        String reply;
        try { reply = dm ? command(m.from, text) : store.name + " here – DM me ? for commands"; }
        catch (RuntimeException e) { reply = "error: " + e.getMessage(); }
        if (reply == null) { store.commandsServed++; store.save(); return; }   // answered asynchronously
        if (!store.motd.isBlank() && !today.equals(cl.lastMotdDay) && text.trim().startsWith("?")) { cl.lastMotdDay = today; reply = "MOTD: " + store.motd + "\n" + reply; }
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
            case "?", "H", "HELP", "MENU" -> { return store.name + " menu: B R P D | M mail | S T N STATS TOP WHO | W TAF METRO | E events, POLL, GB guestbook, SUB | TRIVIA GUESS ADV | NET | I PING (?2 = details)"; }
            case "?2", "HELP2" -> { return "B list(B 2 next) R 3 read P text post D 3 del | M mail, M bob hi, M board!bob hi | S signal T trace N nodes STATS TOP WHO n | W TAF METRO C call | E events POLL/V n GB text SUB WX|EVENTS | TRIVIA(A ans) GUESS(G n) ADV | NET peers"; }
            case "PING" -> { return "PONG from " + store.name; }
            case "I", "INFO" -> {
                long up = (System.currentTimeMillis() - store.started) / 60000;
                return store.name + " on " + state.nodeName(state.myNodeNum()) + ". " + store.posts.size() + " posts, " + store.mail.size() + " mail, " + store.commandsServed + " cmds, up " + up / 60 + "h" + up % 60 + "m. " + store.welcome;
            }
            case "B", "LIST" -> {
                if (store.posts.isEmpty()) return "No bulletins yet. P text to post.";
                BbsStore.Caller cl = store.callers.get(from);
                int newer = 0; for (BbsStore.Post p : store.posts) if (cl != null && p.id > cl.lastPostSeen) newer++;
                String newNote = cl != null && cl.lastPostSeen > 0 && newer > 0 ? newer + " new since your last visit. " : "";
                if (cl != null) cl.lastPostSeen = store.posts.isEmpty() ? 0 : store.posts.get(store.posts.size() - 1).id;
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
                return (page == 1 ? newNote : "") + sb.toString().trim();
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
                if (mp[0].contains("!") && !mp[0].startsWith("!")) {   // board!user → route via MeshNet
                    String[] bu = mp[0].split("!", 2); BbsStore.Peer pr = peerByName(bu[0]);
                    if (pr == null) return "No MeshNet peer called '" + bu[0] + "'. NET lists them.";
                    send(pr.num, "NET MAIL " + bu[1] + " " + who + ": " + mp[1]);
                    log("mail routed to " + pr.name + " for " + bu[1]);
                    return "Mail handed to " + pr.name + " for " + bu[1] + ".";
                }
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
                store.trivia.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> sb.append("trivia champ ").append(shortName(state.nodeName(e.getKey()))).append(" (").append(e.getValue()).append(" pts); "));
                NodeEntry farCaller = null; for (BbsStore.Caller c : store.callers.values()) { NodeEntry n = state.node(c.num); if (n != null && me != null && me.hasPosition && n.hasPosition && (farCaller == null || meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, n.lat, n.lon) > meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, farCaller.lat, farCaller.lon))) farCaller = n; }
                if (farCaller != null) sb.append("farthest caller ").append(farCaller.shortLabel()).append(String.format(" %.0f km; ", meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, farCaller.lat, farCaller.lon)));
                ChatMessage farDm = null; for (ChatMessage cm : state.messages()) if (!cm.outgoing && !cm.isBroadcast() && cm.hops >= 0 && (farDm == null || cm.hops > farDm.hops)) farDm = cm;
                if (farDm != null) sb.append("most-hops DM ").append(shortName(state.nodeName(farDm.from))).append(" (").append(farDm.hops).append(")");
                return sb.toString();
            }
            case "SUB", "SUBSCRIBE" -> {
                String what = arg.trim().toUpperCase();
                if (!what.equals("WX") && !what.equals("EVENTS")) { Set<String> cur = store.subs.getOrDefault(from, Set.of()); return "SUB WX = daily weather at " + store.digestTime + ", SUB EVENTS = new events. UNSUB to stop. You have: " + (cur.isEmpty() ? "none" : String.join(",", cur)); }
                store.subs.computeIfAbsent(from, k -> new HashSet<>()).add(what);
                return "Subscribed to " + what + ". Daily at " + store.digestTime + ". UNSUB to stop.";
            }
            case "UNSUB" -> { store.subs.remove(from); return "Unsubscribed from everything."; }
            case "WHO" -> {
                if (arg.isEmpty()) return "WHO name – look up a node (short name, long name or !id)";
                Integer num = resolve(arg.trim());
                NodeEntry n = num == null ? null : state.node(num);
                if (n == null) return "Don't know '" + arg + "'.";
                int mail = 0; for (BbsStore.Mail x : store.mail) if (x.to == n.num) mail++;
                NodeEntry me = state.myNode();
                String dist = (me != null && me.hasPosition && n.hasPosition) ? String.format(", %.1f km", meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, n.lat, n.lon)) : "";
                return String.format("%s %s: %s, heard %s ago, %s, SNR %.1f/RSSI %d%s, batt %s%s", n.displayName(), n.idString(), n.hwModel, meshconsole.ui.FmtBridge.ago(n.lastHeardMillis()), n.hopsAway < 0 ? "hops ?" : n.hopsAway == 0 ? "direct" : n.hopsAway + " hops", n.snr, n.rssi, dist, n.battery < 0 ? "?" : n.battery > 100 ? "ext" : n.battery + "%", mail > 0 ? ", " + mail + " mail waiting here" : "");
            }
            case "POLL" -> {
                if (store.pollQuestion.isBlank()) return "No poll open.";
                StringBuilder sb = new StringBuilder("POLL: " + store.pollQuestion + "\n");
                int[] tally = new int[store.pollOptions.size()];
                for (int v : store.pollVotes.values()) if (v >= 0 && v < tally.length) tally[v]++;
                for (int i = 0; i < store.pollOptions.size(); i++) sb.append(i + 1).append(") ").append(store.pollOptions.get(i)).append(" [").append(tally[i]).append("] ");
                sb.append("– V n to vote");
                return sb.toString();
            }
            case "V", "VOTE" -> {
                if (store.pollQuestion.isBlank()) return "No poll open.";
                int v; try { v = Integer.parseInt(arg.trim()) - 1; } catch (NumberFormatException e) { return "V n – vote for option n (POLL to see them)"; }
                if (v < 0 || v >= store.pollOptions.size()) return "Options are 1–" + store.pollOptions.size();
                store.pollVotes.put(from, v);
                return "Vote recorded: " + store.pollOptions.get(v) + ". POLL for the tally.";
            }
            case "GB", "GUESTBOOK" -> {
                if (arg.isEmpty()) {
                    if (store.guestbook.isEmpty()) return "Guestbook is empty. GB your message to sign it.";
                    StringBuilder sb = new StringBuilder();
                    List<BbsStore.GuestEntry> g = new ArrayList<>(store.guestbook); Collections.reverse(g);
                    for (BbsStore.GuestEntry e : g) { String line = shortName(e.name) + ": " + e.text + "\n"; if (sb.length() + line.length() > 190) break; sb.append(line); }
                    return sb.toString().trim();
                }
                BbsStore.GuestEntry e = new BbsStore.GuestEntry(); e.time = System.currentTimeMillis(); e.num = from; e.name = who; e.text = arg.length() > 80 ? arg.substring(0, 80) : arg;
                store.guestbook.add(e); while (store.guestbook.size() > 200) store.guestbook.remove(0);
                bridge("✍️ guestbook – " + who + ": " + e.text);
                return "Signed. GB to read the book.";
            }
            case "SYSOP" -> {
                if (store.sysopPassword.isBlank()) return "Remote admin is not enabled.";
                if (!arg.equals(store.sysopPassword)) { log("bad sysop password from " + who); return "No."; }
                store.admins.put(from, System.currentTimeMillis() + 3600_000L);
                return "Sysop mode for 1 h. X POST text, X DEL n, X CALLERS, X BAN name, X UNBAN name, X MOTD text, X ADVERT, X STATS, X NET";
            }
            case "X" -> {
                Long until = store.admins.get(from);
                if (until == null || until < System.currentTimeMillis()) return "Not in sysop mode (SYSOP password).";
                String[] xp = arg.split("\\s+", 2); String sub = xp[0].toUpperCase(); String xa = xp.length > 1 ? xp[1] : "";
                switch (sub) {
                    case "POST" -> { BbsStore.Post p = new BbsStore.Post(); p.id = store.nextId(); p.time = System.currentTimeMillis(); p.author = state.myNodeNum(); p.authorName = "sysop"; p.text = xa; store.posts.add(p); bridge("📌 sysop: " + xa); return "Posted #" + p.id; }
                    case "DEL" -> { BbsStore.Post p = post(xa); if (p == null) return "No such post."; store.posts.remove(p); return "Deleted #" + p.id; }
                    case "CALLERS" -> { List<BbsStore.Caller> l = new ArrayList<>(store.callers.values()); l.sort((a, b) -> Long.compare(b.last, a.last)); StringBuilder sb = new StringBuilder(); for (BbsStore.Caller c : l) { String line = shortName(c.name) + " " + c.commands + "x " + meshconsole.ui.FmtBridge.ago(c.last) + "; "; if (sb.length() + line.length() > 190) break; sb.append(line); } return sb.length() == 0 ? "No callers yet." : sb.toString(); }
                    case "BAN" -> { Integer n = resolve(xa); if (n == null) return "Unknown node."; store.banned.add(n); return "Banned " + state.nodeName(n); }
                    case "UNBAN" -> { Integer n = resolve(xa); if (n == null) return "Unknown node."; store.banned.remove(n); return "Unbanned " + state.nodeName(n); }
                    case "MOTD" -> { store.motd = xa; return xa.isBlank() ? "MOTD cleared." : "MOTD set."; }
                    case "ADVERT" -> { advertiseNow(); return "Advert sent."; }
                    case "STATS" -> { return stateLine(); }
                    case "NET" -> { int sent = netExchangeNow(); return "MeshNet exchange started with " + store.peers.size() + " peer(s), " + sent + " post(s) queued."; }
                    default -> { return "X POST|DEL|CALLERS|BAN|UNBAN|MOTD|ADVERT|STATS|NET"; }
                }
            }
            case "NET" -> {
                if (store.peers.isEmpty()) return "This board is not networked yet.";
                StringBuilder sb = new StringBuilder("MeshNet peers: ");
                for (BbsStore.Peer pr : store.peers) sb.append(pr.name.isEmpty() ? state.nodeName(pr.num) : pr.name).append(pr.lastExchange > 0 ? " (" + meshconsole.ui.FmtBridge.ago(pr.lastExchange) + " ago)" : " (never)").append("; ");
                return sb.append("Posts from peers show 'via <board>'. M board!name text mails a user on a peer.").toString();
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

    private boolean isPeer(int num) { for (BbsStore.Peer p : store.peers) if (p.num == num) return true; return false; }
    private BbsStore.Peer peerByName(String name) {
        String q = name.toLowerCase();
        for (BbsStore.Peer p : store.peers) if (p.name.equalsIgnoreCase(name) || state.nodeName(p.num).equalsIgnoreCase(name)) return p;
        for (BbsStore.Peer p : store.peers) { NodeEntry n = state.node(p.num); if (p.name.toLowerCase().startsWith(q) || state.nodeName(p.num).toLowerCase().startsWith(q) || (n != null && n.shortName.equalsIgnoreCase(name))) return p; }
        return null;
    }

    /**
     * MeshNet: WWIVnet-style store-and-forward between boards, over DMs. Lines (each ≤ 200 bytes):
     *   NET HELLO <board name>                       – announce / handshake
     *   NET POST <id> <author>: <text>               – a bulletin headline from the peer (deduped by peer:id)
     *   NET MAIL <user> <from>: <text>               – mail for a local user, routed from the peer
     *   NET ACK <id>                                 – delivery confirmation for a POST
     */
    private void handleNet(int peer, String line) {
        BbsStore.Peer pr = null; for (BbsStore.Peer p : store.peers) if (p.num == peer) pr = p;
        if (pr == null) return;
        String[] parts = line.split("\\s+", 2); String op = parts[0].toUpperCase(); String rest = parts.length > 1 ? parts[1] : "";
        switch (op) {
            case "HELLO" -> { if (pr.name.isEmpty() && !rest.isBlank()) pr.name = rest.trim(); pr.lastExchange = System.currentTimeMillis(); log("MeshNet hello from " + pr.name); }
            case "POST" -> {
                String[] pp = rest.split("\\s+", 2); if (pp.length < 2) return;
                String key = Integer.toUnsignedString(peer) + ":" + pp[0];
                if (store.netSeen.add(key)) {
                    int colon = pp[1].indexOf(':');
                    BbsStore.Post p = new BbsStore.Post(); p.id = store.nextId(); p.time = System.currentTimeMillis(); p.author = peer;
                    p.authorName = (colon > 0 ? pp[1].substring(0, colon).trim() : pr.name) + " via " + (pr.name.isEmpty() ? state.nodeName(peer) : pr.name);
                    p.text = colon > 0 ? pp[1].substring(colon + 1).trim() : pp[1];
                    store.posts.add(p); while (store.posts.size() > store.maxPosts) store.posts.remove(0);
                    log("MeshNet post from " + pr.name + ": " + p.text);
                    bridge("🌐 " + p.authorName + ": " + p.text);
                }
                send(peer, "NET ACK " + pp[0]);
                pr.lastExchange = System.currentTimeMillis();
            }
            case "MAIL" -> {
                String[] mp = rest.split("\\s+", 2); if (mp.length < 2) return;
                Integer to = resolve(mp[0]);
                int colon = mp[1].indexOf(':');
                BbsStore.Mail x = new BbsStore.Mail(); x.id = store.nextId(); x.time = System.currentTimeMillis(); x.from = peer; x.fromName = (colon > 0 ? mp[1].substring(0, colon).trim() : "?") + "@" + (pr.name.isEmpty() ? state.nodeName(peer) : pr.name); x.text = colon > 0 ? mp[1].substring(colon + 1).trim() : mp[1];
                if (to == null) { log("MeshNet mail for unknown user " + mp[0] + " – dropped"); return; }
                x.to = to; store.mail.add(x); log("MeshNet mail from " + pr.name + " for " + state.nodeName(to));
            }
            case "ACK" -> { pr.lastExchange = System.currentTimeMillis(); }
            default -> { }
        }
        store.save();
    }

    /** Sends each peer the local posts it hasn't had yet (max 5 per exchange, oldest first) and a HELLO. Returns posts queued. */
    public int netExchangeNow() {
        int queued = 0;
        for (BbsStore.Peer pr : store.peers) {
            send(pr.num, "NET HELLO " + store.name);
            int n = 0;
            for (BbsStore.Post p : store.posts) {
                if (p.id <= pr.lastPostSent || p.author == pr.num || p.authorName.contains(" via ")) continue;   // don't echo a peer's own posts back
                String text = "NET POST " + p.id + " " + shortName(p.authorName) + ": " + p.text;
                send(pr.num, text.length() > 195 ? text.substring(0, 195) : text);
                pr.lastPostSent = p.id; queued++; if (++n >= 5) break;
            }
        }
        store.save();
        return queued;
    }

    /** Daily subscriber digest: weather and new events, sent as DMs. */
    private void sendDigest() {
        WeatherObs o = weather == null ? null : weather.latest();
        String wx = o == null ? null : String.format("WX %s: %.0f°F RH %.0f%% wind %s kt %s", o.station(), o.tempC() * 9 / 5 + 32, o.humidityPct(), Double.isNaN(o.windKt()) ? "?" : String.valueOf((int) o.windKt()), o.wx());
        String ev = store.events.isEmpty() ? null : command(0, "E");
        for (Map.Entry<Integer, Set<String>> e : store.subs.entrySet()) {
            if (e.getValue().contains("WX") && wx != null) send(e.getKey(), wx);
            if (e.getValue().contains("EVENTS") && ev != null) send(e.getKey(), ev);
        }
        log("digest sent to " + store.subs.size() + " subscriber(s)");
    }

    /** Discord → board: new messages in the configured channel that start with !post become bulletins. Needs a bot token. */
    private void pollDiscord() {
        if (store.discordBotToken.isBlank() || store.discordChannelId.isBlank()) return;
        bg.submit(() -> {
            try {
                String url = "https://discord.com/api/v10/channels/" + store.discordChannelId + "/messages?limit=20" + (store.discordLastId.isBlank() ? "" : "&after=" + store.discordLastId);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
                c.setRequestProperty("Authorization", "Bot " + store.discordBotToken); c.setRequestProperty("User-Agent", "MeshConsole-BBS"); c.setConnectTimeout(6000); c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) { log("discord poll: HTTP " + c.getResponseCode()); return; }
                String body; try (java.io.InputStream in = c.getInputStream()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                Object root = meshconsole.analysis.Json.parse(body);
                if (!(root instanceof List<?> arr)) return;
                List<Map<?, ?>> msgs = new ArrayList<>(); for (Object o : arr) if (o instanceof Map<?, ?> mm) msgs.add(mm);
                Collections.reverse(msgs);   // oldest first
                for (Map<?, ?> mm : msgs) {
                    String id = meshconsole.analysis.Json.str(mm.get("id")); store.discordLastId = id;
                    String content = meshconsole.analysis.Json.str(mm.get("content"));
                    if (!content.toLowerCase().startsWith("!post ")) continue;
                    Map<?, ?> author = (Map<?, ?>) mm.get("author"); String name = author == null ? "discord" : meshconsole.analysis.Json.str(author.get("username"));
                    BbsStore.Post p = new BbsStore.Post(); p.id = store.nextId(); p.time = System.currentTimeMillis(); p.author = 0; p.authorName = name + " (discord)"; p.text = content.substring(6).trim();
                    if (p.text.length() > 180) p.text = p.text.substring(0, 180);
                    store.posts.add(p); log("post from discord by " + name + ": " + p.text);
                }
                store.save();
            } catch (Exception e) { log("discord poll failed: " + e.getMessage()); }
        });
    }

    private long lastDiscordPoll;

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
