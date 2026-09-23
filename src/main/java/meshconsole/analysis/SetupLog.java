package meshconsole.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * setup_log.csv — a timestamped record of the physical setup of each radio: antenna, where the antenna
 * is (category), how high (metres above ground), where the radio is. Signal samples are attributed to
 * the setup in force for the receiving radio at that time, so setups can be compared.
 */
public class SetupLog {
    public static final String[] PLACEMENTS = {"indoor desk", "indoor window", "attic", "balcony / porch", "outdoor wall", "roof", "mast / pole", "vehicle", "handheld", "other"};

    public record Setup(long time, int radio, String antenna, String placement, double heightM, String radioLocation, String notes) {
        public String label() {
            return antenna + " @ " + placement + (Double.isNaN(heightM) ? "" : String.format(" %.0f m", heightM)) + (radioLocation.isEmpty() ? "" : " (" + radioLocation + ")");
        }
    }

    private final Path file;
    private final List<Setup> list = new ArrayList<>();

    public SetupLog(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split(",", -1);
                if (f.length < 7) continue;
                try { list.add(new Setup(Long.parseLong(f[0]), Integer.parseUnsignedInt(f[1]), un(f[2]), un(f[3]), f[4].isEmpty() ? Double.NaN : Double.parseDouble(f[4]), un(f[5]), un(f[6]))); }
                catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    public synchronized List<Setup> all() { return new ArrayList<>(list); }

    public synchronized void add(Setup s) throws IOException {
        list.add(s);
        Files.writeString(file, s.time() + "," + Integer.toUnsignedString(s.radio()) + "," + esc(s.antenna()) + "," + esc(s.placement()) + "," + (Double.isNaN(s.heightM()) ? "" : s.heightM())
                + "," + esc(s.radioLocation()) + "," + esc(s.notes()) + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /** The setup in force for a radio at a time, or null. */
    public synchronized Setup at(int radio, long time) {
        Setup best = null;
        for (Setup s : list) if (s.radio() == radio && s.time() <= time && (best == null || s.time() > best.time())) best = s;
        return best;
    }

    public synchronized Setup current(int radio) { return at(radio, Long.MAX_VALUE); }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace(",", "\\c").replace("\n", " "); }
    private static String un(String s) { return s.replace("\\c", ",").replace("\\\\", "\\"); }
}
