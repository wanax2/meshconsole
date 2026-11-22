package meshconsole.bbs;

import meshconsole.analysis.WeatherHistory;
import meshconsole.analysis.WeatherObs;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/**
 * 1.29.1: the W / WX reply in plain words for people on the mesh, not pilots: °F first, wind in mph with its
 * direction and gusts, "feels like", sky cover, visibility, rain/snow/fog in words, humidity and the pressure
 * trend. Built from the latest METAR (sky cover read from its raw text) plus the one three hours earlier.
 */
final class WxReply {
    private WxReply() { }

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("KDCA", "Reagan National"), Map.entry("KIAD", "Dulles"), Map.entry("KBWI", "BWI"),
            Map.entry("KADW", "Andrews"), Map.entry("KSYR", "Syracuse"), Map.entry("KITH", "Ithaca"),
            Map.entry("KROC", "Rochester"), Map.entry("KBUF", "Buffalo"), Map.entry("KELM", "Elmira"),
            Map.entry("KPEO", "Penn Yan"), Map.entry("KFZY", "Fulton"));
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    /** "Reagan National 3:52 PM: 48°F (9°C), feels 43. Overcast, low cloud 2,500 ft. Light rain. Wind NW 12 mph, gusts 24. Vis 4 mi. Humidity 80%. Pressure 29.92 inHg, falling." */
    static String now(WeatherObs o, WeatherHistory h) {
        StringBuilder s = new StringBuilder();
        s.append(NAMES.getOrDefault(o.station(), o.station())).append(' ')
         .append(HM.format(Instant.ofEpochMilli(o.time()).atZone(ZoneId.systemDefault())));
        long ageMin = (System.currentTimeMillis() - o.time()) / 60_000;
        if (ageMin > 90) s.append(" (").append(ageMin / 60).append(" h old)");
        s.append(": ");
        double f = o.tempC() * 9 / 5 + 32, mph = Double.isNaN(o.windKt()) ? Double.NaN : o.windKt() * 1.15078;
        if (!Double.isNaN(f)) {
            s.append(Math.round(f)).append("°F (").append(Math.round(o.tempC())).append("°C)");
            double feels = feelsLike(f, mph, o.humidityPct());
            if (!Double.isNaN(feels) && Math.abs(feels - f) >= 2) s.append(", feels ").append(Math.round(feels));
            s.append(". ");
        }
        String sky = sky(o.raw());
        if (!sky.isEmpty()) s.append(sky).append(". ");
        String wx = words(o.wx());
        if (!wx.isEmpty()) s.append(cap(wx)).append(". ");
        s.append(wind(o, mph)).append(". ");
        if (!Double.isNaN(o.visibilityMi()) && o.visibilityMi() < 10)
            s.append("Vis ").append(o.visibilityMi() < 1 ? String.format(Locale.US, "%.1f", o.visibilityMi()) : String.valueOf(Math.round(o.visibilityMi()))).append(" mi. ");
        if (!Double.isNaN(o.humidityPct())) s.append("Humidity ").append(Math.round(o.humidityPct())).append("%. ");
        if (!Double.isNaN(o.pressureHpa())) {
            double inHg = o.pressureHpa() / 33.8639;
            s.append(String.format(Locale.US, "Pressure %.2f inHg", inHg));
            WeatherObs before = h == null ? null : h.at(o.time() - 3 * 3600_000L);
            if (before != null && !Double.isNaN(before.pressureHpa()) && o.time() - before.time() > 2 * 3600_000L) {
                double d = inHg - before.pressureHpa() / 33.8639;
                s.append(d >= 0.06 ? ", rising fast" : d >= 0.02 ? ", rising" : d <= -0.06 ? ", falling fast" : d <= -0.02 ? ", falling" : ", steady");
            }
            s.append('.');
        }
        String out = s.toString().trim();
        // keep it to one mesh packet (200 bytes): drop humidity first, then the pressure figure (keeping its trend)
        if (bytes(out) > 200) out = out.replaceFirst(" Humidity \\d+%\\.", "");
        if (bytes(out) > 200) out = out.replaceFirst("Pressure [0-9.]+ inHg, ", "Pressure ").replaceFirst(" Pressure [0-9.]+ inHg\\.", "");
        return out;
    }

    private static int bytes(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }

    /** Short line for the daily subscriber digest. */
    static String digest(WeatherObs o, WeatherHistory h) { return "WX " + now(o, h); }

    // ---------------------------------------------------------------- pieces

    static String wind(WeatherObs o, double mph) {
        if (Double.isNaN(mph)) return "Wind not reported";
        if (mph < 1) return "Calm";
        String dir = Double.isNaN(o.windDir()) ? "variable" : compass(o.windDir());
        StringBuilder w = new StringBuilder("Wind ").append(dir).append(' ').append(Math.round(mph)).append(" mph");
        if (!Double.isNaN(o.gustKt()) && o.gustKt() > o.windKt()) w.append(", gusts ").append(Math.round(o.gustKt() * 1.15078));
        return w.toString();
    }

    static String compass(double deg) {
        String[] p = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return p[(int) Math.round(((deg % 360) + 360) % 360 / 22.5) % 16];
    }

    /** Wind chill at 50 °F and below with wind over 3 mph; heat index at 80 °F and above; otherwise NaN. */
    static double feelsLike(double f, double mph, double rh) {
        if (f <= 50 && !Double.isNaN(mph) && mph > 3) {
            double v = Math.pow(mph, 0.16);
            return 35.74 + 0.6215 * f - 35.75 * v + 0.4275 * f * v;
        }
        if (f >= 80 && !Double.isNaN(rh)) {
            return -42.379 + 2.04901523 * f + 10.14333127 * rh - 0.22475541 * f * rh - 6.83783e-3 * f * f
                    - 5.481717e-2 * rh * rh + 1.22874e-3 * f * f * rh + 8.5282e-4 * f * rh * rh - 1.99e-6 * f * f * rh * rh;
        }
        return Double.NaN;
    }

    private static final Pattern LAYER = Pattern.compile("\\b(FEW|SCT|BKN|OVC|VV)(\\d{3})(CB|TCU)?\\b");

    /** Sky cover from the raw METAR: the most-covered layer in words, plus the cloud base when it's broken or overcast. */
    static String sky(String raw) {
        if (raw == null) return "";
        String body = raw.split(" RMK ", 2)[0];
        if (body.matches(".*\\b(CLR|SKC|NCD|NSC)\\b.*")) return "Clear skies";
        if (body.contains("CAVOK")) return "Clear skies";
        Matcher m = LAYER.matcher(body);
        int worst = -1, ceilFt = -1; boolean storm = false;
        List<String> order = List.of("FEW", "SCT", "BKN", "OVC", "VV");
        while (m.find()) {
            int rank = order.indexOf(m.group(1)), ft = Integer.parseInt(m.group(2)) * 100;
            if (rank > worst) worst = rank;
            if (rank >= 2 && ceilFt < 0) ceilFt = ft;
            if (m.group(3) != null) storm = true;
        }
        if (worst < 0) return "";
        String s = switch (worst) {
            case 0 -> "A few clouds";
            case 1 -> "Partly cloudy";
            case 2 -> "Mostly cloudy";
            case 3 -> "Overcast";
            default -> "Sky hidden";
        };
        if (ceilFt >= 0 && ceilFt < 10_000) s += ", cloud base " + String.format(Locale.US, "%,d", ceilFt) + " ft";
        if (storm) s += ", storm clouds";
        return s;
    }

    private static final Map<String, String> CODES = new LinkedHashMap<>();
    static {
        CODES.put("TS", "thunderstorm"); CODES.put("FZRA", "freezing rain"); CODES.put("FZDZ", "freezing drizzle");
        CODES.put("FZFG", "freezing fog"); CODES.put("SHRA", "rain showers"); CODES.put("SHSN", "snow showers");
        CODES.put("BLSN", "blowing snow"); CODES.put("RA", "rain"); CODES.put("SN", "snow"); CODES.put("DZ", "drizzle");
        CODES.put("PL", "sleet"); CODES.put("GR", "hail"); CODES.put("GS", "small hail"); CODES.put("SG", "snow grains");
        CODES.put("UP", "precipitation"); CODES.put("FG", "fog"); CODES.put("BR", "mist"); CODES.put("HZ", "haze");
        CODES.put("FU", "smoke"); CODES.put("DU", "dust"); CODES.put("SQ", "squalls");
    }

    /** "-RA BR" → "light rain, mist"; "+TSRA" → "heavy thunderstorm, rain"; "VCSH" → "showers nearby". */
    static String words(String wx) {
        if (wx == null || wx.isBlank()) return "";
        List<String> out = new ArrayList<>();
        for (String g : wx.trim().split("\\s+")) {
            String pre = "", post = "";
            if (g.startsWith("-")) { pre = "light "; g = g.substring(1); }
            else if (g.startsWith("+")) { pre = "heavy "; g = g.substring(1); }
            if (g.startsWith("VC")) { post = " nearby"; g = g.substring(2); }
            if (g.equals("SH")) { out.add(pre + "showers" + post); continue; }
            List<String> parts = new ArrayList<>();
            String rest = g;
            outer:
            while (!rest.isEmpty()) {
                for (var e : CODES.entrySet()) if (rest.startsWith(e.getKey())) { parts.add(e.getValue()); rest = rest.substring(e.getKey().length()); continue outer; }
                rest = rest.substring(Math.min(2, rest.length()));   // unknown code: skip it
            }
            if (!parts.isEmpty()) out.add(pre + String.join(", ", parts) + post);
        }
        return String.join(", ", out);
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
