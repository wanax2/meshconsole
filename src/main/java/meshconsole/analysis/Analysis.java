package meshconsole.analysis;

import meshconsole.mesh.*;
import org.meshtastic.proto.ConfigProtos.Config;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/** Pure computations over the data the app collects. */
public final class Analysis {
    private Analysis() { }

    // ---- 1. hour-of-day × day-of-week ---------------------------------------------------------------
    /** [7][24] average RSSI (NaN where no data) and counts, Monday = 0. */
    public static double[][][] heatmap(List<SignalSample> samples) {
        double[][] sum = new double[7][24], cnt = new double[7][24], snr = new double[7][24];
        ZoneId z = ZoneId.systemDefault();
        for (SignalSample s : samples) {
            ZonedDateTime t = Instant.ofEpochMilli(s.time()).atZone(z);
            int d = t.getDayOfWeek().getValue() - 1, h = t.getHour();
            sum[d][h] += s.rssi(); snr[d][h] += s.snr(); cnt[d][h]++;
        }
        double[][] avg = new double[7][24], avgSnr = new double[7][24];
        for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++) { avg[d][h] = cnt[d][h] == 0 ? Double.NaN : sum[d][h] / cnt[d][h]; avgSnr[d][h] = cnt[d][h] == 0 ? Double.NaN : snr[d][h] / cnt[d][h]; }
        return new double[][][]{avg, cnt, avgSnr};
    }

    // ---- 2. link margin ----------------------------------------------------------------------------
    /** LoRa demodulation SNR limit for the spreading factor in use (dB). */
    public static double snrLimit(Config.LoRaConfig lora) {
        int sf = (int) Airtime.params(lora)[0];
        return switch (sf) { case 7 -> -7.5; case 8 -> -10; case 9 -> -12.5; case 10 -> -15; case 11 -> -17.5; default -> -20; };
    }
    public static double linkMargin(double avgSnr, Config.LoRaConfig lora) { return avgSnr - snrLimit(lora); }

    // ---- 4/5. mesh graph ---------------------------------------------------------------------------
    public record GraphStats(Map<Integer, Integer> degree, Set<Integer> articulation, Map<Integer, Integer> hopsFromMe, int reachable, int components) { }

    public static GraphStats graph(Map<Integer, Set<Integer>> adj, int me) {
        Map<Integer, Integer> degree = new HashMap<>();
        adj.forEach((k, v) -> degree.put(k, v.size()));
        // Tarjan articulation points
        Set<Integer> art = new HashSet<>();
        Map<Integer, Integer> disc = new HashMap<>(), low = new HashMap<>();
        int[] timer = {0};
        for (Integer root : adj.keySet()) if (!disc.containsKey(root)) dfs(root, -1, adj, disc, low, art, timer);
        // BFS from me
        Map<Integer, Integer> hops = new HashMap<>();
        if (adj.containsKey(me)) {
            Deque<Integer> q = new ArrayDeque<>(); q.add(me); hops.put(me, 0);
            while (!q.isEmpty()) { int u = q.poll(); for (int v : adj.getOrDefault(u, Set.of())) if (!hops.containsKey(v)) { hops.put(v, hops.get(u) + 1); q.add(v); } }
        }
        int comps = 0; Set<Integer> seen = new HashSet<>();
        for (Integer n : adj.keySet()) if (seen.add(n)) { comps++; Deque<Integer> q = new ArrayDeque<>(List.of(n)); while (!q.isEmpty()) { int u = q.poll(); for (int v : adj.getOrDefault(u, Set.of())) if (seen.add(v)) q.add(v); } }
        return new GraphStats(degree, art, hops, hops.size(), comps);
    }

    private static void dfs(int u, int parent, Map<Integer, Set<Integer>> adj, Map<Integer, Integer> disc, Map<Integer, Integer> low, Set<Integer> art, int[] timer) {
        disc.put(u, timer[0]); low.put(u, timer[0]); timer[0]++;
        int children = 0;
        for (int v : adj.getOrDefault(u, Set.of())) {
            if (v == parent) continue;
            if (!disc.containsKey(v)) {
                children++;
                dfs(v, u, adj, disc, low, art, timer);
                low.put(u, Math.min(low.get(u), low.get(v)));
                if (parent != -1 && low.get(v) >= disc.get(u)) art.add(u);
            } else low.put(u, Math.min(low.get(u), disc.get(v)));
        }
        if (parent == -1 && children > 1) art.add(u);
    }

    // ---- 6. churn ----------------------------------------------------------------------------------
    public record Churn(int[] arrivalsPerDay, int[] activePerDay, int departed, long medianLifetimeMs, int total) { }

    public static Churn churn(List<NodeEntry> nodes, int me, int days) {
        long now = System.currentTimeMillis(), day = 86400_000L;
        int[] arrivals = new int[days], active = new int[days];
        List<Long> lifetimes = new ArrayList<>();
        int departed = 0, total = 0;
        for (NodeEntry n : nodes) {
            if (n.num == me) continue;
            total++;
            long first = n.firstSeen, last = n.lastHeardMillis();
            if (first > 0) { int d = (int) ((now - first) / day); if (d >= 0 && d < days) arrivals[days - 1 - d]++; }
            if (first > 0 && last > 0) {
                lifetimes.add(Math.max(0, last - first));
                for (int d = 0; d < days; d++) { long ds = now - (long) (days - d) * day, de = ds + day; if (first < de && last >= ds) active[d]++; }
            }
            if (last > 0 && now - last > day) departed++;
        }
        Collections.sort(lifetimes);
        long median = lifetimes.isEmpty() ? 0 : lifetimes.get(lifetimes.size() / 2);
        return new Churn(arrivals, active, departed, median, total);
    }

    // ---- 7. utilisation by hour --------------------------------------------------------------------
    public static double[] byHour(List<UtilSample> util) {
        double[] sum = new double[24], cnt = new double[24];
        ZoneId z = ZoneId.systemDefault();
        for (UtilSample u : util) { int h = Instant.ofEpochMilli(u.time()).atZone(z).getHour(); sum[h] += u.channelUtil(); cnt[h]++; }
        double[] out = new double[24];
        for (int h = 0; h < 24; h++) out[h] = cnt[h] == 0 ? Double.NaN : sum[h] / cnt[h];
        return out;
    }

    // ---- 10. delivery buckets ----------------------------------------------------------------------
    public record Bucket(String label, int sent, int delivered) { public int pct() { return sent == 0 ? -1 : Math.round(100f * delivered / sent); } }

    public static Map<String, List<Bucket>> deliveryBuckets(List<ChatMessage> messages, MeshState state) {
        // one logical message per (dest, text, hour)
        Map<String, List<ChatMessage>> logical = new LinkedHashMap<>();
        for (ChatMessage m : messages) if (m.outgoing && !m.isBroadcast()) logical.computeIfAbsent(m.to + "|" + m.text + "|" + m.time / 3_600_000, k -> new ArrayList<>()).add(m);
        String[] hopL = {"direct", "1 hop", "2 hops", "3+ hops", "unknown"};
        String[] distL = {"< 1 km", "1–5 km", "5–20 km", "> 20 km", "no position"};
        String[] hourL = {"00–06", "06–12", "12–18", "18–24"};
        int[][] hop = new int[5][2], dist = new int[5][2], hour = new int[4][2];
        NodeEntry me = state.myNode();
        ZoneId z = ZoneId.systemDefault();
        for (List<ChatMessage> g : logical.values()) {
            ChatMessage first = g.get(0);
            boolean del = g.stream().anyMatch(m -> m.status == ChatMessage.Status.DELIVERED);
            NodeEntry dest = state.node(first.to);
            int hi = dest == null || dest.hopsAway < 0 ? 4 : Math.min(dest.hopsAway, 3);
            int di = 4;
            if (dest != null && dest.hasPosition && me != null && me.hasPosition) {
                double km = Metar.distanceKm(me.lat, me.lon, dest.lat, dest.lon);
                di = km < 1 ? 0 : km < 5 ? 1 : km < 20 ? 2 : 3;
            }
            int hr = Instant.ofEpochMilli(first.time).atZone(z).getHour() / 6;
            hop[hi][0]++; dist[di][0]++; hour[hr][0]++;
            if (del) { hop[hi][1]++; dist[di][1]++; hour[hr][1]++; }
        }
        Map<String, List<Bucket>> out = new LinkedHashMap<>();
        out.put("By hops", toBuckets(hopL, hop)); out.put("By distance", toBuckets(distL, dist)); out.put("By time of day", toBuckets(hourL, hour));
        return out;
    }
    private static List<Bucket> toBuckets(String[] l, int[][] c) { List<Bucket> b = new ArrayList<>(); for (int i = 0; i < l.length; i++) b.add(new Bucket(l[i], c[i][0], c[i][1])); return b; }

    // ---- 11. antenna A/B ---------------------------------------------------------------------------
    public record AntennaResult(String label, int samples, double avgRssi, double avgSnr, int nodes, double commonRssi, double commonSnr, int commonNodes) { }

    /** periods: sorted (startMillis, label). Compares labels on all samples and on the node set heard under every label. */
    public static List<AntennaResult> antennaAB(List<long[]> periodStarts, List<String> labels, List<SignalSample> samples) {
        Map<String, Map<Integer, double[]>> perLabelNode = new LinkedHashMap<>();   // label → node → {n, rssiSum, snrSum}
        for (SignalSample s : samples) {
            String lab = null;
            for (int i = 0; i < periodStarts.size(); i++) if (s.time() >= periodStarts.get(i)[0]) lab = labels.get(i);
            if (lab == null) continue;
            double[] acc = perLabelNode.computeIfAbsent(lab, k -> new HashMap<>()).computeIfAbsent(s.from(), k -> new double[3]);
            acc[0]++; acc[1] += s.rssi(); acc[2] += s.snr();
        }
        Set<Integer> common = null;
        for (Map<Integer, double[]> m : perLabelNode.values()) { if (common == null) common = new HashSet<>(m.keySet()); else common.retainAll(m.keySet()); }
        if (common == null) common = Set.of();
        List<AntennaResult> out = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, double[]>> e : perLabelNode.entrySet()) {
            double n = 0, r = 0, sn = 0, cn = 0, cr = 0, cs = 0;
            for (Map.Entry<Integer, double[]> ne : e.getValue().entrySet()) {
                double[] a = ne.getValue(); n += a[0]; r += a[1]; sn += a[2];
                if (common.contains(ne.getKey())) { cn += a[0]; cr += a[1]; cs += a[2]; }
            }
            out.add(new AntennaResult(e.getKey(), (int) n, r / n, sn / n, e.getValue().size(), cn == 0 ? Double.NaN : cr / cn, cn == 0 ? Double.NaN : cs / cn, common.size()));
        }
        return out;
    }

    /** Per-slot signal summary: slot → {samples, avgRssi, avgSnr, distinct nodes, first, last}. */
    public static Map<Integer, double[]> bySlot(List<SignalSample> samples) {
        Map<Integer, double[]> m = new TreeMap<>();
        Map<Integer, Set<Integer>> nodes = new HashMap<>();
        for (SignalSample s : samples) {
            if (s.hops() == -1) continue;   // hourly averages carry no slot
            double[] a = m.computeIfAbsent(s.slot(), k -> new double[]{0, 0, 0, 0, Double.MAX_VALUE, 0});
            a[0]++; a[1] += s.rssi(); a[2] += s.snr(); a[4] = Math.min(a[4], s.time()); a[5] = Math.max(a[5], s.time());
            nodes.computeIfAbsent(s.slot(), k -> new HashSet<>()).add(s.from());
        }
        for (Map.Entry<Integer, double[]> e : m.entrySet()) { double[] a = e.getValue(); a[1] /= a[0]; a[2] /= a[0]; a[3] = nodes.get(e.getKey()).size(); }
        return m;
    }

    /** Per receiving radio: rxNode → {samples, avgRssi, avgSnr, distinct nodes, direct %, first, last}. */
    public static Map<Integer, double[]> byRadio(List<SignalSample> samples) {
        Map<Integer, double[]> m = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> nodes = new HashMap<>();
        for (SignalSample s : samples) {
            if (s.hops() == -1 || s.rxNode() == 0) continue;
            double[] a = m.computeIfAbsent(s.rxNode(), k -> new double[]{0, 0, 0, 0, 0, Double.MAX_VALUE, 0});
            a[0]++; a[1] += s.rssi(); a[2] += s.snr(); if (s.hops() == 0) a[4]++; a[5] = Math.min(a[5], s.time()); a[6] = Math.max(a[6], s.time());
            nodes.computeIfAbsent(s.rxNode(), k -> new HashSet<>()).add(s.from());
        }
        for (Map.Entry<Integer, double[]> e : m.entrySet()) { double[] a = e.getValue(); a[1] /= a[0]; a[2] /= a[0]; a[4] = 100.0 * a[4] / a[0]; a[3] = nodes.get(e.getKey()).size(); }
        return m;
    }

    /** Direct-message delivery per sending radio: from → {logical sent, delivered}. */
    public static Map<Integer, int[]> deliveryByRadio(List<ChatMessage> messages) {
        Map<String, List<ChatMessage>> logical = new LinkedHashMap<>();
        for (ChatMessage m : messages) if (m.outgoing && !m.isBroadcast()) logical.computeIfAbsent(m.from + "|" + m.to + "|" + m.text + "|" + m.time / 3_600_000, k -> new ArrayList<>()).add(m);
        Map<Integer, int[]> out = new LinkedHashMap<>();
        for (List<ChatMessage> g : logical.values()) {
            int[] a = out.computeIfAbsent(g.get(0).from, k -> new int[2]);
            a[0]++;
            if (g.stream().anyMatch(x -> x.status == ChatMessage.Status.DELIVERED)) a[1]++;
        }
        return out;
    }

    public record SetupResult(SetupLog.Setup setup, int samples, double avgRssi, double avgSnr, int nodes, double directPct, long hours) { }

    /** Compares physical setups: each sample goes to the setup in force for its receiving radio at that time. */
    public static List<SetupResult> bySetup(SetupLog setups, List<SignalSample> samples) {
        Map<SetupLog.Setup, double[]> acc = new LinkedHashMap<>();
        Map<SetupLog.Setup, Set<Integer>> nodes = new HashMap<>();
        for (SignalSample s : samples) {
            if (s.hops() == -1 || s.rxNode() == 0) continue;
            SetupLog.Setup st = setups.at(s.rxNode(), s.time());
            if (st == null) continue;
            double[] a = acc.computeIfAbsent(st, k -> new double[]{0, 0, 0, 0, Double.MAX_VALUE, 0});
            a[0]++; a[1] += s.rssi(); a[2] += s.snr(); if (s.hops() == 0) a[3]++; a[4] = Math.min(a[4], s.time()); a[5] = Math.max(a[5], s.time());
            nodes.computeIfAbsent(st, k -> new HashSet<>()).add(s.from());
        }
        List<SetupResult> out = new ArrayList<>();
        for (Map.Entry<SetupLog.Setup, double[]> e : acc.entrySet()) {
            double[] a = e.getValue();
            out.add(new SetupResult(e.getKey(), (int) a[0], a[1] / a[0], a[2] / a[0], nodes.get(e.getKey()).size(), 100.0 * a[3] / a[0], Math.round((a[5] - a[4]) / 3600_000.0)));
        }
        out.sort((x, y) -> Double.compare(y.avgRssi(), x.avgRssi()));
        return out;
    }

    // ---- 12. coverage grid -------------------------------------------------------------------------
    public record Cell(double lat, double lon, double medianRssi, int n) { }

    public static List<Cell> coverageGrid(List<CoverageSample> cov, double cellMeters) {
        Map<Long, List<Integer>> cells = new HashMap<>();
        Map<Long, double[]> centre = new HashMap<>();
        for (CoverageSample c : cov) {
            double dLat = cellMeters / 111_320.0, dLon = cellMeters / (111_320.0 * Math.cos(Math.toRadians(c.lat())));
            long iy = (long) Math.floor(c.lat() / dLat), ix = (long) Math.floor(c.lon() / dLon);
            long key = iy * 4_000_000L + ix;
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(c.rssi());
            centre.putIfAbsent(key, new double[]{(iy + 0.5) * dLat, (ix + 0.5) * dLon});
        }
        List<Cell> out = new ArrayList<>();
        for (Map.Entry<Long, List<Integer>> e : cells.entrySet()) {
            List<Integer> v = e.getValue(); Collections.sort(v);
            double[] c = centre.get(e.getKey());
            out.add(new Cell(c[0], c[1], v.get(v.size() / 2), v.size()));
        }
        return out;
    }

    // ---- 3. weather correlation --------------------------------------------------------------------
    public record WeatherCorr(int node, int hours, double rTemp, double rHumidity, double rWind, double rPressure, double dryRssi, double wetRssi, int wetHours, double baseRssi) { }

    /** Pearson r between a node's hourly-average RSSI and each weather variable, plus wet-vs-dry RSSI. */
    public static List<WeatherCorr> weatherCorrelation(List<SignalSample> samples, WeatherHistory weather, int minHours) {
        // hourly averages per node
        Map<Integer, Map<Long, double[]>> hourly = new HashMap<>();
        for (SignalSample s : samples) {
            double[] a = hourly.computeIfAbsent(s.from(), k -> new HashMap<>()).computeIfAbsent(s.time() / 3_600_000L, k -> new double[2]);
            a[0]++; a[1] += s.rssi();
        }
        List<WeatherCorr> out = new ArrayList<>();
        for (Map.Entry<Integer, Map<Long, double[]>> e : hourly.entrySet()) {
            List<double[]> rows = new ArrayList<>();   // rssi, temp, rh, wind, pressure, precip
            for (Map.Entry<Long, double[]> h : e.getValue().entrySet()) {
                WeatherObs w = weather.at(h.getKey() * 3_600_000L + 1_800_000L);
                if (w == null) continue;
                rows.add(new double[]{h.getValue()[1] / h.getValue()[0], w.tempC(), w.humidityPct(), w.windKt(), w.pressureHpa(), w.precip() ? 1 : 0});
            }
            if (rows.size() < minHours) continue;
            double dry = 0, wet = 0; int nd = 0, nw = 0, all = 0; double base = 0;
            for (double[] r : rows) { base += r[0]; all++; if (r[5] > 0) { wet += r[0]; nw++; } else { dry += r[0]; nd++; } }
            out.add(new WeatherCorr(e.getKey(), rows.size(), pearson(rows, 1), pearson(rows, 2), pearson(rows, 3), pearson(rows, 4),
                    nd == 0 ? Double.NaN : dry / nd, nw == 0 ? Double.NaN : wet / nw, nw, base / all));
        }
        out.sort((a, b) -> Integer.compare(b.hours(), a.hours()));
        return out;
    }

    private static double pearson(List<double[]> rows, int col) {
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0; int n = 0;
        for (double[] r : rows) { double x = r[col], y = r[0]; if (Double.isNaN(x)) continue; n++; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; }
        if (n < 3) return Double.NaN;
        double den = Math.sqrt((n * sxx - sx * sx) * (n * syy - sy * sy));
        return den == 0 ? Double.NaN : (n * sxy - sx * sy) / den;
    }
}
