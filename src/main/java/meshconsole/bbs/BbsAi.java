package meshconsole.bbs;

import java.nio.file.Path;
import java.util.*;

/**
 * ASK – questions answered by a local AI model (Ollama), best server first with automatic fallback.
 * Settings in &lt;data folder&gt;/ai.properties:
 *   enabled=true
 *   servers=http://gpu-box:11434|qwen2.5:7b, http://pi-box:11434|qwen2.5:1.5b
 *   perHour=4        questions per person per hour
 *   name=Lyon's Den BBS – Arlington VA
 * Answers are kept to one mesh message (~200 bytes).
 * 1.29.5: the servers live in LocalAi (shared with the web console); the model is told the date and time, where the
 * board is, the current weather, what the mesh looks like and which BBS commands answer live questions, and a failure
 * now says why in the BBS log.
 */
final class BbsAi {
    private final LocalAi ai;
    private final Map<Integer, Deque<Long>> asked = new HashMap<>();

    BbsAi(Path dataDir) { this.ai = LocalAi.forDir(dataDir); }

    boolean enabled() { return ai.enabled(); }
    String lastError() { return ai.lastError(); }
    String lastServer() { return ai.lastServer(); }

    /** null if this person may ask now, otherwise the reason they can't. */
    synchronized String allow(int from) {
        long now = System.currentTimeMillis();
        int perHour = Math.max(1, ai.perHour());
        Deque<Long> q = asked.computeIfAbsent(from, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > 3_600_000L) q.pollFirst();
        if (q.size() >= perHour) return "That's " + perHour + " questions this hour – try again in " + Math.max(1, (3_600_000L - (now - q.peekFirst())) / 60_000L) + " min.";
        q.addLast(now);
        return null;
    }

    /** A failed answer doesn't use up one of the person's questions. */
    synchronized void refund(int from) { Deque<Long> q = asked.get(from); if (q != null && !q.isEmpty()) q.pollLast(); }

    /** Asks the first server that answers; never throws; null if no server answered. context = facts about the board right now. */
    String ask(String question, String who, String context) {
        String place = ai.place();
        String sys = "You answer questions sent over a LoRa mesh radio BBS" + (place.isEmpty() ? "" : " (" + place + ")") + ". "
                + "Reply in plain text, no markdown, at most 190 characters. Be accurate; if unsure, say so briefly. "
                + "Use the facts below when they answer the question; never invent live data such as weather, times or schedules – "
                + "if the question needs live data you don't have, name the BBS command that gives it.\n"
                + (who == null || who.isBlank() ? "" : "The person asking: " + who + ".\n")
                + (context == null ? "" : context);
        String text = ai.chat(sys, List.<String[]>of(new String[]{"user", question}), 90);
        if (text == null) return null;
        return clip(text.replaceAll("[*_#`]+", "").replaceAll("\\s+", " ").trim(), 200);
    }

    static String clip(String s, int maxBytes) {
        if (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) return s;
        while (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes - 1) s = s.substring(0, s.length() - 1);
        int sp = s.lastIndexOf(' '); if (sp > maxBytes / 2) s = s.substring(0, sp);
        return s + "…";
    }
}
