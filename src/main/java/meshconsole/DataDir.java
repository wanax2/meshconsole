package meshconsole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Where all data files live. Default: the folder the app was started from. The user can pick another
 * folder (File → Data folder…); the choice is remembered in the OS user preferences and takes effect on
 * the next start. On the first start of a new app version, existing data files are backed up first.
 */
public final class DataDir {
    private static final Preferences PREFS = Preferences.userNodeForPackage(DataDir.class);
    private static Path dir;

    /** Data files the app owns; used for backups and moves. */
    public static final List<String> FILES = List.of("messages.log", "nodes.json", "signal_history.csv", "weather_history.csv", "util_history.csv",
            "antenna_log.csv", "antennas.json", "radios.json", "setup_log.csv", "config_log.csv", "alerts.log", "sessions.log", "meshconsole.log", "mesh_report.html", "data_version.txt");

    private DataDir() { }

    public static synchronized Path get() {
        if (dir == null) {
            String p = PREFS.get("dataDir", "");
            dir = p.isEmpty() ? Path.of("").toAbsolutePath() : Path.of(p);
            try { Files.createDirectories(dir); } catch (IOException e) { dir = Path.of("").toAbsolutePath(); }
        }
        return dir;
    }

    public static Path file(String name) { return get().resolve(name); }

    public static boolean isCustom() { return !PREFS.get("dataDir", "").isEmpty(); }

    /** Remembers a new folder for the next start. */
    public static void set(Path p) { if (p == null) PREFS.remove("dataDir"); else PREFS.put("dataDir", p.toAbsolutePath().toString()); }

    /** Copies (or moves) the data files to another folder. Returns the number of files copied. */
    public static int copyData(Path to, boolean move) throws IOException {
        Files.createDirectories(to);
        int n = 0;
        for (String f : FILES) {
            Path src = file(f);
            if (!Files.exists(src)) continue;
            if (move) Files.move(src, to.resolve(f), StandardCopyOption.REPLACE_EXISTING); else Files.copy(src, to.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            n++;
        }
        Path tiles = file("tilecache");
        if (Files.isDirectory(tiles) && !Files.exists(to.resolve("tilecache"))) {
            try (var walk = Files.walk(tiles)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path dest = to.resolve("tilecache").resolve(tiles.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(dest); else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return n;
    }

    /**
     * Zips every data file (plus tile-cache-free extras such as captures in the folder) into
     * <name>.zip in the target directory. Name defaults to meshconsole-data-YYYYMMDD-HHmmss.
     */
    public static Path exportZip(Path targetDir, boolean includeCaptures) throws IOException {
        Files.createDirectories(targetDir);
        String name = "meshconsole-data-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
        Path zip = targetDir.resolve(name);
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            for (String f : FILES) {
                Path src = file(f);
                if (Files.exists(src)) addEntry(out, src, f);
            }
            if (includeCaptures) {
                try (var list = Files.list(get())) {
                    for (Path p : (Iterable<Path>) list::iterator)
                        if (p.getFileName().toString().endsWith(".mcap")) addEntry(out, p, p.getFileName().toString());
                }
            }
            String info = Version.NAME + " " + Version.VERSION + "\nexported " + java.time.ZonedDateTime.now() + "\nfrom " + get() + "\n";
            out.putNextEntry(new java.util.zip.ZipEntry("export_info.txt"));
            out.write(info.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    private static void addEntry(java.util.zip.ZipOutputStream out, Path src, String name) throws IOException {
        java.util.zip.ZipEntry e = new java.util.zip.ZipEntry(name);
        e.setTime(Files.getLastModifiedTime(src).toMillis());
        out.putNextEntry(e);
        Files.copy(src, out);
        out.closeEntry();
    }

    /**
     * Called once at startup. If the data was last written by a different app version, back it up to
     * backup/<oldversion>-<date>/ before this version touches it, then record the current version.
     * Returns a description of what happened, or null if nothing was needed.
     */
    public static String checkVersion() {
        Path marker = file("data_version.txt");
        String previous = "";
        try { if (Files.exists(marker)) previous = Files.readString(marker, StandardCharsets.UTF_8).trim(); } catch (IOException ignored) { }
        boolean anyData = FILES.stream().anyMatch(f -> !f.equals("data_version.txt") && Files.exists(file(f)));
        String result = null;
        if (anyData && !previous.equals(Version.VERSION)) {
            String tag = (previous.isEmpty() ? "pre-1.3.3" : previous) + "-" + LocalDate.now();
            Path backup = file("backup").resolve(tag);
            try {
                Files.createDirectories(backup);
                int n = 0;
                for (String f : FILES) {
                    Path src = file(f);
                    if (Files.exists(src)) { Files.copy(src, backup.resolve(f), StandardCopyOption.REPLACE_EXISTING); n++; }
                }
                result = "First start of " + Version.VERSION + " with data from " + (previous.isEmpty() ? "an earlier version" : previous) + ": " + n + " files backed up to " + backup;
            } catch (IOException e) {
                result = "Could not back up data files before upgrading: " + e.getMessage();
            }
        }
        try { Files.writeString(marker, Version.VERSION + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) { }
        return result;
    }
}
