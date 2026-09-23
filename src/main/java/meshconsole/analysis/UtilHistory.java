package meshconsole.analysis;

import meshconsole.mesh.UtilSample;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** util_history.csv — own-node utilisation samples plus duplicate/rx counters, kept 90 days. */
public class UtilHistory {
    public record DupeSample(long time, long rx, long dupe, int online) { }
    public record NoiseSample(long time, int noiseFloorDbm) { }
    public record PowerSample(long time, int battery, float voltage) { }
    public record CountSample(long time, int online, int heardLastHour, int total) { }
    private final List<CountSample> counts = new ArrayList<>();
    public synchronized void addCount(int online, int heardLastHour, int total) {
        CountSample c = new CountSample(System.currentTimeMillis(), online, heardLastHour, total);
        if (!counts.isEmpty() && counts.get(counts.size() - 1).time() > c.time() - 15 * 60_000L) return;   // every 15 min
        counts.add(c); append("C," + c.time() + "," + online + "," + heardLastHour + "," + total);
    }
    public synchronized List<CountSample> counts() { return new ArrayList<>(counts); }
    private final List<PowerSample> power = new ArrayList<>();
    public synchronized void addPower(int battery, float voltage) {
        PowerSample p = new PowerSample(System.currentTimeMillis(), battery, voltage);
        if (!power.isEmpty() && power.get(power.size() - 1).time() > p.time() - 5 * 60_000L) return;
        power.add(p); append("P," + p.time() + "," + battery + "," + voltage);
    }
    public synchronized List<PowerSample> power() { return new ArrayList<>(power); }
    private final List<NoiseSample> noise = new ArrayList<>();
    public synchronized void addNoise(int dbm) {
        if (dbm == 0) return;
        NoiseSample n = new NoiseSample(System.currentTimeMillis(), dbm);
        if (!noise.isEmpty() && noise.get(noise.size() - 1).time() > n.time() - 60_000L) return;
        noise.add(n); append("N," + n.time() + "," + dbm);
    }
    public synchronized List<NoiseSample> noise() { return new ArrayList<>(noise); }
    private final Path file;
    private final List<UtilSample> util = new ArrayList<>();
    private final List<DupeSample> dupes = new ArrayList<>();

    public UtilHistory(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        long cutoff = System.currentTimeMillis() - 90L * 86400_000L;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                try {
                    long t = Long.parseLong(f[1]);
                    if (t < cutoff) continue;
                    if (f[0].equals("U") && f.length >= 5) util.add(new UtilSample(t, Float.parseFloat(f[2]), Float.parseFloat(f[3]), Integer.parseInt(f[4])));
                    else if (f[0].equals("D") && f.length >= 5) dupes.add(new DupeSample(t, Long.parseLong(f[2]), Long.parseLong(f[3]), Integer.parseInt(f[4])));
                    else if (f[0].equals("N") && f.length >= 3) noise.add(new NoiseSample(t, Integer.parseInt(f[2])));
                    else if (f[0].equals("P") && f.length >= 4) power.add(new PowerSample(t, Integer.parseInt(f[2]), Float.parseFloat(f[3])));
                    else if (f[0].equals("C") && f.length >= 5) counts.add(new CountSample(t, Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4])));
                } catch (RuntimeException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private void append(String line) {
        try { Files.writeString(file, line + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (IOException ignored) { }
    }
    public synchronized void addUtil(UtilSample u) { util.add(u); append("U," + u.time() + "," + u.channelUtil() + "," + u.airUtilTx() + "," + u.txQueueFree()); }
    public synchronized void addDupe(long rx, long dupe, int online) {
        DupeSample d = new DupeSample(System.currentTimeMillis(), rx, dupe, online);
        if (!dupes.isEmpty() && dupes.get(dupes.size() - 1).time() > d.time() - 600_000L) return;   // one per 10 min is plenty
        dupes.add(d); append("D," + d.time() + "," + rx + "," + dupe + "," + online);
    }
    public synchronized List<UtilSample> util() { return new ArrayList<>(util); }
    public synchronized List<DupeSample> dupes() { return new ArrayList<>(dupes); }
}
