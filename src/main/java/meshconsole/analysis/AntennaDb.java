package meshconsole.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** antennas.json — the antennas you own, with their specs and measured results. */
public class AntennaDb {
    public static class Antenna {
        public String name = "", type = "", connector = "", band = "", notes = "", mounting = "";
        public double gainDbi = Double.NaN, lengthCm = Double.NaN;
        public long added, lastSwrTime;
        public double swrBest = Double.NaN, swrBestMhz = Double.NaN, swrAtCentre = Double.NaN, swrWorst = Double.NaN;
        public double sweepStartMhz = Double.NaN, sweepStopMhz = Double.NaN;
    }

    private final Path file;
    private final List<Antenna> list = new ArrayList<>();

    public AntennaDb(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (root instanceof List<?> arr) for (Object o : arr) if (o instanceof Map<?, ?> m) {
                Antenna a = new Antenna();
                a.name = Json.str(m.get("name")); a.type = Json.str(m.get("type")); a.connector = Json.str(m.get("connector")); a.band = Json.str(m.get("band"));
                a.notes = Json.str(m.get("notes")); a.mounting = Json.str(m.get("mounting"));
                a.gainDbi = Json.num(m.get("gainDbi"), Double.NaN); a.lengthCm = Json.num(m.get("lengthCm"), Double.NaN);
                a.added = (long) Json.num(m.get("added"), 0); a.lastSwrTime = (long) Json.num(m.get("lastSwrTime"), 0);
                a.swrBest = Json.num(m.get("swrBest"), Double.NaN); a.swrBestMhz = Json.num(m.get("swrBestMhz"), Double.NaN);
                a.swrAtCentre = Json.num(m.get("swrAtCentre"), Double.NaN); a.swrWorst = Json.num(m.get("swrWorst"), Double.NaN);
                a.sweepStartMhz = Json.num(m.get("sweepStartMhz"), Double.NaN); a.sweepStopMhz = Json.num(m.get("sweepStopMhz"), Double.NaN);
                if (!a.name.isEmpty()) list.add(a);
            }
        } catch (RuntimeException | IOException ignored) { }
    }

    public synchronized List<Antenna> all() { return new ArrayList<>(list); }
    public synchronized Antenna get(String name) { for (Antenna a : list) if (a.name.equalsIgnoreCase(name)) return a; return null; }

    public synchronized void put(Antenna a) throws IOException {
        list.removeIf(x -> x.name.equalsIgnoreCase(a.name));
        if (a.added == 0) a.added = System.currentTimeMillis();
        list.add(a);
        list.sort(Comparator.comparing(x -> x.name.toLowerCase()));
        save();
    }

    public synchronized void remove(String name) throws IOException { list.removeIf(x -> x.name.equalsIgnoreCase(name)); save(); }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        boolean first = true;
        for (Antenna a : list) {
            if (!first) sb.append(",\n"); first = false;
            sb.append("  {").append(kv("name", a.name)).append(kv("type", a.type)).append(kv("connector", a.connector)).append(kv("band", a.band)).append(kv("mounting", a.mounting)).append(kv("notes", a.notes))
              .append(kv("gainDbi", a.gainDbi)).append(kv("lengthCm", a.lengthCm)).append(kv("added", a.added)).append(kv("lastSwrTime", a.lastSwrTime))
              .append(kv("swrBest", a.swrBest)).append(kv("swrBestMhz", a.swrBestMhz)).append(kv("swrAtCentre", a.swrAtCentre)).append(kv("swrWorst", a.swrWorst))
              .append(kv("sweepStartMhz", a.sweepStartMhz)).append(kv("sweepStopMhz", a.sweepStopMhz));
            sb.setLength(sb.length() - 1);
            sb.append("}");
        }
        sb.append("\n]\n");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String kv(String k, Object v) {
        String val;
        if (v instanceof String s) val = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        else if (v instanceof Double d) val = d.isNaN() ? "null" : String.format(Locale.ROOT, "%.3f", d);
        else val = String.valueOf(v);
        return "\"" + k + "\":" + val + ",";
    }
}
