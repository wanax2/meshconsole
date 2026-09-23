package meshconsole.tools;

import meshconsole.DataDir;
import meshconsole.mesh.ChatMessage;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/** Timed jobs: daily report, weekly traceroutes to a watch-list, nightly export, periodic beacon. */
public class Scheduler {
    private static final Preferences P = Preferences.userNodeForPackage(Scheduler.class);
    private final MeshClient client;
    private final MeshState state;
    private final Supplier<String> reportHtml;
    private long lastReportDay, lastExportDay, lastTraceWeek, lastBeacon;

    public boolean reportOn = P.getBoolean("reportOn", false); public String reportTime = P.get("reportTime", "06:00");
    public boolean exportOn = P.getBoolean("exportOn", false); public String exportDir = P.get("exportDir", ""); public String exportTime = P.get("exportTime", "03:00");
    public boolean traceOn = P.getBoolean("traceOn", false); public int traceWeekday = P.getInt("traceWeekday", 7); public String traceTime = P.get("traceTime", "12:00");
    public boolean beaconOn = P.getBoolean("beaconOn", false); public int beaconMinutes = P.getInt("beaconMinutes", 60); public String beaconText = P.get("beaconText", "test from 22201"); public int beaconTo = (int) P.getLong("beaconTo", 0xFFFFFFFFL);

    public Scheduler(MeshClient client, Supplier<String> reportHtml) { this.client = client; this.state = client.state(); this.reportHtml = reportHtml; }

    public void save() {
        P.putBoolean("reportOn", reportOn); P.put("reportTime", reportTime); P.putBoolean("exportOn", exportOn); P.put("exportDir", exportDir); P.put("exportTime", exportTime);
        P.putBoolean("traceOn", traceOn); P.putInt("traceWeekday", traceWeekday); P.put("traceTime", traceTime); P.putBoolean("beaconOn", beaconOn); P.putInt("beaconMinutes", beaconMinutes); P.put("beaconText", beaconText); P.putLong("beaconTo", Integer.toUnsignedLong(beaconTo));
    }

    /** Call once a minute. */
    public void tick() {
        LocalTime now = LocalTime.now();
        long day = System.currentTimeMillis() / 86400_000L, week = day / 7;
        if (reportOn && due(now, reportTime) && lastReportDay != day) {
            lastReportDay = day;
            try { java.nio.file.Files.writeString(DataDir.file("mesh_report.html"), reportHtml.get(), java.nio.charset.StandardCharsets.UTF_8); state.emitLog("Scheduled report written to mesh_report.html"); }
            catch (IOException e) { state.emitLog("Scheduled report failed: " + e.getMessage()); }
        }
        if (exportOn && !exportDir.isBlank() && due(now, exportTime) && lastExportDay != day) {
            lastExportDay = day;
            try { Path z = DataDir.exportZip(Path.of(exportDir), false); state.emitLog("Scheduled export: " + z); }
            catch (IOException e) { state.emitLog("Scheduled export failed: " + e.getMessage()); }
        }
        if (traceOn && client.isConnected() && java.time.LocalDate.now().getDayOfWeek().getValue() == traceWeekday && due(now, traceTime) && lastTraceWeek != week) {
            lastTraceWeek = week;
            int sent = 0;
            for (var n : state.nodes()) if (n.watched && n.num != state.myNodeNum()) { try { client.traceroute(n.num); sent++; Thread.sleep(30_000); } catch (Exception ignored) { } }
            state.emitLog("Scheduled traceroutes sent to " + sent + " watched node(s)");
        }
        if (beaconOn && client.isConnected() && System.currentTimeMillis() - lastBeacon > beaconMinutes * 60_000L) {
            lastBeacon = System.currentTimeMillis();
            try { ChatMessage m = client.sendText(beaconText, beaconTo, 0); state.emitLog("Scheduled beacon sent: " + beaconText); }
            catch (IOException e) { state.emitLog("Beacon failed: " + e.getMessage()); }
        }
    }

    private static boolean due(LocalTime now, String hhmm) {
        try { LocalTime t = LocalTime.parse(hhmm.trim()); return now.getHour() == t.getHour() && now.getMinute() == t.getMinute(); } catch (Exception e) { return false; }
    }
}
