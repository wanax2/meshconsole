package meshconsole.ui;

/** Public access to Fmt for the mesh package. */
public final class FmtBridge {
    private FmtBridge() { }
    public static String ago(long millis) { return Fmt.ago(millis); }
    public static String time(long millis) { return Fmt.time(millis); }
}
