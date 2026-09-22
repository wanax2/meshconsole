package meshconsole.mesh;

public class WaypointEntry {
    public int id;
    public double lat, lon;
    public String name = "", description = "";
    public long expire;         // epoch seconds, 0 = never
    public int lockedTo;
    public int from;
    public long received;
}
