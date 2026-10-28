package meshconsole.bbs;

import meshconsole.analysis.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** bbs.json — posts, mail, settings and a few counters. */
public class BbsStore {
    public static class Post { public int id; public long time; public int author; public String authorName = "", text = ""; }
    public static class Event { public int id; public long time, when; public String text = ""; }
    public final List<Event> events = new ArrayList<>();
    public final Map<Integer, Integer> trivia = new HashMap<>();      // node → points
    public final Map<Integer, Integer> guessTarget = new HashMap<>(), guessTries = new HashMap<>(), advRoom = new HashMap<>(), triviaQ = new HashMap<>();
    public final Map<Integer, Long> triviaAsked = new HashMap<>();
    public final Set<Integer> advHasSa = new HashSet<>();
    public String wmataKey = "", discordWebhook = "", station = "KDCA";
    public boolean advertOn = false; public String advertTime = "09:00", advertText = "", advertDay = "";
    public int advertIndex;
    public String defaultAdvert() { return name + " BBS is online: DM this node ? for bulletins, mail, weather, your signal report & games. 73"; }
    /** advertText may hold several messages separated by a line of "---"; one is sent per day, in rotation. */
    public java.util.List<String> advertVariants() {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (String part : advertText.split("(?m)^\\s*---\\s*$")) if (!part.isBlank()) l.add(part.trim().replaceAll("\\s*\\n\\s*", " "));
        if (l.isEmpty()) l.add(defaultAdvert());
        return l;
    }
    /** Shuffle: every advert once per cycle in random order (a "deck"), reshuffled when it runs out or the adverts change. */
    public boolean advertShuffle; public final java.util.List<Integer> advertBag = new java.util.ArrayList<>(); public int advertBagHash, advertLast = -1;

    public String nextAdvert() {
        java.util.List<String> l = advertVariants();
        if (!advertShuffle) { String a = l.get(Math.floorMod(advertIndex, l.size())); advertIndex++; return a; }
        int hash = l.hashCode();
        advertBag.removeIf(i -> i >= l.size());
        if (advertBag.isEmpty() || hash != advertBagHash) {
            advertBag.clear(); for (int i = 0; i < l.size(); i++) advertBag.add(i);
            java.util.Collections.shuffle(advertBag);
            if (advertBag.size() > 1 && advertBag.get(0) == advertLast) java.util.Collections.swap(advertBag, 0, 1);   // never the same one twice in a row across a reshuffle
            advertBagHash = hash;
        }
        advertLast = advertBag.remove(0);
        return l.get(advertLast);
    }
    // ---- 2.1 additions
    public final Map<String, Integer> callsPerDay = new TreeMap<>();      // yyyy-MM-dd → calls
    public final Map<String, Integer> cmdCounts = new TreeMap<>();        // command → count
    public final int[] hourCounts = new int[24];
    public final Map<Integer, Set<String>> subs = new HashMap<>();        // node → {WX, EVENTS}
    public String digestTime = "07:30", digestDay = "";
    public String pollQuestion = ""; public final List<String> pollOptions = new ArrayList<>(); public final Map<Integer, Integer> pollVotes = new HashMap<>();
    public String motd = "";
    public static class GuestEntry { public long time; public int num; public String name = "", text = ""; }
    public final List<GuestEntry> guestbook = new ArrayList<>();
    public String sysopPassword = ""; public final Map<Integer, Long> admins = new HashMap<>();
    public String discordBotToken = "", discordChannelId = "", discordLastId = "";
    public static class Peer { public int num; public String name = "", secret = ""; public long lastExchange; public int lastPostSent; public Set<String> caps = new HashSet<>(); }
    public static class Outbox { public int peer; public String line, gid; public int attempts; public long last; }
    public final List<Outbox> outbox = new ArrayList<>();
    public int netMailSeq = 1;
    public final List<Peer> peers = new ArrayList<>(); public final Set<String> netSeen = new HashSet<>(); public String netTime = "04:00", netDay = "";
    public boolean netOn = false;
    public String avStations = "KDCA,KHEF", catStations = "KDCA,KHEF,KIAD,KBWI,KJYO,KADW", windsSites = "EMI,RIC,ACY";
    public boolean nwsBroadcast = false; public final Set<String> nwsSeen = new LinkedHashSet<>();
    /** Board profile: command groups switched off, trivia categories switched on (empty = all), region preset name. */
    public final Set<String> offGroups = new LinkedHashSet<>(), triviaCats = new LinkedHashSet<>(); public String region = "";
    public static class Caller { public int num; public String name = ""; public long first, last; public int commands; public String lastCommand = ""; public int lastPostSeen; public String lastMotdDay = ""; public int repliesSent, repliesAcked; }
    public final Map<Integer, Caller> callers = new LinkedHashMap<>();
    public long callsToday; public String callsDay = "";
    public static class Mail { public int id; public long time, delivered; public int from, to; public String fromName = "", text = ""; }

    public String name = "Lyon's Den", welcome = "The Lyon's Den – Lyon Village, Arlington VA 22201. Leave a bulletin or mail; send ? for commands. 73";
    public boolean enabled = false, channelTrigger = false;
    public String triggerWord = "bbs?";
    public int maxPosts = 200, cooldownSec = 20;
    public final Set<Integer> banned = new HashSet<>();
    public final List<Post> posts = new ArrayList<>();
    public final List<Mail> mail = new ArrayList<>();
    public long commandsServed, repliesSent, repliesAcked, started;
    private int nextId = 1;
    private final Path file;

    /** True when the file existed but could not be read; save() then refuses to overwrite it with defaults. */
    public boolean loadFailed;

    public BbsStore(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Object root;
            try { root = Json.parse(text); }
            catch (RuntimeException bad) {
                // repair the known 1.8.0–1.8.6 fault (double commas) and try once more
                String fixed = text.replace(",,", ",").replace(",}", "}").replace(",]", "]");
                root = Json.parse(fixed);
            }
            if (!(root instanceof Map<?, ?> m)) { loadFailed = true; quarantine(); return; }
            if (m.get("name") != null) name = Json.str(m.get("name"));
            if (m.get("welcome") != null) welcome = Json.str(m.get("welcome"));
            enabled = Boolean.TRUE.equals(m.get("enabled")); channelTrigger = Boolean.TRUE.equals(m.get("channelTrigger"));
            if (m.get("triggerWord") != null) triggerWord = Json.str(m.get("triggerWord"));
            maxPosts = (int) Json.num(m.get("maxPosts"), 200); cooldownSec = (int) Json.num(m.get("cooldownSec"), 20);
            commandsServed = (long) Json.num(m.get("commandsServed"), 0); repliesSent = (long) Json.num(m.get("repliesSent"), 0);
            nextId = (int) Json.num(m.get("nextId"), 1);
            if (m.get("banned") instanceof List<?> b) for (Object o : b) banned.add((int) (long) Json.num(o, 0));
            if (m.get("posts") instanceof List<?> ps) for (Object o : ps) if (o instanceof Map<?, ?> p) {
                Post x = new Post(); x.id = (int) Json.num(p.get("id"), 0); x.time = (long) Json.num(p.get("time"), 0); x.author = (int) (long) Json.num(p.get("author"), 0);
                x.authorName = Json.str(p.get("authorName")); x.text = Json.str(p.get("text")); posts.add(x);
            }
            callsToday = (long) Json.num(m.get("callsToday"), 0); repliesAcked = (long) Json.num(m.get("repliesAcked"), 0); callsDay = Json.str(m.get("callsDay"));
            if (m.get("wmataKey") != null) wmataKey = Json.str(m.get("wmataKey"));
            if (m.get("discordWebhook") != null) discordWebhook = Json.str(m.get("discordWebhook"));
            if (m.get("station") != null) station = Json.str(m.get("station"));
            if (m.get("avStations") != null) avStations = Json.str(m.get("avStations"));
            if (m.get("catStations") != null) catStations = Json.str(m.get("catStations"));
            if (m.get("windsSites") != null) windsSites = Json.str(m.get("windsSites"));
            if (m.get("callsPerDay") instanceof Map<?, ?> cp) for (Map.Entry<?, ?> e : cp.entrySet()) callsPerDay.put(String.valueOf(e.getKey()), (int) Json.num(e.getValue(), 0));
            if (m.get("cmdCounts") instanceof Map<?, ?> cc) for (Map.Entry<?, ?> e : cc.entrySet()) cmdCounts.put(String.valueOf(e.getKey()), (int) Json.num(e.getValue(), 0));
            if (m.get("hourCounts") instanceof List<?> hc) for (int i = 0; i < Math.min(24, hc.size()); i++) hourCounts[i] = (int) Json.num(hc.get(i), 0);
            if (m.get("subs") instanceof Map<?, ?> sm) for (Map.Entry<?, ?> e : sm.entrySet()) { Set<String> set = new HashSet<>(); if (e.getValue() instanceof List<?> l) for (Object o : l) set.add(String.valueOf(o)); try { subs.put((int) Long.parseLong(String.valueOf(e.getKey())), set); } catch (NumberFormatException ignored) { } }
            if (m.get("digestTime") != null) digestTime = Json.str(m.get("digestTime")); if (m.get("digestDay") != null) digestDay = Json.str(m.get("digestDay"));
            if (m.get("pollQuestion") != null) pollQuestion = Json.str(m.get("pollQuestion"));
            if (m.get("pollOptions") instanceof List<?> po) for (Object o : po) pollOptions.add(String.valueOf(o));
            if (m.get("pollVotes") instanceof Map<?, ?> pv) for (Map.Entry<?, ?> e : pv.entrySet()) try { pollVotes.put((int) Long.parseLong(String.valueOf(e.getKey())), (int) Json.num(e.getValue(), 0)); } catch (NumberFormatException ignored) { }
            if (m.get("motd") != null) motd = Json.str(m.get("motd"));
            if (m.get("guestbook") instanceof List<?> gb) for (Object o : gb) if (o instanceof Map<?, ?> g) { GuestEntry x = new GuestEntry(); x.time = (long) Json.num(g.get("time"), 0); x.num = (int) (long) Json.num(g.get("num"), 0); x.name = Json.str(g.get("name")); x.text = Json.str(g.get("text")); guestbook.add(x); }
            if (m.get("sysopPassword") != null) sysopPassword = Json.str(m.get("sysopPassword"));
            if (m.get("discordBotToken") != null) discordBotToken = Json.str(m.get("discordBotToken")); if (m.get("discordChannelId") != null) discordChannelId = Json.str(m.get("discordChannelId")); if (m.get("discordLastId") != null) discordLastId = Json.str(m.get("discordLastId"));
            if (m.get("peers") instanceof List<?> ps2) for (Object o : ps2) if (o instanceof Map<?, ?> g) { Peer x = new Peer(); x.num = (int) (long) Json.num(g.get("num"), 0); x.name = Json.str(g.get("name")); x.secret = Json.str(g.get("secret")); x.lastExchange = (long) Json.num(g.get("lastExchange"), 0); x.lastPostSent = (int) Json.num(g.get("lastPostSent"), 0); if (g.get("caps") instanceof List<?> cl) for (Object c : cl) x.caps.add(String.valueOf(c)); if (x.num != 0) peers.add(x); }
            if (m.get("outbox") instanceof List<?> ob) for (Object o : ob) if (o instanceof Map<?, ?> g) { Outbox x = new Outbox(); x.peer = (int) (long) Json.num(g.get("peer"), 0); x.line = Json.str(g.get("line")); x.gid = Json.str(g.get("gid")); x.attempts = (int) Json.num(g.get("attempts"), 0); x.last = (long) Json.num(g.get("last"), 0); outbox.add(x); }
            netMailSeq = (int) Json.num(m.get("netMailSeq"), 1);
            if (m.get("netSeen") instanceof List<?> ns) for (Object o : ns) netSeen.add(String.valueOf(o));
            if (m.get("netTime") != null) netTime = Json.str(m.get("netTime")); if (m.get("netDay") != null) netDay = Json.str(m.get("netDay"));
            netOn = Boolean.TRUE.equals(m.get("netOn")); nwsBroadcast = Boolean.TRUE.equals(m.get("nwsBroadcast"));
            if (m.get("nwsSeen") instanceof List<?> nl) for (Object o : nl) nwsSeen.add(String.valueOf(o));
            if (m.get("offGroups") instanceof List<?> og) for (Object o : og) offGroups.add(String.valueOf(o));
            if (m.get("triviaCats") instanceof List<?> tc) for (Object o : tc) triviaCats.add(String.valueOf(o));
            if (m.get("region") != null) region = Json.str(m.get("region"));
            advertOn = Boolean.TRUE.equals(m.get("advertOn")); advertIndex = (int) Json.num(m.get("advertIndex"), 0); advertShuffle = Boolean.TRUE.equals(m.get("advertShuffle")); advertBagHash = (int) Json.num(m.get("advertBagHash"), 0); advertLast = (int) Json.num(m.get("advertLast"), -1);
            if (m.get("advertBag") instanceof List<?> ab) for (Object o : ab) advertBag.add((int) Json.num(o, 0)); if (m.get("advertTime") != null) advertTime = Json.str(m.get("advertTime")); if (m.get("advertText") != null) advertText = Json.str(m.get("advertText")); if (m.get("advertDay") != null) advertDay = Json.str(m.get("advertDay"));
            if (m.get("events") instanceof List<?> es) for (Object o : es) if (o instanceof Map<?, ?> p) { Event x = new Event(); x.id = (int) Json.num(p.get("id"), 0); x.time = (long) Json.num(p.get("time"), 0); x.when = (long) Json.num(p.get("when"), 0); x.text = Json.str(p.get("text")); events.add(x); }
            if (m.get("trivia") instanceof Map<?, ?> tm) for (Map.Entry<?, ?> e : tm.entrySet()) try { trivia.put((int) Long.parseLong(String.valueOf(e.getKey())), (int) Json.num(e.getValue(), 0)); } catch (NumberFormatException ignored) { }
            if (m.get("callers") instanceof List<?> cs) for (Object o : cs) if (o instanceof Map<?, ?> p) {
                Caller x = new Caller(); x.num = (int) (long) Json.num(p.get("num"), 0); x.name = Json.str(p.get("name")); x.first = (long) Json.num(p.get("first"), 0); x.last = (long) Json.num(p.get("last"), 0);
                x.commands = (int) Json.num(p.get("commands"), 0); x.lastCommand = Json.str(p.get("lastCommand")); x.lastPostSeen = (int) Json.num(p.get("lastPostSeen"), 0); x.lastMotdDay = Json.str(p.get("lastMotdDay")); x.repliesSent = (int) Json.num(p.get("repliesSent"), 0); x.repliesAcked = (int) Json.num(p.get("repliesAcked"), 0); if (x.num != 0) callers.put(x.num, x);
            }
            if (m.get("mail") instanceof List<?> ms) for (Object o : ms) if (o instanceof Map<?, ?> p) {
                Mail x = new Mail(); x.id = (int) Json.num(p.get("id"), 0); x.time = (long) Json.num(p.get("time"), 0); x.delivered = (long) Json.num(p.get("delivered"), 0);
                x.from = (int) (long) Json.num(p.get("from"), 0); x.to = (int) (long) Json.num(p.get("to"), 0); x.fromName = Json.str(p.get("fromName")); x.text = Json.str(p.get("text")); mail.add(x);
            }
        } catch (RuntimeException | IOException e) { loadFailed = true; quarantine(); }
    }

    public synchronized int nextId() { return nextId++; }

    private void quarantine() {
        try {
            Path keep = file.resolveSibling(file.getFileName() + ".broken-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.copy(file, keep, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    private static String list(String key, Collection<String> items) {
        StringBuilder b = new StringBuilder("\"" + key + "\":[");
        boolean first = true; for (String x : items) { if (!first) b.append(','); first = false; b.append('"').append(x.replace("\\", "\\\\").replace("\"", "\\\"")).append('"'); }
        return b.append("],\n").toString();
    }

    public synchronized void save() {
        if (loadFailed) return;   // never overwrite a file we couldn't read with defaults; the original is kept as bbs.json.broken-*
        StringBuilder sb = new StringBuilder("{\n");
        sb.append(kv("name", name)).append(kv("welcome", welcome)).append(kv("enabled", enabled)).append(kv("channelTrigger", channelTrigger)).append(kv("triggerWord", triggerWord))
          .append(kv("maxPosts", maxPosts)).append(kv("cooldownSec", cooldownSec)).append(kv("commandsServed", commandsServed)).append(kv("repliesSent", repliesSent)).append(kv("nextId", nextId)).append(kv("repliesAcked", repliesAcked)).append(kv("callsToday", callsToday)).append(kv("callsDay", callsDay)).append(kv("wmataKey", wmataKey)).append(kv("discordWebhook", discordWebhook)).append(kv("station", station)).append(kv("avStations", avStations)).append(kv("catStations", catStations)).append(kv("windsSites", windsSites)).append(kv("digestTime", digestTime)).append(kv("digestDay", digestDay)).append(kv("pollQuestion", pollQuestion)).append(kv("motd", motd)).append(kv("sysopPassword", sysopPassword)).append(kv("discordBotToken", discordBotToken)).append(kv("discordChannelId", discordChannelId)).append(kv("discordLastId", discordLastId)).append(kv("netTime", netTime)).append(kv("netDay", netDay)).append(kv("netOn", netOn)).append(kv("nwsBroadcast", nwsBroadcast)).append(kv("advertOn", advertOn)).append(kv("advertTime", advertTime)).append(kv("advertText", advertText)).append(kv("advertDay", advertDay)).append(kv("advertIndex", advertIndex)).append(kv("advertShuffle", advertShuffle)).append(kv("advertBagHash", advertBagHash)).append(kv("advertLast", advertLast));
        { StringBuilder b = new StringBuilder("\"advertBag\":["); for (int i = 0; i < advertBag.size(); i++) { if (i > 0) b.append(','); b.append(advertBag.get(i)); } sb.append(b).append("],\n"); }
        sb.append("\"callsPerDay\":").append(mapJson(callsPerDay)).append(",\"cmdCounts\":").append(mapJson(cmdCounts)).append(",\"hourCounts\":").append(Arrays.toString(hourCounts)).append(",\n");
        sb.append("\"subs\":{"); boolean sf = true; for (Map.Entry<Integer, Set<String>> e : subs.entrySet()) { if (!sf) sb.append(','); sf = false; sb.append('"').append(Integer.toUnsignedLong(e.getKey())).append("\":["); boolean ff = true; for (String v : e.getValue()) { if (!ff) sb.append(','); ff = false; sb.append('"').append(v).append('"'); } sb.append(']'); } sb.append("},\n");
        sb.append("\"pollOptions\":["); sf = true; for (String o : pollOptions) { if (!sf) sb.append(','); sf = false; sb.append(kv("x", o), 4, kv("x", o).length() - 1); } sb.append("],\"pollVotes\":{"); sf = true; for (Map.Entry<Integer, Integer> e : pollVotes.entrySet()) { if (!sf) sb.append(','); sf = false; sb.append('"').append(Integer.toUnsignedLong(e.getKey())).append("\":").append(e.getValue()); } sb.append("},\n");
        sb.append("\"guestbook\":["); sf = true; for (GuestEntry g : guestbook) { if (!sf) sb.append(','); sf = false; sb.append('{').append(kv("time", g.time)).append("\"num\":").append(Integer.toUnsignedLong(g.num)).append(',').append(kv("name", g.name)).append(kv("text", g.text)); sb.setLength(sb.length() - 1); sb.append('}'); } sb.append("],\n");
        sb.append("\"peers\":["); sf = true; for (Peer pr : peers) { if (!sf) sb.append(','); sf = false; sb.append("{\"num\":").append(Integer.toUnsignedLong(pr.num)).append(',').append(kv("name", pr.name)).append(kv("secret", pr.secret)).append(kv("lastExchange", pr.lastExchange)).append(kv("lastPostSent", pr.lastPostSent)).append("\"caps\":["); boolean cf = true; for (String c : pr.caps) { if (!cf) sb.append(','); cf = false; sb.append('"').append(c).append('"'); } sb.append("]}"); }
        sb.append("],\"outbox\":["); sf = true; for (Outbox ob : outbox) { if (!sf) sb.append(','); sf = false; sb.append("{\"peer\":").append(Integer.toUnsignedLong(ob.peer)).append(',').append(kv("line", ob.line)).append(kv("gid", ob.gid)).append(kv("attempts", ob.attempts)).append(kv("last", ob.last)); sb.setLength(sb.length() - 1); sb.append('}'); }
        sb.append("],").append(kv("netMailSeq", netMailSeq));
        sb.append("\"netSeen\":["); sf = true; for (String n : netSeen) { if (!sf) sb.append(','); sf = false; sb.append('"').append(n).append('"'); } sb.append("],\n");
        sb.append("\"nwsSeen\":["); boolean nf = true; for (String n : nwsSeen) { if (!nf) sb.append(','); nf = false; sb.append(kv("x", n), 4, kv("x", n).length() - 1); } sb.append("],\n");
        sb.append(list("offGroups", offGroups)).append(list("triviaCats", triviaCats)).append(kv("region", region));
        sb.append("\"trivia\":{");
        boolean tf = true; for (Map.Entry<Integer, Integer> e : trivia.entrySet()) { if (!tf) sb.append(','); tf = false; sb.append('"').append(Integer.toUnsignedLong(e.getKey())).append("\":").append(e.getValue()); }
        sb.append("},\n\"events\":[\n");
        tf = true;
        for (Event e : events) { if (!tf) sb.append(",\n"); tf = false; sb.append("  {").append(kv("id", e.id)).append(kv("time", e.time)).append(kv("when", e.when)).append(kv("text", e.text)); sb.setLength(sb.length() - 1); sb.append('}'); }
        sb.append("\n],\n");
        sb.append("\"banned\":[");
        boolean f = true; for (int b : banned) { if (!f) sb.append(','); f = false; sb.append(Integer.toUnsignedLong(b)); }
        sb.append("],\n\"posts\":[\n");
        f = true;
        for (Post p : posts) { if (!f) sb.append(",\n"); f = false; sb.append("  {").append(kv("id", p.id)).append(kv("time", p.time)).append("\"author\":").append(Integer.toUnsignedLong(p.author)).append(',').append(kv("authorName", p.authorName)).append(kv("text", p.text)); sb.setLength(sb.length() - 1); sb.append('}'); }
        sb.append("\n],\n\"callers\":[\n");
        f = true;
        for (Caller c : callers.values()) { if (!f) sb.append(",\n"); f = false; sb.append("  {\"num\":").append(Integer.toUnsignedLong(c.num)).append(',').append(kv("name", c.name)).append(kv("first", c.first)).append(kv("last", c.last)).append(kv("commands", c.commands)).append(kv("lastCommand", c.lastCommand)).append(kv("lastPostSeen", c.lastPostSeen)).append(kv("lastMotdDay", c.lastMotdDay)).append(kv("repliesSent", c.repliesSent)).append(kv("repliesAcked", c.repliesAcked)); sb.setLength(sb.length() - 1); sb.append('}'); }
        sb.append("\n],\n\"mail\":[\n");
        f = true;
        for (Mail m : mail) { if (!f) sb.append(",\n"); f = false; sb.append("  {").append(kv("id", m.id)).append(kv("time", m.time)).append(kv("delivered", m.delivered)).append("\"from\":").append(Integer.toUnsignedLong(m.from)).append(",\"to\":").append(Integer.toUnsignedLong(m.to)).append(',').append(kv("fromName", m.fromName)).append(kv("text", m.text)); sb.setLength(sb.length() - 1); sb.append('}'); }
        sb.append("\n]\n}\n");
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);   // one generation back
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    private static String mapJson(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder("{"); boolean f = true;
        for (Map.Entry<String, Integer> e : m.entrySet()) { if (!f) sb.append(','); f = false; String kv = kv(e.getKey(), e.getValue()); sb.append(kv, 0, kv.length() - 1); }   // kv() ends with a comma
        return sb.append('}').toString();
    }

    private static String kv(String k, Object v) {
        String val = v instanceof String s ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" : String.valueOf(v);
        return "\"" + k + "\":" + val + ",";
    }
}
