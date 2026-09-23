package meshconsole.tools;

import org.meshtastic.proto.AppOnlyProtos.ChannelSet;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;
import org.meshtastic.proto.ConfigProtos.Config;

import java.util.Base64;
import java.util.List;

/** https://meshtastic.org/e/#<base64url ChannelSet> import/export. */
public final class ChannelUrl {
    private ChannelUrl() { }

    public static ChannelSet parse(String url) throws Exception {
        String s = url.trim();
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(hash + 1);
        s = s.replace("?add=true", "").replace("&add=true", "");
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        byte[] b = Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
        return ChannelSet.parseFrom(b);
    }

    public static String build(List<Channel> channels, Config.LoRaConfig lora) {
        ChannelSet.Builder cs = ChannelSet.newBuilder();
        for (Channel c : channels) if (c.getRole() != Channel.Role.DISABLED) cs.addSettings(c.getSettings());
        if (lora != null) cs.setLoraConfig(lora);
        return "https://meshtastic.org/e/#" + Base64.getUrlEncoder().withoutPadding().encodeToString(cs.build().toByteArray());
    }

    public static String describe(ChannelSet cs) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (ChannelSettings s : cs.getSettingsList()) {
            sb.append("Channel ").append(i++).append(": ").append(s.getName().isEmpty() ? "(default)" : s.getName()).append("  psk ").append(s.getPsk().size()).append(" B")
              .append(s.getUplinkEnabled() ? " uplink" : "").append(s.getDownlinkEnabled() ? " downlink" : "").append('\n');
        }
        if (cs.hasLoraConfig()) {
            Config.LoRaConfig l = cs.getLoraConfig();
            sb.append("LoRa: ").append(l.getRegion()).append(' ').append(l.getUsePreset() ? l.getModemPreset().name() : "custom").append(" slot ").append(l.getChannelNum()).append(" hops ").append(l.getHopLimit()).append('\n');
        }
        return sb.toString();
    }
}
