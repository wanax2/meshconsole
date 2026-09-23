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
    public static class Caller { public int num; public String name = ""; public long first, last; public int commands; public String lastCommand = ""; }
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
    public long commandsServed, repliesSent, started;
    private int nextId = 1;
    private final Path file;

    public BbsStore(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m)) return;
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
            callsToday = (long) Json.num(m.get("callsToday"), 0); callsDay = Json.str(m.get("callsDay"));
            if (m.get("callers") instanceof List<?> cs) for (Object o : cs) if (o instanceof Map<?, ?> p) {
                Caller x = new Caller(); x.num = (int) (long) Json.num(p.get("num"), 0); x.name = Json.str(p.get("name")); x.first = (long) Json.num(p.get("first"), 0); x.last = (long) Json.num(p.get("last"), 0);
                x.commands = (int) Json.num(p.get("commands"), 0); x.lastCommand = Json.str(p.get("lastCommand")); if (x.num != 0) callers.put(x.num, x);
            }
            if (m.get("mail") instanceof List<?> ms) for (Object o : ms) if (o instanceof Map<?, ?> p) {
                Mail x = new Mail(); x.id = (int) Json.num(p.get("id"), 0); x.time = (long) Json.num(p.get("time"), 0); x.delivered = (long) Json.num(p.get("delivered"), 0);
                x.from = (int) (long) Json.num(p.get("from"), 0); x.to = (int) (long) Json.num(p.get("to"), 0); x.fromName = Json.str(p.get("fromName")); x.text = Json.str(p.get("text")); mail.add(x);
            }
        } catch (RuntimeException | IOException ignored) { }
    }

    public synchronized int nextId() { return nextId++; }

    public synchronized void save() {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append(kv("name", name)).append(kv("welcome", welcome)).append(kv("enabled", enabled)).append(kv("channelTrigger", channelTrigger)).append(kv("triggerWord", triggerWord))
          .append(kv("maxPosts", maxPosts)).append(kv("cooldownSec", cooldownSec)).append(kv("commandsServed", commandsServed)).append(kv("repliesSent", repliesSent)).append(kv("nextId", nextId)).append(kv("callsToday", callsToday)).append(kv("callsDay", callsDay));
        sb.append("\"banned\":[");
        boolean f = true; for (int b : banned) { if (!f) sb.append(','); f = false; sb.append(Integer.toUnsignedLong(b)); }
        sb.append("],\n\"posts\":[\n");
        f = true;
        for (Post p : posts) { if (!f) sb.append(",\n"); f = false; sb.append("  {").append(kv("id", p.id)).append(kv("time", p.time)).append("\"author\":").append(Integer.toUnsignedLong(p.author)).append(',').append(kv("authorName", p.authorName)).append(kv("text", p.text)); sb.setLength(sb.length() - 1); sb.append('}'); }
        sb.append("\n],\n\"callers\":[\n");
        f = true;
        for (Caller c : callers.values()) { if (!f) sb.append(",\n"); f = false; sb.append("  {\"num\":").append(Integer.toUnsignedLong(c.num)).append(',').append(kv("name", c.name)).append(kv("first", c.first)).append(kv("last", c.last)).append(kv("commands", c.commands)).append(kv("lastCommand", c.lastCommand)); sb.setLength(sb.length() - 1); sb.append('}'); }
        sb.append("\n],\n\"mail\":[\n");
        f = true;
        for (Mail m : mail) { if (!f) sb.append(",\n"); f = false; sb.append("  {").append(kv("id", m.id)).append(kv("time", m.time)).append(kv("delivered", m.delivered)).append("\"from\":").append(Integer.toUnsignedLong(m.from)).append(",\"to\":").append(Integer.toUnsignedLong(m.to)).append(',').append(kv("fromName", m.fromName)).append(kv("text", m.text)); sb.setLength(sb.length() - 1); sb.append('}'); }
        sb.append("\n]\n}\n");
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    private static String kv(String k, Object v) {
        String val = v instanceof String s ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" : String.valueOf(v);
        return "\"" + k + "\":" + val + ",";
    }
}
