package meshconsole.mesh;

import org.meshtastic.proto.MeshProtos.FromRadio;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Packet capture: every FromRadio frame written as [8-byte millis][2-byte len][bytes] to a .mcap file,
 * and replay of such a file into a MeshState at a chosen speed.
 */
public class Capture implements AutoCloseable {
    private final DataOutputStream out;
    private final Path path;
    private long frames;

    public Capture(Path path) throws IOException {
        this.path = path;
        out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
        out.writeBytes("MESHCAP1");
    }

    public Path path() { return path; }
    public long frames() { return frames; }

    public synchronized void write(FromRadio fr) {
        try {
            byte[] b = fr.toByteArray();
            out.writeLong(System.currentTimeMillis());
            out.writeShort(b.length);
            out.write(b);
            frames++;
            if (frames % 50 == 0) out.flush();
        } catch (IOException ignored) { }
    }

    @Override public void close() { try { out.close(); } catch (IOException ignored) { } }

    /** Replays a capture. speed 0 = as fast as possible; 1 = real time; 10 = 10×. Returns frame count. */
    public static long replay(Path path, double speed, Consumer<FromRadio> sink, java.util.function.BooleanSupplier cancelled) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            if (!new String(magic).equals("MESHCAP1")) throw new IOException("Not a Mesh Console capture file");
            long n = 0, prevT = 0;
            while (!cancelled.getAsBoolean()) {
                long t;
                try { t = in.readLong(); } catch (EOFException e) { break; }
                int len = in.readUnsignedShort();
                byte[] b = new byte[len];
                in.readFully(b);
                if (speed > 0 && prevT != 0) {
                    long wait = (long) ((t - prevT) / speed);
                    if (wait > 0) try { Thread.sleep(Math.min(wait, 5000)); } catch (InterruptedException e) { break; }
                }
                prevT = t;
                sink.accept(FromRadio.parseFrom(b));
                n++;
            }
            return n;
        }
    }
}
