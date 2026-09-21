package meshconsole;

import com.google.protobuf.ByteString;
import meshconsole.mesh.*;
import org.meshtastic.proto.AdminProtos.AdminMessage;
import org.meshtastic.proto.ChannelProtos.*;
import org.meshtastic.proto.MeshProtos.*;
import org.meshtastic.proto.Portnums.PortNum;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;

/** Fake radio that answers admin requests; checks session-passkey handling and channel writes. */
public class AdminTest {
    static byte[] frame(FromRadio fr) { return SmokeTest.frame(fr); }

    public static void main(String[] a) throws Exception {
        MeshState state = new MeshState(new MessageLog(Files.createTempFile("m", ".log")));
        state.addListener(new MeshState.Listener() { @Override public void onLog(String l) { System.out.println("LOG " + l); } });
        MeshClient client = new MeshClient(state);
        PipedInputStream toClient = new PipedInputStream(1 << 16);
        PipedOutputStream radioOut = new PipedOutputStream(toClient);
        PipedInputStream radioIn = new PipedInputStream(1 << 16);
        PipedOutputStream toRadio = new PipedOutputStream(radioIn);
        int me = 0x11223344;
        byte[] key = {1, 2, 3, 4, 5, 6, 7, 8};
        StringBuilder seen = new StringBuilder();

        // fake radio
        Thread radio = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                ByteArrayOutputStream acc = new ByteArrayOutputStream();
                while (true) {
                    int n = radioIn.read(buf);
                    if (n < 0) return;
                    acc.write(buf, 0, n);
                    byte[] d = acc.toByteArray();
                    int pos = 0;
                    while (pos + 4 <= d.length) {
                        if ((d[pos] & 0xFF) != 0x94) { pos++; continue; }
                        int len = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
                        if (pos + 4 + len > d.length) break;
                        ToRadio tr = ToRadio.parseFrom(Arrays.copyOfRange(d, pos + 4, pos + 4 + len));
                        pos += 4 + len;
                        if (tr.hasWantConfigId()) {
                            radioOut.write(frame(FromRadio.newBuilder().setMyInfo(MyNodeInfo.newBuilder().setMyNodeNum(me)).build()));
                            radioOut.write(frame(FromRadio.newBuilder().setChannel(Channel.newBuilder().setIndex(0).setRole(Channel.Role.PRIMARY)
                                .setSettings(ChannelSettings.newBuilder().setPsk(ByteString.copyFrom(new byte[]{1})))).build()));
                            radioOut.write(frame(FromRadio.newBuilder().setConfigCompleteId(tr.getWantConfigId()).build()));
                        } else if (tr.hasPacket() && tr.getPacket().getDecoded().getPortnum() == PortNum.ADMIN_APP) {
                            AdminMessage am = AdminMessage.parseFrom(tr.getPacket().getDecoded().getPayload());
                            seen.append(am.getPayloadVariantCase()).append(am.getSessionPasskey().equals(ByteString.copyFrom(key)) ? "(key ok)" : "(no key)").append(' ');
                            if (am.hasGetDeviceMetadataRequest()) {
                                AdminMessage resp = AdminMessage.newBuilder().setSessionPasskey(ByteString.copyFrom(key))
                                    .setGetDeviceMetadataResponse(DeviceMetadata.newBuilder().setFirmwareVersion("2.7.9")).build();
                                radioOut.write(frame(FromRadio.newBuilder().setPacket(MeshPacket.newBuilder().setFrom(me).setTo(me)
                                    .setDecoded(Data.newBuilder().setPortnum(PortNum.ADMIN_APP).setPayload(resp.toByteString()))).build()));
                            }
                        }
                        radioOut.flush();
                    }
                    acc.reset();
                    acc.write(d, pos, d.length - pos);
                }
            } catch (IOException e) { }
        });
        radio.setDaemon(true);
        radio.start();

        client.connectStreams(toClient, toRadio, "fake", null);
        Thread.sleep(500);
        System.out.println("config complete=" + state.configComplete() + " channels=" + state.allChannels().size());
        client.setOwner("New Name", "NEW");
        client.setChannel(Channel.newBuilder().setIndex(1).setRole(Channel.Role.SECONDARY)
            .setSettings(ChannelSettings.newBuilder().setName("Private").setPsk(ByteString.copyFrom(new byte[32]))).build());
        Thread.sleep(300);
        System.out.println("radio saw: " + seen);
        System.out.println("firmware=" + state.firmware() + " channels now=" + state.allChannels().size() + " ch1=" + state.allChannels().get(1).getSettings().getName());
        client.disconnect();
        System.exit(0);
    }
}
