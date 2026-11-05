package meshconsole.bbs;

import meshconsole.analysis.Json;
import meshconsole.mesh.ChatMessage;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The community side of the BBS: a weekly check-in net, Metro alerts by line, satellite passes, MAP, a welcome for
 * first-time callers, a daily word game, and the advert → callers report. Settings in bbs-extra.properties, logs in
 * advert_log.csv, callers_log.csv and net_log.csv (all in the data folder).
 */
public final class BbsCommunity {
    final Properties p = new Properties();
    private final BbsEngine engine;
    private final MeshState state;
    private final Path dir, file;
    private final Map<Integer, Checkin> checkins = new LinkedHashMap<>();
    private String netDayKey = "";            // date of the net the current check-ins belong to
    private boolean netOpened, netClosed;
    private final Map<String, Long> metroSeen = new HashMap<>();
    private long lastMetroPoll;
    private final Map<Integer, List<String>> wordGuesses = new HashMap<>();
    private String wordDay = "";
    static final String[] LINE_CODES = {"RD", "OR", "BL", "SV", "GR", "YL"};
    static final Map<String, String> LINE_NAMES = Map.of("RD", "Red", "OR", "Orange", "BL", "Blue", "SV", "Silver", "GR", "Green", "YL", "Yellow");

    record Checkin(int num, String name, long time, String comment, int rssi, float snr, int hops) { }

    public BbsCommunity(BbsEngine engine, Path dataDir) {
        this.engine = engine; this.state = engine.state; this.dir = dataDir; this.file = dataDir.resolve("bbs-extra.properties");
        try (var r = Files.newBufferedReader(file)) { p.load(r); } catch (Exception ignored) { }
    }

    public String get(String k, String d) { return p.getProperty(k, d); }
    public void save(Map<String, String> f) throws IOException {
        for (String k : List.of("netOn", "netDay", "netTime", "n2yoKey", "welcome")) if (f.containsKey(k)) p.setProperty(k, f.get(k).trim());
        try (var w = Files.newBufferedWriter(file)) { p.store(w, "Mesh Console BBS extras"); }
    }
    public String json() {
        return "{\"netOn\":" + get("netOn", "false") + ",\"netDay\":" + meshconsole.ui.WebConsole.q(get("netDay", "THURSDAY")) + ",\"netTime\":" + meshconsole.ui.WebConsole.q(get("netTime", "20:00"))
            + ",\"hasN2yoKey\":" + !get("n2yoKey", "").isBlank() + ",\"welcome\":" + meshconsole.ui.WebConsole.q(get("welcome", defaultWelcome()))
            + ",\"checkins\":" + checkins.size() + ",\"netStatus\":" + meshconsole.ui.WebConsole.q(netStatus()) + "}";
    }

    // ------------------------------------------------------------------ logs for the report
    private void append(String name, String line) {
        try { Files.writeString(dir.resolve(name), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { }
    }
    void advertSent(String text) { append("advert_log.csv", System.currentTimeMillis() + "," + text.replace(",", ";").replace("\n", " ")); }
    void callerSeen(int num, boolean firstTime) { append("callers_log.csv", System.currentTimeMillis() + "," + Integer.toUnsignedString(num) + "," + (firstTime ? 1 : 0)); }

    /** Which adverts bring callers: for each advert text, sends and the distinct callers (and first-timers) within 2 hours. */
    public String advertReport(int days) {
        long from = System.currentTimeMillis() - days * 86_400_000L;
        List<long[]> callers = new ArrayList<>();   // time, node, first
        try { for (String l : Files.readAllLines(dir.resolve("callers_log.csv"))) { String[] c = l.split(","); if (c.length >= 3 && Long.parseLong(c[0]) >= from) callers.add(new long[]{Long.parseLong(c[0]), Long.parseLong(c[1]), Long.parseLong(c[2])}); } } catch (Exception ignored) { }
        Map<String, int[]> stats = new LinkedHashMap<>();   // text -> sends, callers, firsts
        Map<String, Set<Long>> seen = new HashMap<>();
        try {
            for (String l : Files.readAllLines(dir.resolve("advert_log.csv"))) {
                int i = l.indexOf(','); if (i < 0) continue;
                long t = Long.parseLong(l.substring(0, i)); if (t < from) continue;
                String text = l.substring(i + 1); String key = text.length() > 60 ? text.substring(0, 60) + "…" : text;
                int[] s = stats.computeIfAbsent(key, k -> new int[3]); s[0]++;
                for (long[] c : callers) if (c[0] > t && c[0] <= t + 7_200_000L && seen.computeIfAbsent(key, k -> new HashSet<>()).add(c[1] * 1_000_000L + t / 3_600_000L)) { s[1]++; if (c[2] == 1) s[2]++; }
            }
        } catch (Exception ignored) { }
        if (stats.isEmpty()) return "No adverts logged in the last " + days + " days yet (logging started with this version).";
        StringBuilder sb = new StringBuilder("Adverts, last " + days + " days – callers within 2 h of each (first-timers in brackets):\n");
        stats.entrySet().stream().sorted((a, b) -> Double.compare((double) b.getValue()[1] / b.getValue()[0], (double) a.getValue()[1] / a.getValue()[0]))
            .forEach(e -> sb.append(String.format("  %.2f per send  ·  %d send(s), %d caller(s) (%d new)  ·  %s%n", (double) e.getValue()[1] / e.getValue()[0], e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getKey())));
        return sb.toString();
    }

    // ------------------------------------------------------------------ welcome
    String defaultWelcome() { return "Welcome to " + engine.store.name + "! Try RANGE (how well I hear you), NEXT + your Metro station, W weather, or ? for the menu."; }
    String welcome() { String w = get("welcome", ""); return w.isBlank() ? defaultWelcome() : w; }

    // ------------------------------------------------------------------ weekly net
    private boolean netOn() { return Boolean.parseBoolean(get("netOn", "false")); }
    private LocalDateTime netStart(LocalDate day) { try { return day.atTime(LocalTime.parse(get("netTime", "20:00"))); } catch (Exception e) { return day.atTime(20, 0); } }
    private DayOfWeek netDow() { try { return DayOfWeek.valueOf(get("netDay", "THURSDAY").toUpperCase(Locale.ROOT)); } catch (Exception e) { return DayOfWeek.THURSDAY; } }
    /** Check-ins open 30 min before the start and close 60 min after. */
    private boolean netWindow(LocalDateTime now) {
        LocalDate d = LocalDate.now(); if (d.getDayOfWeek() != netDow()) return false;
        LocalDateTime s = netStart(d); return !now.isBefore(s.minusMinutes(30)) && now.isBefore(s.plusMinutes(60));
    }
    String netStatus() {
        if (!netOn()) return "net off";
        LocalDate d = LocalDate.now(); LocalDate next = d;
        while (next.getDayOfWeek() != netDow() || (next.equals(d) && LocalDateTime.now().isAfter(netStart(d).plusMinutes(60)))) next = next.plusDays(1);
        return (netWindow(LocalDateTime.now()) ? "OPEN now – " + checkins.size() + " checked in; " : "") + "next " + netDow().toString().charAt(0) + netDow().toString().substring(1, 3).toLowerCase() + " " + next.format(DateTimeFormatter.ofPattern("MMM d")) + " at " + get("netTime", "20:00");
    }

    String checkin(int from, String comment) {
        if (!netOn()) return "No net scheduled on this board.";
        LocalDateTime now = LocalDateTime.now();
        if (!netWindow(now)) return "Net not open. " + netStatus() + ". Check-ins open 30 min before.";
        NodeEntry n = state.node(from);
        int rssi = n == null ? 0 : n.rssi != 0 ? n.rssi : (int) Math.round(n.avgRssi()); float snr = n == null ? 0 : n.rssi != 0 ? n.snr : (float) n.avgSnr();
        Checkin c = new Checkin(from, state.nodeName(from), System.currentTimeMillis(), comment == null ? "" : comment.trim(), rssi, snr, n == null ? -1 : n.hopsAway);
        boolean first = !checkins.containsKey(from);
        checkins.put(from, c);
        if (first) append("net_log.csv", c.time + "," + LocalDate.now() + "," + Integer.toUnsignedString(from) + "," + c.name.replace(",", ";") + "," + c.rssi + "," + c.snr + "," + c.hops + "," + c.comment.replace(",", ";"));
        return (first ? "Checked in #" + checkins.size() : "Comment updated") + " for the " + engine.store.name + " net" + (n != null && n.rssiCount > 0 ? String.format(" – you're %s at %d dBm / %.1f dB", n.hopsAway == 0 ? "direct" : n.hopsAway + " hop(s)", rssi, snr) : "") + ". Roll call goes out when it closes.";
    }

    /** Called about once a minute: announces the net, then posts and broadcasts the roll call. */
    void tick() {
        if (!netOn()) return;
        LocalDateTime now = LocalDateTime.now(); LocalDate d = LocalDate.now();
        String key = d.toString();
        if (!key.equals(netDayKey) && d.getDayOfWeek() == netDow()) { netDayKey = key; checkins.clear(); netOpened = false; netClosed = false; }
        if (d.getDayOfWeek() != netDow()) return;
        LocalDateTime s = netStart(d);
        if (!netOpened && !now.isBefore(s.minusMinutes(30))) {
            netOpened = true;
            broadcast(engine.store.name + " weekly net opens at " + get("netTime", "20:00") + ". DM me CHECKIN (and a comment) – roll call with signal reports follows. " + (engine.store.name.contains("Lyon") ? "Go Bills." : ""));
        }
        if (!netClosed && now.isAfter(s.plusMinutes(60))) {
            netClosed = true;
            StringBuilder roll = new StringBuilder("Net roll call " + d.format(DateTimeFormatter.ofPattern("MMM d")) + ": " + checkins.size() + " checked in");
            if (checkins.isEmpty()) { broadcast(roll + ". Next week!"); return; }
            roll.append(" – ");
            List<String> names = new ArrayList<>();
            for (Checkin c : checkins.values()) names.add(c.name + (c.hops == 0 ? "*" : ""));
            roll.append(String.join(", ", names)).append(". *=direct. Thanks all, 73.");
            String text = roll.toString();
            broadcast(text.length() > 195 ? text.substring(0, 192) + "…" : text);
            BbsStore.Post post = new BbsStore.Post(); post.id = engine.store.nextId(); post.time = System.currentTimeMillis(); post.author = 0; post.authorName = engine.store.name + " net";
            StringBuilder body = new StringBuilder("Roll call " + d + ": ");
            for (Checkin c : checkins.values()) body.append(c.name).append(c.hops == 0 ? " (direct, " : " (" + (c.hops < 0 ? "?" : c.hops + " hops, ")).append(c.rssi).append(" dBm)").append(c.comment.isEmpty() ? "" : " \"" + c.comment + "\"").append("; ");
            post.text = body.toString().trim(); engine.store.posts.add(post); engine.store.save();
        }
    }

    private void broadcast(String text) {
        try { engine.client.sendOnce(text, ChatMessage.BROADCAST, 0); engine.log("net: " + text); } catch (IOException e) { engine.log("net broadcast failed: " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ Metro alerts by line
    static String lineCode(String s) {
        String u = s.trim().toUpperCase(Locale.ROOT);
        for (String c : LINE_CODES) if (u.equals(c) || u.equals(LINE_NAMES.get(c).toUpperCase(Locale.ROOT))) return c;
        return null;
    }
    String subMetro(int from, String line) {
        String code = lineCode(line);
        if (code == null) return "SUB METRO + a line: RED, ORANGE, BLUE, SILVER, GREEN or YELLOW (or RD/OR/BL/SV/GR/YL).";
        engine.store.subs.computeIfAbsent(from, k -> new HashSet<>()).add("METRO:" + code); engine.store.save();
        return "You'll get a DM when WMATA posts a " + LINE_NAMES.get(code) + " Line disruption. UNSUB to stop everything.";
    }
    void metroTick() {
        if (engine.store.wmataKey.isBlank() || System.currentTimeMillis() - lastMetroPoll < 5 * 60_000L) return;
        lastMetroPoll = System.currentTimeMillis();
        boolean anyone = false;
        for (Set<String> s : engine.store.subs.values()) for (String x : s) if (x.startsWith("METRO:")) anyone = true;
        if (!anyone) return;
        try {
            Object root = Json.parse(BbsExtras.get("https://api.wmata.com/Incidents.svc/json/Incidents", "api_key", engine.store.wmataKey));
            List<?> inc = (List<?>) ((Map<?, ?>) root).get("Incidents");
            long now = System.currentTimeMillis();
            metroSeen.values().removeIf(t -> t < now - 86_400_000L);
            if (inc == null) return;
            for (Object o : inc) {
                Map<?, ?> m = (Map<?, ?>) o;
                String id = Json.str(m.get("IncidentID")); if (id.isBlank()) id = Json.str(m.get("Description"));
                if (metroSeen.containsKey(id)) continue;
                metroSeen.put(id, now);
                String lines = Json.str(m.get("LinesAffected")).toUpperCase(Locale.ROOT);
                String desc = Json.str(m.get("Description"));
                for (Map.Entry<Integer, Set<String>> e : new ArrayList<>(engine.store.subs.entrySet())) {
                    List<String> hit = new ArrayList<>();
                    for (String x : e.getValue()) if (x.startsWith("METRO:") && lines.contains(x.substring(6))) hit.add(LINE_NAMES.get(x.substring(6)));
                    if (hit.isEmpty()) continue;
                    String text = "[Metro " + String.join("/", hit) + "] " + desc;
                    engine.send(e.getKey(), text.length() > 195 ? text.substring(0, 192) + "…" : text);
                }
            }
        } catch (IOException | RuntimeException e) { engine.log("metro alerts: " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ satellite passes (N2YO, free key)
    String sat(double lat, double lon) {
        String key = get("n2yoKey", "");
        if (lat == 0) return "No position known (yours or the board's).";
        if (key.isBlank()) return "SAT needs a free N2YO API key on this board (web console → Settings → BBS extras). Meanwhile: " + BbsExtras.iss(lat, lon);
        int[][] sats = {{25544, 10}, {33591, 10}, {28654, 10}, {27607, 10}};   // ISS, NOAA 19, NOAA 18, SO-50
        String[] names = {"ISS", "NOAA19", "NOAA18", "SO-50"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sats.length && out.size() < 3; i++) {
            try {
                Map<?, ?> root = (Map<?, ?>) Json.parse(BbsExtras.get(String.format(Locale.ROOT, "https://api.n2yo.com/rest/v1/satellite/radiopasses/%d/%.4f/%.4f/0/2/%d/&apiKey=%s", sats[i][0], lat, lon, sats[i][1], key)));
                List<?> passes = (List<?>) root.get("passes");
                if (passes == null || passes.isEmpty()) continue;
                Map<?, ?> ps = (Map<?, ?>) passes.get(0);
                long start = (long) Json.num(ps.get("startUTC"), 0) * 1000L;
                String when = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE HH:mm"));
                out.add(String.format(Locale.ROOT, "%s %s %.0f° max, %s→%s, %d min", names[i], when, Json.num(ps.get("maxEl"), 0), Json.str(ps.get("startAzCompass")), Json.str(ps.get("endAzCompass")), (long) ((Json.num(ps.get("endUTC"), 0) - Json.num(ps.get("startUTC"), 0)) / 60)));
            } catch (IOException | RuntimeException e) { out.add(names[i] + ": n/a"); }
        }
        return out.isEmpty() ? "No passes over 10° in the next 2 days." : "Next passes (>10°): " + String.join(" | ", out);
    }

    // ------------------------------------------------------------------ MAP
    String map(int from) {
        NodeEntry n = state.node(from);
        if (n == null || !n.hasPosition) return "Your node hasn't shared a position with me. Turn on position sharing (or set a fixed one) and try again.";
        NodeEntry me = state.myNode();
        String rel = "";
        if (me != null && me.hasPosition) {
            double km = meshconsole.analysis.Metar.distanceKm(me.lat, me.lon, n.lat, n.lon);
            double y = Math.sin(Math.toRadians(n.lon - me.lon)) * Math.cos(Math.toRadians(n.lat));
            double x = Math.cos(Math.toRadians(me.lat)) * Math.sin(Math.toRadians(n.lat)) - Math.sin(Math.toRadians(me.lat)) * Math.cos(Math.toRadians(n.lat)) * Math.cos(Math.toRadians(n.lon - me.lon));
            double b = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
            String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            rel = String.format(Locale.ROOT, ", %.1f km %s of %s", km, dirs[(int) Math.round(b / 45.0) % 8], engine.store.name);
        }
        return String.format(Locale.ROOT, "You: %.4f, %.4f%s – https://www.openstreetmap.org/?mlat=%.4f&mlon=%.4f#map=15/%.4f/%.4f", n.lat, n.lon, rel, n.lat, n.lon, n.lat, n.lon);
    }

    // ------------------------------------------------------------------ WORD (daily, Wordle-style)
    static final String[] WORDS = ("radio quiet trace pulse chirp relay nodes range field tower solar power board bytes crisp trail storm frost lakes beach train metro lines route "
        + "fancy ghost habit jolly knack lemon mango noble ocean piano query roast sugar tiger ultra vivid whale yacht zebra brave cloud dream eagle flame grape happy ivory juice kayak "
        + "light music night olive pearl quick river stone truth unity voice water young alpha delta bravo tango oscar hotel india romeo sabre wings erie queen allen depew shea "
        + "mesh antenna signal digit mail chair plant bread glass smile coast sound earth ").trim().split("\\s+");
    private static final List<String> FIVE = new ArrayList<>();
    static { for (String w : WORDS) if (w.length() == 5 && !FIVE.contains(w)) FIVE.add(w); }

    String wordOfDay() { String d = LocalDate.now().toString(); return FIVE.get(Math.floorMod(d.hashCode() * 31 + d.length(), FIVE.size())); }
    String word(int from, String guess) {
        String today = LocalDate.now().toString();
        if (!today.equals(wordDay)) { wordDay = today; wordGuesses.clear(); }
        List<String> g = wordGuesses.computeIfAbsent(from, k -> new ArrayList<>());
        String answer = wordOfDay();
        if (guess == null || guess.isBlank()) return "WORD: guess today's 5-letter word (" + g.size() + "/6 used). Send WORD + your guess, e.g. WORD RADIO. 🟩 right spot, 🟨 wrong spot, ⬜ not in word.";
        String w = guess.trim().toLowerCase(Locale.ROOT);
        if (w.length() != 5 || !w.chars().allMatch(Character::isLetter)) return "Guesses are 5 letters, e.g. WORD RADIO.";
        if (g.size() >= 6 && !g.contains(answer)) return "You've used your 6 guesses. Today's word was " + answer.toUpperCase(Locale.ROOT) + ". New word tomorrow!";
        if (g.contains(answer)) return "You already solved today's word (" + answer.toUpperCase(Locale.ROOT) + ") in " + (g.indexOf(answer) + 1) + ". New word tomorrow!";
        g.add(w);
        StringBuilder sb = new StringBuilder();
        int[] left = new int[26];
        for (int i = 0; i < 5; i++) if (w.charAt(i) != answer.charAt(i)) left[answer.charAt(i) - 'a']++;
        for (int i = 0; i < 5; i++) {
            char c = w.charAt(i);
            if (c == answer.charAt(i)) sb.append("🟩");
            else if (left[c - 'a'] > 0) { sb.append("🟨"); left[c - 'a']--; }
            else sb.append("⬜");
        }
        if (w.equals(answer)) return sb + " " + w.toUpperCase(Locale.ROOT) + " – solved in " + g.size() + "! New word tomorrow.";
        return sb + " " + w.toUpperCase(Locale.ROOT) + " (" + g.size() + "/6)" + (g.size() == 6 ? ". Out of guesses – it was " + answer.toUpperCase(Locale.ROOT) + "." : "");
    }
}
