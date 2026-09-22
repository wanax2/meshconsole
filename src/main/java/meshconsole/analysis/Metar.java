package meshconsole.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fetches METARs from NOAA aviationweather.gov (free, no key). */
public final class Metar {
    private Metar() { }

    public record Station(String id, String name, double lat, double lon, double distanceKm) { }

    /** Latest observation for one station id (e.g. KDCA). */
    public static WeatherObs latest(String id) throws IOException {
        List<WeatherObs> l = parse(get("https://aviationweather.gov/api/data/metar?ids=" + id.trim().toUpperCase() + "&format=json"));
        if (l.isEmpty()) throw new IOException("No METAR returned for " + id + " (unknown station id?)");
        return l.get(0);
    }

    /** Observations for the last N hours (1–48) for one station. */
    public static List<WeatherObs> history(String id, int hours) throws IOException {
        return parse(get("https://aviationweather.gov/api/data/metar?ids=" + id.trim().toUpperCase() + "&hours=" + Math.max(1, Math.min(48, hours)) + "&format=json"));
    }

    /** Stations reporting METARs within ~1° of a point, nearest first. */
    public static List<Station> nearest(double lat, double lon) throws IOException {
        double d = 1.0;
        String bbox = String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", lat - d, lon - d, lat + d, lon + d);
        Object root = Json.parse(get("https://aviationweather.gov/api/data/metar?bbox=" + bbox + "&format=json"));
        Map<String, Station> seen = new LinkedHashMap<>();
        if (root instanceof List<?> arr) {
            for (Object o : arr) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String id = Json.str(m.get("icaoId"));
                double slat = Json.num(m.get("lat"), Double.NaN), slon = Json.num(m.get("lon"), Double.NaN);
                if (id.isEmpty() || Double.isNaN(slat)) continue;
                double km = distanceKm(lat, lon, slat, slon);
                seen.putIfAbsent(id, new Station(id, Json.str(m.get("name")), slat, slon, km));
            }
        }
        List<Station> l = new ArrayList<>(seen.values());
        l.sort(Comparator.comparingDouble(Station::distanceKm));
        return l;
    }

    static List<WeatherObs> parse(String json) {
        List<WeatherObs> out = new ArrayList<>();
        Object root = Json.parse(json);
        if (!(root instanceof List<?> arr)) return out;
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) continue;
            double t = Json.num(m.get("temp"), Double.NaN), dp = Json.num(m.get("dewp"), Double.NaN);
            double rh = Double.NaN;
            if (!Double.isNaN(t) && !Double.isNaN(dp)) {
                rh = 100 * Math.exp((17.625 * dp) / (243.04 + dp)) / Math.exp((17.625 * t) / (243.04 + t));
                rh = Math.max(0, Math.min(100, rh));
            }
            double altimHpa = Json.num(m.get("altim"), Double.NaN);
            if (!Double.isNaN(altimHpa) && altimHpa < 100) altimHpa *= 33.8639;   // inHg → hPa
            long time = (long) (Json.num(m.get("obsTime"), 0) * 1000);
            if (time == 0) {
                String rt = Json.str(m.get("reportTime"));
                try { time = java.time.Instant.parse(rt.replace(" ", "T") + (rt.endsWith("Z") ? "" : "Z")).toEpochMilli(); } catch (Exception e) { time = System.currentTimeMillis(); }
            }
            out.add(new WeatherObs(time, Json.str(m.get("icaoId")), t, dp, rh, Json.num(m.get("wspd"), Double.NaN), Json.num(m.get("wgst"), Double.NaN),
                    Json.num(m.get("wdir"), Double.NaN), altimHpa, Json.num(m.get("visib"), Double.NaN), Json.str(m.get("wxString")), Json.str(m.get("rawOb"))));
        }
        out.sort(Comparator.comparingLong(WeatherObs::time));
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "MeshConsole/1.3 (Meshtastic desktop client; weather correlation)");
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        if (c.getResponseCode() != 200) throw new IOException("aviationweather.gov returned HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
