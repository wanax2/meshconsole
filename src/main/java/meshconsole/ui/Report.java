package meshconsole.ui;

import meshconsole.analysis.*;
import meshconsole.mesh.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Self-contained HTML mesh report. */
final class Report {
    private Report() { }

    static String html(MeshState state, SignalHistory history, UtilHistory utilHistory, WeatherHistory weather, AntennaLog antennaLog, Path alertsLog) {
        long now = System.currentTimeMillis(), week = 7L * 86400_000L;
        int me = state.myNodeNum();
        NodeEntry meN = state.myNode();
        List<NodeEntry> nodes = state.nodes();
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset='utf-8'><title>Mesh report</title><style>")
         .append("body{font-family:system-ui,sans-serif;max-width:1000px;margin:24px auto;padding:0 16px;color:#222}h1{margin-bottom:0}h2{border-bottom:2px solid #ddd;margin-top:32px}")
         .append("table{border-collapse:collapse;margin:8px 0}td,th{border:1px solid #ddd;padding:4px 8px;text-align:left;font-size:14px}th{background:#f3f3f3}.muted{color:#666}.bad{color:#b00}.good{color:#080}")
         .append(".bar{display:inline-block;height:12px;background:#4a6fd0}</style></head><body>");
        h.append("<h1>Mesh report</h1><div class='muted'>").append(esc(meN == null ? "" : meN.displayName() + " " + meN.idString())).append(" · generated ").append(Fmt.time(now))
         .append(" · ").append(meshconsole.Version.NAME).append(' ').append(meshconsole.Version.VERSION).append("</div>");

        // nodes overview
        int total = 0, active24 = 0, newWeek = 0, gone = 0;
        List<NodeEntry> newNodes = new ArrayList<>(), goneNodes = new ArrayList<>();
        for (NodeEntry n : nodes) {
            if (n.num == me) continue;
            total++;
            long last = n.lastHeardMillis();
            if (now - last < 86400_000L) active24++;
            if (n.firstSeen > now - week) { newWeek++; newNodes.add(n); }
            if (last > 0 && now - last > week && n.firstSeen > 0 && now - last < 4 * week) { gone++; goneNodes.add(n); }
        }
        h.append("<h2>Nodes</h2><p>").append(total).append(" known, <b>").append(active24).append("</b> heard in the last 24 h, <b>").append(newWeek).append("</b> new this week, ").append(gone).append(" not heard for over a week.</p>");
        if (!newNodes.isEmpty()) { h.append("<p><b>New:</b> "); for (NodeEntry n : newNodes) h.append(esc(n.displayName())).append(" (").append(n.hwModel).append(", first ").append(Fmt.time(n.firstSeen)).append("); "); h.append("</p>"); }
        if (!goneNodes.isEmpty()) { h.append("<p><b>Gone quiet:</b> "); for (NodeEntry n : goneNodes) h.append(esc(n.displayName())).append(" (last ").append(Fmt.time(n.lastHeardMillis())).append("); "); h.append("</p>"); }

        // busiest talkers
        List<NodeEntry> talkers = new ArrayList<>(); for (NodeEntry n : nodes) if (n.airtimeMs > 0 && n.num != me) talkers.add(n);
        talkers.sort((a, b) -> Long.compare(b.airtimeMs, a.airtimeMs));
        long elapsed = Math.max(1, now - state.trafficSince());
        h.append("<h2>Busiest talkers</h2><div class='muted'>airtime as heard here since ").append(Fmt.time(state.trafficSince())).append("</div><table><tr><th>Node</th><th>Packets</th><th>Airtime</th><th>% of elapsed</th><th></th></tr>");
        for (NodeEntry n : talkers.subList(0, Math.min(15, talkers.size()))) {
            double pct = 100.0 * n.airtimeMs / elapsed;
            h.append("<tr><td>").append(esc(n.displayName())).append("</td><td>").append(n.packetsSeen).append("</td><td>").append(String.format("%.1f s", n.airtimeMs / 1000.0)).append("</td><td")
             .append(pct > 10 ? " class='bad'" : "").append(">").append(String.format("%.2f%%", pct)).append("</td><td><span class='bar' style='width:").append((int) Math.min(300, pct * 30)).append("px'></span></td></tr>");
        }
        h.append("</table>");

        // weakest links
        List<NodeEntry> links = new ArrayList<>(); for (NodeEntry n : nodes) if (n.snrCount > 0 && n.num != me) links.add(n);
        links.sort(Comparator.comparingDouble(NodeEntry::avgSnr));
        h.append("<h2>Weakest links</h2><table><tr><th>Node</th><th>Avg SNR</th><th>Margin</th><th>Avg RSSI</th><th>Hops</th><th>Samples</th></tr>");
        for (NodeEntry n : links.subList(0, Math.min(12, links.size()))) {
            double m = Analysis.linkMargin(n.avgSnr(), state.lora());
            h.append("<tr><td>").append(esc(n.displayName())).append("</td><td>").append(String.format("%.1f", n.avgSnr())).append("</td><td").append(m < 3 ? " class='bad'" : " class='good'").append(">").append(String.format("%+.1f dB", m))
             .append("</td><td>").append(n.rssiCount == 0 ? "" : String.format("%.0f", n.avgRssi())).append("</td><td>").append(n.hopsAway < 0 ? "?" : n.hopsAway == 0 ? "direct" : String.valueOf(n.hopsAway)).append("</td><td>").append(n.snrCount).append("</td></tr>");
        }
        h.append("</table>");

        // structure
        Analysis.GraphStats g = Analysis.graph(state.graphEdges(), me);
        h.append("<h2>Mesh structure</h2><p>").append(state.graphEdges().size()).append(" nodes with known links, ").append(g.components()).append(" component(s), ").append(g.reachable()).append(" reachable from this node.</p>");
        if (!g.articulation().isEmpty()) { h.append("<p><b>Critical relays</b> (losing one splits the mesh): "); for (int a : g.articulation()) h.append(esc(state.nodeName(a))).append("; "); h.append("</p>"); }
        Map<Integer, Long> relays = state.relayCounts();
        if (!relays.isEmpty()) {
            long rt = relays.values().stream().mapToLong(Long::longValue).sum();
            List<Map.Entry<Integer, Long>> rl = new ArrayList<>(relays.entrySet()); rl.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            h.append("<p><b>Relay share:</b> ");
            for (Map.Entry<Integer, Long> e : rl.subList(0, Math.min(8, rl.size()))) {
                StringBuilder names = new StringBuilder(); for (NodeEntry n : nodes) if ((n.num & 0xFF) == e.getKey()) names.append(names.length() > 0 ? "/" : "").append(esc(n.shortLabel()));
                h.append(names.length() == 0 ? String.format("..%02x", e.getKey()) : names).append(String.format(" %.0f%%; ", 100.0 * e.getValue() / rt));
            }
            h.append("</p>");
        }

        // utilisation curve
        List<UtilSample> u = utilHistory != null ? utilHistory.util() : state.utilHistory();
        double[] byHour = Analysis.byHour(u);
        h.append("<h2>Channel utilisation by hour</h2><table><tr>"); for (int i = 0; i < 24; i++) h.append("<th>").append(i).append("</th>"); h.append("</tr><tr>");
        for (int i = 0; i < 24; i++) { double v = byHour[i]; h.append("<td").append(!Double.isNaN(v) && v >= 25 ? " class='bad'" : "").append(">").append(Double.isNaN(v) ? "–" : String.valueOf((int) Math.round(v))).append("</td>"); }
        h.append("</tr></table><div class='muted'>average % per hour of day; ≥25 % is congested</div>");
        if (utilHistory != null && !utilHistory.noise().isEmpty()) {
            List<UtilHistory.NoiseSample> ns = utilHistory.noise();
            int best = 0, worst = -200; for (UtilHistory.NoiseSample n : ns) { best = Math.min(best, n.noiseFloorDbm()); worst = Math.max(worst, n.noiseFloorDbm()); }
            h.append("<p><b>Noise floor:</b> now ").append(ns.get(ns.size() - 1).noiseFloorDbm()).append(" dBm, best ").append(best).append(", worst ").append(worst).append(" (healthy ≈ −110 to −115).</p>");
        }

        // delivery
        h.append("<h2>Direct-message delivery</h2><table><tr><th>Group</th><th>Bucket</th><th>Sent</th><th>Delivered</th><th>Success</th></tr>");
        for (Map.Entry<String, List<Analysis.Bucket>> e : Analysis.deliveryBuckets(state.messages(), state).entrySet())
            for (Analysis.Bucket b : e.getValue()) if (b.sent() > 0) h.append("<tr><td>").append(e.getKey()).append("</td><td>").append(b.label()).append("</td><td>").append(b.sent()).append("</td><td>").append(b.delivered()).append("</td><td>").append(b.pct()).append("%</td></tr>");
        h.append("</table>");

        // weather
        if (weather != null && weather.size() > 0 && history != null) {
            List<Analysis.WeatherCorr> wc = Analysis.weatherCorrelation(history.since(now - 365L * 86400_000L), weather, 12);
            WeatherObs latest = weather.latest();
            h.append("<h2>Weather</h2><p>").append(weather.size()).append(" observations from ").append(latest == null ? "" : esc(latest.station())).append(latest == null ? "" : ", latest " + Fmt.time(latest.time()) + ": " + esc(latest.raw())).append("</p>");
            if (!wc.isEmpty()) {
                h.append("<table><tr><th>Node</th><th>Hours</th><th>r temp</th><th>r humidity</th><th>r wind</th><th>r pressure</th><th>Rain effect</th></tr>");
                for (Analysis.WeatherCorr w : wc.subList(0, Math.min(15, wc.size())))
                    h.append("<tr><td>").append(esc(state.nodeName(w.node()))).append("</td><td>").append(w.hours()).append("</td><td>").append(r(w.rTemp())).append("</td><td>").append(r(w.rHumidity())).append("</td><td>").append(r(w.rWind())).append("</td><td>").append(r(w.rPressure()))
                     .append("</td><td>").append(Double.isNaN(w.wetRssi()) || Double.isNaN(w.dryRssi()) ? "–" : String.format("%+.1f dB (%d wet h)", w.wetRssi() - w.dryRssi(), w.wetHours())).append("</td></tr>");
                h.append("</table>");
            }
        }

        // antenna
        if (antennaLog != null && !antennaLog.labels.isEmpty() && history != null) {
            h.append("<h2>Antenna comparison</h2><table><tr><th>Antenna</th><th>Samples</th><th>Avg RSSI</th><th>Avg SNR</th><th>Common-node RSSI</th><th>Common-node SNR</th></tr>");
            for (Analysis.AntennaResult a : Analysis.antennaAB(antennaLog.starts, antennaLog.labels, history.since(now - 365L * 86400_000L)))
                h.append("<tr><td>").append(esc(a.label())).append("</td><td>").append(a.samples()).append("</td><td>").append(String.format("%.1f", a.avgRssi())).append("</td><td>").append(String.format("%.1f", a.avgSnr())).append("</td><td>")
                 .append(Double.isNaN(a.commonRssi()) ? "–" : String.format("%.1f", a.commonRssi())).append("</td><td>").append(Double.isNaN(a.commonSnr()) ? "–" : String.format("%.1f", a.commonSnr())).append("</td></tr>");
            h.append("</table>");
        }

        // alerts
        if (alertsLog != null && Files.exists(alertsLog)) {
            try {
                List<String> lines = Files.readAllLines(alertsLog, StandardCharsets.UTF_8);
                h.append("<h2>Recent alerts</h2><pre style='font-size:12px'>");
                for (String l : lines.subList(Math.max(0, lines.size() - 30), lines.size())) h.append(esc(l)).append('\n');
                h.append("</pre>");
            } catch (IOException ignored) { }
        }
        h.append("<p class='muted'>Airtime from packet size × modem preset; only packets this radio heard are counted. Map data © OpenStreetMap contributors. Weather: NOAA aviationweather.gov.</p></body></html>");
        return h.toString();
    }

    private static String r(double v) { return Double.isNaN(v) ? "–" : String.format("%+.2f", v); }
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
