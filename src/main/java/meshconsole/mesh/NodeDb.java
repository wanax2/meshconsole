package meshconsole.mesh;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persistent node database (nodes.json) so first/last-seen, packet counts and averages survive restarts.
 * Simple hand-written JSON: one object per node, no library needed.
 */
public class NodeDb {
    private final Path file;

    public NodeDb(Path file) { this.file = file; }

    public Path path() { return file; }
    public boolean exists() { return Files.exists(file); }
    public long lastSaved() { try { return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0; } catch (IOException e) { return 0; } }

    public synchronized void save(Collection<NodeEntry> nodes) throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        boolean first = true;
        for (NodeEntry n : nodes) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("  {")
              .append("\"num\":").append(Integer.toUnsignedString(n.num)).append(',').append(kv("long", n.longName)).append(kv("short", n.shortName)).append(kv("hw", n.hwModel)).append(kv("role", n.role))
              .append(kv("key", n.publicKey.length == 0 ? "" : Base64.getEncoder().encodeToString(n.publicKey)))
              .append(kv("lat", n.hasPosition ? n.lat : null)).append(kv("lon", n.hasPosition ? n.lon : null)).append(kv("alt", n.hasPosition ? n.altitude : null))
              .append(kv("lastHeard", n.lastHeardMillis())).append(kv("firstSeen", n.firstSeen)).append(kv("packets", n.packetsSeen)).append(kv("direct", n.directPackets))
              .append(kv("rssiSum", n.rssiSum)).append(kv("rssiCount", n.rssiCount)).append(kv("snrSum", n.snrSum)).append(kv("snrCount", n.snrCount))
              .append(kv("hops", n.hopsAway)).append(kv("battery", n.battery)).append(kv("favorite", n.isFavorite)).append(kv("licensed", n.isLicensed))
              .append(kv("sessions", n.sessionsSeen));
            sb.setLength(sb.length() - 1);   // drop trailing comma
            sb.append("}");
        }
        sb.append("\n]\n");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized List<NodeEntry> load() {
        List<NodeEntry> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        String text;
        try { text = Files.readString(file, StandardCharsets.UTF_8); } catch (IOException e) { return out; }
        for (String obj : text.split("\\{")) {
            int end = obj.indexOf('}');
            if (end < 0) continue;
            Map<String, String> m = new HashMap<>();
            for (String pair : obj.substring(0, end).split(",(?=\\s*\")")) {
                int c = pair.indexOf(':');
                if (c < 0) continue;
                String k = pair.substring(0, c).trim().replace("\"", "");
                String v = pair.substring(c + 1).trim();
                if (v.startsWith("\"")) v = unquote(v);
                m.put(k, v);
            }
            try {
                NodeEntry n = new NodeEntry(Integer.parseUnsignedInt(m.getOrDefault("num", "0")));
                if (n.num == 0) continue;
                n.longName = m.getOrDefault("long", ""); n.shortName = m.getOrDefault("short", ""); n.hwModel = m.getOrDefault("hw", ""); n.role = m.getOrDefault("role", "");
                String key = m.getOrDefault("key", "");
                if (!key.isEmpty() && !key.equals("null")) n.publicKey = Base64.getDecoder().decode(key);
                if (!"null".equals(m.get("lat")) && m.containsKey("lat")) { n.lat = Double.parseDouble(m.get("lat")); n.lon = Double.parseDouble(m.get("lon")); n.altitude = (int) Double.parseDouble(m.getOrDefault("alt", "0")); n.hasPosition = true; }
                n.lastLocalRx = Long.parseLong(m.getOrDefault("lastHeard", "0"));
                n.firstSeen = Long.parseLong(m.getOrDefault("firstSeen", "0"));
                n.packetsSeen = (int) Long.parseLong(m.getOrDefault("packets", "0"));
                n.directPackets = (int) Long.parseLong(m.getOrDefault("direct", "0"));
                n.rssiSum = Long.parseLong(m.getOrDefault("rssiSum", "0")); n.rssiCount = Integer.parseInt(m.getOrDefault("rssiCount", "0"));
                n.snrSum = Double.parseDouble(m.getOrDefault("snrSum", "0")); n.snrCount = Integer.parseInt(m.getOrDefault("snrCount", "0"));
                n.hopsAway = (int) Long.parseLong(m.getOrDefault("hops", "-1"));      // older files wrote -1 as 4294967295
                n.battery = (int) Long.parseLong(m.getOrDefault("battery", "-1"));
                n.isFavorite = Boolean.parseBoolean(m.getOrDefault("favorite", "false"));
                n.isLicensed = Boolean.parseBoolean(m.getOrDefault("licensed", "false"));
                n.sessionsSeen = Integer.parseInt(m.getOrDefault("sessions", "0"));
                n.fromDb = true;
                out.add(n);
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    public synchronized void reset() throws IOException { Files.deleteIfExists(file); }

    private static String kv(String k, Object v) {
        String val;
        if (v == null) val = "null";
        else if (v instanceof String s) val = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        else val = String.valueOf(v);
        return "\"" + k + "\":" + val + ",";
    }

    private static String unquote(String v) {
        v = v.substring(1);
        int e = v.lastIndexOf('"');
        if (e >= 0) v = v.substring(0, e);
        return v.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
