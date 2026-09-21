package meshconsole.mesh;

import com.google.protobuf.InvalidProtocolBufferException;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.MeshProtos.*;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;
import org.meshtastic.proto.Portnums.PortNum;
import org.meshtastic.proto.TelemetryProtos.DeviceMetrics;
import org.meshtastic.proto.TelemetryProtos.LocalStats;
import org.meshtastic.proto.TelemetryProtos.Telemetry;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds everything decoded from the radio. All public accessors return copies,
 * so the Swing thread can read while the serial thread writes.
 */
public class MeshState {

    public interface Listener {
        default void onNodesChanged() { }
        default void onMessage(ChatMessage m, boolean isNew) { }
        default void onStatusChanged() { }
        default void onLog(String line) { }
        default void onTraceroute(String summary) { }
        default void onConfigChanged() { }
        default void onMqttProxyMessage(MqttClientProxyMessage m) { }
        default void onAdminResponse(AdminMessage m) { }
    }

    private final Object lock = new Object();
    private final Map<Integer, NodeEntry> nodes = new HashMap<>();
    private final List<ChatMessage> messages = new ArrayList<>();
    private final Deque<SignalSample> signal = new ArrayDeque<>();
    private final List<Channel> channels = new ArrayList<>();
    private final Map<Config.PayloadVariantCase, Config> configs = new EnumMap<>(Config.PayloadVariantCase.class);
    private final Map<ModuleConfig.PayloadVariantCase, ModuleConfig> moduleConfigs = new EnumMap<>(ModuleConfig.PayloadVariantCase.class);
    private com.google.protobuf.ByteString sessionPasskey = com.google.protobuf.ByteString.EMPTY;
    private long sessionPasskeyTime;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final MessageLog log;
    private volatile boolean verbose;

    private int myNodeNum;
    private boolean configComplete;
    private String firmware = "";
    private Config.LoRaConfig lora;
    private LocalStats localStats;
    private int queueFree = -1;
    private int encryptedSeen;
    private static final int SIGNAL_HISTORY = 600;

    public MeshState(MessageLog log) {
        this.log = log;
        for (ChatMessage m : log.load()) messages.add(m);
    }

    public void setVerbose(boolean v) { verbose = v; }
    public boolean verbose() { return verbose; }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // ---- accessors --------------------------------------------------------

    public int myNodeNum() { synchronized (lock) { return myNodeNum; } }
    public boolean configComplete() { synchronized (lock) { return configComplete; } }
    public String firmware() { synchronized (lock) { return firmware; } }
    public Config.LoRaConfig lora() { synchronized (lock) { return lora; } }
    public LocalStats localStats() { synchronized (lock) { return localStats; } }
    public int queueFree() { synchronized (lock) { return queueFree; } }
    public int encryptedSeen() { synchronized (lock) { return encryptedSeen; } }

    /** Enabled channels only, sorted by index. */
    public List<Channel> channels() {
        synchronized (lock) {
            List<Channel> l = new ArrayList<>();
            for (Channel c : channels) if (c.getRole() != Channel.Role.DISABLED) l.add(c);
            return l;
        }
    }

    /** All channel slots the radio reported (including disabled ones), sorted by index. */
    public List<Channel> allChannels() { synchronized (lock) { return new ArrayList<>(channels); } }

    public Config config(Config.PayloadVariantCase which) { synchronized (lock) { return configs.get(which); } }
    public ModuleConfig moduleConfig(ModuleConfig.PayloadVariantCase which) { synchronized (lock) { return moduleConfigs.get(which); } }

    public com.google.protobuf.ByteString sessionPasskey() { synchronized (lock) { return sessionPasskey; } }
    public long sessionPasskeyAgeMs() { synchronized (lock) { return sessionPasskeyTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - sessionPasskeyTime; } }

    public void storeChannel(Channel c) {
        synchronized (lock) {
            channels.removeIf(x -> x.getIndex() == c.getIndex());
            channels.add(c);
            channels.sort(Comparator.comparingInt(Channel::getIndex));
        }
        fire(Listener::onConfigChanged);
        fire(Listener::onStatusChanged);
    }

    public void storeConfig(Config c) {
        if (c.getPayloadVariantCase() == Config.PayloadVariantCase.PAYLOADVARIANT_NOT_SET) return;
        synchronized (lock) {
            configs.put(c.getPayloadVariantCase(), c);
            if (c.hasLora()) lora = c.getLora();
        }
        fire(Listener::onConfigChanged);
        fire(Listener::onStatusChanged);
    }

    public void storeModuleConfig(ModuleConfig c) {
        if (c.getPayloadVariantCase() == ModuleConfig.PayloadVariantCase.PAYLOADVARIANT_NOT_SET) return;
        synchronized (lock) { moduleConfigs.put(c.getPayloadVariantCase(), c); }
        fire(Listener::onConfigChanged);
    }

    public NodeEntry myNode() { synchronized (lock) { return nodes.get(myNodeNum); } }

    public NodeEntry node(int num) { synchronized (lock) { return nodes.get(num); } }

    public String nodeName(int num) {
        if (num == ChatMessage.BROADCAST) return "Broadcast";
        NodeEntry n = node(num);
        return n == null ? String.format("!%08x", num) : n.displayName();
    }

    /** Snapshot of nodes, most recently heard first. */
    public List<NodeEntry> nodes() {
        synchronized (lock) {
            List<NodeEntry> l = new ArrayList<>(nodes.values());
            int my = myNodeNum;
            l.sort((a, b) -> {
                if (a.num == my) return -1;
                if (b.num == my) return 1;
                return Long.compare(b.lastHeardMillis(), a.lastHeardMillis());
            });
            return l;
        }
    }

    public List<ChatMessage> messages() { synchronized (lock) { return new ArrayList<>(messages); } }

    public List<SignalSample> signalHistory() { synchronized (lock) { return new ArrayList<>(signal); } }

    public void resetForConnect() {
        synchronized (lock) {
            nodes.clear();
            channels.clear();
            configs.clear();
            moduleConfigs.clear();
            sessionPasskey = com.google.protobuf.ByteString.EMPTY;
            sessionPasskeyTime = 0;
            configComplete = false;
            localStats = null;
            queueFree = -1;
            encryptedSeen = 0;
            signal.clear();
        }
        fire(Listener::onNodesChanged);
        fire(Listener::onStatusChanged);
    }

    // ---- outgoing bookkeeping --------------------------------------------

    public void addOutgoing(ChatMessage m) {
        synchronized (lock) { messages.add(m); }
        log.append(m);
        fire(l -> l.onMessage(m, true));
    }

    public void updateMessage(ChatMessage m) {
        fire(l -> l.onMessage(m, false));
    }

    // ---- incoming ---------------------------------------------------------

    public void handle(FromRadio fr) {
        if (verbose) {
            switch (fr.getPayloadVariantCase()) {
                case PACKET -> { }   // logged in handlePacket with decoded detail
                case NODE_INFO -> emitLog("[rx] node_info " + String.format("!%08x", fr.getNodeInfo().getNum()) + " " + fr.getNodeInfo().getUser().getLongName());
                case CONFIG -> emitLog("[rx] config " + fr.getConfig().getPayloadVariantCase());
                case MODULECONFIG -> emitLog("[rx] module_config " + fr.getModuleConfig().getPayloadVariantCase());
                case CHANNEL -> emitLog("[rx] channel " + fr.getChannel().getIndex() + " " + fr.getChannel().getRole() + " '" + fr.getChannel().getSettings().getName() + "'");
                case MQTTCLIENTPROXYMESSAGE -> emitLog("[rx] mqtt_proxy → broker " + fr.getMqttClientProxyMessage().getTopic());
                case LOG_RECORD -> { }
                default -> emitLog("[rx] " + fr.getPayloadVariantCase().name().toLowerCase() + " (" + fr.getSerializedSize() + " B)");
            }
        }
        switch (fr.getPayloadVariantCase()) {
            case MY_INFO -> {
                synchronized (lock) { myNodeNum = fr.getMyInfo().getMyNodeNum(); }
                fire(Listener::onStatusChanged);
            }
            case NODE_INFO -> {
                applyNodeInfo(fr.getNodeInfo());
                fire(Listener::onNodesChanged);
            }
            case METADATA -> {
                synchronized (lock) { firmware = fr.getMetadata().getFirmwareVersion(); }
                fire(Listener::onStatusChanged);
            }
            case CONFIG -> storeConfig(fr.getConfig());
            case MODULECONFIG -> storeModuleConfig(fr.getModuleConfig());
            case CHANNEL -> storeChannel(fr.getChannel());
            case MQTTCLIENTPROXYMESSAGE -> fire(l -> l.onMqttProxyMessage(fr.getMqttClientProxyMessage()));
            case CONFIG_COMPLETE_ID -> {
                synchronized (lock) { configComplete = true; }
                emitLog("Config + node DB received (" + nodes.size() + " nodes)");
                fire(Listener::onStatusChanged);
                fire(Listener::onNodesChanged);
            }
            case PACKET -> handlePacket(fr.getPacket());
            case LOG_RECORD -> {
                LogRecord r = fr.getLogRecord();
                emitLog("[" + r.getLevel() + "] " + r.getSource() + " " + r.getMessage().trim());
            }
            case QUEUESTATUS -> {
                synchronized (lock) { queueFree = fr.getQueueStatus().getFree(); }
                fire(Listener::onStatusChanged);
            }
            case CLIENTNOTIFICATION -> emitLog("Device notification: " + fr.getClientNotification().getMessage());
            case REBOOTED -> emitLog("Device reports it rebooted");
            default -> { }
        }
    }

    private void applyNodeInfo(NodeInfo ni) {
        synchronized (lock) {
            NodeEntry n = nodes.computeIfAbsent(ni.getNum(), NodeEntry::new);
            if (ni.hasUser()) applyUser(n, ni.getUser());
            if (ni.hasPosition()) applyPosition(n, ni.getPosition());
            if (ni.getSnr() != 0) n.snr = ni.getSnr();
            if (ni.getLastHeard() != 0) n.lastHeard = Integer.toUnsignedLong(ni.getLastHeard());
            if (ni.hasHopsAway()) n.hopsAway = ni.getHopsAway();
            n.viaMqtt = ni.getViaMqtt();
            if (ni.hasDeviceMetrics()) applyMetrics(n, ni.getDeviceMetrics());
        }
    }

    private static void applyUser(NodeEntry n, User u) {
        n.longName = u.getLongName();
        n.shortName = u.getShortName();
        n.hwModel = u.getHwModel().name();
        if (!u.getPublicKey().isEmpty()) n.publicKey = u.getPublicKey().toByteArray();
    }

    private static void applyPosition(NodeEntry n, Position p) {
        if (p.hasLatitudeI() && p.hasLongitudeI() && (p.getLatitudeI() != 0 || p.getLongitudeI() != 0)) {
            n.lat = p.getLatitudeI() * 1e-7;
            n.lon = p.getLongitudeI() * 1e-7;
            n.altitude = p.getAltitude();
            n.hasPosition = true;
        }
    }

    private static void applyMetrics(NodeEntry n, DeviceMetrics m) {
        if (m.hasBatteryLevel()) n.battery = m.getBatteryLevel();
        if (m.hasVoltage()) n.voltage = m.getVoltage();
        if (m.hasChannelUtilization()) n.channelUtil = m.getChannelUtilization();
        if (m.hasAirUtilTx()) n.airUtilTx = m.getAirUtilTx();
    }

    private void handlePacket(MeshPacket p) {
        int from = p.getFrom();
        boolean fromMe;
        int hops = p.getHopStart() > 0 ? p.getHopStart() - p.getHopLimit() : -1;
        NodeEntry n;
        synchronized (lock) {
            fromMe = from == myNodeNum;
            n = nodes.computeIfAbsent(from, NodeEntry::new);
            if (!fromMe) {
                n.lastLocalRx = System.currentTimeMillis();
                if (p.hasRxTime() && p.getRxTime() != 0) n.lastHeard = Integer.toUnsignedLong(p.getRxTime());
                n.packetsSeen++;
                if (p.getRxSnr() != 0) n.snr = p.getRxSnr();
                if (p.hasRxRssi() && p.getRxRssi() != 0) n.rssi = p.getRxRssi();
                if (hops >= 0) n.hopsAway = hops;
                n.viaMqtt = p.getViaMqtt();
                if (p.hasRxRssi() && p.getRxRssi() != 0 && !p.getViaMqtt()) {
                    signal.addLast(new SignalSample(System.currentTimeMillis(), from, p.getRxRssi(), p.getRxSnr(), hops));
                    while (signal.size() > SIGNAL_HISTORY) signal.removeFirst();
                }
            }
        }
        if (!fromMe) fire(Listener::onNodesChanged);
        if (verbose) {
            String port = p.hasDecoded() ? p.getDecoded().getPortnum().name() : "ENCRYPTED(" + p.getEncrypted().size() + " B)";
            emitLog(String.format("[rx] packet id=%08x %s → %s ch=%d %s%s hops=%s rssi=%d snr=%.2f%s%s",
                    p.getId(), nodeName(from), nodeName(p.getTo()), p.getChannel(), port,
                    p.hasDecoded() && p.getDecoded().getRequestId() != 0 ? String.format(" reply_to=%08x", p.getDecoded().getRequestId()) : "",
                    hops < 0 ? "?" : String.valueOf(hops), p.hasRxRssi() ? p.getRxRssi() : 0, p.getRxSnr(),
                    p.getViaMqtt() ? " via_mqtt" : "", p.getWantAck() ? " want_ack" : ""));
        }

        if (p.getPayloadVariantCase() == MeshPacket.PayloadVariantCase.ENCRYPTED) {
            synchronized (lock) { encryptedSeen++; }
            emitLog("Encrypted packet from " + nodeName(from) + " on a channel this radio can't decrypt");
            return;
        }
        if (!p.hasDecoded()) return;
        Data d = p.getDecoded();
        try {
            switch (d.getPortnum()) {
                case TEXT_MESSAGE_APP -> {
                    ChatMessage m = new ChatMessage();
                    m.time = System.currentTimeMillis();
                    m.from = from;
                    m.to = p.getTo();
                    m.channel = p.getChannel();
                    m.text = d.getPayload().toStringUtf8();
                    m.outgoing = fromMe;
                    m.packetId = p.getId();
                    m.status = fromMe ? ChatMessage.Status.TRANSMITTED : ChatMessage.Status.RECEIVED;
                    m.rssi = p.hasRxRssi() ? p.getRxRssi() : 0;
                    m.snr = p.getRxSnr();
                    m.hops = hops;
                    synchronized (lock) { messages.add(m); }
                    log.append(m);
                    fire(l -> l.onMessage(m, true));
                }
                case POSITION_APP -> {
                    Position pos = Position.parseFrom(d.getPayload());
                    synchronized (lock) { applyPosition(n, pos); }
                    fire(Listener::onNodesChanged);
                }
                case NODEINFO_APP -> {
                    User u = User.parseFrom(d.getPayload());
                    synchronized (lock) { applyUser(n, u); }
                    fire(Listener::onNodesChanged);
                }
                case TELEMETRY_APP -> {
                    Telemetry t = Telemetry.parseFrom(d.getPayload());
                    if (t.hasDeviceMetrics()) {
                        synchronized (lock) { applyMetrics(n, t.getDeviceMetrics()); }
                        fire(Listener::onNodesChanged);
                    }
                    if (t.hasLocalStats() && fromMe) {
                        synchronized (lock) { localStats = t.getLocalStats(); }
                        fire(Listener::onStatusChanged);
                    }
                }
                case ROUTING_APP -> handleRouting(from, d);
                case ADMIN_APP -> handleAdmin(d);
                case TRACEROUTE_APP -> handleTraceroute(p, d);
                case NEIGHBORINFO_APP -> {
                    NeighborInfo ni = NeighborInfo.parseFrom(d.getPayload());
                    StringBuilder sb = new StringBuilder("Neighbors of " + nodeName(ni.getNodeId()) + ":");
                    for (Neighbor nb : ni.getNeighborsList()) {
                        sb.append(' ').append(nodeName(nb.getNodeId())).append(String.format(" (%.1f dB)", nb.getSnr()));
                    }
                    emitLog(sb.toString());
                }
                default -> { }
            }
        } catch (InvalidProtocolBufferException e) {
            emitLog("Could not decode " + d.getPortnum() + " payload from " + nodeName(from));
        }
    }

    private void handleRouting(int from, Data d) {
        Routing r;
        try {
            r = Routing.parseFrom(d.getPayload());
        } catch (InvalidProtocolBufferException e) {
            return;
        }
        if (r.getVariantCase() != Routing.VariantCase.ERROR_REASON) return;
        int reqId = d.getRequestId();
        ChatMessage target = null;
        synchronized (lock) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage m = messages.get(i);
                if (m.outgoing && m.packetId == reqId) { target = m; break; }
            }
        }
        if (target == null) return;
        boolean fromMe;
        synchronized (lock) { fromMe = from == myNodeNum; }
        Routing.Error err = r.getErrorReason();
        if (err == Routing.Error.NONE) {
            if (fromMe) {
                // Our own radio acknowledged that it put the packet on the air.
                if (target.status != ChatMessage.Status.DELIVERED) {
                    target.status = ChatMessage.Status.TRANSMITTED;
                    target.statusDetail = (target.isBroadcast() ? "sent (broadcast)" : "sent, awaiting delivery") + (target.attempt > 1 ? " (attempt " + target.attempt + ")" : "");
                }
            } else {
                target.status = ChatMessage.Status.DELIVERED;
                target.statusDetail = "delivered to " + nodeName(from);
            }
        } else {
            target.status = ChatMessage.Status.FAILED;
            target.statusDetail = err.name() + (fromMe ? "" : " (from " + nodeName(from) + ")");
        }
        log.append(target);
        updateMessage(target);
    }

    private void handleAdmin(Data d) throws InvalidProtocolBufferException {
        AdminMessage a = AdminMessage.parseFrom(d.getPayload());
        if (verbose) emitLog("[rx] admin " + a.getPayloadVariantCase() + (a.getSessionPasskey().isEmpty() ? "" : " (+session key)"));
        if (!a.getSessionPasskey().isEmpty()) {
            synchronized (lock) {
                sessionPasskey = a.getSessionPasskey();
                sessionPasskeyTime = System.currentTimeMillis();
            }
        }
        switch (a.getPayloadVariantCase()) {
            case GET_CONFIG_RESPONSE -> storeConfig(a.getGetConfigResponse());
            case GET_MODULE_CONFIG_RESPONSE -> storeModuleConfig(a.getGetModuleConfigResponse());
            case GET_CHANNEL_RESPONSE -> storeChannel(a.getGetChannelResponse());
            case GET_OWNER_RESPONSE -> {
                synchronized (lock) { applyUser(nodes.computeIfAbsent(myNodeNum, NodeEntry::new), a.getGetOwnerResponse()); }
                fire(Listener::onNodesChanged);
            }
            case GET_DEVICE_METADATA_RESPONSE -> {
                synchronized (lock) { firmware = a.getGetDeviceMetadataResponse().getFirmwareVersion(); }
                fire(Listener::onStatusChanged);
            }
            default -> { }
        }
        fire(l -> l.onAdminResponse(a));
    }

    private void handleTraceroute(MeshPacket p, Data d) {
        RouteDiscovery rd;
        try {
            rd = RouteDiscovery.parseFrom(d.getPayload());
        } catch (InvalidProtocolBufferException e) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        // "towards" is our node -> ... -> target; the reply travels the route back.
        sb.append("Traceroute ").append(nodeName(p.getTo())).append(" → ").append(nodeName(p.getFrom())).append(": ");
        sb.append(nodeName(p.getTo()));
        List<Integer> route = rd.getRouteList();
        List<Integer> snrs = rd.getSnrTowardsList();
        for (int i = 0; i < route.size(); i++) {
            sb.append(snrLabel(snrs, i)).append(nodeName(route.get(i)));
        }
        sb.append(snrLabel(snrs, route.size())).append(nodeName(p.getFrom()));
        if (rd.getRouteBackCount() > 0 || rd.getSnrBackCount() > 0) {
            sb.append("   |   back: ").append(nodeName(p.getFrom()));
            List<Integer> back = rd.getRouteBackList();
            List<Integer> snrB = rd.getSnrBackList();
            for (int i = 0; i < back.size(); i++) {
                sb.append(snrLabel(snrB, i)).append(nodeName(back.get(i)));
            }
            sb.append(snrLabel(snrB, back.size())).append(nodeName(p.getTo()));
        }
        String s = sb.toString();
        emitLog(s);
        fire(l -> l.onTraceroute(s));
    }

    private static String snrLabel(List<Integer> snrs, int i) {
        if (i < snrs.size() && snrs.get(i) != -128) {
            return String.format(" ─(%.2f dB)→ ", snrs.get(i) / 4.0);
        }
        return " ─(?)→ ";
    }

    public void emitLog(String line) {
        fire(l -> l.onLog(line));
    }

    private void fire(java.util.function.Consumer<Listener> c) {
        for (Listener l : listeners) {
            try { c.accept(l); } catch (RuntimeException e) { e.printStackTrace(); }
        }
    }
}
