package meshconsole.mqtt;

import meshconsole.mesh.MeshState;
import meshconsole.tools.MeshCrypto;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.MQTTProtos.ServiceEnvelope;
import org.meshtastic.proto.MeshProtos.Data;
import org.meshtastic.proto.MeshProtos.MeshPacket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/**
 * Read-only subscriber to a public Meshtastic MQTT broker: shows what the gateways heard, in particular
 * packets from OUR node(s) — an independent check that the mesh hears us, regardless of our own receiver.
 */
public class MqttWitness implements AutoCloseable {
    public record Seen(long time, String gateway, String channel, int from, int to, String port, int hops, int rssi, float snr, boolean mine, String text) { }

    private final MeshState state;
    private final Consumer<String> status;
    private final Set<Integer> myNodes = new HashSet<>();
    private final Deque<Seen> recent = new ArrayDeque<>();
    private final Map<String, long[]> gateways = new TreeMap<>();      // gateway → {packets, lastTime}
    private final Map<Integer, long[]> nodesViaMqtt = new HashMap<>(); // node → {packets, lastTime}
    private final List<Consumer<Seen>> listeners = new ArrayList<>();
    private MqttClient mqtt;
    private long total, mine, lastMine;

    public MqttWitness(MeshState state, Consumer<String> status) { this.state = state; this.status = status; }

    public void addMyNode(int num) { if (num != 0) myNodes.add(num); }
    public Set<Integer> myNodes() { return myNodes; }
    public void addListener(Consumer<Seen> l) { listeners.add(l); }
    public boolean isRunning() { return mqtt != null; }
    public synchronized List<Seen> recent() { return new ArrayList<>(recent); }
    public synchronized Map<String, long[]> gateways() { return new TreeMap<>(gateways); }
    public synchronized Map<Integer, long[]> nodesViaMqtt() { return new HashMap<>(nodesViaMqtt); }
    public long total() { return total; }
    public long mine() { return mine; }
    public long lastMine() { return lastMine; }

    public synchronized void start(String host, int port, boolean tls, String user, String pass, String topic) throws IOException {
        close();
        status.accept("Connecting to " + host + "…");
        mqtt = new MqttClient(host, port, tls, user, pass, "meshconsole-witness-" + Long.toHexString(System.nanoTime() & 0xFFFFFF), new MqttClient.Listener() {
            @Override public void onMessage(String t, byte[] payload, boolean retained) { onEnvelope(t, payload); }
            @Override public void onLog(String line) { state.emitLog("[witness] " + line); }
            @Override public void onDisconnected(String reason) { status.accept("Disconnected: " + reason); synchronized (MqttWitness.this) { mqtt = null; } }
        });
        mqtt.subscribe(topic);
        status.accept("Listening on " + topic + " at " + host);
        state.emitLog("[witness] subscribed to " + topic);
    }

    private void onEnvelope(String topic, byte[] payload) {
        ServiceEnvelope env;
        try { env = ServiceEnvelope.parseFrom(payload); } catch (Exception e) { return; }
        if (!env.hasPacket()) return;
        MeshPacket p = env.getPacket();
        String port = "?"; String text = "";
        Data d = null;
        if (p.hasDecoded()) d = p.getDecoded();
        else if (p.hasEncrypted()) d = tryDecrypt(p, env.getChannelId());
        if (d != null) {
            port = d.getPortnum().name().replace("_APP", "");
            if (d.getPortnum() == org.meshtastic.proto.Portnums.PortNum.TEXT_MESSAGE_APP) text = d.getPayload().toStringUtf8();
        }
        int hops = p.getHopStart() > 0 ? p.getHopStart() - p.getHopLimit() : -1;
        boolean isMine = myNodes.contains(p.getFrom());
        Seen s = new Seen(System.currentTimeMillis(), env.getGatewayId(), env.getChannelId(), p.getFrom(), p.getTo(), port, hops, p.getRxRssi(), p.getRxSnr(), isMine, text);
        synchronized (this) {
            total++;
            recent.addLast(s); while (recent.size() > 2000) recent.removeFirst();
            long[] g = gateways.computeIfAbsent(env.getGatewayId(), k -> new long[2]); g[0]++; g[1] = s.time();
            long[] n = nodesViaMqtt.computeIfAbsent(p.getFrom(), k -> new long[2]); n[0]++; n[1] = s.time();
            if (isMine) { mine++; lastMine = s.time(); }
        }
        if (isMine) {
            String line = String.format("Gateway %s heard my node %s: %s%s (hops %s, rssi %d, snr %.1f)", env.getGatewayId(), state.nodeName(p.getFrom()), port, text.isEmpty() ? "" : " \"" + text + "\"", hops < 0 ? "?" : String.valueOf(hops), p.getRxRssi(), p.getRxSnr());
            state.emitLog("[witness] " + line);
            state.emitAlert("WITNESS", line);
            try { Files.writeString(meshconsole.DataDir.file("mqtt_witness.csv"), s.time() + "," + env.getGatewayId() + "," + env.getChannelId() + "," + String.format("!%08x", p.getFrom()) + "," + port + "," + hops + "," + p.getRxRssi() + "," + p.getRxSnr() + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (IOException ignored) { }
        }
        for (Consumer<Seen> l : listeners) l.accept(s);
    }

    /** Tries the default key and every PSK the connected radio has; returns decoded Data or null. */
    private Data tryDecrypt(MeshPacket p, String channelId) {
        List<byte[]> keys = new ArrayList<>();
        keys.add(MeshCrypto.DEFAULT_KEY);
        for (Channel c : state.allChannels()) if (!c.getSettings().getPsk().isEmpty()) keys.add(MeshCrypto.expandKey(c.getSettings().getPsk().toByteArray()));
        for (byte[] k : keys) {
            if (k.length != 16 && k.length != 32) continue;
            try {
                byte[] plain = MeshCrypto.decrypt(k, p.getId(), p.getFrom(), p.getEncrypted().toByteArray());
                Data d = Data.parseFrom(plain);
                if (d.getPortnumValue() > 0 && d.getPortnumValue() < 512) return d;
            } catch (Exception ignored) { }
        }
        return null;
    }

    @Override public synchronized void close() { if (mqtt != null) { mqtt.close(); mqtt = null; status.accept("Stopped"); } }
}
