package meshconsole;

import com.google.protobuf.ByteString;
import org.meshtastic.proto.MeshProtos.*;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.Portnums.PortNum;
import org.meshtastic.proto.TelemetryProtos.DeviceMetrics;
import java.io.*; import java.net.*; import java.util.Arrays;

/** Fake Meshtastic node on TCP 4403 for trying the GUI / CLI without hardware: answers the config dump, acks text, answers traceroute, broadcasts a message every 2 s. Run: gradle fakeRadio, then connect with TCP… 127.0.0.1:4403 */
public class FakeRadio {
    /** -Dfake.nokeys=true: refuse PKI direct messages with PKI_SEND_FAIL_PUBLIC_KEY, as a radio does when it no longer has the node's key. */
    static final boolean NO_KEYS = Boolean.getBoolean("fake.nokeys");
    /** -Dfake.contacts=true (with nokeys): an admin add_contact gives the radio the key, as real firmware does. */
    static final boolean CONTACTS_WORK = Boolean.getBoolean("fake.contacts");
    static final java.util.Set<Integer> contacts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static byte[] frame(FromRadio fr) { byte[] p = fr.toByteArray(); byte[] f = new byte[p.length + 4]; f[0] = (byte)0x94; f[1] = (byte)0xC3; f[2] = (byte)(p.length >> 8); f[3] = (byte)p.length; System.arraycopy(p, 0, f, 4, p.length); return f; }
    public static void main(String[] a) throws Exception {
        int me = 0x11223344, other = 0xAABBCCDD;
        try (ServerSocket ss = new ServerSocket(4403)) {
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> {
                    try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                        out.write("INFO | fake radio booted\r\n".getBytes());
                        ByteArrayOutputStream acc = new ByteArrayOutputStream(); byte[] buf = new byte[4096];
                        long lastChat = System.currentTimeMillis();
                        s.setSoTimeout(500);
                        while (true) {
                            int n;
                            try { n = in.read(buf); } catch (SocketTimeoutException e) { n = 0; }
                            if (n < 0) return;
                            if (n > 0) acc.write(buf, 0, n);
                            byte[] d = acc.toByteArray(); int pos = 0;
                            while (pos + 4 <= d.length) {
                                if ((d[pos] & 0xFF) != 0x94 || (d[pos+1] & 0xFF) != 0xC3) { pos++; continue; }
                                int len = ((d[pos+2] & 0xFF) << 8) | (d[pos+3] & 0xFF);
                                if (pos + 4 + len > d.length) break;
                                ToRadio tr = ToRadio.parseFrom(Arrays.copyOfRange(d, pos + 4, pos + 4 + len)); pos += 4 + len;
                                if (tr.hasWantConfigId()) {
                                    out.write(frame(FromRadio.newBuilder().setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(me)).build()));
                                    out.write(frame(FromRadio.newBuilder().setMetadata(DeviceMetadata.newBuilder().setFirmwareVersion("2.7.9.fake")).build()));
                                    out.write(frame(FromRadio.newBuilder().setConfig(Config.newBuilder().setLora(Config.LoRaConfig.newBuilder().setHopLimit(3))).build()));
                                    out.write(frame(FromRadio.newBuilder().setNodeInfo(NodeInfo.newBuilder().setNum(me).setUser(User.newBuilder().setLongName("Fake RAK").setShortName("FAKE").setHwModel(HardwareModel.RAK4631)).setPosition(Position.newBuilder().setLatitudeI(377749000).setLongitudeI(-1224194000)).setDeviceMetrics(DeviceMetrics.newBuilder().setBatteryLevel(77).setVoltage(3.9f).setChannelUtilization(4.2f))).build()));
                                    out.write(frame(FromRadio.newBuilder().setNodeInfo(NodeInfo.newBuilder().setNum(other).setUser(User.newBuilder().setLongName("Hilltop").setShortName("HILL").setPublicKey(ByteString.copyFrom(new byte[32]))).setSnr(6.5f).setLastHeard((int)(System.currentTimeMillis()/1000) - 90).setHopsAway(1)).build()));
                                    out.write(frame(FromRadio.newBuilder().setConfigCompleteId(tr.getWantConfigId()).build()));
                                } else if (tr.hasPacket()) {
                                    MeshPacket p = tr.getPacket();
                                    System.out.println("radio got: to=" + Integer.toHexString(p.getTo()) + " port=" + p.getDecoded().getPortnum() + " pki=" + p.getPkiEncrypted() + " text=" + p.getDecoded().getPayload().toStringUtf8());
                                    Routing ok = Routing.newBuilder().setErrorReason(Routing.Error.NONE).build();
                                    if (p.getDecoded().getPortnum() == PortNum.ADMIN_APP && p.getTo() == me) {
                                        var adm = org.meshtastic.proto.AdminProtos.AdminMessage.parseFrom(p.getDecoded().getPayload());
                                        if (adm.hasAddContact()) { contacts.add(adm.getAddContact().getNodeNum()); System.out.println("radio got: add_contact " + Integer.toHexString(adm.getAddContact().getNodeNum()) + " key=" + adm.getAddContact().getUser().getPublicKey().size() + " bytes"); }
                                        continue;
                                    }
                                    if (NO_KEYS && p.getPkiEncrypted() && !(CONTACTS_WORK && contacts.contains(p.getTo()))) {   // like a radio whose node list has dropped the destination
                                        Routing nak = Routing.newBuilder().setErrorReason(Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY).build();
                                        out.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(me).setTo(me).setDecoded(Data.newBuilder().setPortnum(PortNum.ROUTING_APP).setRequestId(p.getId()).setPayload(nak.toByteString()))).build()));
                                        continue;
                                    }
                                    out.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(me).setTo(me).setDecoded(Data.newBuilder().setPortnum(PortNum.ROUTING_APP).setRequestId(p.getId()).setPayload(ok.toByteString()))).build()));
                                    if (p.getTo() != 0xFFFFFFFF) {
                                        Thread.sleep(300);
                                        if (p.getDecoded().getPortnum() == PortNum.TRACEROUTE_APP) {
                                            RouteDiscovery rd = RouteDiscovery.newBuilder().addRoute(0x55555555).addSnrTowards(28).addSnrTowards(-12).addSnrBack(20).addSnrBack(30).addRouteBack(0x55555555).build();
                                            out.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(p.getTo()).setTo(me).setDecoded(Data.newBuilder().setPortnum(PortNum.TRACEROUTE_APP).setRequestId(p.getId()).setPayload(rd.toByteString()))).build()));
                                        } else {
                                            out.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(p.getTo()).setTo(me).setDecoded(Data.newBuilder().setPortnum(PortNum.ROUTING_APP).setRequestId(p.getId()).setPayload(ok.toByteString()))).build()));
                                        }
                                    }
                                } else if (tr.hasDisconnect()) { System.out.println("radio: client disconnected"); return; }
                            }
                            acc.reset(); acc.write(d, pos, d.length - pos);
                            if (System.currentTimeMillis() - lastChat > 2000) {
                                lastChat = System.currentTimeMillis();
                                out.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(other).setTo(0xFFFFFFFF).setId(99).setRxRssi(-101).setRxSnr(5.25f).setHopStart(3).setHopLimit(2).setDecoded(Data.newBuilder().setPortnum(PortNum.TEXT_MESSAGE_APP).setPayload(ByteString.copyFromUtf8("hello from the hill")))).build()));
                            }
                            out.flush();
                        }
                    } catch (Exception e) { System.out.println("radio thread: " + e); }
                }).start();
            }
        }
    }
}
