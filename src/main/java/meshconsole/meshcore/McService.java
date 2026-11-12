package meshconsole.meshcore;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;

/**
 * Keeps one MeshCore radio connected for this data folder. Settings live in meshcore.properties:
 *   connect=tcp:192.168.1.50:5000     (Companion firmware with WiFi)  or   connect=serial:/dev/ttyACM1
 * No file, or an empty connect=, means MeshCore is off. Reconnects every 20 s while the radio is away.
 */
public final class McService {
    private static volatile McClient client;
    private static volatile String spec = "";
    private static Path file;
    private McService() { }

    public static synchronized void start(Path dataDir, Consumer<String> log) {
        if (client != null) return;
        file = dataDir.resolve("meshcore.properties");
        spec = readSpec();
        client = new McClient(dataDir, log);
        Thread t = new Thread(() -> {
            long lastBatt = 0;
            while (true) {
                try {
                    String s = spec;
                    if (!s.isEmpty() && !client.isConnected()) {
                        try { client.connect(s); } catch (Exception e) { log.accept("MeshCore: can't connect to " + s + " (" + e.getMessage() + ") – retrying in 20 s"); }
                    } else if (s.isEmpty() && client.isConnected()) client.disconnect();
                    if (client.isConnected() && System.currentTimeMillis() - lastBatt > 300_000) { lastBatt = System.currentTimeMillis(); client.battery(); }
                    client.checkAlive();
                    Thread.sleep(20_000);
                } catch (InterruptedException e) { return; } catch (Exception ignored) { }
            }
        }, "meshcore-keepalive");
        t.setDaemon(true); t.start();
        if (!spec.isEmpty()) log.accept("MeshCore: radio configured at " + spec);
    }

    public static McClient client() { return client; }
    public static String spec() { return spec; }

    /** Save a new connection (or "" to switch MeshCore off) and reconnect now. */
    public static synchronized String configure(String newSpec) {
        String s = newSpec == null ? "" : newSpec.trim();
        if (!s.isEmpty() && !s.startsWith("tcp:") && !s.startsWith("serial:")) return "Use tcp:<address>[:5000] or serial:<port>, e.g. tcp:192.168.1.50:5000 or serial:/dev/ttyACM1";
        try { Files.writeString(file, "# MeshCore radio for this data folder – tcp:<host>[:5000] or serial:<port>; empty = off\nconnect=" + s + "\n", StandardCharsets.UTF_8); }
        catch (Exception e) { return "couldn't save: " + e.getMessage(); }
        spec = s;
        if (client != null) {
            client.disconnect();
            if (!s.isEmpty()) new Thread(() -> { try { client.connect(s); } catch (Exception ignored) { } }, "meshcore-connect").start();
        }
        return s.isEmpty() ? "MeshCore switched off" : "saved – connecting to " + s;
    }

    private static String readSpec() {
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                l = l.trim();
                if (l.startsWith("connect=")) return l.substring(8).trim();
            }
        } catch (Exception ignored) { }
        return "";
    }
}
