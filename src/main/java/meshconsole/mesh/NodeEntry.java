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
