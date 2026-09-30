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
