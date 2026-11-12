package meshconsole.meshcore;

import java.util.*;

import static meshconsole.ui.WebConsole.q;

/** JSON for the /meshcore page. */
public final class McWeb {
    private McWeb() { }

    public static String state() {
        McClient c = McService.client();
        if (c == null) return "{\"configured\":false}";
        StringBuilder b = new StringBuilder("{");
        b.append("\"configured\":").append(!McService.spec().isEmpty())
         .append(",\"connected\":").append(c.isConnected())
         .append(",\"where\":").append(q(McService.spec()))
         .append(",\"name\":").append(q(c.selfName)).append(",\"key\":").append(q(c.selfKey))
         .append(",\"firmware\":").append(q(c.firmware)).append(",\"board\":").append(q(c.board)).append(",\"build\":").append(q(c.buildDate))
         .append(",\"freq\":").append(c.freqMhz).append(",\"bw\":").append(c.bwKhz).append(",\"sf\":").append(c.sf).append(",\"cr\":").append(c.cr)
         .append(",\"tx\":").append(c.txPower).append(",\"txMax\":").append(c.maxTxPower)
         .append(",\"lat\":").append(c.selfLat).append(",\"lon\":").append(c.selfLon).append(",\"battMv\":").append(c.batteryMv)
         .append(",\"shareLoc\":").append(c.locPolicy == 1);
        b.append(",\"channels\":[");
        boolean first = true;
        for (McClient.Channel ch : c.channels) { if (!first) b.append(','); first = false; b.append("{\"idx\":").append(ch.idx).append(",\"name\":").append(q(ch.name)).append('}'); }
        b.append("],\"contacts\":[");
        List<McClient.Contact> cs = new ArrayList<>(c.contacts.values());
        cs.sort(Comparator.comparingLong((McClient.Contact x) -> Math.max(x.lastHeard, x.lastAdvert)).reversed());
        first = true;
        for (McClient.Contact x : cs) {
            if (!first) b.append(','); first = false;
            b.append("{\"key\":").append(q(x.key)).append(",\"prefix\":").append(q(x.key.substring(0, 12))).append(",\"name\":").append(q(x.name))
             .append(",\"type\":").append(q(McClient.TYPES[Math.min(Math.max(x.type, 0), 4)])).append(",\"lat\":").append(x.lat).append(",\"lon\":").append(x.lon)
             .append(",\"lastAdvert\":").append(x.lastAdvert).append(",\"lastHeard\":").append(x.lastHeard).append(",\"heard\":").append(x.heard)
             .append(",\"hops\":").append(x.hops).append(",\"path\":").append(x.outPathLen)
             .append(",\"rssi\":").append(x.sigCount > 0 ? String.format(Locale.US, "%.0f", x.rssiSum / x.sigCount) : "null")
             .append(",\"snr\":").append(x.sigCount > 0 ? String.format(Locale.US, "%.1f", x.snrSum / x.sigCount) : "null")
             .append(",\"direct\":").append(x.sigCount).append('}');
        }
        b.append("]}");
        return b.toString();
    }

    public static String messages(long since) {
        McClient c = McService.client();
        StringBuilder b = new StringBuilder("[");
        if (c != null) {
            boolean first = true;
            for (McClient.Message m : c.messages) {
                if (m.time < since) continue;
                if (!first) b.append(','); first = false;
                b.append("{\"t\":").append(m.time).append(",\"out\":").append(m.out).append(",\"peer\":").append(q(m.peer))
                 .append(",\"from\":").append(q(m.from)).append(",\"text\":").append(q(m.text)).append(",\"status\":").append(q(m.status))
                 .append(",\"snr\":").append(Double.isNaN(m.snr) ? "null" : String.valueOf(m.snr)).append(",\"hops\":").append(m.hops).append('}');
            }
        }
        return b.append(']').toString();
    }

    /** Packets heard in the last 24 h: totals by type, and the recent ones for the chart. */
    public static String signal() {
        McClient c = McService.client();
        if (c == null) return "{}";
        long since = System.currentTimeMillis() - 86_400_000L;
        Map<String, Integer> byType = new TreeMap<>();
        StringBuilder pts = new StringBuilder("[");
        int n = 0, direct = 0;
        boolean first = true;
        for (McClient.Sample s : c.samples) {
            if (s.time < since) continue;
            n++; if (s.hops == 0) direct++;
            byType.merge(McClient.PKT[s.ptype], 1, Integer::sum);
            if (!first) pts.append(','); first = false;
            pts.append('[').append(s.time).append(',').append(s.rssi).append(',').append(s.snr).append(',').append(s.hops).append(',')
               .append(q(McClient.PKT[s.ptype])).append(',').append(q(s.sender)).append(',').append(q(s.lastHop)).append(']');
        }
        StringBuilder t = new StringBuilder("{");
        first = true;
        for (var e : byType.entrySet()) { if (!first) t.append(','); first = false; t.append(q(e.getKey())).append(':').append(e.getValue()); }
        return "{\"packets\":" + n + ",\"direct\":" + direct + ",\"byType\":" + t + "},\"points\":" + pts + "]}";
    }

    public static String send(Map<String, String> f) {
        McClient c = McService.client();
        if (c == null || !c.isConnected()) return "not connected";
        String text = f.getOrDefault("text", "").trim(), to = f.getOrDefault("to", "");
        if (text.isEmpty()) return "nothing to send";
        if (to.startsWith("#")) { try { return c.sendChannel(Integer.parseInt(to.substring(1)), text); } catch (NumberFormatException e) { return "bad channel"; } }
        return c.sendDm(to, text);
    }
}
