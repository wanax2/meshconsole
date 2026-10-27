package meshconsole.bbs;

import meshconsole.analysis.Json;

import java.io.IOException;
import java.util.*;

/**
 * Aviation weather for the BBS, from aviationweather.gov (NOAA, free, no key) and api.weather.gov. Every reply is
 * built to fit one or two 200-byte mesh messages. Responses are cached for five minutes so a busy board doesn't
 * hammer the services.
 */
public final class Aviation {
    private Aviation() { }

    private record Cached(long at, String body) { }
    private static final Map<String, Cached> CACHE = new HashMap<>();

    private static String fetch(String url, String... headers) throws IOException {
        synchronized (CACHE) { Cached c = CACHE.get(url); if (c != null && System.currentTimeMillis() - c.at < 300_000) return c.body; }
        String body = BbsExtras.get(url, headers);
        synchronized (CACHE) { CACHE.put(url, new Cached(System.currentTimeMillis(), body)); }
        return body;
    }

    /** "dca" → "KDCA"; four-letter ids are left alone. */
    public static String icao(String id) {
        String s = id.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return s.length() == 3 && Character.isLetter(s.charAt(0)) ? "K" + s : s;
    }
    public static List<String> ids(String list) {
        List<String> out = new ArrayList<>();
        for (String s : list.split("[,\\s]+")) if (!s.isBlank()) out.add(icao(s));
        return out;
    }

    // ---- METAR parsing --------------------------------------------------------------------------
    record Obs(String id, String raw, double tempC, double dewC, double altInHg, double elevFt, double visSm, int ceilFt, String wind) { }

    private static List<Obs> metars(List<String> ids) throws IOException {
        Object root = Json.parse(fetch("https://aviationweather.gov/api/data/metar?ids=" + String.join(",", ids) + "&format=json"));
        Map<String, Obs> byId = new LinkedHashMap<>();
        if (root instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) {
            String id = Json.str(m.get("icaoId"));
            if (byId.containsKey(id)) continue;   // newest first
            double alt = Json.num(m.get("altim"), Double.NaN); if (alt > 100) alt *= 0.0295300;   // hPa → inHg
            double vis; Object v = m.get("visib");
            if (v instanceof Number n) vis = n.doubleValue(); else { String vs = Json.str(v).replace("+", ""); try { vis = Double.parseDouble(vs); } catch (NumberFormatException e) { vis = Double.NaN; } }
            int ceil = Integer.MAX_VALUE;
            if (m.get("clouds") instanceof List<?> cl) for (Object c : cl) if (c instanceof Map<?, ?> cm) {
                String cover = Json.str(cm.get("cover"));
                if (cover.equals("BKN") || cover.equals("OVC") || cover.equals("OVX")) ceil = Math.min(ceil, (int) Json.num(cm.get("base"), Integer.MAX_VALUE));
            }
            Object wd = m.get("wdir"); String dir = wd instanceof Number n ? String.format("%03d", n.intValue()) : Json.str(wd);
            int spd = (int) Json.num(m.get("wspd"), 0), gst = (int) Json.num(m.get("wgst"), 0);
            String wind = spd == 0 ? "calm" : dir + "@" + spd + (gst > 0 ? "G" + gst : "") + "kt";
            byId.put(id, new Obs(id, Json.str(m.get("rawOb")), Json.num(m.get("temp"), Double.NaN), Json.num(m.get("dewp"), Double.NaN),
                    alt, Json.num(m.get("elev"), 0) * 3.28084, vis, ceil, wind));
        }
        List<Obs> out = new ArrayList<>();
        for (String id : ids) if (byId.containsKey(id)) out.add(byId.get(id));
        return out;
    }

    /** FAA flight category from ceiling (BKN/OVC base, ft AGL) and visibility (statute miles). */
    static String category(Obs o) {
        double v = Double.isNaN(o.visSm) ? 10 : o.visSm; int c = o.ceilFt;
        if (c < 500 || v < 1) return "LIFR";
        if (c < 1000 || v < 3) return "IFR";
        if (c <= 3000 || v <= 5) return "MVFR";
        return "VFR";
    }

    /** Density altitude in feet: pressure altitude corrected for temperature against ISA. */
    static int densityAltitude(Obs o) {
        if (Double.isNaN(o.altInHg) || Double.isNaN(o.tempC)) return Integer.MIN_VALUE;
        double pa = o.elevFt + (29.92 - o.altInHg) * 1000;
        double isa = 15 - 1.98 * pa / 1000;
        return (int) Math.round(pa + 118.8 * (o.tempC - isa));
    }

    private static String stripRemarks(String raw) { int i = raw.indexOf(" RMK"); return (i > 0 ? raw.substring(0, i) : raw).replaceAll("^METAR |^SPECI ", "").trim(); }

    // ---- commands ---------------------------------------------------------------------------------
    /** METAR for each station, one line each: raw (without remarks) + category + density altitude. */
    public static List<String> metar(List<String> ids) {
        try {
            List<Obs> obs = metars(ids);
            if (obs.isEmpty()) return List.of("No METAR for " + String.join(",", ids) + ".");
            List<String> out = new ArrayList<>();
            for (Obs o : obs) {
                int da = densityAltitude(o);
                out.add(stripRemarks(o.raw) + " | " + category(o) + (da == Integer.MIN_VALUE ? "" : ", DA " + da + "ft"));
            }
            return out;
        } catch (IOException | RuntimeException e) { return List.of("METAR unavailable (" + e.getMessage() + ")"); }
    }

    /** Flight categories for a list of airports on one line. */
    public static String categories(List<String> ids) {
        try {
            List<Obs> obs = metars(ids);
            if (obs.isEmpty()) return "No reports for " + String.join(",", ids) + ".";
            StringBuilder sb = new StringBuilder();
            for (Obs o : obs) {
                String ceil = o.ceilFt == Integer.MAX_VALUE ? "no ceil" : "ceil " + o.ceilFt;
                String vis = Double.isNaN(o.visSm) ? "" : " " + (o.visSm >= 10 ? "10+" : trim(o.visSm)) + "SM";
                String item = o.id.substring(1) + " " + category(o) + " " + ceil + vis + " " + o.wind + "; ";
                if (sb.length() + item.length() > 380) break;
                sb.append(item);
            }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "Flight categories unavailable (" + e.getMessage() + ")"; }
    }
    private static String trim(double d) { return d == Math.rint(d) ? String.valueOf((int) d) : String.valueOf(d); }

    /** Density altitude with the working, for one airport. */
    public static String densityAltitude(String id) {
        try {
            List<Obs> obs = metars(List.of(id));
            if (obs.isEmpty()) return "No METAR for " + id + ".";
            Obs o = obs.get(0); int da = densityAltitude(o);
            if (da == Integer.MIN_VALUE) return id + ": temperature or altimeter missing from the METAR.";
            double pa = o.elevFt + (29.92 - o.altInHg) * 1000;
            return String.format(Locale.ROOT, "%s density altitude %,d ft (field %d ft, %.0f°C, altimeter %.2f, pressure alt %,d ft)%s",
                    id, da, Math.round(o.elevFt), o.tempC, o.altInHg, Math.round(pa), da > o.elevFt + 2000 ? " – expect reduced climb." : ".");
        } catch (IOException | RuntimeException e) { return "Density altitude unavailable (" + e.getMessage() + ")"; }
    }

    /** Recent pilot reports within 100 nm of an airport. */
    public static String pireps(String id) {
        try {
            String raw = fetch("https://aviationweather.gov/api/data/pirep?id=" + id + "&distance=100&age=3&format=raw").trim();
            if (raw.isEmpty()) return "No PIREPs within 100 nm of " + id + " in the last 3 h.";
            StringBuilder sb = new StringBuilder("PIREPs near " + id + ": ");
            int n = 0;
            for (String line : raw.split("\\R")) {
                line = line.trim().replaceAll("\\s+", " "); if (line.isEmpty()) continue;
                String item = (line.length() > 150 ? line.substring(0, 149) + "…" : line) + " || ";
                if (sb.length() + item.length() > 380 || n == 3) break;
                sb.append(item); n++;
            }
            return sb.toString().replaceAll(" \\|\\| $", "");
        } catch (IOException | RuntimeException e) { return "PIREPs unavailable (" + e.getMessage() + ")"; }
    }

    private static final String[] FB_LEVELS = {"3k", "6k", "9k", "12k", "18k", "24k", "30k", "34k", "39k"};

    /** Winds and temperatures aloft (FB) for the nearest forecast point to try: the given id, then fallbacks. */
    public static String windsAloft(String id, List<String> fallbacks) {
        try {
            String text = fetch("https://aviationweather.gov/api/data/windtemp?region=all&level=low&fcst=06");
            Map<String, String[]> rows = new HashMap<>();
            for (String line : text.split("\\R")) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 2 && f[0].matches("[A-Z0-9]{3}") && !f[0].equals("FOR")) rows.put(f[0], Arrays.copyOfRange(f, 1, f.length));
            }
            List<String> tryIds = new ArrayList<>();
            if (id != null && !id.isBlank()) tryIds.add(fb(id));
            for (String f : fallbacks) tryIds.add(fb(f));
            for (String t : tryIds) {
                String[] g = rows.get(t);
                if (g == null) continue;
                int offset = FB_LEVELS.length - g.length;   // high stations omit the low levels
                StringBuilder sb = new StringBuilder("Winds aloft " + t + " (6h fcst): ");
                for (int i = 0; i < g.length && i + offset < 6; i++) sb.append(FB_LEVELS[i + offset]).append(' ').append(decodeFb(g[i])).append("; ");
                return sb.toString().trim();
            }
            return "No winds-aloft point for " + (id == null || id.isBlank() ? String.join("/", fallbacks) : id) + ". Try WINDS with a nearby FB site (e.g. WINDS EMI, WINDS RIC).";
        } catch (IOException | RuntimeException e) { return "Winds aloft unavailable (" + e.getMessage() + ")"; }
    }
    private static String fb(String id) { String s = id.trim().toUpperCase(Locale.ROOT); return s.length() == 4 && s.startsWith("K") ? s.substring(1) : s; }

    /** "2715" → "270°/15kt", "2635+05" → "260°/35kt +5°C", "9900" → "light & variable"; 100+ kt encoded as dir+50. */
    static String decodeFb(String g) {
        if (g.length() < 4) return g;
        try {
            int dd = Integer.parseInt(g.substring(0, 2)), ss = Integer.parseInt(g.substring(2, 4));
            String temp = "";
            if (g.length() > 4) { String t = g.substring(4); temp = " " + (t.startsWith("+") || t.startsWith("-") ? t : "-" + t) + "°C"; }
            if (dd == 99 && ss == 0) return "lt&var" + temp;
            if (dd > 36) { dd -= 50; ss += 100; }
            return String.format("%03d/%d", dd * 10, ss) + temp;
        } catch (NumberFormatException e) { return g; }
    }

    // ---- FAA airport status (delays, ground stops, closures) --------------------------------------
    /** FAA NAS status for the given airports (3- or 4-letter ids); "ALL" lists the national picture. */
    public static String delays(List<String> ids, boolean all) {
        try {
            String xml = fetch("https://nasstatus.faa.gov/api/airport-status-information");
            List<String[]> items = new ArrayList<>();   // {airport, text}
            for (java.util.regex.Matcher m = java.util.regex.Pattern.compile("<Ground_Delay>(.*?)</Ground_Delay>", java.util.regex.Pattern.DOTALL).matcher(xml); m.find(); )
                items.add(new String[]{tag(m.group(1), "ARPT"), "ground delay avg " + tag(m.group(1), "Avg") + " (" + tag(m.group(1), "Reason") + ")"});
            java.util.regex.Matcher gs = java.util.regex.Pattern.compile("<Ground_Stop_List>(.*?)</Ground_Stop_List>", java.util.regex.Pattern.DOTALL).matcher(xml);
            if (gs.find()) for (java.util.regex.Matcher m = java.util.regex.Pattern.compile("<Program>(.*?)</Program>", java.util.regex.Pattern.DOTALL).matcher(gs.group(1)); m.find(); )
                items.add(new String[]{tag(m.group(1), "ARPT"), "GROUND STOP until " + tag(m.group(1), "End_Time") + " (" + tag(m.group(1), "Reason") + ")"});
            for (java.util.regex.Matcher m = java.util.regex.Pattern.compile("<Delay>(.*?)</Delay>", java.util.regex.Pattern.DOTALL).matcher(xml); m.find(); ) {
                String b = m.group(1), type = "";
                java.util.regex.Matcher t = java.util.regex.Pattern.compile("<Arrival_Departure Type=\"([^\"]+)\">(.*?)</Arrival_Departure>", java.util.regex.Pattern.DOTALL).matcher(b);
                if (t.find()) type = t.group(1).toLowerCase(Locale.ROOT) + " delays " + tag(t.group(2), "Min") + "–" + tag(t.group(2), "Max") + " " + tag(t.group(2), "Trend").toLowerCase(Locale.ROOT);
                items.add(new String[]{tag(b, "ARPT"), (type.isEmpty() ? "delays" : type) + " (" + tag(b, "Reason") + ")"});
            }
            java.util.regex.Matcher cl = java.util.regex.Pattern.compile("<Airport_Closure_List>(.*?)</Airport_Closure_List>", java.util.regex.Pattern.DOTALL).matcher(xml);
            if (cl.find()) for (java.util.regex.Matcher m = java.util.regex.Pattern.compile("<Airport>(.*?)</Airport>", java.util.regex.Pattern.DOTALL).matcher(cl.group(1)); m.find(); )
                items.add(new String[]{tag(m.group(1), "ARPT"), "CLOSED " + tag(m.group(1), "Reason")});
            Set<String> want = new HashSet<>(); for (String id : ids) want.add(fb(id));
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (String[] it : items) {
                if (!all && !want.contains(it[0])) continue;
                String line = it[0] + ": " + it[1].replaceAll("\\s+", " ").replace(" ()", "") + "; ";
                if (sb.length() + line.length() > 380) { sb.append("(+more)"); break; }
                sb.append(line); shown++;
            }
            if (shown == 0) return all ? "FAA: no delays, ground stops or closures reported nationwide." : "FAA: no delays reported for " + String.join(", ", want) + ".";
            return "FAA: " + sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "FAA status unavailable (" + e.getMessage() + ")"; }
    }
    private static String tag(String xml, String t) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("<" + t + ">\\s*(.*?)\\s*</" + t + ">", java.util.regex.Pattern.DOTALL).matcher(xml); return m.find() ? m.group(1).trim() : ""; }

    // ---- aircraft overhead (OpenSky Network, anonymous) -------------------------------------------
    /** The closest aircraft within radiusKm of the board: callsign, altitude, heading, speed, distance and bearing. */
    public static String planes(double lat, double lon, int radiusKm) {
        if (lat == 0) return "No position set on this board.";
        try {
            double dLat = radiusKm / 111.0, dLon = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)));
            String url = String.format(Locale.ROOT, "https://opensky-network.org/api/states/all?lamin=%.3f&lomin=%.3f&lamax=%.3f&lomax=%.3f", lat - dLat, lon - dLon, lat + dLat, lon + dLon);
            Object root = Json.parse(fetchShort(url));
            if (!(root instanceof Map<?, ?> m) || !(m.get("states") instanceof List<?> states) || states.isEmpty()) return "No aircraft reported within " + radiusKm + " km right now.";
            record Ac(String cs, double km, double brg, int altFt, int hdg, int kt, boolean ground) { }
            List<Ac> ac = new ArrayList<>();
            for (Object o : states) {
                if (!(o instanceof List<?> s) || s.size() < 11 || s.get(5) == null || s.get(6) == null) continue;
                double alat = ((Number) s.get(6)).doubleValue(), alon = ((Number) s.get(5)).doubleValue();
                double km = meshconsole.analysis.Metar.distanceKm(lat, lon, alat, alon);
                if (km > radiusKm) continue;
                double y = Math.sin(Math.toRadians(alon - lon)) * Math.cos(Math.toRadians(alat)), x = Math.cos(Math.toRadians(lat)) * Math.sin(Math.toRadians(alat)) - Math.sin(Math.toRadians(lat)) * Math.cos(Math.toRadians(alat)) * Math.cos(Math.toRadians(alon - lon));
                double brg = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
                String cs = s.get(1) == null ? "" : s.get(1).toString().trim(); if (cs.isEmpty()) cs = String.valueOf(s.get(0));
                int alt = s.get(7) == null ? -1 : (int) Math.round(((Number) s.get(7)).doubleValue() * 3.28084);
                int hdg = s.get(10) == null ? -1 : (int) Math.round(((Number) s.get(10)).doubleValue());
                int kt = s.get(9) == null ? -1 : (int) Math.round(((Number) s.get(9)).doubleValue() * 1.94384);
                ac.add(new Ac(cs, km, brg, alt, hdg, kt, Boolean.TRUE.equals(s.get(8))));
            }
            if (ac.isEmpty()) return "No aircraft reported within " + radiusKm + " km right now.";
            ac.sort(Comparator.comparingDouble(Ac::km));
            String[] pts = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            StringBuilder sb = new StringBuilder(ac.size() + " aircraft within " + radiusKm + " km. ");
            for (Ac a : ac) {
                String item = a.cs + " " + (a.ground ? "on ground" : (a.altFt >= 0 ? String.format("%,dft", a.altFt) : "?ft") + (a.hdg >= 0 ? " hdg " + a.hdg : "") + (a.kt >= 0 ? " " + a.kt + "kt" : ""))
                        + String.format(Locale.ROOT, " %.1fkm %s; ", a.km, pts[(int) Math.round(a.brg / 45) % 8]);
                if (sb.length() + item.length() > 380) break;
                sb.append(item);
            }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "Aircraft data unavailable (" + e.getMessage() + ")"; }
    }
    /** Live positions go stale fast: cache for 30 s rather than 5 minutes. */
    private static String fetchShort(String url) throws IOException {
        synchronized (CACHE) { Cached c = CACHE.get(url); if (c != null && System.currentTimeMillis() - c.at < 30_000) return c.body; }
        String body = BbsExtras.get(url);
        synchronized (CACHE) { CACHE.put(url, new Cached(System.currentTimeMillis(), body)); }
        return body;
    }

    // ---- NWS forecast ---------------------------------------------------------------------------
    private static String forecastUrl; private static double fLat, fLon;

    /** The next three NWS forecast periods for the board's position, short form. */
    public static String forecast(double lat, double lon) {
        if (lat == 0) return "No position set on this board.";
        try {
            if (forecastUrl == null || Math.abs(lat - fLat) > 0.01 || Math.abs(lon - fLon) > 0.01) {
                Map<?, ?> p = (Map<?, ?>) Json.parse(fetch(String.format(Locale.ROOT, "https://api.weather.gov/points/%.4f,%.4f", lat, lon), "Accept", "application/geo+json"));
                forecastUrl = Json.str(((Map<?, ?>) p.get("properties")).get("forecast")); fLat = lat; fLon = lon;
            }
            Map<?, ?> f = (Map<?, ?>) Json.parse(fetch(forecastUrl, "Accept", "application/geo+json"));
            List<?> periods = (List<?>) ((Map<?, ?>) f.get("properties")).get("periods");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, periods.size()); i++) {
                Map<?, ?> pr = (Map<?, ?>) periods.get(i);
                sb.append(Json.str(pr.get("name"))).append(": ").append(Json.str(pr.get("shortForecast"))).append(", ")
                  .append((int) Json.num(pr.get("temperature"), 0)).append(Json.str(pr.get("temperatureUnit"))).append(". ");
            }
            String s = sb.toString().trim(); return s.length() > 380 ? s.substring(0, 377) + "…" : s;
        } catch (IOException | RuntimeException e) { return "Forecast unavailable (" + e.getMessage() + ")"; }
    }
}
