package meshconsole.mesh;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only message log on disk (tab separated, one line per message;
 * status updates re-append the same packet id and the newest line wins on load).
 */
public class MessageLog {
    private final Path file;

    public MessageLog(Path file) {
        this.file = file;
    }

    public Path path() { return file; }

    public synchronized void append(ChatMessage m) {
        String line = m.time + "\t" + (m.outgoing ? "OUT" : "IN") + "\t"
                + Integer.toUnsignedString(m.from) + "\t" + Integer.toUnsignedString(m.to) + "\t"
                + m.channel + "\t" + Integer.toUnsignedString(m.packetId) + "\t"
                + m.status + "\t" + esc(m.statusDetail) + "\t"
                + m.rssi + "\t" + m.snr + "\t" + m.hops + "\t" + esc(m.text) + "\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("message log write failed: " + e);
        }
    }

    public synchronized List<ChatMessage> load() {
        Map<String, ChatMessage> byKey = new LinkedHashMap<>();
        if (!Files.exists(file)) return new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 12) continue;
                try {
                    ChatMessage m = new ChatMessage();
                    m.time = Long.parseLong(f[0]);
                    m.outgoing = f[1].equals("OUT");
                    m.from = Integer.parseUnsignedInt(f[2]);
                    m.to = Integer.parseUnsignedInt(f[3]);
                    m.channel = Integer.parseInt(f[4]);
                    m.packetId = Integer.parseUnsignedInt(f[5]);
                    m.status = ChatMessage.Status.valueOf(f[6]);
                    m.statusDetail = unesc(f[7]);
                    m.rssi = Integer.parseInt(f[8]);
                    m.snr = Float.parseFloat(f[9]);
                    m.hops = Integer.parseInt(f[10]);
                    m.text = unesc(f[11]);
                    // Anything still "in flight" at load time can't be resolved anymore.
                    if (m.status == ChatMessage.Status.QUEUED) {
                        m.status = ChatMessage.Status.HISTORY;
                        m.statusDetail = "status unknown (app closed)";
                    }
                    String key = m.time + ":" + Integer.toUnsignedString(m.packetId);
                    byKey.remove(key);          // keep ordering by first appearance? no: newest wins, place at first
                    byKey.put(key, m);
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException e) {
            System.err.println("message log read failed: " + e);
        }
        List<ChatMessage> l = new ArrayList<>(byKey.values());
        l.sort((a, b) -> Long.compare(a.time, b.time));
        return l;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                if (d == 't') sb.append('\t');
                else if (d == 'n') sb.append('\n');
                else sb.append(d);
            } else sb.append(c);
        }
        return sb.toString();
    }
}
