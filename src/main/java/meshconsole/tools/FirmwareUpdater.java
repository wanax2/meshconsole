package meshconsole.tools;

import meshconsole.DataDir;
import meshconsole.analysis.Json;
import meshconsole.mesh.MeshClient;
import org.meshtastic.proto.AdminProtos.AdminMessage;

import javax.swing.filechooser.FileSystemView;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads Meshtastic firmware releases and flashes nRF52 (UF2/DFU) boards directly, ESP32 boards via esptool. */
public final class FirmwareUpdater {
    private FirmwareUpdater() { }

    public record Release(String tag, boolean prerelease, String url, List<Asset> assets) { }
    public record Asset(String name, String url, long size) { }

    public enum Family { NRF52, ESP32, ESP32S3, ESP32C3, RP2040, OTHER }

    /** Which firmware family a hardware model belongs to (drives asset choice and flash method). */
    public static Family family(String hw) {
        String h = hw.toUpperCase();
        if (h.matches(".*(RAK4631|RAK3401|RAK2560|RAK11310|T_ECHO|TRACKER_T1000_E|T114|WIO_TRACKER|NRF52|NANO_G2|XIAO_BLE|MESH_POCKET|HELTEC_MESH_NODE|RAK_WISMESH|CANARYONE|PROMICRO|SEEED_WIO|FEATHER_NRF|THINKNODE_M1|SEEED_SOLAR|MESHTAB).*")) return Family.NRF52;
        if (h.matches(".*(STATION_G2|T_DECK|HELTEC_V3|HELTEC_V4|HELTEC_WIRELESS|HELTEC_VISION|TBEAM_S3|T_WATCH|S3|XIAO_S3|UNPHONE|CHATTER|SENSECAP|PICOMPUTER|CROWPANEL|M5STACK_CORES3|TLORA_T3S3|TLORA_C6|WIPHONE|MESH_TAB).*")) return Family.ESP32S3;
        if (h.matches(".*(HT_CT62|C3|SEEED_XIAO_C3).*")) return Family.ESP32C3;
        if (h.matches(".*(TBEAM|TLORA|HELTEC_V2|HELTEC_V1|STATION_G1|DIY_V1|NANO_G1|M5STACK|DR_DEV|LILYGO|HELTEC_WSL|BETAFPV).*")) return Family.ESP32;
        if (h.matches(".*(RP2040|RPI_PICO|PICO).*")) return Family.RP2040;
        return Family.OTHER;
    }

    /** The name Meshtastic uses for the board's firmware files (best guess; the user can override). */
    public static String target(String hw) {
        return switch (hw.toUpperCase()) {
            case "RAK4631" -> "rak4631"; case "RAK3401" -> "rak3401"; case "STATION_G2" -> "station-g2"; case "T_DECK" -> "t-deck"; case "T_DECK_PRO" -> "t-deck-pro";
            case "HELTEC_V3" -> "heltec-v3"; case "HELTEC_V4" -> "heltec-v4"; case "TRACKER_T1000_E" -> "tracker-t1000-e"; case "T_ECHO" -> "t-echo";
            case "HELTEC_MESH_NODE_T114" -> "heltec-mesh-node-t114"; case "TBEAM" -> "tbeam"; case "TBEAM_S3_CORE" -> "tbeam-s3-core"; case "HELTEC_WIRELESS_TRACKER" -> "heltec-wireless-tracker";
            default -> hw.toLowerCase().replace('_', '-');
        };
    }

    public static List<Release> releases(boolean includePrerelease) throws IOException {
        String body = get("https://api.github.com/repos/meshtastic/firmware/releases?per_page=15");
        Object root = Json.parse(body);
        List<Release> out = new ArrayList<>();
        if (!(root instanceof List<?> arr)) throw new IOException("Unexpected reply from GitHub" + (body.contains("rate limit") ? " (rate limited – try again in an hour)" : ""));
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) continue;
            boolean pre = Boolean.TRUE.equals(m.get("prerelease"));
            if (pre && !includePrerelease) continue;
            List<Asset> assets = new ArrayList<>();
            if (m.get("assets") instanceof List<?> as) for (Object a : as) if (a instanceof Map<?, ?> am) assets.add(new Asset(Json.str(am.get("name")), Json.str(am.get("browser_download_url")), (long) Json.num(am.get("size"), 0)));
            out.add(new Release(Json.str(m.get("tag_name")).replaceFirst("^v", ""), pre, Json.str(m.get("html_url")), assets));
        }
        return out;
    }

    /** Picks the platform zip for a family (newer releases split by platform; older have one big zip). */
    public static Asset pickAsset(Release r, Family f) {
        String key = switch (f) { case NRF52 -> "nrf52840"; case ESP32S3 -> "esp32s3"; case ESP32C3 -> "esp32c3"; case ESP32 -> "esp32-"; case RP2040 -> "rp2040"; default -> "firmware-"; };
        Asset best = null;
        for (Asset a : r.assets()) {
            String n = a.name().toLowerCase();
            if (!n.endsWith(".zip") || n.contains("debug") || n.contains("ota") && !n.contains("firmware")) continue;
            if (n.contains(key)) return a;
            if (n.startsWith("firmware-") && !n.contains("esp32") && !n.contains("nrf52") && !n.contains("rp2040") && !n.contains("stm32")) best = a;   // the all-in-one zip
        }
        return best;
    }

    /** Downloads (with a simple cache) and returns the local zip path. */
    public static Path download(Asset a, Consumer<String> progress) throws IOException {
        Path dir = DataDir.file("firmware");
        Files.createDirectories(dir);
        Path out = dir.resolve(a.name());
        if (Files.exists(out) && Files.size(out) == a.size()) { progress.accept("Already downloaded " + a.name()); return out; }
        HttpURLConnection c = (HttpURLConnection) URI.create(a.url()).toURL().openConnection();
        c.setRequestProperty("User-Agent", "MeshConsole"); c.setInstanceFollowRedirects(true); c.setConnectTimeout(10000); c.setReadTimeout(60000);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream o = Files.newOutputStream(out)) {
            byte[] buf = new byte[65536]; long done = 0; int n; long lastPct = -1;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); done += n; long pct = total > 0 ? done * 100 / total : -1; if (pct != lastPct && pct % 5 == 0) { progress.accept("Downloading " + a.name() + " … " + pct + "%"); lastPct = pct; } }
        }
        return out;
    }

    /** Lists firmware files inside the zip that look like they belong to a board target. */
    public static List<String> listFiles(Path zip, String targetHint) throws IOException {
        List<String> all = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) { String n = e.getName(); if (!e.isDirectory() && (n.endsWith(".uf2") || n.endsWith(".bin")) && n.startsWith("firmware-") && !n.contains("littlefs")) all.add(n); }
        }
        all.sort((x, y) -> { boolean a = x.contains("-" + targetHint + "-"), b = y.contains("-" + targetHint + "-"); return a == b ? x.compareTo(y) : a ? -1 : 1; });
        return all;
    }

    public static Path extract(Path zip, String entryName) throws IOException {
        Path dir = DataDir.file("firmware");
        Files.createDirectories(dir);
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (e.getName().equals(entryName)) { Path out = dir.resolve(Path.of(entryName).getFileName().toString()); Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING); return out; }
        }
        throw new IOException("File not in zip: " + entryName);
    }

    // ---- nRF52: DFU over UF2 drive ------------------------------------------------------------

    /** Sends enter_dfu_mode to the connected radio. It reboots as a USB mass-storage drive. */
    public static void enterDfu(MeshClient client) throws IOException {
        client.sendAdmin(AdminMessage.newBuilder().setEnterDfuModeRequest(true), false);
    }

    /** Waits up to timeoutSec for a UF2 bootloader drive (contains INFO_UF2.TXT) to appear. */
    public static Path waitForUf2Drive(int timeoutSec, Consumer<String> progress) throws IOException, InterruptedException {
        Set<String> before = new HashSet<>();
        for (Path p : candidateRoots()) before.add(p.toString());
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (Path p : candidateRoots()) {
                if (Files.exists(p.resolve("INFO_UF2.TXT"))) { progress.accept("Bootloader drive found: " + p + " (" + FileSystemView.getFileSystemView().getSystemDisplayName(p.toFile()) + ")"); return p; }
            }
            Thread.sleep(1000);
        }
        throw new IOException("No UF2 bootloader drive appeared within " + timeoutSec + " s. Double-tap the radio's RESET button to enter the bootloader manually, then retry.");
    }

    private static List<Path> candidateRoots() {
        List<Path> l = new ArrayList<>();
        for (File f : File.listRoots()) l.add(f.toPath());
        for (String base : new String[]{"/media", "/run/media", "/Volumes"}) {
            Path b = Path.of(base);
            if (!Files.isDirectory(b)) continue;
            try (var s = Files.list(b)) { for (Path p : (Iterable<Path>) s::iterator) { if (Files.isDirectory(p)) { l.add(p); try (var s2 = Files.list(p)) { for (Path q : (Iterable<Path>) s2::iterator) if (Files.isDirectory(q)) l.add(q); } catch (IOException ignored) { } } } } catch (IOException ignored) { }
        }
        return l;
    }

    /** Copies the UF2 onto the bootloader drive; the board reboots itself when done. */
    public static void flashUf2(Path uf2, Path drive, Consumer<String> progress) throws IOException {
        progress.accept("Copying " + uf2.getFileName() + " (" + Files.size(uf2) / 1024 + " KB) to " + drive + " …");
        try { Files.copy(uf2, drive.resolve(uf2.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException e) {
            // the drive usually disappears mid-copy as the board reboots; that's success
            if (!Files.exists(drive.resolve("INFO_UF2.TXT"))) { progress.accept("Drive vanished during copy – the board rebooted into the new firmware."); return; }
            throw e;
        }
        progress.accept("Copy finished; the board should reboot now.");
    }

    // ---- ESP32: esptool ----------------------------------------------------------------------

    /** Finds an esptool invocation on this machine, or null. */
    public static List<String> findEsptool() {
        String os = System.getProperty("os.name").toLowerCase();
        List<List<String>> candidates = new ArrayList<>();
        candidates.add(List.of("esptool"));
        candidates.add(List.of("esptool.py"));
        candidates.add(List.of(os.contains("win") ? "py" : "python3", "-m", "esptool"));
        candidates.add(List.of("python", "-m", "esptool"));
        for (List<String> c : candidates) {
            try {
                List<String> cmd = new ArrayList<>(c); cmd.add("version");
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (p.waitFor() == 0 && out.toLowerCase().contains("esptool")) return c;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /**
     * Runs esptool. update=true writes the -update.bin at 0x10000 (keeps settings); update=false writes the
     * factory .bin at 0x0 after erasing (full install). Output is streamed to progress.
     */
    public static int flashEsp(List<String> esptool, String port, Path bin, boolean update, Consumer<String> progress) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(esptool);
        cmd.addAll(List.of("--port", port, "--baud", "921600"));
        if (!update) { cmd.addAll(List.of("erase_flash")); progress.accept("$ " + String.join(" ", cmd)); int rc = run(cmd, progress); if (rc != 0) return rc; cmd = new ArrayList<>(esptool); cmd.addAll(List.of("--port", port, "--baud", "921600")); }
        cmd.addAll(List.of("write_flash", update ? "0x10000" : "0x0", bin.toString()));
        progress.accept("$ " + String.join(" ", cmd));
        return run(cmd, progress);
    }

    private static int run(List<String> cmd, Consumer<String> progress) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) progress.accept(line);
        }
        return p.waitFor();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", "MeshConsole"); c.setRequestProperty("Accept", "application/vnd.github+json"); c.setConnectTimeout(10000); c.setReadTimeout(20000);
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        try (in) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
}
