package meshconsole.mesh;

import com.fazecast.jSerialComm.SerialPort;
import com.google.protobuf.ByteString;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.MeshProtos.*;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;
import org.meshtastic.proto.Portnums.PortNum;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

/** High-level operations on top of MeshSerial + MeshState. */
public class MeshClient implements MeshSerial.Listener {

    public interface ConnectionListener {
        void onConnectionChanged(boolean connected, String detail);
    }

    private final MeshState state;
    private final SecureRandom rnd = new SecureRandom();
    private MeshSerial serial;
    private ConnectionListener connListener = (c, d) -> { };
    /** All radio writes happen here so a slow or wedged device can never freeze the UI thread. */
    private final java.util.concurrent.ExecutorService sender = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mesh-sender"); t.setDaemon(true); return t;
    });

    // ---- automatic retry of failed / undelivered direct messages -----------
    private volatile boolean autoRetry = true;
    private volatile int maxAttempts = 6;          // total attempts incl. the first
    private volatile int retryDelaySec = 45;       // wait before re-sending (lets the mesh settle)
    private final java.util.concurrent.ScheduledExecutorService retryTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mesh-retry"); t.setDaemon(true); return t;
    });

    public MeshClient(MeshState state) {
        this.state = state;
        state.addListener(new MeshState.Listener() {
            @Override public void onMessage(ChatMessage m, boolean isNew) { if (!isNew) considerRetry(m); }
        });
    }

    public void setAutoRetry(boolean on, int maxAttempts, int delaySec) {
        this.autoRetry = on; this.maxAttempts = Math.max(1, maxAttempts); this.retryDelaySec = Math.max(5, delaySec);
    }
    public boolean autoRetry() { return autoRetry; }
    public int maxAttempts() { return maxAttempts; }
    public int retryDelaySec() { return retryDelaySec; }

    /**
     * The firmware gives up on a DM after its own 3 retransmissions (MAX_RETRANSMIT). When that
     * happens, or a DM was transmitted but never acknowledged, send it again after a pause.
     */
    private void considerRetry(ChatMessage m) {
        if (!autoRetry || !m.outgoing || m.isBroadcast() || m.attempt >= maxAttempts) return;
        boolean failed = m.status == ChatMessage.Status.FAILED
                && (m.statusDetail.startsWith("MAX_RETRANSMIT") || m.statusDetail.startsWith("NO_ROUTE") || m.statusDetail.startsWith("TIMEOUT"));
        boolean sentNoAck = m.status == ChatMessage.Status.TRANSMITTED;
        if (!failed && !sentNoAck) return;
        int delay = failed ? retryDelaySec : retryDelaySec * 2;   // give an un-NAK'd packet longer
        final int attemptNow = m.attempt;
        retryTimer.schedule(() -> {
            // still not delivered, and this is still the latest attempt for that text/destination?
            if (m.status == ChatMessage.Status.DELIVERED || m.attempt != attemptNow) return;
            for (ChatMessage x : state.messages())
                if (x != m && x.outgoing && x.to == m.to && x.text.equals(m.text) && x.time > m.time) return;
            if (!isConnected()) return;
            try {
                ChatMessage r = sendText(m.text, m.to, m.channel);
                r.attempt = m.attempt + 1;
                if (r.status == ChatMessage.Status.QUEUED) r.statusDetail = "queued (retry " + r.attempt + "/" + maxAttempts + ")";
                state.updateMessage(r);
                if (m.status != ChatMessage.Status.FAILED) { m.status = ChatMessage.Status.FAILED; m.statusDetail = "no delivery ack – retried"; state.updateMessage(m); }
                state.emitLog("Auto-retry " + r.attempt + "/" + maxAttempts + " to " + state.nodeName(m.to));
            } catch (IOException e) {
                state.emitLog("Auto-retry failed: " + e.getMessage());
            }
        }, delay, java.util.concurrent.TimeUnit.SECONDS);
    }

    public MeshState state() { return state; }

    private volatile boolean traceFrames;
    private volatile Capture capture;
    private final java.nio.file.Path sessionLog = meshconsole.DataDir.file("sessions.log");
    private long connectedAt;

    public void startCapture(java.nio.file.Path p) throws IOException { stopCapture(); capture = new Capture(p); state.emitLog("Recording packets to " + p); }
    public void stopCapture() { Capture c = capture; capture = null; if (c != null) { c.close(); state.emitLog("Capture stopped: " + c.frames() + " frames in " + c.path()); } }
    public boolean isCapturing() { return capture != null; }
    public long captureFrames() { Capture c = capture; return c == null ? 0 : c.frames(); }

    /** Replays a capture file into the state as if it came from the radio (disconnects first). */
    public long replay(java.nio.file.Path p, double speed, java.util.function.BooleanSupplier cancelled) throws IOException {
        disconnect();
        state.resetForConnect();
        state.emitLog("Replaying " + p + (speed == 0 ? " (fast)" : " at " + speed + "×"));
        connListener.onConnectionChanged(true, "replay " + p.getFileName());
        try {
            return Capture.replay(p, speed, state::handle, cancelled);
        } finally {
            connListener.onConnectionChanged(false, "");
            state.emitLog("Replay finished");
        }
    }

    private void session(String line) {
        try {
            java.nio.file.Files.writeString(sessionLog, java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "  " + line + "\n",
                    java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }
    public void setTraceFrames(boolean on) { traceFrames = on; MeshSerial s = serial; if (s != null) s.traceFrames = on; }

    public void setConnectionListener(ConnectionListener l) { connListener = l; }

    public boolean isConnected() { return serial != null; }

    public synchronized void connect(SerialPort port) throws IOException {
        disconnect();
        state.resetForConnect();
        serial = new MeshSerial(port, this);
        serial.traceFrames = traceFrames;
        serial.start(rnd.nextInt(Integer.MAX_VALUE) + 1);
        state.emitLog("Opened " + port.getSystemPortName() + ", requesting config…");
        connectedAt = System.currentTimeMillis();
        session("connect serial " + port.getSystemPortName() + " (" + port.getDescriptivePortName() + ")");
        connListener.onConnectionChanged(true, port.getSystemPortName());
    }

    /** Connect over arbitrary streams (tests, bridges). */
    public synchronized void connectStreams(java.io.InputStream in, java.io.OutputStream out, String name, Runnable onClose) {
        disconnect();
        state.resetForConnect();
        serial = new MeshSerial(in, out, this, onClose);
        serial.traceFrames = traceFrames;
        serial.start(rnd.nextInt(Integer.MAX_VALUE) + 1);
        connListener.onConnectionChanged(true, name);
    }

    /** Same protocol over TCP (port 4403) for WiFi/Ethernet nodes, or a serial→TCP bridge. */
    public synchronized void connectTcp(String host, int port) throws IOException {
        disconnect();
        state.resetForConnect();
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 5000);
        sock.setTcpNoDelay(true);
        serial = new MeshSerial(sock.getInputStream(), sock.getOutputStream(), this, () -> {
            try { sock.close(); } catch (IOException ignored) { }
        });
        serial.traceFrames = traceFrames;
        serial.start(rnd.nextInt(Integer.MAX_VALUE) + 1);
        state.emitLog("Connected to " + host + ":" + port + ", requesting config…");
        connectedAt = System.currentTimeMillis();
        session("connect tcp " + host + ":" + port);
        connListener.onConnectionChanged(true, host + ":" + port);
    }

    public synchronized void disconnect() {
        if (serial != null) {
            MeshSerial s = serial;
            serial = null;
            s.close();
            state.emitLog("Disconnected");
            session("disconnect by user after " + (System.currentTimeMillis() - connectedAt) / 1000 + " s");
            saveDbQuietly();
            connListener.onConnectionChanged(false, "");
        }
    }

    private int newPacketId() {
        int id;
        do { id = rnd.nextInt(); } while (id == 0);
        return id;
    }

    private int hopLimit() {
        var lora = state.lora();
        return lora != null && lora.getHopLimit() > 0 ? lora.getHopLimit() : 3;
    }

    /** Sends a text message; returns the ChatMessage tracked for ack/nak updates. */
    public ChatMessage sendText(String text, int to, int channel) throws IOException {
        ChatMessage m = new ChatMessage();
        m.time = System.currentTimeMillis();
        m.from = state.myNodeNum();
        m.to = to;
        m.channel = channel;
        m.text = text;
        m.outgoing = true;
        m.packetId = newPacketId();
        m.status = ChatMessage.Status.QUEUED;
        m.statusDetail = "queued";
        state.addOutgoing(m);
        requireSerial();
        sender.submit(() -> {
            try {
                transmit(m);
            } catch (IOException e) {
                m.status = ChatMessage.Status.FAILED;
                m.statusDetail = e.getMessage();
                state.updateMessage(m);
                state.emitLog("Send failed: " + e.getMessage());
            }
        });
        return m;
    }

    /** Re-sends the same text to the same destination with a fresh packet id. */
    public ChatMessage resend(ChatMessage original) throws IOException {
        return sendText(original.text, original.to, original.channel);
    }

    private void transmit(ChatMessage m) throws IOException {
        MeshSerial s = requireSerial();
        Data data = Data.newBuilder()
                .setPortnum(PortNum.TEXT_MESSAGE_APP)
                .setPayload(ByteString.copyFromUtf8(m.text))
                .build();
        MeshPacket.Builder pkt = MeshPacket.newBuilder()
                .setTo(m.to)
                .setChannel(m.channel)
                .setId(m.packetId)
                .setWantAck(true)
                .setHopLimit(hopLimit())
                .setDecoded(data);
        if (!m.isBroadcast()) {
            NodeEntry dest = state.node(m.to);
            // Direct messages to nodes that advertise a public key use PKI, like the official apps.
            if (dest != null && dest.publicKey.length > 0 && m.channel == 0) {
                pkt.setPkiEncrypted(true);
            }
        }
        if (state.verbose()) state.emitLog(String.format("[tx] text id=%08x → %s ch=%d %d B%s", m.packetId, state.nodeName(m.to), m.channel, m.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, pkt.getPkiEncrypted() ? " pki" : ""));
        s.send(ToRadio.newBuilder().setPacket(pkt.build()).build());
    }

    /** Asks the mesh for the route to a node; result arrives via MeshState.Listener.onTraceroute. */
    public void traceroute(int to) throws IOException {
        MeshSerial s = requireSerial();
        sender.submit(() -> { try { tracerouteNow(s, to); } catch (IOException e) { state.emitLog("Traceroute send failed: " + e.getMessage()); } });
    }

    private void tracerouteNow(MeshSerial s, int to) throws IOException {
        Data data = Data.newBuilder()
                .setPortnum(PortNum.TRACEROUTE_APP)
                .setPayload(RouteDiscovery.newBuilder().build().toByteString())
                .setWantResponse(true)
                .build();
        MeshPacket pkt = MeshPacket.newBuilder()
                .setTo(to).setChannel(0).setId(newPacketId())
                .setWantAck(true).setHopLimit(hopLimit()).setDecoded(data).build();
        s.send(ToRadio.newBuilder().setPacket(pkt).build());
        state.emitLog("Traceroute request sent to " + state.nodeName(to) + " (can take up to a minute)");
    }

    /** Asks a node to send its position (want_response on POSITION_APP). */
    public void requestPosition(int to) throws IOException {
        MeshSerial s = requireSerial();
        sender.submit(() -> { try { requestPositionNow(s, to); } catch (IOException e) { state.emitLog("Position request failed: " + e.getMessage()); } });
    }

    private void requestPositionNow(MeshSerial s, int to) throws IOException {
        Data data = Data.newBuilder()
                .setPortnum(PortNum.POSITION_APP)
                .setPayload(Position.newBuilder().build().toByteString())
                .setWantResponse(true)
                .build();
        MeshPacket pkt = MeshPacket.newBuilder()
                .setTo(to).setChannel(0).setId(newPacketId())
                .setWantAck(true).setHopLimit(hopLimit()).setDecoded(data).build();
        s.send(ToRadio.newBuilder().setPacket(pkt).build());
        state.emitLog("Position request sent to " + state.nodeName(to));
    }

    // ---- admin / configuration --------------------------------------------

    /** Sends an AdminMessage to our own radio, attaching the current session passkey. */
    public void sendAdmin(AdminMessage.Builder msg, boolean wantResponse) throws IOException {
        MeshSerial s = requireSerial();
        if (!state.sessionPasskey().isEmpty()) msg.setSessionPasskey(state.sessionPasskey());
        Data data = Data.newBuilder()
                .setPortnum(PortNum.ADMIN_APP)
                .setPayload(msg.build().toByteString())
                .setWantResponse(wantResponse)
                .build();
        MeshPacket pkt = MeshPacket.newBuilder()
                .setTo(state.myNodeNum()).setChannel(0).setId(newPacketId())
                .setWantAck(false).setHopLimit(0).setDecoded(data)
                .setPriority(MeshPacket.Priority.RELIABLE).build();
        if (state.verbose()) state.emitLog("[tx] admin " + msg.getPayloadVariantCase() + (wantResponse ? " (want_response)" : ""));
        s.send(ToRadio.newBuilder().setPacket(pkt).build());
    }

    /**
     * The firmware only accepts state-changing admin messages that carry a fresh session
     * passkey; any "get" response hands one out. Call before a set.
     */
    public void ensureSessionKey() throws IOException {
        if (state.sessionPasskeyAgeMs() < 240_000) return;
        sendAdmin(AdminMessage.newBuilder().setGetDeviceMetadataRequest(true), true);
        long deadline = System.currentTimeMillis() + 5000;
        while (state.sessionPasskeyAgeMs() > 240_000 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        if (state.sessionPasskeyAgeMs() > 240_000) throw new IOException("Radio did not answer the admin session request");
    }

    public void setOwner(String longName, String shortName) throws IOException {
        ensureSessionKey();
        User u = User.newBuilder().setLongName(longName).setShortName(shortName).build();
        sendAdmin(AdminMessage.newBuilder().setSetOwner(u), false);
        state.emitLog("Owner set to " + longName + " / " + shortName);
    }

    public void setConfig(Config c) throws IOException {
        ensureSessionKey();
        sendAdmin(AdminMessage.newBuilder().setBeginEditSettings(true), false);
        sendAdmin(AdminMessage.newBuilder().setSetConfig(c), false);
        sendAdmin(AdminMessage.newBuilder().setCommitEditSettings(true), false);
        state.storeConfig(c);
        state.emitLog("Config " + c.getPayloadVariantCase() + " written (radio may reboot to apply it)" + (c.hasLora() ? ": " + MeshState.loraSummary(c.getLora()) : ""));
    }

    public void setModuleConfig(ModuleConfig c) throws IOException {
        ensureSessionKey();
        sendAdmin(AdminMessage.newBuilder().setBeginEditSettings(true), false);
        sendAdmin(AdminMessage.newBuilder().setSetModuleConfig(c), false);
        sendAdmin(AdminMessage.newBuilder().setCommitEditSettings(true), false);
        state.storeModuleConfig(c);
        state.emitLog("Module config " + c.getPayloadVariantCase() + " written");
    }

    public void setChannel(Channel c) throws IOException {
        ensureSessionKey();
        sendAdmin(AdminMessage.newBuilder().setBeginEditSettings(true), false);
        sendAdmin(AdminMessage.newBuilder().setSetChannel(c), false);
        sendAdmin(AdminMessage.newBuilder().setCommitEditSettings(true), false);
        state.storeChannel(c);
        state.emitLog("Channel " + c.getIndex() + " written");
    }

    /** Stores a fixed position on the radio (also enables position.fixed_position). */
    public void setFixedPosition(double lat, double lon, int altitudeM) throws IOException {
        ensureSessionKey();
        Position pos = Position.newBuilder()
                .setLatitudeI((int) Math.round(lat * 1e7)).setLongitudeI((int) Math.round(lon * 1e7))
                .setAltitude(altitudeM).setTime((int) (System.currentTimeMillis() / 1000))
                .setLocationSource(Position.LocSource.LOC_MANUAL).build();
        sendAdmin(AdminMessage.newBuilder().setSetFixedPosition(pos), false);
        NodeEntry me = state.myNode();
        if (me != null) { me.lat = lat; me.lon = lon; me.altitude = altitudeM; me.hasPosition = true; }
        state.emitLog(String.format("Fixed position set to %.5f, %.5f (%d m)", lat, lon, altitudeM));
        state.notifyNodesChanged();
    }

    public void removeFixedPosition() throws IOException {
        ensureSessionKey();
        sendAdmin(AdminMessage.newBuilder().setRemoveFixedPosition(true), false);
        state.emitLog("Fixed position removed (radio will use GPS if it has one)");
    }

    public void reboot(int seconds) throws IOException {
        ensureSessionKey();
        sendAdmin(AdminMessage.newBuilder().setRebootSeconds(seconds), false);
        state.emitLog("Reboot requested in " + seconds + " s");
    }

    /** Forwards a message received from the MQTT broker into the radio (client-proxy mode). */
    public void sendMqttProxy(MqttClientProxyMessage m) throws IOException {
        MeshSerial s = requireSerial();
        sender.submit(() -> { try { s.send(ToRadio.newBuilder().setMqttClientProxyMessage(m).build()); } catch (IOException e) { state.emitLog("MQTT forward to radio failed: " + e.getMessage()); } });
    }

    // ---- requests to other nodes ------------------------------------------

    private void sendTo(int to, Data data, boolean wantAck, String logLine) throws IOException {
        MeshSerial s = requireSerial();
        MeshPacket pkt = MeshPacket.newBuilder()
                .setTo(to).setChannel(0).setId(newPacketId())
                .setWantAck(wantAck).setHopLimit(hopLimit()).setDecoded(data).build();
        sender.submit(() -> {
            try { s.send(ToRadio.newBuilder().setPacket(pkt).build()); if (logLine != null) state.emitLog(logLine); }
            catch (IOException e) { state.emitLog("Send failed: " + e.getMessage()); }
        });
    }

    /** Asks a node to (re)send its user info: name, hardware, role, public key. */
    public void requestNodeInfo(int to) throws IOException {
        NodeEntry me = state.myNode();
        User.Builder u = User.newBuilder().setId(String.format("!%08x", state.myNodeNum()));
        if (me != null) u.setLongName(me.longName).setShortName(me.shortName);
        Data d = Data.newBuilder().setPortnum(PortNum.NODEINFO_APP).setPayload(u.build().toByteString()).setWantResponse(true).build();
        sendTo(to, d, true, "Node info request sent to " + state.nodeName(to));
    }

    /** Asks a remote node for its metadata and LoRa config (it must trust us: admin key or shared admin channel). */
    public void requestRemoteInfo(int to) throws IOException {
        for (AdminMessage.Builder b : new AdminMessage.Builder[]{
                AdminMessage.newBuilder().setGetDeviceMetadataRequest(true),
                AdminMessage.newBuilder().setGetConfigRequest(AdminMessage.ConfigType.LORA_CONFIG)}) {
            Data d = Data.newBuilder().setPortnum(PortNum.ADMIN_APP).setPayload(b.build().toByteString()).setWantResponse(true).build();
            sendTo(to, d, true, null);
        }
        state.emitLog("Remote admin request sent to " + state.nodeName(to) + " (answers appear here; needs admin rights on that node)");
    }

    /** Asks a store-and-forward server node to replay the last windowMinutes of traffic. */
    public void requestStoreForwardHistory(int server, int windowMinutes) throws IOException {
        org.meshtastic.proto.StoreAndForwardProtos.StoreAndForward sf = org.meshtastic.proto.StoreAndForwardProtos.StoreAndForward.newBuilder()
                .setRr(org.meshtastic.proto.StoreAndForwardProtos.StoreAndForward.RequestResponse.CLIENT_HISTORY)
                .setHistory(org.meshtastic.proto.StoreAndForwardProtos.StoreAndForward.History.newBuilder().setWindow(windowMinutes).setLastRequest(0))
                .build();
        Data d = Data.newBuilder().setPortnum(PortNum.STORE_FORWARD_APP).setPayload(sf.toByteString()).setWantResponse(true).build();
        sendTo(server, d, true, "Store & forward history request (" + windowMinutes + " min) sent to " + state.nodeName(server));
    }

    /** Broadcasts a waypoint (map marker) to the mesh. */
    public void sendWaypoint(String name, String description, double lat, double lon, long expireEpochSec) throws IOException {
        int id = newPacketId() & 0x7FFFFFFF;
        Waypoint w = Waypoint.newBuilder().setId(id).setName(name).setDescription(description)
                .setLatitudeI((int) Math.round(lat * 1e7)).setLongitudeI((int) Math.round(lon * 1e7))
                .setExpire((int) expireEpochSec).build();
        Data d = Data.newBuilder().setPortnum(PortNum.WAYPOINT_APP).setPayload(w.toByteString()).build();
        sendTo(ChatMessage.BROADCAST, d, false, "Waypoint '" + name + "' sent");
        WaypointEntry e = new WaypointEntry();
        e.id = id; e.lat = lat; e.lon = lon; e.name = name; e.description = description; e.expire = expireEpochSec;
        e.from = state.myNodeNum(); e.received = System.currentTimeMillis();
        state.putWaypoint(e);
    }

    private MeshSerial requireSerial() throws IOException {
        MeshSerial s = serial;
        if (s == null) throw new IOException("Not connected");
        return s;
    }

    // ---- MeshSerial.Listener ---------------------------------------------

    @Override
    public void onFromRadio(FromRadio fr) {
        Capture c = capture;
        if (c != null) c.write(fr);
        if (fr.hasConfigCompleteId()) session("config complete: node " + String.format("!%08x", state.myNodeNum()) + " " + state.nodeName(state.myNodeNum()) + ", firmware " + state.firmware() + ", " + state.nodes().size() + " nodes, lora " + MeshState.loraSummary(state.lora()));
        state.handle(fr);
    }

    public void saveDbQuietly() {
        try { state.saveNodeDb(); } catch (IOException e) { state.emitLog("Could not save nodes.json: " + e.getMessage()); }
    }

    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    @Override
    public void onDebugText(String line) {
        // firmware debug output carries ANSI colour codes; drop them and the [serial] prefix noise
        String clean = ANSI.matcher(line).replaceAll("").trim();
        if (clean.isEmpty()) return;
        state.emitLog("[serial] " + clean);
    }

    @Override
    public void onDisconnected(String reason) {
        synchronized (this) {
            if (serial != null) {
                MeshSerial s = serial;
                serial = null;
                new Thread(s::close).start();
            }
        }
        state.emitLog("Connection lost: " + reason);
        session("connection lost (" + reason + ") after " + (System.currentTimeMillis() - connectedAt) / 1000 + " s");
        saveDbQuietly();
        connListener.onConnectionChanged(false, reason);
    }
}
