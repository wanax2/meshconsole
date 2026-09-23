package meshconsole.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** radios.json — your own Meshtastic nodes, registered automatically when connected, annotated by you. */
public class RadioDb {
    public static class Radio {
        public int num;                       // node number (key)
        public String name = "", hardware = "", firmware = "", antenna = "", location = "", notes = "", role = "";
        public int txPower;
        public double swrBest = Double.NaN;
        public long firstConnected, lastConnected, connectedMs;
        public String idString() { return String.format("!%08x", num); }
    }

    private final Path file;
    private final Map<Integer, Radio> map = new LinkedHashMap<>();

    public RadioDb(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (root instanceof List<?> arr) for (Object o : arr) if (o instanceof Map<?, ?> m) {
                Radio r = new Radio();
                r.num = (int) (long) Json.num(m.get("num"), 0);
                if (r.num == 0) continue;
                r.name = Json.str(m.get("name")); r.hardware = Json.str(m.get("hardware")); r.firmware = Json.str(m.get("firmware")); r.antenna = Json.str(m.get("antenna"));
                r.location = Json.str(m.get("location")); r.notes = Json.str(m.get("notes")); r.role = Json.str(m.get("role"));
                r.txPower = (int) Json.num(m.get("txPower"), 0);
                r.firstConnected = (long) Json.num(m.get("firstConnected"), 0); r.lastConnected = (long) Json.num(m.get("lastConnected"), 0); r.connectedMs = (long) Json.num(m.get("connectedMs"), 0);
                map.put(r.num, r);
            }
        } catch (RuntimeException | IOException ignored) { }
    }

    public synchronized List<Radio> all() { return new ArrayList<>(map.values()); }
    public synchronized Radio get(int num) { return map.get(num); }

    /** Registers or refreshes the connected radio's technical facts; keeps user-entered fields. */
    public synchronized Radio touch(int num, String nodeName, String hardware, String firmware, int txPower, String role) throws IOException {
        Radio r = map.computeIfAbsent(num, k -> { Radio x = new Radio(); x.num = k; x.firstConnected = System.currentTimeMillis(); return x; });
        if (r.name.isEmpty() && nodeName != null && !nodeName.isEmpty()) r.name = nodeName;
        if (hardware != null && !hardware.isEmpty()) r.hardware = hardware;
        if (firmware != null && !firmware.isEmpty()) r.firmware = firmware;
        if (txPower != 0) r.txPower = txPower;
        if (role != null && !role.isEmpty()) r.role = role;
        r.lastConnected = System.currentTimeMillis();
        save();
        return r;
    }

    public synchronized void addConnectedTime(int num, long ms) throws IOException {
        Radio r = map.get(num);
        if (r == null) return;
        r.connectedMs += Math.max(0, ms);
        save();
    }

    public synchronized void put(Radio r) throws IOException { map.put(r.num, r); save(); }
    public synchronized void remove(int num) throws IOException { map.remove(num); save(); }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        boolean first = true;
        for (Radio r : map.values()) {
            if (!first) sb.append(",\n"); first = false;
            sb.append("  {\"num\":").append(Integer.toUnsignedLong(r.num)).append(',').append(kv("name", r.name)).append(kv("hardware", r.hardware)).append(kv("firmware", r.firmware)).append(kv("antenna", r.antenna))
              .append(kv("location", r.location)).append(kv("notes", r.notes)).append(kv("role", r.role)).append(kv("txPower", r.txPower))
              .append(kv("firstConnected", r.firstConnected)).append(kv("lastConnected", r.lastConnected)).append(kv("connectedMs", r.connectedMs));
            sb.setLength(sb.length() - 1);
            sb.append("}");
        }
        sb.append("\n]\n");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String kv(String k, Object v) {
        String val = v instanceof String s ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" : String.valueOf(v);
        return "\"" + k + "\":" + val + ",";
    }
}
