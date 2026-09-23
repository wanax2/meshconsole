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
    public interface Listener { void onActivity(String line); }

    private static final int MAX_BYTES = 200;
    private final MeshClient client;
    private final MeshState state;
    private final BbsStore store;
    private final Map<Integer, Long> lastReply = new HashMap<>();
    private final Deque<String> activity = new ArrayDeque<>();
    private WeatherHistory weather;
    private Listener listener = l -> { };
    private long lastMailSweep;

    public BbsEngine(MeshClient client, BbsStore store) {
        this.client = client;
        this.state = client.state();
        this.store = store;
        store.started = System.currentTimeMillis();
        state.addListener(new MeshState.Listener() {
            @Override public void onMessage(ChatMessage m, boolean isNew) { if (isNew && !m.outgoing) handle(m); }
            @Override public void onNodesChanged() { deliverPendingMail(); }
        });
    }

    public BbsStore store() { return store; }
    public void setWeather(WeatherHistory w) { weather = w; }
    public void setListener(Listener l) { listener = l; }
    public List<String> activity() { synchronized (activity) { return new ArrayList<>(activity); } }

    private void log(String s) {
        String line = meshconsole.ui.FmtBridge.time(System.currentTimeMillis()) + "  " + s;
        synchronized (activity) { activity.addLast(line); while (activity.size() > 500) activity.removeFirst(); }
        listener.onActivity(line);
        state.emitLog("[bbs] " + s);
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
        String reply;
        try { reply = dm ? command(m.from, text) : store.name + " here – DM me ? for commands"; }
        catch (RuntimeException e) { reply = "error: " + e.getMessage(); }
        if (reply == null) return;
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
            case "?", "H", "HELP", "MENU" -> { return store.name + ": B=bulletins R n=read P text=post D n=delete mine M=mail M name text=send mail N=nodes S=your signal W=weather I=info PING"; }
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
                return "Posted #" + p.id + ". B to list.";
            }
            case "D", "DEL", "DELETE" -> {
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
            case "N", "NODES" -> {
                long cutoff = System.currentTimeMillis() - 3600_000L;
                List<String> names = new ArrayList<>();
                for (NodeEntry n : state.nodes()) if (n.num != state.myNodeNum() && n.lastHeardMillis() > cutoff) names.add(n.shortLabel());
                if (names.isEmpty()) return "Nobody heard in the last hour.";
                String s = names.size() + " nodes/1h: " + String.join(" ", names);
                return s.length() > 180 ? s.substring(0, 177) + "…" : s;
            }
            case "S", "SIG", "SIGNAL" -> {
                NodeEntry n = state.node(from);
                if (n == null || n.rssiCount == 0) return "Haven't measured you yet.";
                return String.format("You at %s: last %d dBm / %.1f dB, %s, avg %.0f dBm over %d pkts", store.name, n.rssi, n.snr, n.hopsAway == 0 ? "direct" : n.hopsAway + " hop" + (n.hopsAway == 1 ? "" : "s"), n.avgRssi(), n.rssiCount);
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
