package meshconsole.mesh;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Long-term signal samples on disk (signal_history.csv), keeps the last 7 days in memory. */
public class SignalHistory {
    private static final long KEEP_MS = 7L * 24 * 3600 * 1000;
    private final Path file;
    private final List<SignalSample> samples = new ArrayList<>();
    private PrintWriter out;

    public SignalHistory(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split(",");
                    if (f.length < 5) continue;
                    try {
                        long t = Long.parseLong(f[0]);
                        if (t < cutoff) continue;
                        samples.add(new SignalSample(t, Integer.parseUnsignedInt(f[1]), Integer.parseInt(f[2]), Float.parseFloat(f[3]), Integer.parseInt(f[4])));
                    } catch (NumberFormatException ignored) { }
                }
            } catch (IOException ignored) { }
        }
    }

    public synchronized void add(SignalSample s) {
        samples.add(s);
        try {
            if (out == null) out = new PrintWriter(new BufferedWriter(new FileWriter(file.toFile(), true)));
            out.println(s.time() + "," + Integer.toUnsignedString(s.from()) + "," + s.rssi() + "," + s.snr() + "," + s.hops());
            if (samples.size() % 20 == 0) out.flush();
        } catch (IOException ignored) { }
        if (samples.size() % 5000 == 0) {
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            samples.removeIf(x -> x.time() < cutoff);
        }
    }

    public synchronized void flush() { if (out != null) out.flush(); }

    /** Samples newer than sinceMillis (0 = all kept). */
    public synchronized List<SignalSample> since(long sinceMillis) {
        List<SignalSample> l = new ArrayList<>();
        for (SignalSample s : samples) if (s.time() >= sinceMillis) l.add(s);
        return l;
    }

    public synchronized int size() { return samples.size(); }
}
