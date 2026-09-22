package meshconsole.mesh;

import com.fazecast.jSerialComm.SerialPort;
import com.google.protobuf.InvalidProtocolBufferException;
import org.meshtastic.proto.MeshProtos.FromRadio;
import org.meshtastic.proto.MeshProtos.Heartbeat;
import org.meshtastic.proto.MeshProtos.ToRadio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Low-level Meshtastic "stream" (serial/USB) transport.
 *
 * Wire format, both directions:
 *   0x94 0xC3 <len MSB> <len LSB> <protobuf bytes>   (len <= 512)
 * Device -> host payloads are FromRadio; host -> device are ToRadio.
 * Anything that is not inside a frame (firmware debug text before the client
 * attaches) is passed up as plain text lines.
 */
public class MeshSerial implements AutoCloseable {

    public interface Listener {
        void onFromRadio(FromRadio fr);
        void onDebugText(String line);
        void onDisconnected(String reason);
    }

    private static final int START1 = 0x94;
    private static final int START2 = 0xC3;
    private static final int MAX_LEN = 512;

    private final SerialPort port;
    private final Listener listener;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean running;
    /** When set, every frame in both directions is logged as protobuf text. */
    public volatile boolean traceFrames;
    private volatile boolean gotAnyFrame;
    private int configRetries;
    private Runnable onClose;
    private Thread reader;
    private ScheduledExecutorService heartbeat;

    public static List<SerialPort> listPorts() {
        return Arrays.asList(SerialPort.getCommPorts());
    }

    public MeshSerial(SerialPort port, Listener listener) throws IOException {
        this.port = port;
        this.listener = listener;
        port.setBaudRate(115200);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        // Writes must never block forever: a wedged device would otherwise hang every caller.
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 250, 2000);
        if (!port.openPort()) {
            throw new IOException("Could not open " + port.getSystemPortName()
                    + " (in use, or missing permission — on Linux add yourself to the dialout group)");
        }
        // nRF52 boards (RAK4631) only talk once the host asserts DTR (USB-CDC "terminal attached").
        // ESP32 boards auto-reset only when DTR and RTS differ, so asserting BOTH is safe for all.
        port.setDTR();
        port.setRTS();
        in = port.getInputStream();
        out = port.getOutputStream();
        port.addDataListener(new com.fazecast.jSerialComm.SerialPortDataListener() {
            @Override public int getListeningEvents() { return SerialPort.LISTENING_EVENT_PORT_DISCONNECTED; }
            @Override public void serialEvent(com.fazecast.jSerialComm.SerialPortEvent e) { fail("device unplugged"); }
        });
    }

    /** Stream-based constructor (used for tests / non-serial transports such as TCP). */
    public MeshSerial(InputStream in, OutputStream out, Listener listener) {
        this(in, out, listener, null);
    }

    public MeshSerial(InputStream in, OutputStream out, Listener listener, Runnable onClose) {
        this.port = null;
        this.listener = listener;
        this.in = in;
        this.out = out;
        this.onClose = onClose;
    }

    public String portName() {
        return port == null ? "stream" : port.getSystemPortName();
    }

    /** Starts the reader thread, requests the config/node-db dump and begins heartbeats. */
    public void start(int configNonce) {
        running = true;
        reader = new Thread(this::readLoop, "mesh-serial-reader");
        reader.setDaemon(true);
        reader.start();

        // The firmware needs a moment after the port opens; send a few empty
        // "wake" bytes the way the reference clients do, then ask for config.
        try {
            synchronized (writeLock) {
                byte[] wake = new byte[32];
                Arrays.fill(wake, (byte) START2);
                out.write(wake);
                out.flush();
            }
            Thread.sleep(100);
            send(ToRadio.newBuilder().setWantConfigId(configNonce).build());
        } catch (IOException | InterruptedException e) {
            fail("write failed: " + e.getMessage());
            return;
        }

        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mesh-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                send(ToRadio.newBuilder().setHeartbeat(Heartbeat.newBuilder().build()).build());
            } catch (IOException e) {
                fail("heartbeat failed: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
        // If the radio rebooted when the port opened (ESP32 boards do this), the first request was
        // lost: keep asking until something arrives.
        heartbeat.scheduleAtFixedRate(() -> {
            if (gotAnyFrame || configRetries >= 10) return;
            configRetries++;
            try {
                listener.onDebugText("[serial] no reply yet, re-requesting config (" + configRetries + ")");
                send(ToRadio.newBuilder().setWantConfigId(configNonce).build());
            } catch (IOException e) {
                fail("write failed: " + e.getMessage());
            }
        }, 3, 3, TimeUnit.SECONDS);
    }

    public void send(ToRadio msg) throws IOException {
        byte[] payload = msg.toByteArray();
        if (traceFrames) listener.onDebugText("[tx " + payload.length + " B] " + shortText(msg));
        if (payload.length > MAX_LEN) {
            throw new IOException("ToRadio too large: " + payload.length);
        }
        byte[] frame = new byte[4 + payload.length];
        frame[0] = (byte) START1;
        frame[1] = (byte) START2;
        frame[2] = (byte) ((payload.length >> 8) & 0xFF);
        frame[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        synchronized (writeLock) {
            if (port != null) {
                int n = port.writeBytes(frame, frame.length);
                if (n != frame.length) throw new IOException("serial write failed or timed out (" + n + "/" + frame.length + " bytes) – radio not reading USB?");
            } else {
                out.write(frame);
                out.flush();
            }
        }
    }

    private void readLoop() {
        byte[] buf = new byte[4096];
        byte[] payload = new byte[MAX_LEN];
        int consecutiveErrors = 0;
        int state = 0;          // 0=want START1, 1=want START2, 2=len MSB, 3=len LSB, 4=payload
        int len = 0, got = 0;
        StringBuilder text = new StringBuilder();

        while (running) {
            int n;
            try {
                if (port != null) {
                    // Use the port API: it returns 0 on timeout. The InputStream wrapper throws
                    // SerialPortTimeoutException instead, which must not be treated as a failure.
                    n = port.readBytes(buf, buf.length);
                    if (n < 0) {
                        // On some Windows setups jSerialComm returns -1 for "no data yet". Never treat that as
                        // a disconnect: real unplugs are reported by the port-disconnected event / isOpen().
                        if (!port.isOpen()) { if (running) fail("port closed (device unplugged or reset?)"); return; }
                        if (++consecutiveErrors == 1) listener.onDebugText("[serial] readBytes returned -1 while port open (harmless, ignoring)");
                        try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
                        continue;
                    }
                    consecutiveErrors = 0;
                } else {
                    n = in.read(buf);
                    if (n < 0) { if (running) fail("stream closed"); return; }
                }
            } catch (com.fazecast.jSerialComm.SerialPortTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running) fail("read failed: " + e.getMessage());
                return;
            }
            if (n == 0) continue;
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xFF;
                switch (state) {
                    case 0:
                        if (b == START1) {
                            state = 1;
                        } else {
                            // plain-text debug output from the firmware
                            if (b == '\n') {
                                if (text.length() > 0) listener.onDebugText(text.toString());
                                text.setLength(0);
                            } else if (b != '\r') {
                                text.append((char) b);
                                if (text.length() > 512) { listener.onDebugText(text.toString()); text.setLength(0); }
                            }
                        }
                        break;
                    case 1:
                        if (b == START2) state = 2;
                        else if (b == START1) state = 1;
                        else { state = 0; if (b != '\r' && b != '\n') text.append((char) b); }
                        break;
                    case 2:
                        len = b << 8;
                        state = 3;
                        break;
                    case 3:
                        len |= b;
                        if (len > MAX_LEN) { state = 0; }
                        else if (len == 0) { state = 0; }
                        else { got = 0; state = 4; }
                        break;
                    case 4:
                        payload[got++] = (byte) b;
                        if (got == len) {
                            state = 0;
                            try {
                                FromRadio fr = FromRadio.parseFrom(Arrays.copyOf(payload, len));
                                gotAnyFrame = true;
                                if (traceFrames) listener.onDebugText("[rx " + len + " B] " + shortText(fr));
                                listener.onFromRadio(fr);
                            } catch (InvalidProtocolBufferException e) {
                                listener.onDebugText("[bad frame: " + e.getMessage() + "]");
                            } catch (RuntimeException e) {
                                listener.onDebugText("[handler error: " + e + "]");
                            }
                        }
                        break;
                    default:
                        state = 0;
                }
            }
        }
    }

    private void fail(String reason) {
        boolean was = running;
        running = false;
        if (was) listener.onDisconnected(reason);
    }

    @Override
    public void close() {
        running = false;
        if (heartbeat != null) heartbeat.shutdownNow();
        try {
            // Tell the firmware the client is leaving so it resumes normal serial behaviour.
            send(ToRadio.newBuilder().setDisconnect(true).build());
        } catch (IOException ignored) {
        }
        try { in.close(); } catch (IOException ignored) { }
        if (port != null) port.closePort();
        if (onClose != null) onClose.run();
    }

    private static String shortText(com.google.protobuf.MessageOrBuilder m) {
        String t = com.google.protobuf.TextFormat.printer().emittingSingleLine(true).printToString(m);
        return t.length() > 600 ? t.substring(0, 600) + "…" : t;
    }

    static String bytesToText(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
