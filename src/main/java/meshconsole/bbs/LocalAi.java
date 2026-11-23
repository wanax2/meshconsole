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
 * 1.29.5: the local AI servers (Ollama) from &lt;data folder&gt;/ai.properties, shared by the BBS's ASK command, the web
 * console's Ask AI tab and the "Explain" buttons. Best server first; a server that fails is skipped for 2 minutes
 * (unless it's the only one left). Never throws: a failed call returns null and leaves the reason in lastError().
 */
public final class LocalAi {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Map<Path, LocalAi> BY_FILE = new HashMap<>();

    private final Path file;
    private long loadedMod = -1;
    private boolean enabled; private int perHour = 4; private String place = "";
    private final List<String[]> servers = new ArrayList<>();
    private final Map<String, Long> down = new HashMap<>();
    private volatile String lastError = "", lastServer = "";

    public static synchronized LocalAi forDir(Path dataDir) {
        return BY_FILE.computeIfAbsent(dataDir.toAbsolutePath().normalize().resolve("ai.properties"), LocalAi::new);
    }
    private LocalAi(Path file) { this.file = file; }

    private synchronized void load() {
        try {
            long m = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
            if (m == loadedMod) return;
            loadedMod = m; servers.clear(); enabled = false;
            if (m == 0) return;
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "false").trim());
            try { perHour = Integer.parseInt(p.getProperty("perHour", "4").trim()); } catch (NumberFormatException e) { perHour = 4; }
            place = p.getProperty("name", "").trim();
            for (String s : p.getProperty("servers", "").split(",")) {
                String[] uv = s.trim().split("\\|", 2);
                if (uv.length == 2 && !uv[0].isBlank()) servers.add(new String[]{uv[0].trim().replaceAll("/+$", ""), uv[1].trim()});
            }
        } catch (Exception e) { enabled = false; lastError = "ai.properties: " + e.getMessage(); }
    }

    public boolean enabled() { load(); synchronized (this) { return enabled && !servers.isEmpty(); } }
    public int perHour() { load(); return perHour; }
    public String place() { load(); return place; }
    public String lastError() { return lastError; }
    public String lastServer() { return lastServer; }
    /** "http://titan:11434 qwen2.5:7b, …" for status lines. */
    public synchronized String describe() { load(); StringBuilder b = new StringBuilder(); for (String[] s : servers) b.append(b.length() > 0 ? ", " : "").append(s[0]).append(' ').append(s[1]); return b.toString(); }

    /**
     * One chat call. turns = alternating {"user"|"assistant", text}, ending with the user's question.
     * maxTokens caps the answer length; returns the reply text, or null if no server answered.
     */
    public String chat(String system, List<String[]> turns, int maxTokens) {
        load();
        StringBuilder msgs = new StringBuilder("[{\"role\":\"system\",\"content\":").append(q(system)).append('}');
        for (String[] t : turns) msgs.append(",{\"role\":").append(q(t[0].equals("assistant") ? "assistant" : "user")).append(",\"content\":").append(q(t[1])).append('}');
        msgs.append(']');
        // the model name goes in by replace(), not String.format(): a "%" anywhere in the prompt ("56%") broke format()
        String body = "{\"model\":@MODEL@,\"stream\":false,\"keep_alive\":\"30m\",\"options\":{\"num_predict\":" + maxTokens + ",\"temperature\":0.4},\"messages\":" + msgs + "}";
        List<String[]> list; synchronized (this) { list = new ArrayList<>(servers); }
        if (list.isEmpty()) { lastError = "no AI servers in " + file; return null; }
        long now = System.currentTimeMillis();
        StringBuilder errs = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String[] s = list.get(i);
            boolean last = i == list.size() - 1;
            Long until; synchronized (this) { until = down.get(s[0]); }
            if (!last && until != null && now < until) { errs.append(s[0]).append(": skipped (failed recently); "); continue; }
            try {
                HttpRequest r = HttpRequest.newBuilder(URI.create(s[0] + "/api/chat")).timeout(Duration.ofSeconds(last ? 180 : 75))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.replace("@MODEL@", q(s[1])))).build();
                HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode() + (resp.body().contains("not found") ? " (model " + s[1] + " not installed on that server?)" : ""));
                String text = content(resp.body());
                if (text.isBlank()) throw new Exception("empty answer");
                synchronized (this) { down.remove(s[0]); }
                lastServer = s[0] + " " + s[1]; lastError = "";
                return text.trim();
            } catch (Exception e) {
                errs.append(s[0]).append(": ").append(e.getClass() == Exception.class ? e.getMessage() : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage())).append("; ");
                synchronized (this) { if (list.size() > 1) down.put(s[0], now + 120_000L); }
            }
        }
        lastError = errs.toString().replaceAll("; $", "");
        return null;
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) { case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n"); case '\r' -> {} case '\t' -> b.append(' ');
                default -> { if (c < 0x20) b.append(' '); else b.append(c); } }
        }
        return b.append('"').toString();
    }

    /** message.content from Ollama's /api/chat reply; keeps line breaks (the mesh reply flattens them itself). */
    static String content(String json) {
        int m = json.indexOf("\"message\""); if (m < 0) return "";
        int c = json.indexOf("\"content\"", m); if (c < 0) return "";
        int i = json.indexOf('"', json.indexOf(':', c) + 1); if (i < 0) return "";
        StringBuilder b = new StringBuilder();
        for (int k = i + 1; k < json.length(); k++) {
            char ch = json.charAt(k);
            if (ch == '\\' && k + 1 < json.length()) {
                char n = json.charAt(++k);
                switch (n) { case 'n' -> b.append('\n'); case 't' -> b.append(' '); case 'r' -> { } case '"' -> b.append('"'); case '\\' -> b.append('\\'); case '/' -> b.append('/');
                    case 'u' -> { if (k + 4 < json.length()) { b.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16)); k += 4; } }
                    default -> b.append(n); }
            } else if (ch == '"') break;
            else b.append(ch);
        }
        return b.toString();
    }
}
