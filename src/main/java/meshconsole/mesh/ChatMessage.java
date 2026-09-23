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
    public int replyId;          // packet id this message replies to (0 = none)
    public boolean emoji;        // true = this is a reaction (text is the emoji) to replyId
    public long ackTime;         // millis when DELIVERED/FAILED arrived (0 = pending)
    public boolean fromStoreForward;
    public int airtimeMs;        // estimated time on air for this packet

    public boolean isBroadcast() {
        return to == BROADCAST;
    }
}
