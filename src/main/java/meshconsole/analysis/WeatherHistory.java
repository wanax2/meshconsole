package meshconsole.analysis;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** weather_history.csv: one row per observation, kept for a year. */
public class WeatherHistory {
    private final Path file;
    private final TreeMap<Long, WeatherObs> obs = new TreeMap<>();

    public WeatherHistory(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        long cutoff = System.currentTimeMillis() - 365L * 86400_000L;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",", -1);
                if (f.length < 12) continue;
                try {
                    long t = Long.parseLong(f[0]);
                    if (t < cutoff) continue;
                    obs.put(t, new WeatherObs(t, f[1], d(f[2]), d(f[3]), d(f[4]), d(f[5]), d(f[6]), d(f[7]), d(f[8]), d(f[9]), f[10], f[11].replace("\\c", ",")));
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private static double d(String s) { return s.isEmpty() ? Double.NaN : Double.parseDouble(s); }
    private static String n(double v) { return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.1f", v); }

    public synchronized boolean add(WeatherObs o) {
        if (obs.containsKey(o.time())) return false;
        obs.put(o.time(), o);
        try {
            Files.writeString(file, o.time() + "," + o.station() + "," + n(o.tempC()) + "," + n(o.dewpointC()) + "," + n(o.humidityPct()) + "," + n(o.windKt()) + "," + n(o.gustKt())
                    + "," + n(o.windDir()) + "," + n(o.pressureHpa()) + "," + n(o.visibilityMi()) + "," + o.wx().replace(",", " ") + "," + o.raw().replace(",", "\\c") + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
        return true;
    }

    public synchronized List<WeatherObs> all() { return new ArrayList<>(obs.values()); }
    public synchronized WeatherObs latest() { return obs.isEmpty() ? null : obs.lastEntry().getValue(); }
    public synchronized int size() { return obs.size(); }

    /** Observation closest to a time (within 90 minutes), or null. */
    public synchronized WeatherObs at(long time) {
        Map.Entry<Long, WeatherObs> lo = obs.floorEntry(time), hi = obs.ceilingEntry(time);
        WeatherObs best = null; long bd = Long.MAX_VALUE;
        for (Map.Entry<Long, WeatherObs> e : new Map.Entry[]{lo, hi}) {
            if (e == null) continue;
            long dd = Math.abs(e.getKey() - time);
            if (dd < bd) { bd = dd; best = e.getValue(); }
        }
        return bd <= 90 * 60_000L ? best : null;
    }
}
