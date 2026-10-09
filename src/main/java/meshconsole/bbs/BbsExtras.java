package meshconsole.bbs;

import meshconsole.analysis.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Network helpers for the BBS: TAF, WMATA alerts, callsign lookup, Discord bridge. Each returns a ≤180-char line or an error string. */
public final class BbsExtras {
    private BbsExtras() { }

    private static String get(String url, String... headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "MeshConsole-BBS"); c.setConnectTimeout(6000); c.setReadTimeout(8000);
        for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
        if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    /** Aviation forecast for the station, compressed to one line. */
    public static String taf(String station) {
        try {
            String raw = get("https://aviationweather.gov/api/data/taf?ids=" + station + "&format=raw").trim();
            if (raw.isEmpty()) return "No TAF for " + station;
            raw = raw.replaceAll("\\s+", " ");
            return raw.length() > 190 ? raw.substring(0, 187) + "…" : raw;
        } catch (IOException e) { return "TAF unavailable (" + e.getMessage() + ")"; }
    }

    /** WMATA rail alerts; needs a free API key from developer.wmata.com. */
    public static String metro(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "Metro alerts not configured on this BBS.";
        try {
            String body = get("https://api.wmata.com/Incidents.svc/json/Incidents", "api_key", apiKey);
            Object root = Json.parse(body);
            List<?> inc = (List<?>) ((Map<?, ?>) root).get("Incidents");
            if (inc == null || inc.isEmpty()) return "Metro: no rail incidents reported.";
            StringBuilder sb = new StringBuilder("Metro: ");
            for (Object o : inc) {
                Map<?, ?> m = (Map<?, ?>) o;
                String lines = Json.str(m.get("LinesAffected")).replace(";", "").trim();
                String desc = Json.str(m.get("Description"));
                String item = "[" + lines + "] " + (desc.length() > 70 ? desc.substring(0, 70) + "…" : desc) + " ";
                if (sb.length() + item.length() > 185) { sb.append("(+more)"); break; }
                sb.append(item);
            }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "Metro alerts unavailable (" + e.getMessage() + ")"; }
    }

    // ---- WMATA: stations, next trains, buses, elevators ---------------------------------------
    private static Map<String, String> stationCodes;   // "CLARENDON" -> "K02"
    private static long stationsLoaded;

    private static synchronized Map<String, String> stations(String key) throws IOException {
        if (stationCodes != null && System.currentTimeMillis() - stationsLoaded < 86400_000L) return stationCodes;
        Map<?, ?> root = (Map<?, ?>) Json.parse(get("https://api.wmata.com/Rail.svc/json/jStations", "api_key", key));
        Map<String, String> m = new LinkedHashMap<>();
        for (Object o : (List<?>) root.get("Stations")) { Map<?, ?> st = (Map<?, ?>) o; m.putIfAbsent(Json.str(st.get("Name")).toUpperCase(), Json.str(st.get("Code"))); }
        stationCodes = m; stationsLoaded = System.currentTimeMillis();
        return m;
    }

    private static String findStation(Map<String, String> m, String q) {
        String u = q.trim().toUpperCase();
        if (m.containsKey(u)) return u;
        for (String n : m.keySet()) if (n.startsWith(u)) return n;
        for (String n : m.keySet()) if (n.contains(u)) return n;
        return null;
    }

    /** NEXT <station>: next trains at a Metrorail station. */
    public static String nextTrains(String apiKey, String station) {
        if (apiKey == null || apiKey.isBlank()) return "Metro lookups not configured on this BBS.";
        if (station == null || station.isBlank()) return "NEXT station – e.g. NEXT CLARENDON, NEXT ROSSLYN, NEXT METRO CENTER";
        try {
            Map<String, String> m = stations(apiKey);
            String name = findStation(m, station);
            if (name == null) return "No station like '" + station + "'.";
            Map<?, ?> root = (Map<?, ?>) Json.parse(get("https://api.wmata.com/StationPrediction.svc/json/GetPrediction/" + m.get(name), "api_key", apiKey));
            List<?> trains = (List<?>) root.get("Trains");
            if (trains == null || trains.isEmpty()) return name + ": no trains predicted (closed or no service).";
            StringBuilder sb = new StringBuilder(cap(name, 14) + ": ");
            int n = 0;
            for (Object o : trains) {
                Map<?, ?> t = (Map<?, ?>) o;
                String line = Json.str(t.get("Line")), dest = Json.str(t.get("DestinationName")), min = Json.str(t.get("Min"));
                if (line.isEmpty() || line.equals("No") || line.equals("--")) continue;
                String item = line + "→" + cap(dest, 10) + " " + (min.equals("ARR") || min.equals("BRD") ? min : min + "m") + "; ";
                if (sb.length() + item.length() > 185) break;
                sb.append(item); if (++n >= 6) break;
            }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "Metro lookup failed (" + e.getMessage() + ")"; }
    }

    /** BUS <stop id>: next buses at a Metrobus stop (the 7-digit number on the stop sign). */
    public static String nextBuses(String apiKey, String stopId) {
        if (apiKey == null || apiKey.isBlank()) return "Metro lookups not configured on this BBS.";
        if (stopId == null || !stopId.trim().matches("\\d{5,8}")) return "BUS stopid – the number on the bus stop sign, e.g. BUS 1001195";
        try {
            Map<?, ?> root = (Map<?, ?>) Json.parse(get("https://api.wmata.com/NextBusService.svc/json/jPredictions?StopID=" + stopId.trim(), "api_key", apiKey));
            List<?> preds = (List<?>) root.get("Predictions"); String stop = Json.str(root.get("StopName"));
            if (preds == null || preds.isEmpty()) return (stop.isEmpty() ? "Stop " + stopId : stop) + ": no buses predicted.";
            StringBuilder sb = new StringBuilder(cap(stop.isEmpty() ? "Stop " + stopId : stop, 24) + ": ");
            int n = 0;
            for (Object o : preds) { Map<?, ?> t = (Map<?, ?>) o; String item = Json.str(t.get("RouteID")) + " " + cap(Json.str(t.get("DirectionText")), 12) + " " + (int) Json.num(t.get("Minutes"), 0) + "m; "; if (sb.length() + item.length() > 185) break; sb.append(item); if (++n >= 6) break; }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "Bus lookup failed (" + e.getMessage() + ")"; }
    }

    /** METRO BUS [route]: bus incidents, optionally for one route. */
    public static String busIncidents(String apiKey, String route) {
        if (apiKey == null || apiKey.isBlank()) return "Metro lookups not configured on this BBS.";
        try {
            Map<?, ?> root = (Map<?, ?>) Json.parse(get("https://api.wmata.com/Incidents.svc/json/BusIncidents" + (route == null || route.isBlank() ? "" : "?Route=" + URLEncoder.encode(route.trim().toUpperCase(), StandardCharsets.UTF_8)), "api_key", apiKey));
            List<?> inc = (List<?>) root.get("BusIncidents");
            if (inc == null || inc.isEmpty()) return "Bus: no incidents" + (route == null || route.isBlank() ? "." : " on " + route.toUpperCase() + ".");
            StringBuilder sb = new StringBuilder("Bus: ");
            for (Object o : inc) { Map<?, ?> m = (Map<?, ?>) o; List<?> rs = (List<?>) m.get("RoutesAffected"); String item = "[" + (rs == null ? "" : String.join(",", rs.stream().map(String::valueOf).toList())) + "] " + cap(Json.str(m.get("Description")), 70) + " "; if (sb.length() + item.length() > 185) { sb.append("(+more)"); break; } sb.append(item); }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "Bus incidents unavailable (" + e.getMessage() + ")"; }
    }

    /** ELEV [station]: elevator/escalator outages, optionally for one station. */
    public static String elevators(String apiKey, String station) {
        if (apiKey == null || apiKey.isBlank()) return "Metro lookups not configured on this BBS.";
        try {
            String code = "";
            if (station != null && !station.isBlank()) { Map<String, String> m = stations(apiKey); String name = findStation(m, station); if (name == null) return "No station like '" + station + "'."; code = m.get(name); }
            Map<?, ?> root = (Map<?, ?>) Json.parse(get("https://api.wmata.com/Incidents.svc/json/ElevatorIncidents" + (code.isEmpty() ? "" : "?StationCode=" + code), "api_key", apiKey));
            List<?> inc = (List<?>) root.get("ElevatorIncidents");
            if (inc == null || inc.isEmpty()) return "Elevators/escalators: no outages" + (code.isEmpty() ? " reported." : " at that station.");
            StringBuilder sb = new StringBuilder(code.isEmpty() ? inc.size() + " outages: " : "Outages: ");
            for (Object o : inc) { Map<?, ?> m = (Map<?, ?>) o; String item = cap(Json.str(m.get("StationName")), 12) + " " + Json.str(m.get("UnitType")).substring(0, 1) + " " + cap(Json.str(m.get("LocationDescription")), 30) + "; "; if (sb.length() + item.length() > 185) { sb.append("(+more)"); break; } sb.append(item); }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "Elevator status unavailable (" + e.getMessage() + ")"; }
    }

    private static String cap(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }

    /** Ham callsign → name/location via callook.info (US callsigns). */
    public static String callsign(String call) {
        try {
            String body = get("https://callook.info/" + URLEncoder.encode(call.toUpperCase(), StandardCharsets.UTF_8) + "/json");
            Map<?, ?> m = (Map<?, ?>) Json.parse(body);
            if (!"VALID".equals(Json.str(m.get("status")))) return "No FCC record for " + call.toUpperCase();
            Map<?, ?> name = (Map<?, ?>) m.get("name"); Map<?, ?> addr = (Map<?, ?>) m.get("address"); Map<?, ?> cur = (Map<?, ?>) m.get("current"); Map<?, ?> other = (Map<?, ?>) m.get("otherInfo");
            String who = name == null ? "" : Json.str(name.get("name")) != null && !Json.str(name.get("name")).isEmpty() ? Json.str(name.get("name")) : "";
            String line2 = addr == null ? "" : Json.str(addr.get("line2"));
            String cls = cur == null ? "" : Json.str(cur.get("operClass"));
            String grid = other == null ? "" : Json.str(other.get("gridSquare"));
            String exp = other == null ? "" : Json.str(other.get("expiryDate"));
            return call.toUpperCase() + ": " + who + (line2.isEmpty() ? "" : ", " + line2) + (cls.isEmpty() ? "" : " (" + cls + ")") + (grid.isEmpty() ? "" : " " + grid) + (exp.isEmpty() ? "" : " exp " + exp);
        } catch (IOException | RuntimeException e) { return "Callsign lookup unavailable (" + e.getMessage() + ")"; }
    }

    /** Posts a line to a Discord/Slack-style webhook; returns null on success. */
    public static String discord(String webhook, String text) {
        if (webhook == null || webhook.isBlank()) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(webhook).toURL().openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(6000); c.setReadTimeout(8000);
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "MeshConsole-BBS");
            String json = "{\"content\":" + q(text) + ",\"username\":\"Mesh BBS\"}";
            try (var o = c.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            return c.getResponseCode() < 300 ? null : "HTTP " + c.getResponseCode();
        } catch (IOException e) { return e.getMessage(); }
    }

    private static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }

    // ---- games -------------------------------------------------------------------------------

    public record Trivia(String q, String a, String[] alts) { }
    public static final Trivia[] TRIVIA = {
        new Trivia("What frequency band does US Meshtastic use?", "915", new String[]{"915mhz", "915 mhz", "902-928"}),
        new Trivia("LoRa stands for Long ___?", "range", new String[]{"long range"}),
        new Trivia("What company makes the RAK4631?", "rak", new String[]{"rak wireless", "rakwireless"}),
        new Trivia("Default Meshtastic modem preset?", "longfast", new String[]{"long fast", "long_fast"}),
        new Trivia("Max hops in a Meshtastic packet?", "7", new String[]{"seven"}),
        new Trivia("Which river separates Arlington from DC?", "potomac", new String[]{}),
        new Trivia("The SX1262 is made by which company?", "semtech", new String[]{}),
        new Trivia("Meshtastic's default channel is called?", "longfast", new String[]{"long fast", "primary"}),
        new Trivia("What does SNR stand for? (signal-to-___ ratio)", "noise", new String[]{"signal to noise", "signal-to-noise"}),
        new Trivia("Which Meshtastic role never rebroadcasts?", "client_mute", new String[]{"client mute", "mute"}),
        new Trivia("What is the ISM band's upper edge in MHz?", "928", new String[]{}),
        new Trivia("What year did Arlington County form (from Alexandria County)?", "1920", new String[]{}),
        new Trivia("Meshtastic messages are limited to how many bytes?", "200", new String[]{"228"}),
        new Trivia("What does a NanoVNA measure? (S_R)", "swr", new String[]{"standing wave ratio", "impedance"}),
        new Trivia("Which Metro line runs through Ballston?", "orange", new String[]{"silver", "orange/silver"}),
        new Trivia("A quarter-wave whip for 915 MHz is about how many cm?", "8", new String[]{"8cm", "8 cm", "82mm"}),
        new Trivia("What chip family is in a Station G2?", "esp32", new String[]{"esp32-s3", "esp32s3"}),
        new Trivia("Meshtastic's public key crypto is based on which curve?", "curve25519", new String[]{"x25519", "25519"}),
        new Trivia("Highest spreading factor LoRa supports?", "12", new String[]{"sf12"}),
        new Trivia("Which Meshtastic app shows 'Delivered to mesh'?", "android", new String[]{"phone", "the android app"}),
    };

    /** Five-room text adventure; state is the room index. */
    public static final String[] ROOMS = {
        "You're at a Lyon Village window. A coax cable snakes outside. Exits: N (attic), SOUTH (garage). Try N, SOUTH, or LOOK.",
        "Attic. Dusty, warm, a Rokland antenna hums on the gable. A tinySA lies here. Exits: SOUTH (window), U (roof). TAKE SA?",
        "Garage. A Raspberry Pi blinks in a box. A 12 V supply hangs on a hook. Exits: N (window). TAKE PSU?",
        "Roof! Wind, the DC skyline ENE, packets everywhere. The mesh is yours. Exits: D (attic). You feel −65 dBm.",
        "You mounted the antenna high and clear. Every node in DC hears you. THE END. Send ADV to play again.",
    };
}
