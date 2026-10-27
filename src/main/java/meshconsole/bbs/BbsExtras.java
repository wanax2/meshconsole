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

    static String get(String url, String... headers) throws IOException {
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

    // ---- NWS alerts ---------------------------------------------------------------------------
    public record Alert(String id, String event, String headline, String ends, String severity) { }
    public static List<Alert> nwsAlerts(double lat, double lon) throws IOException {
        Map<?, ?> root = (Map<?, ?>) Json.parse(get(String.format(java.util.Locale.ROOT, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", lat, lon), "Accept", "application/geo+json"));
        List<Alert> out = new ArrayList<>();
        for (Object o : (List<?>) root.get("features")) {
            Map<?, ?> f = (Map<?, ?>) o; Map<?, ?> pr = (Map<?, ?>) f.get("properties"); if (pr == null) continue;
            out.add(new Alert(Json.str(f.get("id")), Json.str(pr.get("event")), Json.str(pr.get("headline")), Json.str(pr.get("ends")), Json.str(pr.get("severity"))));
        }
        return out;
    }
    public static String alertsLine(double lat, double lon) {
        if (lat == 0) return "No position set on this board.";
        try {
            List<Alert> al = nwsAlerts(lat, lon);
            if (al.isEmpty()) return "NWS: no active alerts for this area.";
            StringBuilder sb = new StringBuilder("NWS: ");
            for (Alert a : al) { String item = a.event() + (a.ends().length() >= 16 ? " until " + a.ends().substring(11, 16) : "") + "; "; if (sb.length() + item.length() > 190) { sb.append("(+more)"); break; } sb.append(item); }
            return sb.toString().trim();
        } catch (IOException | RuntimeException e) { return "NWS alerts unavailable (" + e.getMessage() + ")"; }
    }

    // ---- scores (ESPN public scoreboard) -------------------------------------------------------
    private static String espn(String sport, String league, String team) {
        try {
            Map<?, ?> root = (Map<?, ?>) Json.parse(get("https://site.api.espn.com/apis/site/v2/sports/" + sport + "/" + league + "/scoreboard"));
            for (Object e : (List<?>) root.get("events")) {
                Map<?, ?> ev = (Map<?, ?>) e; Map<?, ?> comp = (Map<?, ?>) ((List<?>) ev.get("competitions")).get(0);
                List<?> cs = (List<?>) comp.get("competitors"); boolean ours = false;
                for (Object c : cs) if (Json.str(((Map<?, ?>) ((Map<?, ?>) c).get("team")).get("displayName")).contains(team)) ours = true;
                if (!ours) continue;
                Map<?, ?> st = (Map<?, ?>) ((Map<?, ?>) comp.get("status")).get("type");
                StringBuilder sb = new StringBuilder();
                for (Object c : cs) { Map<?, ?> cm = (Map<?, ?>) c; sb.append(Json.str(((Map<?, ?>) cm.get("team")).get("abbreviation"))).append(' ').append(Json.str(cm.get("score"))).append(sb.length() < 10 ? " - " : ""); }
                return sb + "  " + Json.str(st.get("shortDetail"));
            }
            return team + ": no game today.";
        } catch (IOException | RuntimeException e) { return team + " score unavailable (" + e.getMessage() + ")"; }
    }
    public static String scores() { return "Bills: " + espn("football", "nfl", "Bills") + " | Sabres: " + espn("hockey", "nhl", "Sabres"); }

    // ---- sun, moon, ISS -------------------------------------------------------------------------
    public static String sun(double lat, double lon) {
        if (lat == 0) return "No position set on this board.";
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        double[] rs = sunRiseSet(lat, lon, now.toLocalDate());
        String rise = rs[0] < 0 ? "none" : hhmm(rs[0], now), set = rs[1] < 0 ? "none" : hhmm(rs[1], now);
        double phase = moonPhase(now); String name = phase < 0.03 || phase > 0.97 ? "new" : phase < 0.22 ? "waxing crescent" : phase < 0.28 ? "first quarter" : phase < 0.47 ? "waxing gibbous" : phase < 0.53 ? "full" : phase < 0.72 ? "waning gibbous" : phase < 0.78 ? "last quarter" : "waning crescent";
        return String.format("Sunrise %s, sunset %s (local). Moon: %s, %.0f%% lit.", rise, set, name, 50 * (1 - Math.cos(2 * Math.PI * phase)));
    }
    private static String hhmm(double utcHours, java.time.ZonedDateTime ref) {
        java.time.ZonedDateTime t = ref.withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).plusSeconds((long) (utcHours * 3600)).withZoneSameInstant(ref.getZone());
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
    /** NOAA-style approximation; returns {rise, set} in UTC hours, -1 when the sun doesn't rise/set. */
    static double[] sunRiseSet(double lat, double lon, java.time.LocalDate d) {
        int n = d.getDayOfYear(); double lngHour = lon / 15;
        double[] out = new double[2];
        for (int i = 0; i < 2; i++) {
            double t = n + ((i == 0 ? 6 : 18) - lngHour) / 24;
            double M = (0.9856 * t) - 3.289;
            double L = M + (1.916 * Math.sin(Math.toRadians(M))) + (0.020 * Math.sin(Math.toRadians(2 * M))) + 282.634; L = (L + 360) % 360;
            double RA = Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(L)))); RA = (RA + 360) % 360;
            RA = RA + (Math.floor(L / 90) * 90 - Math.floor(RA / 90) * 90); RA /= 15;
            double sinDec = 0.39782 * Math.sin(Math.toRadians(L)), cosDec = Math.cos(Math.asin(sinDec));
            double cosH = (Math.cos(Math.toRadians(90.833)) - (sinDec * Math.sin(Math.toRadians(lat)))) / (cosDec * Math.cos(Math.toRadians(lat)));
            if (cosH > 1 || cosH < -1) { out[i] = -1; continue; }
            double H = i == 0 ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH)); H /= 15;
            double T = H + RA - (0.06571 * t) - 6.622; double UT = T - lngHour; out[i] = ((UT % 24) + 24) % 24;
        }
        return out;
    }
    static double moonPhase(java.time.ZonedDateTime now) {
        double days = (now.toEpochSecond() - 947182440L) / 86400.0;   // new moon 2000-01-06 18:14 UTC
        double p = (days / 29.530588853) % 1; return p < 0 ? p + 1 : p;
    }
    public static String iss(double lat, double lon) {
        try {
            Map<?, ?> m = (Map<?, ?>) Json.parse(get("https://api.wheretheiss.at/v1/satellites/25544"));
            double ilat = Json.num(m.get("latitude"), 0), ilon = Json.num(m.get("longitude"), 0), alt = Json.num(m.get("altitude"), 0);
            String vis = Json.str(m.get("visibility"));
            double km = lat == 0 ? Double.NaN : meshconsole.analysis.Metar.distanceKm(lat, lon, ilat, ilon);
            String where = String.format("%.0f%s %.0f%s", Math.abs(ilat), ilat >= 0 ? "N" : "S", Math.abs(ilon), ilon >= 0 ? "E" : "W");
            return "ISS now at " + where + ", " + (int) alt + " km up, " + (Double.isNaN(km) ? "" : (int) km + " km from here, ") + "in " + vis + (km < 1500 ? " – look up!" : ".");
        } catch (IOException | RuntimeException e) { return "ISS position unavailable (" + e.getMessage() + ")"; }
    }

    // ---- HF propagation (hamqsl.com) ------------------------------------------------------------
    public static String solar() {
        try {
            String xml = get("https://www.hamqsl.com/solarxml.php");
            String sfi = tag(xml, "solarflux"), k = tag(xml, "kindex"), a = tag(xml, "aindex"), ss = tag(xml, "sunspots"), xray = tag(xml, "xray"), geo = tag(xml, "geomagfield");
            StringBuilder sb = new StringBuilder("SFI " + sfi + " SN " + ss + " A " + a + " K " + k + " X-ray " + xray + " geomag " + geo + ". Day: ");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<band name=\"([^\"]+)\" time=\"day\">([^<]+)</band>").matcher(xml);
            while (m.find()) sb.append(m.group(1)).append(' ').append(m.group(2)).append(", ");
            String s = sb.toString().replaceAll(", $", ""); return s.length() > 195 ? s.substring(0, 192) + "…" : s;
        } catch (IOException | RuntimeException e) { return "Solar data unavailable (" + e.getMessage() + ")"; }
    }
    private static String tag(String xml, String t) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("<" + t + ">\\s*([^<]*?)\\s*</" + t + ">").matcher(xml); return m.find() ? m.group(1) : "?"; }

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

    /** Built-in bank plus the lines of <data>/trivia.txt (question|answer|alt1;alt2), re-read when the file changes. */
    private static List<Trivia> bank; private static long bankStamp = -1;
    public static synchronized List<Trivia> trivia() {
        java.nio.file.Path f = meshconsole.DataDir.file("trivia.txt");
        long stamp = 0; try { stamp = java.nio.file.Files.exists(f) ? java.nio.file.Files.getLastModifiedTime(f).toMillis() : 0; } catch (IOException ignored) { }
        if (bank != null && stamp == bankStamp) return bank;
        List<Trivia> l = new ArrayList<>(Arrays.asList(TRIVIA));
        if (stamp > 0) try {
            for (String line : java.nio.file.Files.readAllLines(f, StandardCharsets.UTF_8)) {
                line = line.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\|");
                if (p.length < 2) continue;
                String[] alts = p.length > 2 ? p[2].split(";") : new String[0];
                for (int i = 0; i < alts.length; i++) alts[i] = alts[i].trim().toLowerCase();
                l.add(new Trivia(p[0].trim(), p[1].trim().toLowerCase(), alts));
            }
        } catch (IOException ignored) { }
        bank = l; bankStamp = stamp; return bank;
    }

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
        new Trivia("Only NFL team to play in four straight Super Bowls?", "bills", new String[]{"buffalo", "buffalo bills", "the bills"}),
        new Trivia("The Bills' stadium is in which NY town?", "orchard park", new String[]{"orchardpark", "orchard"}),
        new Trivia("Jim Kelly's jersey number?", "12", new String[]{"twelve"}),
        new Trivia("The Bills' no-huddle offense of the early 90s was called the ___ offense", "k-gun", new String[]{"k gun", "kgun"}),
        new Trivia("Marv Levy: 'Where would you rather be than right ___, right now?'", "here", new String[]{}),
        new Trivia("How many hops from Orchard Park NY to this board? (hint: it's the hop limit)", "3", new String[]{"three"}),
        new Trivia("The Sabres' 1970s top line, Perreault-Martin-Robert, was nicknamed the ___ Connection", "french", new String[]{"the french connection", "french connection"}),
        new Trivia("Sabres goalie known as The Dominator?", "hasek", new String[]{"dominik hasek", "dominic hasek"}),
        new Trivia("Buffalo wings were invented at which bar in 1964?", "anchor bar", new String[]{"anchor", "the anchor bar"}),
        new Trivia("Roast beef on a kummelweck roll with horseradish is called beef on ___", "weck", new String[]{"beef on weck", "kummelweck"}),
        new Trivia("Buffalo's long-gone hockey arena, 1940-1996, was nicknamed The ___", "aud", new String[]{"the aud", "auditorium", "memorial auditorium"}),
        // --- more: mesh and radio
        new Trivia("How many bytes can a Meshtastic text message carry at most?", "200", new String[]{"200 bytes"}),
        new Trivia("Which chip is the brain of a T-Deck or Station G2?", "esp32", new String[]{"esp32-s3", "esp32s3", "espressif"}),
        new Trivia("What does PKI stand for in Meshtastic direct messages?", "public key infrastructure", new String[]{"public key", "public-key infrastructure"}),
        new Trivia("Meshtastic's LongFast preset uses what spreading factor?", "11", new String[]{"sf11"}),
        new Trivia("Bandwidth of the LongFast preset, in kHz?", "250", new String[]{"250khz", "250 khz"}),
        new Trivia("Which node role never rebroadcasts other nodes' packets?", "client_mute", new String[]{"client mute", "mute"}),
        new Trivia("The CLIENT_BASE role gives priority to which nodes?", "favorites", new String[]{"favourites", "favorite", "favourite", "favorited nodes"}),
        new Trivia("Meshtastic DMs show 'Delivered to mesh' when what is heard?", "rebroadcast", new String[]{"a rebroadcast", "relay", "a relay", "another node repeating it"}),
        new Trivia("What is the FCC's EIRP limit for 915 MHz mesh nodes, in dBm?", "36", new String[]{"36 dbm", "4 watts", "4w"}),
        new Trivia("What company makes the Station G2?", "b&q", new String[]{"bq", "b and q", "b & q", "bqvoy", "b&q voyage"}),
        new Trivia("A yagi's longest element is called the?", "reflector", new String[]{"the reflector"}),
        new Trivia("A quarter-wave whip needs what beneath it to work well?", "ground plane", new String[]{"a ground plane", "groundplane", "ground"}),
        new Trivia("What does a cavity filter remove from a receiver's input?", "out-of-band signals", new String[]{"interference", "out of band", "cell signals", "cellular", "noise", "out-of-band"}),
        new Trivia("An LNA goes where in a receiver chain?", "first", new String[]{"at the front", "front end", "before the radio", "right after the antenna", "input"}),
        new Trivia("Meshtastic uses which spread-spectrum modulation?", "lora", new String[]{"chirp spread spectrum", "css"}),
        new Trivia("A node's !id is derived from what?", "mac address", new String[]{"mac", "its mac address", "the mac"}),
        new Trivia("Hop limit default on Meshtastic?", "3", new String[]{"three"}),
        new Trivia("What unit is RSSI measured in?", "dbm", new String[]{"dBm", "decibel-milliwatts", "decibels"}),
        new Trivia("Positive SNR means the signal is ___ than the noise.", "stronger", new String[]{"louder", "above", "bigger", "greater"}),
        // --- ham radio
        new Trivia("Morse code for the letter S?", "...", new String[]{"dot dot dot", "di di dit", "sss"}),
        new Trivia("What does 73 mean at the end of a message?", "best regards", new String[]{"regards", "best wishes", "goodbye", "bye"}),
        new Trivia("What does QRZ mean?", "who is calling", new String[]{"who is calling me", "who's calling"}),
        new Trivia("The 2-meter ham band is around what frequency in MHz?", "146", new String[]{"144", "145", "144-148", "144 to 148"}),
        new Trivia("Which US agency licenses amateur radio operators?", "fcc", new String[]{"the fcc", "federal communications commission"}),
        new Trivia("The entry-level US ham license class today is?", "technician", new String[]{"tech"}),
        new Trivia("SFI in a solar report stands for?", "solar flux index", new String[]{"solar flux"}),
        new Trivia("A K-index of 0 means the geomagnetic field is?", "quiet", new String[]{"calm", "stable"}),
        new Trivia("What does CQ mean?", "calling any station", new String[]{"seek you", "calling anyone", "anyone"}),
        new Trivia("The ARRL is headquartered in which state?", "connecticut", new String[]{"ct", "newington"}),
        new Trivia("What is a 'net' in ham radio?", "a scheduled on-air meeting", new String[]{"scheduled meeting", "on-air meeting", "a meeting", "scheduled contact", "roundtable"}),
        new Trivia("Which US radio service needs no license: FRS, GMRS, or ham?", "frs", new String[]{"family radio service"}),
        // --- DC, Arlington and the region
        new Trivia("Which Metro line is the only one with no stops in Virginia?", "red", new String[]{"red line"}),
        new Trivia("Arlington's tallest building is in which neighborhood?", "rosslyn", new String[]{}),
        new Trivia("Which bridge carries I-66 over the Potomac?", "roosevelt", new String[]{"theodore roosevelt", "theodore roosevelt bridge", "roosevelt bridge"}),
        new Trivia("The airport code for Reagan National?", "dca", new String[]{}),
        new Trivia("Which Metro station serves the Pentagon?", "pentagon", new String[]{"pentagon station"}),
        new Trivia("Clarendon, Courthouse, Ballston: which comes first heading east?", "ballston", new String[]{}),
        new Trivia("The Washington Monument is how tall, in feet (nearest 5)?", "555", new String[]{"555 feet", "555ft"}),
        new Trivia("Which river joins the Potomac at Washington?", "anacostia", new String[]{"the anacostia"}),
        new Trivia("Arlington National Cemetery sits on land once owned by which general?", "lee", new String[]{"robert e lee", "robert e. lee", "robert lee"}),
        new Trivia("The Beltway is which interstate?", "495", new String[]{"i-495", "i495", "interstate 495"}),
        new Trivia("Name the only US state bordering DC besides Virginia.", "maryland", new String[]{"md"}),
        new Trivia("Which county is Tysons in?", "fairfax", new String[]{"fairfax county"}),
        new Trivia("The DC Metro opened in what year?", "1976", new String[]{}),
        new Trivia("WTOP broadcasts on what FM frequency?", "103.5", new String[]{"103.5 fm"}),
        new Trivia("Arlington's ZIP code for Lyon Village?", "22201", new String[]{}),
        // --- general / fun
        new Trivia("How many hops does light take to cross the room? (trick question)", "0", new String[]{"zero", "none"}),
        new Trivia("What year did the first BBS go online (CBBS, Chicago)?", "1978", new String[]{}),
        new Trivia("A baud is a unit of?", "symbols per second", new String[]{"symbol rate", "signaling rate", "symbols/second"}),
        new Trivia("The first message sent over ARPANET was meant to be LOGIN; what got through?", "lo", new String[]{"l o"}),
        new Trivia("What does RTFM stand for? (keep it clean)", "read the manual", new String[]{"read the fine manual", "read the friendly manual", "read the manual first"}),
        new Trivia("How many bits in a byte?", "8", new String[]{"eight"}),
        new Trivia("What does 'de' mean before a callsign in a message?", "from", new String[]{"this is", "from the station"}),
        // --- computing history
        new Trivia("Who wrote the first compiler, for the A-0 system?", "grace hopper", new String[]{"hopper", "admiral hopper"}),
        new Trivia("The first commercially successful microprocessor, 1971?", "4004", new String[]{"intel 4004"}),
        new Trivia("What did the first ever web page describe?", "the world wide web", new String[]{"the web", "www", "world wide web", "the project"}),
        new Trivia("Unix time starts on 1 January of which year?", "1970", new String[]{}),
        new Trivia("What year did Unix time hit one billion seconds?", "2001", new String[]{}),
        new Trivia("The 'bug' Grace Hopper's team taped into a logbook was what insect?", "moth", new String[]{"a moth"}),
        new Trivia("Which company sold the Altair 8800?", "mits", new String[]{}),
        new Trivia("Ken Thompson and Dennis Ritchie built Unix at which lab?", "bell labs", new String[]{"bell", "bell laboratories", "at&t bell labs"}),
        new Trivia("Which Apple computer was the first with a mouse, 1983?", "lisa", new String[]{"apple lisa"}),
        new Trivia("The Commodore 64 had how many kilobytes of RAM?", "64", new String[]{"64k", "64kb"}),
        new Trivia("What does GNU stand for?", "gnu's not unix", new String[]{"gnus not unix", "gnu is not unix"}),
        new Trivia("Linus Torvalds posted Linux 0.01 in what year?", "1991", new String[]{}),
        new Trivia("Who is credited with the term 'software engineering', 1960s NASA?", "margaret hamilton", new String[]{"hamilton"}),
        new Trivia("The IBM PC shipped in 1981 with which CPU?", "8088", new String[]{"intel 8088"}),
        new Trivia("What was the top speed of a 300-baud modem, in bits per second?", "300", new String[]{"300 bps", "300bps"}),
        new Trivia("Which BBS software ran on IBM PCs and had 'doors'? (W___)", "wwiv", new String[]{}),
        new Trivia("FidoNet's weekly mail exchange was nicknamed?", "zone mail hour", new String[]{"zmh", "national mail hour"}),
        new Trivia("What does the 'C' in 'C programming language' come from?", "b", new String[]{"the b language", "it followed b"}),
        new Trivia("Who wrote 'The Mythical Man-Month'?", "fred brooks", new String[]{"brooks", "frederick brooks"}),
        new Trivia("Which error made Mariner 1 crash in 1962? (a missing ___)", "hyphen", new String[]{"overbar", "a hyphen"}),
        new Trivia("What year did the Morris worm hit the internet?", "1988", new String[]{}),
        new Trivia("The first computer game played on a PDP-1, 1962?", "spacewar", new String[]{"spacewar!", "space war"}),
        new Trivia("What company made the TRS-80?", "tandy", new String[]{"radio shack", "radioshack", "tandy radio shack"}),
        new Trivia("The QWERTY layout was designed for which machine?", "typewriter", new String[]{"the typewriter", "sholes typewriter"}),
        new Trivia("Who designed the Difference Engine?", "babbage", new String[]{"charles babbage"}),
        new Trivia("Who is often called the first programmer?", "ada lovelace", new String[]{"lovelace", "ada"}),
        new Trivia("ENIAC was completed in which year?", "1945", new String[]{"1946"}),
        new Trivia("Which language did Dijkstra say 'cripples the mind'?", "basic", new String[]{}),
        new Trivia("What does 'Hello, World' first appear in, 1978?", "the c programming language", new String[]{"k&r", "kernighan and ritchie", "k&r c", "the c book"}),
        new Trivia("Which company's slogan was 'Think'? (before Apple's 'Think different')", "ibm", new String[]{}),
        // --- nerd general
        new Trivia("What's the answer to life, the universe and everything?", "42", new String[]{"forty-two", "forty two"}),
        new Trivia("In binary, what is 1010?", "10", new String[]{"ten"}),
        new Trivia("What is 0x1F in decimal?", "31", new String[]{}),
        new Trivia("How many values can one byte hold?", "256", new String[]{}),
        new Trivia("Which HTTP status code means 'I'm a teapot'?", "418", new String[]{}),
        new Trivia("Which port does SSH use by default?", "22", new String[]{}),
        new Trivia("What does 'sudo' stand for?", "superuser do", new String[]{"substitute user do", "super user do"}),
        new Trivia("Which text editor is famously hard to exit?", "vim", new String[]{"vi"}),
        new Trivia("What does RAID 0 give you? (speed or safety)", "speed", new String[]{"striping", "performance"}),
        new Trivia("What is the speed of light in km/s, nearest thousand?", "300000", new String[]{"300,000", "300k", "299792"}),
        new Trivia("How many bits is an IPv4 address?", "32", new String[]{}),
        new Trivia("Which planet has the Great Red Spot?", "jupiter", new String[]{}),
        new Trivia("Which number is both a square and a cube, under 100 and over 1?", "64", new String[]{}),
        new Trivia("What is the only even prime number?", "2", new String[]{"two"}),
        new Trivia("Which Star Trek captain is from Iowa?", "kirk", new String[]{"james kirk", "james t kirk", "james t. kirk"}),
        new Trivia("The TARDIS is bigger on the ___.", "inside", new String[]{"the inside"}),
        new Trivia("Who wrote Neuromancer?", "william gibson", new String[]{"gibson"}),
        new Trivia("What does 'PEBKAC' describe?", "user error", new String[]{"problem exists between keyboard and chair", "the user", "a user problem"}),
        new Trivia("Which game is Konami's code from? (up up down down...)", "contra", new String[]{"gradius"}),
        new Trivia("What is the chemical symbol for tungsten?", "w", new String[]{}),
        new Trivia("Which year did the first Doom release?", "1993", new String[]{}),
        new Trivia("The Voyager probes carry a golden what?", "record", new String[]{"golden record", "a record"}),
        new Trivia("What's a 'magic smoke' joke about?", "letting the smoke out of electronics", new String[]{"smoke", "burnt electronics", "the smoke", "when a chip burns", "letting the smoke out"}),
        new Trivia("How many keys on a standard piano?", "88", new String[]{}),
        new Trivia("What do you call a 1 followed by 100 zeros?", "googol", new String[]{"a googol"}),
        new Trivia("Which Linux command shows who is logged in?", "who", new String[]{"w", "users"}),
        new Trivia("The first message on the Meshtastic mesh you're on was probably?", "test", new String[]{"testing", "hello", "ping"}),
    };

    /** Sly Buffalo Bills material. The sysop is from Amherst, NY; the mesh is in Virginia; the board doesn't care. */
    public static final String[] BILLS = {
        "GO BILLS. Where would you rather be than right here, right now? - Marv Levy",
        "Bills Mafia, Lyon Village chapter. Yes, in 22201. Yes, we know.",
        "Four straight Super Bowls. Nobody else has done it. We don't talk about the scores.",
        "Scott Norwood is a good man and we will hear no more about it.",
        "Josh Allen hurdles linebackers. This board hurdles a cell site at 860 MHz. Same energy.",
        "The table is set. (We do not jump through it. On this board.)",
        "Orchard Park, NY: 900 miles and three hops from here. Longest path on the mesh.",
        "Music City Miracle: forward lateral. The board stands by this.",
        "K-Gun offense, 1990. Kelly, Thomas, Reed, Lofton. Still the best thing on a 915 MHz channel.",
        "Highmark Stadium wind chill advisory in effect. Also it is October in Arlington.",
        "Jim Kelly wore 12. Thurman Thomas wore 34. Bruce Smith wore 78. Hop limit is 3. Some numbers you just know.",
        "Wide right? Never heard of it. Try NEXT ROSSLYN for a train that arrives.",
        "Bills Mafia rules of the mesh: be kind, leave a bulletin, and if a folding table is involved, we weren't there.",
        "Nathan Peterman started a game once. This board has also had bad days. We move on.",
        "Fourth and 1. Josh Allen is going to run it. Everyone knows. Nobody can stop it. Like LongFast on slot 20.",
        "The 13 seconds happened. We acknowledge it the way we acknowledge the cell site at 860 MHz: with a filter.",
        "Thurman Thomas lost his helmet before Super Bowl XXVI. This board checks its antenna connector every morning.",
        "Stefon Diggs caught 1,000 yards in a Buffalo winter. The 1W catches 300 nodes in an Arlington one.",
        "Pat McAfee once said the Bills fans are 'the most insane'. Correct. Also the most on-time with NEXT ROSSLYN.",
        "Ralph Wilson Stadium. The Ralph. Rich Stadium before that. Highmark now. The wind hasn't changed its name.",
        "Von Miller, Tre'Davious White, Dawson Knox: fine people. Knox is on the trivia bank. Try TRIVIA.",
        "Bills-Dolphins week. This board rebroadcasts everything except Dolphins fans.",
        "Chicken wing count at Highmark on game day: classified. Higher than the node count on this mesh.",
        "OJ ran for 2,003 yards in 1973. We're done talking about OJ.",
        "The Bills' fight song is 'Shout'. The mesh's fight song is the airtime warning at 25%.",
        "'Circle the wagons' - Chris Berman, every Sunday, 1990-1993. This board circles the wagons at hop 3.",
    };
    public static String bills() { return BILLS[new Random().nextInt(BILLS.length)]; }

    public static final String[] SABRES = {
        "GO SABRES. The French Connection: Perreault, Martin, Robert. Still the best line on any channel.",
        "No Goal. 1999. Brett Hull's skate was in the crease. This board has reviewed the tape.",
        "Dominik Hasek, The Dominator. Six Vezinas, two Harts, one glove on the ice.",
        "The Aud, Memorial Auditorium, 1940-1996. Orange seats, blue seats, gold seats. Three hops of nostalgia.",
        "Rick Jeanneret: 'Top shelf where mama hides the cookies!' This board hides its cookies in bbs.json.",
        "Sabres playoff drought: we count it in LongFast packets now. It's a big number.",
        "Gilbert Perreault wore 11. Hop limit is 3. Some numbers go up in the rafters anyway.",
        "Blue and gold since 1970. Not black and red. We don't talk about the goat head.",
        "Buffalo: two major-league teams, zero championships, infinite loyalty. Also two cavity filters.",
        "Tage Thompson's shot comes off the stick at 100 mph. The G2 hears it at -102 dBm.",
        "Ryan Miller stood on his head in 2010. The yagi stood backwards for a day. Both forgiven.",
        "Pat LaFontaine, 148 points, 1992-93. The same year Amherst Central graduated its best class.",
        "'May Day! May Day!' - Brad May, 1993, overtime. The board's auto-reconnect sounds the same.",
        "The Sabres' goat-head logo (1996-2006) is the yagi pointed the wrong way of hockey logos.",
        "Rob Ray fought everyone. The cavity filter fights the cell site. Both undefeated at home.",
        "Daniel Briere, Chris Drury, the 2006-07 Sabres: the best team never to win it. Relatable.",
        "Alexander Mogilny, number 89, left Russia for Buffalo. Your packet left Petworth for Lyon Village. Both long trips.",
        "KeyBank Center sits where The Aud's parking lot was. Progress, of a kind. Like firmware 2.7.26.",
        "A Sabres playoff game in May means lake-effect snow in the parking lot. The board does not make the rules.",
    };
    public static String sabres() { return SABRES[new Random().nextInt(SABRES.length)]; }

    public static final String[] BUFFALO = {
        "Buffalo wings were invented at the Anchor Bar, 1964. Everything since has been a rebroadcast.",
        "Beef on weck: roast beef, kummelweck roll, horseradish. Ask for it anywhere else and watch the face.",
        "Lake-effect snow: 7 feet in four days, November 2014. The mesh ran fine. Nobody could get to it.",
        "Sponge candy. If you know, you know. If you don't, you've never been to Buffalo in December.",
        "Loganberry. A drink, a color, a state of mind. Not available south of Erie, PA.",
        "Tim Hortons before it was a chain: 1964, Hamilton. Buffalo adopted it anyway.",
        "Niagara Falls is 20 minutes away and the locals never go. It's still there.",
        "Amherst Central Tigers. Class of '92 is on the mesh. Three hops and thirty years.",
        "The Erie Canal, 1825, ended in Buffalo. First long-haul store-and-forward network in the state.",
        "Shea's, the Albright-Knox, the grain elevators, Wright's Darwin Martin House. More hops than you'd think.",
        "'City of Good Neighbors.' This board tries. Leave a bulletin.",
        "Mighty Taco. Pizza with the cup-and-char pepperoni. Chiavetta's chicken at every fire hall. None of it ships to 22201.",
        "Blizzard of '77. Buffalo closed for a week. The mesh would have been fine; nobody owned a radio.",
        "Ted's Hot Dogs, charcoal, since 1927. The only correct hot dog. Arlington has opinions; Buffalo has Ted's.",
        "Wegmans started in Rochester. Buffalo forgives this because Wegmans.",
        "Dyngus Day, the Monday after Easter: polka, pussy willows, and a parade. Buffalo's second-biggest holiday after any Bills win.",
        "The Peace Bridge to Canada: 20 minutes, one toll, and a Tim Hortons on both sides.",
        "'Talking Proud', 1980. The city's theme song. Nobody can sing it. Everybody knows it.",
        "Delaware Park, Olmsted's, is bigger than the National Mall is wide. Buffalo built it for the trees.",
        "Paula's Donuts. Fourteen Buffalo breakfasts to one DC brunch. The board has done the math.",
        "Buffalo Chicken Wing Festival, Labor Day weekend: 2 million wings. Zero nodes. They'd be too busy to text.",
        "The Skyway, the grain elevators, Canalside in winter: ugly to outsiders, home to everyone else. Like a T-Deck.",
        "Chef's on Seneca Street. Spaghetti Parm. If you know, say so in the guestbook.",
        "SPoT Coffee, Elmwood Avenue. The Starbucks of the 716. Every table has a Bills jersey at it.",
        "Amherst, NY: Boulevard Mall, the Northtown Center, Sweet Home, Williamsville, and one anatomic pathologist on a mesh in Virginia.",
    };
    public static String buffalo() { return BUFFALO[new Random().nextInt(BUFFALO.length)]; }

    /** Five-room text adventure; state is the room index. */
    public static final String[] ROOMS = {
        "You're at a Lyon Village window. A coax cable snakes outside. Exits: N (attic), SOUTH (garage). Try N, SOUTH, or LOOK.",
        "Attic. Dusty, warm, a Rokland antenna hums on the gable. A tinySA lies here. Exits: SOUTH (window), U (roof). TAKE SA?",
        "Garage. A Raspberry Pi blinks in a box. A 12 V supply hangs on a hook. Exits: N (window). TAKE PSU?",
        "Roof! Wind, the DC skyline ENE, packets everywhere. The mesh is yours. Exits: D (attic). You feel −65 dBm.",
        "You mounted the antenna high and clear. Every node in DC hears you. THE END. Send ADV to play again.",
    };
}
