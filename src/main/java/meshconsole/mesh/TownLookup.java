package meshconsole.mesh;

import meshconsole.analysis.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * 1.29.4: the nearest town for each node's position ("Aurora, NY"), from OpenStreetMap's free Nominatim service.
 * One lookup every 3 seconds at most (Nominatim allows one a second), only for nodes heard in the last week, and
 * cached by a ~2 km grid in towns.csv in the data folder, so a node that stays put is looked up once ever.
 */
public final class TownLookup {
    private TownLookup() { }
    private static volatile boolean started;
    private static final Map<String, String> cache = new HashMap<>();
    private static final Set<String> failed = new HashSet<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("New York", "NY"), Map.entry("Virginia", "VA"), Map.entry("Maryland", "MD"), Map.entry("District of Columbia", "DC"),
            Map.entry("Pennsylvania", "PA"), Map.entry("New Jersey", "NJ"), Map.entry("Delaware", "DE"), Map.entry("West Virginia", "WV"),
            Map.entry("North Carolina", "NC"), Map.entry("Ohio", "OH"), Map.entry("Connecticut", "CT"), Map.entry("Massachusetts", "MA"),
            Map.entry("Vermont", "VT"), Map.entry("New Hampshire", "NH"), Map.entry("Maine", "ME"), Map.entry("Rhode Island", "RI"),
            Map.entry("Ontario", "ON"), Map.entry("Quebec", "QC"));

    static String key(double lat, double lon) { return String.format(Locale.ROOT, "%.2f,%.2f", Math.round(lat * 50) / 50.0, Math.round(lon * 50) / 50.0); }

    public static synchronized void start(MeshState state, Path dataDir) {
        if (started) return;
        started = true;
        Path file = dataDir.resolve("towns.csv");
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int t = line.indexOf('\t');
                if (t > 0) cache.put(line.substring(0, t), line.substring(t + 1));
            }
        } catch (IOException ignored) { }
        Thread th = new Thread(() -> {
            while (true) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                try { step(state, file); } catch (Throwable ignored) { }
            }
        }, "town-lookup");
        th.setDaemon(true);
        th.start();
    }

    private static void step(MeshState state, Path file) {
        long now = System.currentTimeMillis();
        NodeEntry todo = null; String todoKey = null;
        for (NodeEntry n : state.nodes()) {
            if (!n.hasPosition || (n.lat == 0 && n.lon == 0)) continue;
            String k = key(n.lat, n.lon);
            String known;
            synchronized (TownLookup.class) { known = cache.get(k); }
            if (known != null) { if (!known.equals(n.town)) n.town = known; continue; }
            if (now - n.lastHeardMillis() > 7 * 86400_000L) continue;
            synchronized (TownLookup.class) { if (failed.contains(k)) continue; }
            if (todo == null || n.lastHeardMillis() > todo.lastHeardMillis()) { todo = n; todoKey = k; }
        }
        if (todo == null) return;
        String town = lookup(todo.lat, todo.lon);
        synchronized (TownLookup.class) {
            if (town == null) { failed.add(todoKey); return; }
            cache.put(todoKey, town);
        }
        todo.town = town;
        try { Files.writeString(file, todoKey + "\t" + town + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { }
    }

    /** "Aurora, NY" – or "" over water / nowhere named; null on a network error (retried next session). */
    static String lookup(double lat, double lon) {
        try {
            String url = String.format(Locale.ROOT, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&addressdetails=1&lat=%.5f&lon=%.5f", lat, lon);
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "MeshConsole/" + meshconsole.Version.VERSION + " (desktop Meshtastic client; node town names)")
                    .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            return town(r.body());
        } catch (Exception e) { return null; }
    }

    /** Pulls "town, ST" out of a Nominatim reverse-geocoding reply. */
    static String town(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?, ?> m) || !(m.get("address") instanceof Map<?, ?> a)) return "";
        String place = "";
        for (String k : new String[]{"city", "town", "village", "hamlet", "suburb", "municipality", "county"}) {
            String v = Json.str(a.get(k));
            if (!v.isEmpty()) { place = v; break; }
        }
        String st = Json.str(a.get("state"));
        String iso = Json.str(a.get("ISO3166-2-lvl4"));
        String abbr = iso.contains("-") ? iso.substring(iso.indexOf('-') + 1) : STATES.getOrDefault(st, st);
        String out = place.isEmpty() ? abbr : abbr.isEmpty() ? place : place + ", " + abbr;
        return out.replaceAll("[{}\"\\t\\n]", "");
    }
}
