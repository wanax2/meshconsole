package meshconsole.mesh;

import java.util.HashSet;
import java.util.Set;

/** Periodic health checks: silent nodes, low battery, high utilisation. Emits through MeshState.onAlert. */
public class AlertEngine {
    private final MeshState state;
    public volatile boolean enabled = true;
    public volatile int silentHours = 6;
    public volatile boolean silentFavoritesOnly = true;
    public volatile int batteryPct = 20;
    public volatile int utilPct = 30;
    private final Set<Integer> silentFlagged = new HashSet<>();
    private final Set<Integer> batteryFlagged = new HashSet<>();
    private boolean utilFlagged;
    private final Set<Integer> watchedSilent = new HashSet<>();

    public AlertEngine(MeshState state) { this.state = state; }

    /** Call every minute or so. */
    public void check() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        int me = state.myNodeNum();
        for (NodeEntry n : state.nodes()) {
            if (n.num == me) {
                if (n.channelUtil >= utilPct) {
                    if (!utilFlagged) { utilFlagged = true; state.emitAlert("UTIL", String.format("Channel utilisation %.0f%% – the mesh is congested", n.channelUtil)); }
                } else if (n.channelUtil > 0 && n.channelUtil < utilPct - 5) utilFlagged = false;
                continue;
            }
            long last = n.lastHeardMillis();
            if (n.watched && last > 0) {
                // watch-list: tighter rules, independent of the general silent-node setting
                if (now - last > 3600_000L) { if (watchedSilent.add(n.num)) state.emitAlert("WATCH", n.displayName() + " (watched) not heard for " + meshconsole.ui.FmtBridge.ago(last)); }
                else if (watchedSilent.remove(n.num)) state.emitAlert("WATCH", n.displayName() + " (watched) is back, heard " + meshconsole.ui.FmtBridge.ago(last) + " ago");
            }
            if (last > 0 && (!silentFavoritesOnly || n.isFavorite) && !n.fromDb) {
                if (now - last > silentHours * 3600_000L) {
                    if (silentFlagged.add(n.num)) state.emitAlert("SILENT", n.displayName() + " has been silent for " + meshconsole.ui.FmtBridge.ago(last));
                } else silentFlagged.remove(n.num);
            }
            if (n.battery >= 0 && n.battery <= 100) {
                if (n.battery <= batteryPct) {
                    if (batteryFlagged.add(n.num)) state.emitAlert("BATTERY", n.displayName() + " battery at " + n.battery + "%");
                } else if (n.battery > batteryPct + 10) batteryFlagged.remove(n.num);
            }
        }
    }
}
