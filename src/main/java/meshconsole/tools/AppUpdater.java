package meshconsole.tools;

import meshconsole.Version;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Self-update. A daily check looks for a newer ready-to-run zip in a folder (e.g. a NAS share mounted on the Pi) or in
 * a GitHub repository's latest release. On Linux/macOS the new jars replace the running ones and the app restarts
 * itself (or simply exits under systemd, which restarts it); on Windows, where a running jar is locked, it only reports
 * that an update is waiting. Settings are shared by every instance on the machine.
 */
public final class AppUpdater {
    private static final Preferences P = Preferences.userRoot().node("meshconsole-global");
    private static final Pattern ZIP = Pattern.compile("meshconsole-(\\d+(?:\\.\\d+)+)-ready-to-run\\.zip");
    private AppUpdater() { }

    public static boolean enabled() { return P.getBoolean("autoUpdate", false); }
    public static String source() { return P.get("updateSource", ""); }
    public static String time() { return P.get("updateTime", "04:15"); }
    public static void configure(boolean on, String source, String time) { P.putBoolean("autoUpdate", on); P.put("updateSource", source.trim()); P.put("updateTime", time.trim()); }

    private static volatile long lastDay = -1;

    /** Starts the daily check. beforeExit saves state and disconnects; restartArgs are this process's arguments. */
    public static void start(Consumer<String> log, Runnable beforeExit, String[] restartArgs) {
        // A sibling instance may already have installed a newer jar: restart onto it at the next check.
        Timer t = new Timer("updater", true);
        t.scheduleAtFixedRate(new TimerTask() { @Override public void run() {
            try {
                if (!enabled() || source().isBlank()) return;
                LocalTime now = LocalTime.now(); long day = System.currentTimeMillis() / 86400_000L;
                LocalTime at; try { at = LocalTime.parse(time()); } catch (Exception e) { at = LocalTime.of(4, 15); }
                if (now.isBefore(at) || lastDay == day) return;
                lastDay = day;
                checkAndApply(log, beforeExit, restartArgs, false);
            } catch (Exception e) { log.accept("Update check failed: " + e.getMessage()); }
        } }, 60_000, 60_000);
    }

    /** One check. Returns a human summary. */
    public static String checkAndApply(Consumer<String> log, Runnable beforeExit, String[] restartArgs, boolean manual) throws Exception {
        Path installDir = installDir();
        String installed = installedVersion(installDir);
        // Restart onto a newer jar only if it was installed after this process started (another instance updated the
        // shared folder); otherwise a zip whose name and jar version disagree would make the app restart forever.
        long started = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(Long.MAX_VALUE);
        long jarTime = 0; try { if (installDir != null) jarTime = Files.getLastModifiedTime(installDir.resolve("meshconsole.jar")).toMillis(); } catch (IOException ignored) { }
        if (installDir != null && newer(installed, Version.VERSION) && jarTime > started) {
            String msg = "Version " + installed + " is already installed next to this one (" + Version.VERSION + ") – restarting onto it.";
            log.accept(msg);
            if (WINDOWS) restartWindows(log, beforeExit, restartArgs, installDir, installDir, installed); else restart(log, beforeExit, restartArgs);
            return msg;
        }
        Candidate c = findLatest(source());
        if (c == null) { String m = "No ready-to-run zip found in " + source(); if (manual) log.accept(m); return m; }
        if (!newer(c.version, Version.VERSION)) { String m = "Up to date (" + Version.VERSION + "; newest available " + c.version + ")."; if (manual) log.accept(m); return m; }
        if (installDir == null) { String m = "Mesh Console " + c.version + " is available at " + c.where + " – unzip it over this folder to update."; log.accept(m); return m; }
        log.accept("Updating Mesh Console " + Version.VERSION + " → " + c.version + " from " + c.where);
        Path zip = c.local != null ? c.local : download(c.url);
        if (WINDOWS) {
            // Running jars are locked on Windows: stage the new files, exit, and let a helper copy them in once every
            // instance using this folder has exited (each instance does the same at its own check), then relaunch.
            Path stage = installDir.resolve("update-pending").resolve(c.version);
            if (!Files.isRegularFile(stage.resolve("meshconsole.jar"))) { Files.createDirectories(stage); extract(zip, stage); }
            if (c.local == null) Files.deleteIfExists(zip);
            log.accept("Staged " + c.version + " – restarting to install it.");
            restartWindows(log, beforeExit, restartArgs, stage, installDir, c.version);
            return "Updating to " + c.version;
        }
        install(zip, installDir, c.version);
        if (c.local == null) Files.deleteIfExists(zip);
        log.accept("Installed " + c.version + " in " + installDir + " – restarting.");
        restart(log, beforeExit, restartArgs);
        return "Updated to " + c.version;
    }

    private record Candidate(String version, Path local, String url, String where) { }

    private static Candidate findLatest(String src) throws Exception {
        if (src.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {   // owner/repo on GitHub
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + src + "/releases/latest")).header("Accept", "application/vnd.github+json").header("User-Agent", "meshconsole").timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException("GitHub returned " + r.statusCode());
            Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]*meshconsole-(\\d+(?:\\.\\d+)+)-ready-to-run\\.zip)\"").matcher(r.body());
            return m.find() ? new Candidate(m.group(2), null, m.group(1), "github.com/" + src) : null;
        }
        Path dir = Path.of(src);
        if (!Files.isDirectory(dir)) throw new IOException("folder not found: " + dir + " (is the share mounted?)");
        Candidate best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) { Matcher m = ZIP.matcher(p.getFileName().toString()); if (m.matches() && (best == null || newer(m.group(1), best.version))) best = new Candidate(m.group(1), p, null, p.toString()); }
        }
        return best;
    }

    private static Path download(String url) throws Exception {
        Path tmp = Files.createTempFile("meshconsole-update", ".zip");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "meshconsole").timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) throw new IOException("download returned " + r.statusCode());
        return tmp;
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Set<String> FILES = Set.of("meshconsole.jar", "protobuf-java.jar", "jSerialComm.jar", "meshconsole.sh", "meshconsole.bat", "CHANGELOG.md");

    /** Unzips just the program files (flattened) into dir. */
    static void extract(Path zip, Path dir) throws IOException {
        int n = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String name = Path.of(e.getName()).getFileName().toString();
                if (!FILES.contains(name)) continue;
                Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING); n++;
            }
        }
        if (n == 0) throw new IOException("the zip contained no Mesh Console files");
    }

    /** Windows: a detached helper waits until the jars can be overwritten (all instances exited), copies, relaunches. */
    private static void restartWindows(Consumer<String> log, Runnable beforeExit, String[] args, Path stage, Path dir, String version) {
        try {
            String java = ProcessHandle.current().info().command().orElse("java");
            Path javaw = Path.of(java).resolveSibling("javaw.exe"); if (Files.isRegularFile(javaw)) java = javaw.toString();
            Path script = Files.createTempFile("meshconsole-update", ".cmd");
            Files.writeString(script, windowsScript(java, args, stage, dir, version));
            new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", script.toString()).start();
        } catch (Exception e) { log.accept("Could not start the updater (" + e.getMessage() + ") – unzip the new version over " + dir + " by hand."); return; }
        try { beforeExit.run(); } catch (Exception ignored) { }
        System.exit(0);
    }

    static String windowsScript(String java, String[] args, Path stage, Path dir, String version) {
            StringBuilder a = new StringBuilder(); for (String x : args) a.append(" \"").append(x.replace("\"", "")).append('"');
            String cp = dir.resolve("meshconsole.jar") + ";" + dir.resolve("protobuf-java.jar") + ";" + dir.resolve("jSerialComm.jar");
            StringBuilder cmd = new StringBuilder("@echo off\r\nset /a n=0\r\n:retry\r\ntimeout /t 5 /nobreak >nul\r\n");
            if (stage.equals(dir)) cmd.append("goto run\r\n");   // files already in place (another instance installed them): just relaunch
            cmd.append("copy /y \"").append(stage.resolve("meshconsole.jar")).append("\" \"").append(dir.resolve("meshconsole.jar")).append("\" >nul 2>&1 || goto again\r\n");
            for (String f : FILES) if (!f.equals("meshconsole.jar") && !f.endsWith(".sh") && Files.exists(stage.resolve(f)))
                cmd.append("copy /y \"").append(stage.resolve(f)).append("\" \"").append(dir.resolve(f)).append("\" >nul 2>&1\r\n");
            cmd.append("echo ").append(version).append("> \"").append(dir.resolve("installed_version.txt")).append("\"\r\ngoto run\r\n");
            cmd.append(":again\r\nset /a n+=1\r\nif %n% lss 120 goto retry\r\n");          // give up after ~10 min and start the old version
            cmd.append(":run\r\nstart \"\" \"").append(java).append("\" -cp \"").append(cp).append("\" meshconsole.Main").append(a).append("\r\n");
            return cmd.toString();
    }

    /** Extracts the jars and launch scripts into installDir, each written to a temp file and moved into place. */
    static void install(Path zip, Path installDir, String version) throws IOException {
        Set<String> wanted = Set.of("meshconsole.jar", "protobuf-java.jar", "jSerialComm.jar", "meshconsole.sh", "meshconsole.bat", "CHANGELOG.md");
        int n = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String name = Path.of(e.getName()).getFileName().toString();
                if (!wanted.contains(name)) continue;
                Path tmp = installDir.resolve(name + ".new");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, installDir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                if (name.endsWith(".sh")) installDir.resolve(name).toFile().setExecutable(true, false);
                n++;
            }
        }
        if (n == 0) throw new IOException("the zip contained no Mesh Console files");
        Files.writeString(installDir.resolve("installed_version.txt"), version + "\n");
    }

    private static void restart(Consumer<String> log, Runnable beforeExit, String[] args) {
        try { beforeExit.run(); } catch (Exception ignored) { }
        if (System.getenv("INVOCATION_ID") != null) { log.accept("Exiting; systemd will restart the service."); System.exit(0); }   // under systemd (Restart=always)
        try {
            String java = ProcessHandle.current().info().command().orElse("java");
            Path jar = installDir().resolve("meshconsole.jar");
            String sep = File.pathSeparator, dir = installDir().toString();
            List<String> cmd = new ArrayList<>(List.of("sh", "-c", "sleep 4; exec \"$@\"", "sh", java, "-cp",
                    dir + "/meshconsole.jar" + sep + dir + "/protobuf-java.jar" + sep + dir + "/jSerialComm.jar", "meshconsole.Main"));
            cmd.addAll(Arrays.asList(args));
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (Exception e) { log.accept("Restart failed (" + e.getMessage() + ") – start Mesh Console again by hand."); }
        System.exit(0);
    }

    static Path installDir() {
        try { Path p = Path.of(AppUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI()); return Files.isRegularFile(p) ? p.getParent() : null; }
        catch (Exception e) { return null; }
    }
    private static String installedVersion(Path dir) {
        try { return dir == null ? "" : Files.readString(dir.resolve("installed_version.txt")).trim(); } catch (IOException e) { return ""; }
    }
    static boolean newer(String a, String b) {
        if (a == null || a.isBlank()) return false;
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? Integer.parseInt(x[i].replaceAll("\\D", "0")) : 0, yi = i < y.length ? Integer.parseInt(y[i].replaceAll("\\D", "0")) : 0;
            if (xi != yi) return xi > yi;
        }
        return false;
    }
}
