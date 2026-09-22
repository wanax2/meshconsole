package meshconsole.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** antenna_log.csv — when each antenna was put into service, for A/B comparison. */
public class AntennaLog {
    private final Path file;
    public final List<long[]> starts = new ArrayList<>();
    public final List<String> labels = new ArrayList<>();

    public AntennaLog(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int c = line.indexOf(',');
                if (c < 0) continue;
                try { starts.add(new long[]{Long.parseLong(line.substring(0, c))}); labels.add(line.substring(c + 1)); } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    public String current() { return labels.isEmpty() ? "" : labels.get(labels.size() - 1); }

    public void set(String label) throws IOException {
        long now = System.currentTimeMillis();
        starts.add(new long[]{now}); labels.add(label);
        Files.writeString(file, now + "," + label.replace(",", " ") + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
