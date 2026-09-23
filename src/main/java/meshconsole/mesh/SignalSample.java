package meshconsole.mesh;

/** One received packet's link measurement. */
public record SignalSample(long time, int from, int rssi, float snr, int hops, int slot, int rxNode) {
    public SignalSample(long time, int from, int rssi, float snr, int hops) { this(time, from, rssi, snr, hops, 0, 0); }
    public SignalSample(long time, int from, int rssi, float snr, int hops, int slot) { this(time, from, rssi, snr, hops, slot, 0); }
}
