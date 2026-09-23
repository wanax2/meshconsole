package meshconsole.mesh;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Long-term signal samples on disk (signal_history.csv, kept for one year).
 * In memory: the last 7 days at full resolution, plus hourly per-node averages for the whole year,
 * so a busy mesh can't exhaust memory.
 */
public class SignalHistory {
    public static final long KEEP_MS = 365L * 24 * 3600 * 1000;
    private static final long RAW_MS = 7L * 24 * 3600 * 1000;
    private static final long HOUR = 3600_000L;

    private final Path file;
    private final List<SignalSample> raw = new ArrayList<>();
    /** key = hourIndex * 2^32 + from  →  {count, rssiSum, snrSum} */
    private final Map<Long, double[]> hourly = new HashMap<>();
    private PrintWriter out;
    private long total;

    public SignalHistory(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        long now = System.currentTimeMillis();
        boolean needsPrune = false;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                if (f.length < 5) continue;
                try {
                    long t = Long.parseLong(f[0]);
                    if (t < now - KEEP_MS) { needsPrune = true; continue; }
                    SignalSample s = new SignalSample(t, Integer.parseUnsignedInt(f[1]), Integer.parseInt(f[2]), Float.parseFloat(f[3]), Integer.parseInt(f[4]), f.length > 5 ? Integer.parseInt(f[5]) : 0, f.length > 6 ? Integer.parseUnsignedInt(f[6]) : 0);
                    total++;
                    bucket(s);
                    if (t >= now - RAW_MS) raw.add(s);
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        if (needsPrune) prune(now);
    }

    private void bucket(SignalSample s) {
        long key = (s.time() / HOUR) * 4294967296L + Integer.toUnsignedLong(s.from());
        double[] b = hourly.computeIfAbsent(key, k -> new double[3]);
        b[0]++; b[1] += s.rssi(); b[2] += s.snr();
    }

    /** Rewrites the file without rows older than a year (done once at startup when needed). */
    private void prune(long now) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                 PrintWriter w = new PrintWriter(Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int c = line.indexOf(',');
                    if (c < 0) continue;
                    try { if (Long.parseLong(line.substring(0, c)) >= now - KEEP_MS) w.println(line); } catch (NumberFormatException ignored) { }
                }
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    public synchronized void add(SignalSample s) {
        raw.add(s);
        bucket(s);
        total++;
        try {
            if (out == null) out = new PrintWriter(new BufferedWriter(new FileWriter(file.toFile(), true)));
            out.println(s.time() + "," + Integer.toUnsignedString(s.from()) + "," + s.rssi() + "," + s.snr() + "," + s.hops() + "," + s.slot() + "," + Integer.toUnsignedString(s.rxNode()));
            if (raw.size() % 20 == 0) out.flush();
        } catch (IOException ignored) { }
        if (raw.size() % 5000 == 0) {
            long cutoff = System.currentTimeMillis() - RAW_MS;
            raw.removeIf(x -> x.time() < cutoff);
        }
    }

    public synchronized void flush() { if (out != null) out.flush(); }

    /**
     * Samples newer than sinceMillis. Within the last 7 days these are individual packets; before
     * that they are hourly per-node averages (hops = -1), one per node per hour.
     */
    public synchronized List<SignalSample> since(long sinceMillis) {
        long rawCutoff = System.currentTimeMillis() - RAW_MS;
        List<SignalSample> l = new ArrayList<>();
        if (sinceMillis < rawCutoff) {
            for (Map.Entry<Long, double[]> e : hourly.entrySet()) {
                long hourStart = (e.getKey() / 4294967296L) * HOUR;
                if (hourStart < sinceMillis || hourStart >= rawCutoff) continue;
                double[] b = e.getValue();
                l.add(new SignalSample(hourStart, (int) (e.getKey() % 4294967296L), (int) Math.round(b[1] / b[0]), (float) (b[2] / b[0]), -1, 0, 0));
            }
            l.sort(Comparator.comparingLong(SignalSample::time));
        }
        for (SignalSample s : raw) if (s.time() >= sinceMillis) l.add(s);
        return l;
    }

    public synchronized int size() { return raw.size(); }
    public synchronized long total() { return total; }
}
