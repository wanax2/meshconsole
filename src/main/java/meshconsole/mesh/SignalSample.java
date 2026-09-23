package meshconsole.mesh;

/** One received packet's link measurement. */
public record SignalSample(long time, int from, int rssi, float snr, int hops, int slot) {
    public SignalSample(long time, int from, int rssi, float snr, int hops) { this(time, from, rssi, snr, hops, 0); }
}
