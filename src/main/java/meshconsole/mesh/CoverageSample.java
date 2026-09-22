package meshconsole.mesh;

/** Where our own node was when it heard a packet, and how strong it was — for drive-test maps. */
public record CoverageSample(long time, double lat, double lon, int from, int rssi, float snr) { }
