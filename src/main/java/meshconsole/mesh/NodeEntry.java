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
