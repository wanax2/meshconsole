package meshconsole.ui;

import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import meshconsole.mesh.SignalSample;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.TelemetryProtos.LocalStats;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

class StatusPanel extends JPanel {
    private final MeshState state;
    private final JTextArea info = new JTextArea(12, 40);
    private final SignalChart chart = new SignalChart();
    private final JComboBox<NodeChoice> chartNode = new JComboBox<>();
    private final JTextArea log = new JTextArea();
    private final JLabel lastSignal = new JLabel(" ");

    record NodeChoice(int num, String label) {
        @Override public String toString() { return label; }
    }

    StatusPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));

        info.setEditable(false);
        info.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane infoScroll = new JScrollPane(info);
        infoScroll.setBorder(BorderFactory.createTitledBorder("Radio"));

        JPanel chartBox = new JPanel(new BorderLayout(4, 4));
        chartBox.setBorder(BorderFactory.createTitledBorder("Live signal (received packets)"));
        JPanel chartTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        chartTop.add(new JLabel("Show:"));
        chartNode.addItem(new NodeChoice(0, "All nodes"));
        chartNode.addActionListener(e -> refreshChart());
        chartTop.add(chartNode);
        chartTop.add(lastSignal);
        chartBox.add(chartTop, BorderLayout.NORTH);
        chartBox.add(chart, BorderLayout.CENTER);

        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, infoScroll, chartBox);
        top.setResizeWeight(0.4);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Device / app log"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, logScroll);
        split.setResizeWeight(0.55);
        add(split, BorderLayout.CENTER);

        state.addListener(new MeshState.Listener() {
            @Override public void onStatusChanged() { SwingUtilities.invokeLater(StatusPanel.this::refreshInfo); }
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(StatusPanel.this::refreshAll); }
            @Override public void onLog(String line) { SwingUtilities.invokeLater(() -> appendLog(line)); }
        });
    }

    void appendLog(String line) {
        log.append(Fmt.time(System.currentTimeMillis()) + "  " + line + "\n");
        if (log.getDocument().getLength() > 200_000) {
            try { log.getDocument().remove(0, 50_000); } catch (Exception ignored) { }
        }
        log.setCaretPosition(log.getDocument().getLength());
    }

    void refreshAll() {
        refreshInfo();
        refreshNodeChoices();
        refreshChart();
    }

    private void refreshNodeChoices() {
        NodeChoice sel = (NodeChoice) chartNode.getSelectedItem();
        int selNum = sel == null ? 0 : sel.num();
        List<NodeEntry> nodes = state.nodes();
        List<NodeChoice> choices = new ArrayList<>();
        choices.add(new NodeChoice(0, "All nodes"));
        int my = state.myNodeNum();
        for (NodeEntry n : nodes) if (n.num != my) choices.add(new NodeChoice(n.num, n.displayName()));
        boolean same = choices.size() == chartNode.getItemCount();
        if (same) for (int i = 0; i < choices.size(); i++) if (!choices.get(i).equals(chartNode.getItemAt(i))) { same = false; break; }
        if (same) return;
        chartNode.removeAllItems();
        for (NodeChoice c : choices) {
            chartNode.addItem(c);
            if (c.num() == selNum) chartNode.setSelectedItem(c);
        }
    }

    void refreshChart() {
        NodeChoice sel = (NodeChoice) chartNode.getSelectedItem();
        List<SignalSample> s = state.signalHistory();
        chart.setData(s, sel == null ? 0 : sel.num());
        SignalSample last = null;
        for (int i = s.size() - 1; i >= 0; i--) {
            if (sel == null || sel.num() == 0 || s.get(i).from() == sel.num()) { last = s.get(i); break; }
        }
        if (last == null) lastSignal.setText(" ");
        else lastSignal.setText(String.format("  last: %s  %d dBm / %.2f dB  (%s)",
                state.nodeName(last.from()), last.rssi(), last.snr(), Fmt.ago(last.time())));
    }

    void refreshInfo() {
        StringBuilder sb = new StringBuilder();
        NodeEntry me = state.myNode();
        if (state.myNodeNum() == 0) {
            sb.append("Not connected / waiting for radio…\n");
        } else {
            sb.append("My node:      ").append(me != null ? me.displayName() : "").append("  ")
              .append(String.format("!%08x", state.myNodeNum())).append('\n');
            if (me != null) {
                sb.append("Hardware:     ").append(me.hwModel).append('\n');
                if (me.battery >= 0) {
                    sb.append("Battery:      ").append(me.battery > 100 ? "external power" : me.battery + " %")
                      .append(String.format("  (%.2f V)", me.voltage)).append('\n');
                }
                sb.append("Channel util: ").append(String.format("%.1f %%   air-util TX %.2f %%", me.channelUtil, me.airUtilTx)).append('\n');
                if (me.hasPosition) sb.append("Position:     ").append(String.format("%.5f, %.5f  alt %d m", me.lat, me.lon, me.altitude)).append('\n');
            }
            sb.append("Firmware:     ").append(state.firmware()).append('\n');
            Config.LoRaConfig lora = state.lora();
            if (lora != null) {
                sb.append("LoRa:         ").append(lora.getRegion()).append("  ")
                  .append(lora.getUsePreset() ? lora.getModemPreset().name()
                          : "BW " + lora.getBandwidth() + " SF" + lora.getSpreadFactor() + " CR" + lora.getCodingRate())
                  .append("  txpwr ").append(lora.getTxPower()).append(" dBm  hops ").append(lora.getHopLimit()).append('\n');
                if (lora.getOverrideFrequency() != 0) sb.append("Frequency:    override ").append(lora.getOverrideFrequency()).append(" MHz\n");
                if (lora.getChannelNum() != 0) sb.append("Slot:         channel_num ").append(lora.getChannelNum()).append('\n');
            }
            List<Channel> chs = state.channels();
            if (!chs.isEmpty()) {
                sb.append("Channels:     ");
                for (Channel c : chs) {
                    String name = c.getSettings().getName().isEmpty() ? (c.getIndex() == 0 ? "(default)" : "?") : c.getSettings().getName();
                    sb.append(c.getIndex()).append('=').append(name).append(c.getRole() == Channel.Role.PRIMARY ? "*" : "").append("  ");
                }
                sb.append('\n');
            }
            LocalStats ls = state.localStats();
            if (ls != null) {
                sb.append(String.format("Local stats:  rx %d (bad %d, dupe %d)  tx %d  relayed %d  online %d/%d nodes\n",
                        ls.getNumPacketsRx(), ls.getNumPacketsRxBad(), ls.getNumRxDupe(), ls.getNumPacketsTx(),
                        ls.getNumTxRelay(), ls.getNumOnlineNodes(), ls.getNumTotalNodes()));
                if (ls.getNoiseFloor() != 0) sb.append("Noise floor:  ").append(ls.getNoiseFloor()).append(" dBm\n");
                sb.append("Uptime:       ").append(ls.getUptimeSeconds() / 3600).append(" h ").append((ls.getUptimeSeconds() % 3600) / 60).append(" m\n");
            }
            if (state.queueFree() >= 0) sb.append("TX queue:     ").append(state.queueFree()).append(" free slots\n");
            sb.append("Nodes known:  ").append(state.nodes().size()).append('\n');
            if (state.encryptedSeen() > 0) sb.append("Undecodable:  ").append(state.encryptedSeen()).append(" encrypted packets\n");
            sb.append(state.configComplete() ? "" : "\n(config still loading…)\n");
        }
        info.setText(sb.toString());
    }
}
