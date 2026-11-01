package meshconsole.bbs;

import meshconsole.analysis.Json;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Scanner on the BBS, from a ScannerDeck page (its transcripts and incidents): SCAN, FIRE / POLICE / EMS, INC, and
 * SUB SCAN alerts for new fire and crash incidents. Replies are pulled on request, so nothing floods the channel.
 */
final class BbsScanner {
    private BbsScanner() { }
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private static String base(String url) { return url.trim().replaceAll("/+$", ""); }

    /** Short label for a system: ARL, ALX, DC, CAY … (shown on each line so people know where a call is from). */
    static String code(String sysLabel) {
        String l = sysLabel.toLowerCase();
        if (l.startsWith("alex")) return "ALX";
        if (l.startsWith("arl")) return "ARL";
        if (l.contains("district of columbia") || l.startsWith("dc") || l.startsWith("washington")) return "DC";
        String c = sysLabel.replaceAll("[^A-Za-z]", "").toUpperCase();
        return c.length() > 3 ? c.substring(0, 3) : c;
    }

    /** Does a system match the area asked for ("ALX", "Alexandria", "ARL", "DC" …)? Blank = everything. */
    static boolean area(String sysLabel, String want) {
        if (want == null || want.isBlank()) return true;
        String w = want.trim().toLowerCase();
        return code(sysLabel).equalsIgnoreCase(w) || sysLabel.toLowerCase().startsWith(w);
    }

    /** The newest transcribed calls in the last hour, optionally only groups/talkgroups matching a filter. */
    static List<String> latest(String url, String filter, int n) { return latest(url, filter, n, null); }

    static List<String> latest(String url, String filter, int n, String want) {
        List<String> out = new ArrayList<>();
        try {
            long from = System.currentTimeMillis() - 3600_000L;
            Object root = Json.parse(BbsExtras.get(base(url) + "/api/calls?from=" + from + "&limit=2000"));
            List<?> calls = root instanceof List<?> l ? l : List.of();
            String re = filter == null ? null : "(?i).*(" + filter + ").*";
            for (int i = calls.size() - 1; i >= 0 && out.size() < n; i--) {
                Map<?, ?> c = (Map<?, ?>) calls.get(i);
                String text = Json.str(c.get("text")).trim(), tg = Json.str(c.get("tgLabel")), group = Json.str(c.get("group"));
                if (text.isEmpty()) continue;
                if (re != null && !(tg + " " + group).matches(re)) continue;
                String sys = Json.str(c.get("sysLabel"));
                if (!area(sys, want)) continue;
                long t = (long) Double.parseDouble(Json.str(c.get("start")));
                out.add(code(sys) + " " + HM.format(Instant.ofEpochMilli(t)) + " " + shortTg(tg) + ": " + clip(text, 105));
            }
        } catch (Exception e) { out.add("Scanner not reachable right now."); }
        if (out.isEmpty()) out.add("Nothing transcribed in the last hour" + (filter == null ? "." : " for that."));
        return out;
    }

    /** Recent incidents (places named in transcripts), newest first. */
    static List<String> incidents(String url, int hours, int n) { return incidents(url, hours, n, null); }

    static List<String> incidents(String url, int hours, int n, String want) {
        List<String> out = new ArrayList<>();
        try {
            Object root = Json.parse(BbsExtras.get(base(url) + "/api/incidents?hours=" + hours));
            List<?> list = root instanceof List<?> l ? l : List.of();
            for (Object o : list) {
                if (out.size() >= n) break;
                Map<?, ?> i = (Map<?, ?>) o;
                if (!area(Json.str(i.get("sysLabel")), want)) continue;
                long t = (long) Double.parseDouble(Json.str(i.get("last")));
                int calls = i.get("calls") instanceof List<?> cl ? cl.size() : 1;
                out.add(code(Json.str(i.get("sysLabel"))) + " " + HM.format(Instant.ofEpochMilli(t)) + " " + Json.str(i.get("type")).toUpperCase() + " " + Json.str(i.get("place")) + " (" + calls + ")");
            }
        } catch (Exception e) { return List.of("Scanner not reachable right now."); }
        if (out.isEmpty()) out.add("No incidents in the last " + hours + " h.");
        return out;
    }

    /** New fire/crash incidents since the last check, as one-line alerts; ids already sent are skipped. */
    static List<String> newAlerts(String url, Set<String> sent) {
        List<String> out = new ArrayList<>();
        try {
            Object root = Json.parse(BbsExtras.get(base(url) + "/api/incidents?hours=1"));
            List<?> list = root instanceof List<?> l ? l : List.of();
            for (Object o : list) {
                Map<?, ?> i = (Map<?, ?>) o;
                String id = Json.str(i.get("id")), type = Json.str(i.get("type"));
                if (sent.contains(id) || !(type.equals("fire") || type.equals("crash"))) continue;
                sent.add(id);
                String first = "";
                if (i.get("notes") instanceof List<?> notes && !notes.isEmpty()) first = Json.str(((Map<?, ?>) notes.get(0)).get("text"));
                long t = (long) Double.parseDouble(Json.str(i.get("first")));
                out.add("Scanner " + code(Json.str(i.get("sysLabel"))) + " " + HM.format(Instant.ofEpochMilli(t)) + ": " + type.toUpperCase() + " at " + Json.str(i.get("place")) + (first.isEmpty() ? "" : " - " + clip(first, 90)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Packs lines into as few ~190-character messages as possible (at most two). */
    static List<String> pack(String head, List<String> lines) {
        List<String> msgs = new ArrayList<>();
        StringBuilder sb = new StringBuilder(head);
        boolean fresh = true;   // nothing after the heading yet
        for (String l : lines) {
            if (sb.length() + l.length() + 3 > 190) { msgs.add(sb.toString()); if (msgs.size() == 2) return msgs; sb = new StringBuilder(); fresh = true; }
            if (!fresh) sb.append(" | ");
            sb.append(l); fresh = false;
        }
        if (sb.length() > 0) msgs.add(sb.toString());
        return msgs;
    }

    static String shortTg(String tg) { return tg.length() > 18 ? tg.substring(0, 18) : tg; }
    static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
}
