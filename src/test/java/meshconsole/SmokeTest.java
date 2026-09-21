package meshconsole;

import com.google.protobuf.ByteString;
import meshconsole.mesh.*;
import org.meshtastic.proto.MeshProtos.*;
import org.meshtastic.proto.Portnums.PortNum;
import org.meshtastic.proto.TelemetryProtos.*;

import java.io.*;
import java.nio.file.Files;
import java.util.List;

/** Headless check of framing + decoding using a fake radio. Run: java -cp ... meshconsole.SmokeTest */
public class SmokeTest {
    static byte[] frame(FromRadio fr) {
        byte[] p = fr.toByteArray();
        byte[] f = new byte[p.length + 4];
        f[0] = (byte) 0x94; f[1] = (byte) 0xC3; f[2] = (byte) (p.length >> 8); f[3] = (byte) p.length;
        System.arraycopy(p, 0, f, 4, p.length);
        return f;
    }

    public static void main(String[] a) throws Exception {
        File log = Files.createTempFile("msgs", ".log").toFile();
        MeshState state = new MeshState(new MessageLog(log.toPath()));
        StringBuilder events = new StringBuilder();
        state.addListener(new MeshState.Listener() {
            @Override public void onMessage(ChatMessage m, boolean isNew) { events.append("MSG ").append(isNew).append(' ').append(m.text).append(' ').append(m.status).append('\n'); }
            @Override public void onLog(String line) { events.append("LOG ").append(line).append('\n'); }
            @Override public void onTraceroute(String s) { events.append("TR ").append(s).append('\n'); }
        });
        MeshClient client = new MeshClient(state);

        ByteArrayOutputStream radioIn = new ByteArrayOutputStream();
        // Byte stream the "radio" sends us, with junk text in between frames.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write("INFO | boot text before client attach\r\n".getBytes());
        stream.write(frame(FromRadio.newBuilder().setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(0x11223344)).build()));
        stream.write(frame(FromRadio.newBuilder().setNodeInfo(NodeInfo.newBuilder().setNum(0x11223344)
                .setUser(User.newBuilder().setLongName("My RAK").setShortName("RAK1").setHwModel(HardwareModel.RAK4631))
                .setPosition(Position.newBuilder().setLatitudeI(377749000).setLongitudeI(-1224194000))).build()));
        stream.write(frame(FromRadio.newBuilder().setNodeInfo(NodeInfo.newBuilder().setNum(0xAABBCCDD)
                .setUser(User.newBuilder().setLongName("Hilltop").setShortName("HILL").setPublicKey(ByteString.copyFrom(new byte[32])))
                .setSnr(7.5f).setLastHeard((int) (System.currentTimeMillis() / 1000)).setHopsAway(0)
                .setDeviceMetrics(DeviceMetrics.newBuilder().setBatteryLevel(88))).build()));
        stream.write(frame(FromRadio.newBuilder().setConfigCompleteId(42).build()));
        // 0x94 inside plain text must not confuse the parser
        stream.write(new byte[]{'x', (byte) 0x94, 'y', '\n'});
        stream.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(0xAABBCCDD).setTo(0xFFFFFFFF).setId(777)
                .setRxRssi(-97).setRxSnr(6.25f).setHopStart(3).setHopLimit(3)
                .setDecoded(Data.newBuilder().setPortnum(PortNum.TEXT_MESSAGE_APP).setPayload(ByteString.copyFromUtf8("hello mesh")))).build()));
        stream.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(0x11223344).setTo(0x11223344)
                .setDecoded(Data.newBuilder().setPortnum(PortNum.TELEMETRY_APP).setPayload(
                        Telemetry.newBuilder().setLocalStats(LocalStats.newBuilder().setNumPacketsRx(10).setNoiseFloor(-115)).build().toByteString()))).build()));
        stream.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(0xAABBCCDD).setTo(0x11223344)
                .setDecoded(Data.newBuilder().setPortnum(PortNum.TRACEROUTE_APP).setPayload(
                        RouteDiscovery.newBuilder().addRoute(0x55555555).addSnrTowards(30).addSnrTowards(-8).build().toByteString()))).build()));

        PipedInputStream toClient = new PipedInputStream(1 << 16);
        PipedOutputStream fromRadio = new PipedOutputStream(toClient);
        MeshSerial serial = new MeshSerial(toClient, radioIn, new MeshSerial.Listener() {
            public void onFromRadio(FromRadio fr) { state.handle(fr); }
            public void onDebugText(String line) { events.append("TXT ").append(line).append('\n'); }
            public void onDisconnected(String reason) { events.append("DISC ").append(reason).append('\n'); }
        });
        serial.start(1);
        fromRadio.write(stream.toByteArray());
        fromRadio.flush();
        Thread.sleep(500);

        // Outgoing message + ack round trip, using the client's packet-building code through reflection-free path:
        // emulate by sending via a second MeshSerial? Simpler: build the message the same way and route acks.
        ChatMessage m = new ChatMessage();
        m.time = System.currentTimeMillis(); m.from = 0x11223344; m.to = 0xAABBCCDD; m.channel = 0; m.text = "ping"; m.outgoing = true; m.packetId = 12345; m.status = ChatMessage.Status.QUEUED;
        state.addOutgoing(m);
        fromRadio.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(0x11223344).setTo(0x11223344)
                .setDecoded(Data.newBuilder().setPortnum(PortNum.ROUTING_APP).setRequestId(12345)
                        .setPayload(Routing.newBuilder().setErrorReason(Routing.Error.NONE).build().toByteString()))).build()));
        fromRadio.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(0xAABBCCDD).setTo(0x11223344)
                .setDecoded(Data.newBuilder().setPortnum(PortNum.ROUTING_APP).setRequestId(12345)
                        .setPayload(Routing.newBuilder().setErrorReason(Routing.Error.NONE).build().toByteString()))).build()));
        fromRadio.flush();
        Thread.sleep(500);

        System.out.println(events);
        System.out.println("my node: " + Integer.toHexString(state.myNodeNum()) + "  nodes=" + state.nodes().size());
        for (NodeEntry n : state.nodes()) System.out.printf("  %s %s pos=%b rssi=%d snr=%.1f hops=%d batt=%d pkts=%d%n", n.idString(), n.displayName(), n.hasPosition, n.rssi, n.snr, n.hopsAway, n.battery, n.packetsSeen);
        for (ChatMessage x : state.messages()) System.out.println("  msg: " + x.text + " " + x.status + " " + x.statusDetail + " rssi=" + x.rssi + " hops=" + x.hops);
        System.out.println("local stats noise=" + state.localStats().getNoiseFloor() + " signal samples=" + state.signalHistory().size());
        // check what we wrote to the radio: wake bytes + want_config frame
        byte[] sent = radioIn.toByteArray();
        System.out.println("bytes sent to radio: " + sent.length + " first frame magic ok=" + ((sent[32] & 0xFF) == 0x94 && (sent[33] & 0xFF) == 0xC3));
        // reload log
        List<ChatMessage> reloaded = new MessageLog(log.toPath()).load();
        System.out.println("reloaded from log: " + reloaded.size() + " -> " + reloaded.get(reloaded.size() - 1).status);
        serial.close();
        System.exit(0);
    }
}
