package meshconsole.mesh;

/** Own-node channel utilisation / air-time as reported by device telemetry. */
public record UtilSample(long time, float channelUtil, float airUtilTx, int txQueueFree) { }
