package meshconsole.mesh;

/** Everything we know about one mesh node (mutable, guarded by MeshState's lock). */
public class NodeEntry {
    public final int num;
    public String longName = "";
    public String shortName = "";
    public String hwModel = "";
    public byte[] publicKey = new byte[0];
    public boolean hasPosition;
    public double lat, lon;
    public int altitude;
    public long lastHeard;          // epoch seconds as reported by the radio (0 = never)
    public long lastLocalRx;        // System.currentTimeMillis() when we last saw a packet from it
    public float snr;               // last SNR of a packet from this node (dB)
    public int rssi;                // last RSSI (dBm), 0 = unknown
    public int hopsAway = -1;       // -1 = unknown
    public boolean viaMqtt;
    public int battery = -1;        // percent, 101 = powered, -1 unknown
    public float voltage;
    public float channelUtil;
    public float airUtilTx;
    public int packetsSeen;

    // ---- link statistics (accumulated from received packets)
    public long firstSeen;          // millis we first saw a packet from it (0 = only from node DB)
    public int directPackets;       // packets that arrived with 0 hops
    public long rssiSum; public int rssiCount;
    public double snrSum; public int snrCount;

    // ---- identity / flags
    public String role = "";        // CLIENT, ROUTER, …
    public boolean isLicensed, isUnmessagable, isFavorite, isIgnored;

    // ---- position detail + track
    public int groundSpeed = -1;    // m/s
    public int groundTrack = -1;    // degrees*1e-5 in proto → stored as degrees
    public int satsInView = -1;
    public int precisionBits;
    public int locationSource;      // 1.29.4: Position.LocSource – 0 unset, 1 typed in (manual), 2 radio's own GPS, 3 phone/computer
    public String town = "";        // 1.29.4: nearest town for the position (OpenStreetMap), filled in the background
    public long positionTime;
    public final java.util.ArrayDeque<double[]> track = new java.util.ArrayDeque<>();   // {millis, lat, lon}

    // ---- telemetry
    public int uptimeSeconds = -1;
    public long deviceMetricsTime, envTime, powerTime;
    public Float temperature, humidity, pressure, lux, windSpeed;
    public Integer iaq, windDirection;
    public final float[] powerVolts = new float[8];
    public final float[] powerAmps = new float[8];
    public int powerChannels;       // highest channel index reported + 1

    // ---- neighbours (from NEIGHBORINFO_APP), node num -> snr
    public final java.util.LinkedHashMap<Integer, Float> neighbors = new java.util.LinkedHashMap<>();
    public long neighborsTime;

    public boolean watched;         // user watch-list: alert on heard / lost / low battery
    public int sessionsSeen;        // how many app sessions this node was heard in
    public boolean fromDb;          // loaded from nodes.json, not (yet) confirmed by this radio
    public int lastRelayNode = -1;  // low byte of the node that relayed the last packet (-1 unknown)
    public int rangeTestLast = -1, rangeTestReceived, rangeTestExpected;
    public Integer paxWifi, paxBle;
    public long airtimeMs;          // total channel time this node's packets occupied (as heard here)
    public long airBytes;

    public double avgRssi() { return rssiCount == 0 ? 0 : (double) rssiSum / rssiCount; }
    public double avgSnr() { return snrCount == 0 ? 0 : snrSum / snrCount; }
    public int directPercent() { return packetsSeen == 0 ? -1 : (int) Math.round(100.0 * directPackets / packetsSeen); }

    public void addTrackPoint(long millis, double lat, double lon) {
        double[] last = track.peekLast();
        if (last != null && Math.abs(last[1] - lat) < 1e-6 && Math.abs(last[2] - lon) < 1e-6) return;
        track.addLast(new double[]{millis, lat, lon});
        while (track.size() > 1000) track.removeFirst();
    }

    /** 1.29.4: above 20,000 ft – an aircraft or a high-altitude balloon. */
    public boolean isAirborne() { return hasPosition && altitude > 6096; }

    /** 1.29.4: positions more than 150 m apart within the last hour. */
    public boolean isMobile(long now) {
        double[] last = track.peekLast();
        if (last == null || now - (long) last[0] > 3600_000L) return false;
        for (double[] p : track) {
            if (now - (long) p[0] > 3600_000L) continue;
            if (meshconsole.analysis.Metar.distanceKm(p[1], p[2], last[1], last[2]) > 0.15) return true;
        }
        return false;
    }

    /** 1.29.4: what the map marker shows – air, mobile, router, base, tracker, sensor, mute, client. */
    public String kind(long now) {
        if (isAirborne()) return "air";
        if (isMobile(now)) return "mobile";
        String r = role == null ? "" : role;
        if (r.startsWith("ROUTER") || r.equals("REPEATER")) return "router";
        if (r.equals("CLIENT_BASE")) return "base";
        if (r.equals("TRACKER") || r.equals("TAK_TRACKER") || r.equals("LOST_AND_FOUND")) return "tracker";
        if (r.equals("SENSOR")) return "sensor";
        if (r.equals("CLIENT_MUTE") || r.equals("CLIENT_HIDDEN")) return "mute";
        return "client";
    }

    /** 1.29.4: where the position came from, in words. */
    public String locationSourceName() {
        return switch (locationSource) { case 1 -> "typed in"; case 2 -> "radio GPS"; case 3 -> "phone/computer"; default -> "unknown"; };
    }

    public NodeEntry(int num) {
        this.num = num;
    }

    public String idString() {
        return String.format("!%08x", num);
    }

    public String displayName() {
        if (!longName.isEmpty()) return longName;
        if (!shortName.isEmpty()) return shortName;
        return idString();
    }

    public String shortLabel() {
        return shortName.isEmpty() ? idString().substring(5) : shortName;
    }

    public long lastHeardMillis() {
        long radio = lastHeard * 1000L;
        return Math.max(radio, lastLocalRx);
    }
}
