package meshconsole.bbs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * ASK – questions answered by a local AI model (Ollama), best server first with automatic fallback.
 * Settings in <data folder>/ai.properties:
 *   enabled=true
 *   servers=http://gpu-box:11434|qwen2.5:7b, http://pi-box:11434|qwen2.5:1.5b
 *   perHour=4        questions per person per hour
 *   name=Lyon's Den BBS – Arlington VA
 * Answers are kept to one mesh message (~200 characters).
 */
final class BbsAi {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Path file;
    private long loadedMod = -1;
    private boolean enabled; private int perHour = 4; private String place = "";
    private final List<String[]> servers = new ArrayList<>();
    private final Map<String, Long> down = new HashMap<>();
    private final Map<Integer, Deque<Long>> asked = new HashMap<>();

    BbsAi(Path dataDir) { this.file = dataDir.resolve("ai.properties"); }

    private synchronized void load() {
        try {
            long m = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
            if (m == loadedMod) return;
            loadedMod = m; servers.clear(); enabled = false;
            if (m == 0) return;
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "false").trim());
            perHour = Integer.parseInt(p.getProperty("perHour", "4").trim());
            place = p.getProperty("name", "").trim();
            for (String s : p.getProperty("servers", "").split(",")) {
                String[] uv = s.trim().split("\\|", 2);
                if (uv.length == 2 && !uv[0].isBlank()) servers.add(new String[]{uv[0].trim().replaceAll("/+$", ""), uv[1].trim()});
            }
        } catch (Exception e) { enabled = false; }
    }

    boolean enabled() { load(); return enabled && !servers.isEmpty(); }

    /** null if this person may ask now, otherwise the reason they can't. */
    synchronized String allow(int from) {
        long now = System.currentTimeMillis();
        Deque<Long> q = asked.computeIfAbsent(from, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
        if (q.size() >= perHour) return "That's " + perHour + " questions this hour – try again in " + Math.max(1, (3_600_000L - (now - q.peekFirst())) / 60_000L) + " min.";
        q.addLast(now);
        return null;
    }

    /** Asks the first server that answers; never throws. */
    String ask(String question, String who) {
        load();
        String sys = "You answer questions sent over a LoRa mesh radio BBS" + (place.isEmpty() ? "" : " (" + place + ")")
                + ". Reply in plain text, no markdown, at most 190 characters. Be accurate; if unsure, say so briefly.";
        String body = "{\"model\":%s,\"stream\":false,\"keep_alive\":\"30m\",\"options\":{\"num_predict\":90,\"temperature\":0.4},"
                + "\"messages\":[{\"role\":\"system\",\"content\":" + q(sys) + "},{\"role\":\"user\",\"content\":" + q(question) + "}]}";
        List<String[]> list; synchronized (this) { list = new ArrayList<>(servers); }
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            String[] s = list.get(i);
            boolean last = i == list.size() - 1;
            Long until; synchronized (this) { until = down.get(s[0]); }
            if (!last && until != null && now < until) continue;
            try {
                HttpRequest r = HttpRequest.newBuilder(URI.create(s[0] + "/api/chat")).timeout(Duration.ofSeconds(last ? 180 : 60))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(String.format(body, q(s[1])))).build();
                HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
                String text = content(resp.body());
                if (text.isBlank()) throw new Exception("empty answer");
                synchronized (this) { down.remove(s[0]); }
                return clip(text.replaceAll("[*_#`]+", "").replaceAll("\\s+", " ").trim(), 200);
            } catch (Exception e) {
                synchronized (this) { if (list.size() > 1) down.put(s[0], now + 120_000L); }
            }
        }
        return "ASK: no answer right now – the AI server is unavailable. Try later.";
    }

    static String clip(String s, int maxBytes) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return s;
        while (s.getBytes(StandardCharsets.UTF_8).length > maxBytes - 1) s = s.substring(0, s.length() - 1);
        int sp = s.lastIndexOf(' '); if (sp > maxBytes / 2) s = s.substring(0, sp);
        return s + "…";
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) { case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n"); case '\r' -> {} case '\t' -> b.append(' ');
                default -> { if (c < 0x20) b.append(' '); else b.append(c); } }
        }
        return b.append('"').toString();
    }

    /** Pulls message.content out of Ollama's /api/chat reply without a JSON library. */
    static String content(String json) {
        int m = json.indexOf("\"message\""); if (m < 0) return "";
        int c = json.indexOf("\"content\"", m); if (c < 0) return "";
        int i = json.indexOf('"', json.indexOf(':', c) + 1); if (i < 0) return "";
        StringBuilder b = new StringBuilder();
        for (int k = i + 1; k < json.length(); k++) {
            char ch = json.charAt(k);
            if (ch == '\\' && k + 1 < json.length()) {
                char n = json.charAt(++k);
                switch (n) { case 'n' -> b.append(' '); case 't' -> b.append(' '); case '"' -> b.append('"'); case '\\' -> b.append('\\'); case '/' -> b.append('/');
                    case 'u' -> { if (k + 4 < json.length()) { b.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16)); k += 4; } }
                    default -> b.append(n); }
            } else if (ch == '"') break;
            else b.append(ch);
        }
        return b.toString();
    }
}
