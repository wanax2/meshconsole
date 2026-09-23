package meshconsole.mqtt;

import com.google.protobuf.ByteString;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import org.meshtastic.proto.MeshProtos.MqttClientProxyMessage;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Meshtastic "MQTT client proxy": the radio has no network of its own, so it hands us
 * MqttClientProxyMessages to publish, and we push everything received on its topics back
 * into it. Requires MQTT module enabled + proxy_to_client_enabled on the radio.
 */
public class MqttProxy implements AutoCloseable {
    private final MeshClient client;
    private final MeshState state;
    private final Consumer<String> status;
    private MqttClient mqtt;
    private long published, received;
    private void trafficLog(String dir, String topic, int bytes) {
        try { java.nio.file.Files.writeString(meshconsole.DataDir.file("mqtt_log.csv"), System.currentTimeMillis() + "," + dir + "," + topic.replace(",", " ") + "," + bytes + "\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (java.io.IOException ignored) { }
    }
    private final MeshState.Listener radioListener = new MeshState.Listener() {
        @Override public void onMqttProxyMessage(MqttClientProxyMessage m) { fromRadio(m); }
    };

    public MqttProxy(MeshClient client, Consumer<String> status) {
        this.client = client;
        this.state = client.state();
        this.status = status;
    }

    public boolean isRunning() { return mqtt != null; }

    public synchronized void start() throws IOException {
        ModuleConfig mc = state.moduleConfig(ModuleConfig.PayloadVariantCase.MQTT);
        if (mc == null) throw new IOException("MQTT module config not loaded yet");
        ModuleConfig.MQTTConfig cfg = mc.getMqtt();
        if (!cfg.getEnabled()) throw new IOException("MQTT module is disabled on the radio (enable it in Settings)");
        if (!cfg.getProxyToClientEnabled()) throw new IOException("proxy_to_client_enabled is off on the radio (enable it in Settings)");

        String address = cfg.getAddress().isEmpty() ? "mqtt.meshtastic.org" : cfg.getAddress();
        String host = address;
        int port = cfg.getTlsEnabled() ? 8883 : 1883;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && colon < address.length() - 1) {
            host = address.substring(0, colon);
            try { port = Integer.parseInt(address.substring(colon + 1)); } catch (NumberFormatException ignored) { }
        }
        String user = cfg.getUsername().isEmpty() ? "meshdev" : cfg.getUsername();
        String pass = cfg.getPassword().isEmpty() ? "large4cats" : cfg.getPassword();
        String root = cfg.getRoot().isEmpty() ? "msh" : cfg.getRoot();
        String clientId = "meshconsole-" + String.format("%08x", state.myNodeNum());

        status.accept("Connecting to " + host + ":" + port + (cfg.getTlsEnabled() ? " (TLS)" : "") + "…");
        mqtt = new MqttClient(host, port, cfg.getTlsEnabled(), user, pass, clientId, new MqttClient.Listener() {
            @Override public void onMessage(String topic, byte[] payload, boolean retained) { fromBroker(topic, payload, retained); }
            @Override public void onLog(String line) { state.emitLog("[mqtt] " + line); }
            @Override public void onDisconnected(String reason) {
                state.emitLog("[mqtt] disconnected: " + reason);
                status.accept("Disconnected: " + reason);
                synchronized (MqttProxy.this) { mqtt = null; }
                state.removeListener(radioListener);
            }
        });
        // The firmware subscribes per downlink channel (root/2/e/<name>/+) plus PKI; a wildcard
        // covers all of them and the radio drops anything it can't decrypt.
        if (cfg.getJsonEnabled()) mqtt.subscribe(root + "/2/e/#", root + "/2/json/#");
        else mqtt.subscribe(root + "/2/e/#");
        state.addListener(radioListener);
        published = received = 0;
        status.accept("Connected to " + host + ":" + port + "  ·  subscribed " + root + "/2/e/#");
        state.emitLog("[mqtt] proxy running as " + clientId);
    }

    private void fromRadio(MqttClientProxyMessage m) {
        MqttClient c;
        synchronized (this) { c = mqtt; }
        if (c == null) return;
        try {
            byte[] payload = m.getPayloadVariantCase() == MqttClientProxyMessage.PayloadVariantCase.TEXT
                    ? m.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : m.getData().toByteArray();
            c.publish(m.getTopic(), payload, m.getRetained(), 0);
            published++;
            trafficLog("up", m.getTopic(), payload.length);
            if (state.verbose()) state.emitLog("[mqtt] ↑ " + m.getTopic() + " (" + payload.length + " B" + (m.getRetained() ? ", retained" : "") + ")");
            status.accept(String.format("Running  ·  published %d  received %d  ·  last ↑ %s", published, received, m.getTopic()));
        } catch (IOException e) {
            state.emitLog("[mqtt] publish failed: " + e.getMessage());
        }
    }

    private void fromBroker(String topic, byte[] payload, boolean retained) {
        try {
            client.sendMqttProxy(MqttClientProxyMessage.newBuilder()
                    .setTopic(topic).setData(ByteString.copyFrom(payload)).setRetained(retained).build());
            received++;
            trafficLog("down", topic, payload.length);
            if (state.verbose()) state.emitLog("[mqtt] ↓ " + topic + " (" + payload.length + " B)");
            status.accept(String.format("Running  ·  published %d  received %d  ·  last ↓ %s", published, received, topic));
        } catch (IOException e) {
            state.emitLog("[mqtt] could not forward to radio: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        state.removeListener(radioListener);
        if (mqtt != null) {
            mqtt.close();
            mqtt = null;
            status.accept("Stopped");
            state.emitLog("[mqtt] proxy stopped");
        }
    }
}
