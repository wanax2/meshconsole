package meshconsole.mesh;

public class ChatMessage {
    public enum Status { QUEUED, TRANSMITTED, DELIVERED, FAILED, RECEIVED, HISTORY }

    public static final int BROADCAST = 0xFFFFFFFF;

    public long time;            // epoch millis
    public int from;
    public int to;
    public int channel;
    public String text;
    public boolean outgoing;
    public int packetId;         // our id for outgoing, radio's id for incoming
    public Status status;
    public String statusDetail = "";
    public int rssi;
    public float snr;
    public int hops = -1;
    public int attempt = 1;      // 1 = first send, 2+ = automatic retries

    public boolean isBroadcast() {
        return to == BROADCAST;
    }
}
