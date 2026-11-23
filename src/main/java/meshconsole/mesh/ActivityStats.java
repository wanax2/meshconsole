package meshconsole.mesh;

import java.util.*;

/**
 * 1.29.4: two views of what the radio hears, kept since the last traffic reset.
 * <ul>
 *   <li>Packets per minute by kind (text, position, telemetry …) for the last 6 hours, with the radio's own
 *       channel-utilisation reading for each minute.</li>
 *   <li>Hop usage: for packets heard over the air from other nodes, how many hops they were sent with
 *       (hop_start) against how many they used to get here (hop_start − hop_limit).</li>
 * </ul>
 */
public final class ActivityStats {
    /** Packet kinds in chart order, with their colours (also used by the web console). */
    public static final String[] KINDS = {"Text", "Position", "Node info", "Telemetry", "Routing", "Traceroute", "Neighbours", "Encrypted", "Other"};
    public static final String[] COLOURS = {"#e5484d", "#3e8ed0", "#8e4ec6", "#30a46c", "#f5a524", "#12a5b5", "#d6409f", "#868e96", "#b08050"};
    public static final int MAX_HOPS = 7;
    static final int KEEP_MIN = 360;

    public record Minute(long startMillis, int[] counts, float util) {
        public int total() { int t = 0; for (int c : counts) t += c; return t; }
    }

    private final TreeMap<Long, int[]> perMinute = new TreeMap<>();
    private final TreeMap<Long, Float> utilMinute = new TreeMap<>();
    private final long[][] hops = new long[MAX_HOPS + 1][MAX_HOPS + 1];   // [hop_start][hops used]

    public static int kind(String port) {
        return switch (port) {
            case "TEXT_MESSAGE_APP", "TEXT_MESSAGE_COMPRESSED_APP", "DETECTION_SENSOR_APP", "ALERT_APP" -> 0;
            case "POSITION_APP", "MAP_REPORT_APP", "WAYPOINT_APP" -> 1;
            case "NODEINFO_APP" -> 2;
            case "TELEMETRY_APP", "PAXCOUNTER_APP", "RANGE_TEST_APP" -> 3;
            case "ROUTING_APP", "ADMIN_APP", "STORE_FORWARD_APP" -> 4;
            case "TRACEROUTE_APP" -> 5;
            case "NEIGHBORINFO_APP" -> 6;
            case "ENCRYPTED" -> 7;
            default -> 8;
        };
    }

    /** One packet heard. util = the radio's latest channel-utilisation reading (NaN if unknown). */
    public synchronized void record(long now, String port, boolean fromOther, boolean viaMqtt, int hopStart, int hopLimit, float util) {
        long m = now / 60_000L * 60_000L;
        perMinute.computeIfAbsent(m, k -> new int[KINDS.length])[kind(port)]++;
        if (!Float.isNaN(util) && util > 0) utilMinute.put(m, util);
        while (!perMinute.isEmpty() && perMinute.firstKey() < m - KEEP_MIN * 60_000L) perMinute.pollFirstEntry();
        while (!utilMinute.isEmpty() && utilMinute.firstKey() < m - KEEP_MIN * 60_000L) utilMinute.pollFirstEntry();
        if (fromOther && !viaMqtt && hopStart > 0 && hopStart <= MAX_HOPS && hopLimit >= 0 && hopLimit <= hopStart)
            hops[hopStart][hopStart - hopLimit]++;
    }

    /** The last N minutes up to now, oldest first, one entry per minute (empty minutes included). */
    public synchronized List<Minute> minutes(int n, long now) {
        long end = now / 60_000L * 60_000L;
        List<Minute> out = new ArrayList<>(n);
        Float lastUtil = null;
        Map.Entry<Long, Float> before = utilMinute.floorEntry(end - (long) n * 60_000L);
        if (before != null) lastUtil = before.getValue();
        for (long m = end - (long) (n - 1) * 60_000L; m <= end; m += 60_000L) {
            int[] c = perMinute.get(m);
            Float u = utilMinute.get(m);
            if (u != null) lastUtil = u;
            out.add(new Minute(m, c == null ? new int[KINDS.length] : c.clone(), lastUtil == null ? Float.NaN : lastUtil));
        }
        return out;
    }

    /** Copy of the hop table: [hop_start][hops used]. */
    public synchronized long[][] hops() {
        long[][] c = new long[MAX_HOPS + 1][];
        for (int i = 0; i <= MAX_HOPS; i++) c[i] = hops[i].clone();
        return c;
    }

    public synchronized void reset() {
        perMinute.clear(); utilMinute.clear();
        for (long[] r : hops) Arrays.fill(r, 0);
    }

    /** A plain-words reading of the hop table, for the hop-usage view. */
    public static String summary(long[][] h) {
        long total = 0, atLimit = 0, unused = 0, over3 = 0, usedOver3 = 0;
        for (int s = 1; s <= MAX_HOPS; s++) for (int u = 0; u <= s; u++) {
            long c = h[s][u];
            total += c;
            if (u == s) atLimit += c;
            unused += c * (s - u);
            if (s > 3) { over3 += c; if (u > 3) usedOver3 += c; }
        }
        if (total == 0) return "No packets with hop information yet.";
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "%,d packets. %.0f%% arrived having used every hop they were sent with (they may have been cut short further on). ",
                total, 100.0 * atLimit / total));
        b.append(String.format(Locale.US, "On average %.1f hops per packet were left unused. ", (double) unused / total));
        if (over3 > 0) b.append(String.format(Locale.US, "%,d (%.0f%%) were sent with more than 3 hops; %,d of those needed more than 3 to reach you.",
                over3, 100.0 * over3 / total, usedOver3));
        return b.toString().trim();
    }
}
