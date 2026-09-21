package meshconsole.mqtt;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocketFactory;

/**
 * Tiny MQTT 3.1.1 client: CONNECT / SUBSCRIBE / PUBLISH (QoS 0 and 1) / PINGREQ.
 * Enough to act as the Meshtastic "MQTT client proxy" without any third-party library.
 */
public class MqttClient implements AutoCloseable {

    public interface Listener {
        void onMessage(String topic, byte[] payload, boolean retained);
        void onLog(String line);
        void onDisconnected(String reason);
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Listener listener;
    private final AtomicInteger packetId = new AtomicInteger(1);
    private volatile boolean running = true;
    private Thread reader, pinger;

    public MqttClient(String host, int port, boolean tls, String username, String password, String clientId, Listener listener) throws IOException {
        this.listener = listener;
        if (tls) {
            socket = SSLSocketFactory.getDefault().createSocket();
        } else {
            socket = new Socket();
        }
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(0);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        // CONNECT
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, "MQTT");
        body.write(4);                                   // protocol level 3.1.1
        int flags = 0x02;                                // clean session
        if (username != null && !username.isEmpty()) flags |= 0x80;
        if (password != null && !password.isEmpty()) flags |= 0x40;
        body.write(flags);
        body.write(0); body.write(60);                   // keep-alive 60 s
        writeString(body, clientId);
        if ((flags & 0x80) != 0) writeString(body, username);
        if ((flags & 0x40) != 0) writeString(body, password);
        sendPacket(0x10, body.toByteArray());

        // CONNACK
        int type = in.readUnsignedByte();
        int len = readRemainingLength();
        byte[] ack = in.readNBytes(len);
        if ((type >> 4) != 2 || ack.length < 2) throw new IOException("Bad CONNACK");
        if (ack[1] != 0) {
            String[] reasons = {"ok", "unacceptable protocol version", "identifier rejected", "server unavailable", "bad username or password", "not authorized"};
            throw new IOException("Broker refused connection: " + (ack[1] < reasons.length ? reasons[ack[1]] : "code " + ack[1]));
        }

        reader = new Thread(this::readLoop, "mqtt-reader");
        reader.setDaemon(true);
        reader.start();
        pinger = new Thread(() -> {
            while (running) {
                try { Thread.sleep(30_000); sendPacket(0xC0, new byte[0]); }
                catch (InterruptedException e) { return; }
                catch (IOException e) { fail("ping failed: " + e.getMessage()); return; }
            }
        }, "mqtt-ping");
        pinger.setDaemon(true);
        pinger.start();
    }

    public void subscribe(String... topics) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int id = packetId.getAndIncrement() & 0xFFFF;
        if (id == 0) id = packetId.getAndIncrement() & 0xFFFF;
        body.write(id >> 8); body.write(id & 0xFF);
        for (String t : topics) { writeString(body, t); body.write(1); }   // QoS 1
        sendPacket(0x82, body.toByteArray());
    }

    public void publish(String topic, byte[] payload, boolean retain, int qos) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, topic);
        if (qos > 0) {
            int id = packetId.getAndIncrement() & 0xFFFF;
            if (id == 0) id = packetId.getAndIncrement() & 0xFFFF;
            body.write(id >> 8); body.write(id & 0xFF);
        }
        body.write(payload);
        sendPacket(0x30 | (qos << 1) | (retain ? 1 : 0), body.toByteArray());
    }

    private void readLoop() {
        try {
            while (running) {
                int first = in.readUnsignedByte();
                int len = readRemainingLength();
                byte[] body = in.readNBytes(len);
                int type = first >> 4;
                switch (type) {
                    case 3 -> {                         // PUBLISH
                        int qos = (first >> 1) & 3;
                        boolean retained = (first & 1) != 0;
                        int tl = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                        String topic = new String(body, 2, tl, StandardCharsets.UTF_8);
                        int pos = 2 + tl;
                        if (qos > 0) {
                            int id = ((body[pos] & 0xFF) << 8) | (body[pos + 1] & 0xFF);
                            pos += 2;
                            sendPacket(0x40, new byte[]{(byte) (id >> 8), (byte) id});   // PUBACK
                        }
                        byte[] payload = new byte[len - pos];
                        System.arraycopy(body, pos, payload, 0, payload.length);
                        listener.onMessage(topic, payload, retained);
                    }
                    case 9 -> {                         // SUBACK
                        List<String> codes = new ArrayList<>();
                        for (int i = 2; i < body.length; i++) codes.add((body[i] & 0xFF) == 0x80 ? "FAIL" : "qos" + body[i]);
                        listener.onLog("Subscribed: " + codes);
                    }
                    case 4, 13 -> { }                   // PUBACK, PINGRESP
                    default -> listener.onLog("MQTT packet type " + type + " ignored");
                }
            }
        } catch (IOException e) {
            if (running) fail(e.getMessage() == null ? "connection closed" : e.getMessage());
        }
    }

    private int readRemainingLength() throws IOException {
        int mult = 1, value = 0, b;
        do {
            b = in.readUnsignedByte();
            value += (b & 0x7F) * mult;
            mult *= 128;
            if (mult > 128 * 128 * 128) throw new IOException("Bad remaining length");
        } while ((b & 0x80) != 0);
        return value;
    }

    private synchronized void sendPacket(int firstByte, byte[] body) throws IOException {
        out.write(firstByte);
        int len = body.length;
        do {
            int b = len % 128;
            len /= 128;
            if (len > 0) b |= 0x80;
            out.write(b);
        } while (len > 0);
        out.write(body);
        out.flush();
    }

    private static void writeString(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(b.length >> 8); o.write(b.length & 0xFF);
        o.write(b, 0, b.length);
    }

    private void fail(String reason) {
        if (!running) return;
        running = false;
        listener.onDisconnected(reason);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        running = false;
        try { sendPacket(0xE0, new byte[0]); } catch (IOException ignored) { }
        if (pinger != null) pinger.interrupt();
        try { socket.close(); } catch (IOException ignored) { }
    }
}
