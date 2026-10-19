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
