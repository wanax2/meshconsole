package meshconsole.ui;

import com.google.protobuf.ByteString;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import meshconsole.mqtt.MqttProxy;
import org.meshtastic.proto.ChannelProtos.Channel;
import org.meshtastic.proto.ChannelProtos.ChannelSettings;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

class SettingsPanel extends JPanel {
    private final MeshClient client;
    private final MeshState state;
    private MqttProxy proxy;

    // owner
    private final JTextField longName = new JTextField(20), shortName = new JTextField(5);
    // lora
    private final JComboBox<Config.LoRaConfig.RegionCode> region = new JComboBox<>();
    private final JComboBox<Config.LoRaConfig.ModemPreset> preset = new JComboBox<>();
    private final JSpinner hopLimit = new JSpinner(new SpinnerNumberModel(3, 1, 7, 1));
    private final JSpinner txPower = new JSpinner(new SpinnerNumberModel(0, 0, 40, 1));
    private final JTextField overrideFreq = new JTextField(7);
    private final JCheckBox txEnabled = new JCheckBox("TX enabled");
    private final JSpinner freqSlot = new JSpinner(new SpinnerNumberModel(0, 0, 255, 1));
    private final JCheckBox okToMqtt = new JCheckBox("OK to MQTT"), ignoreMqtt = new JCheckBox("Ignore MQTT");
    // device
    private final JComboBox<Config.DeviceConfig.Role> role = new JComboBox<>();
    private final JTextField posLat = new JTextField(10), posLon = new JTextField(10), posAlt = new JTextField(5);
    private final JCheckBox wifiOn = new JCheckBox("WiFi enabled");
    private final JTextField wifiSsid = new JTextField(16), ntp = new JTextField(14);
    private final JPasswordField wifiPsk = new JPasswordField(14);
    private final JCheckBox ethOn = new JCheckBox("Ethernet enabled");
    private final JLabel wifiNote = new JLabel(" ");
    private final JCheckBox debugLogApi = new JCheckBox("Stream firmware debug log to this app (security.debug_log_api_enabled)");
    private final JCheckBox paFanOff = new JCheckBox("PA fan off"), rxBoost = new JCheckBox("Chip RX boost (+2 dB)");
    private final JCheckBox powerTelemetry = new JCheckBox("Report supply voltage/current (INA219 power telemetry)");
    // mqtt
    private final JCheckBox mqttEnabled = new JCheckBox("MQTT module enabled");
    private final JTextField mqttAddress = new JTextField(22), mqttUser = new JTextField(12), mqttRoot = new JTextField(10);
    private final JPasswordField mqttPass = new JPasswordField(12);
    private final JCheckBox mqttEncrypt = new JCheckBox("Encryption"), mqttJson = new JCheckBox("JSON"), mqttTls = new JCheckBox("TLS"),
            mqttProxy = new JCheckBox("Proxy to client (needed for RAK/nRF52 – no WiFi)"), mqttMap = new JCheckBox("Map reporting");
    private final JButton proxyBtn = new JButton("Start MQTT proxy");
    private final JLabel proxyStatus = new JLabel("Stopped");
    // channels
    private final ChannelModel chModel = new ChannelModel();
    private final JTable chTable = new JTable(chModel);
    private final JLabel loaded = new JLabel("Settings not loaded – connect to a radio.");

    SettingsPanel(MeshClient client) {
        this.client = client;
        this.state = client.state();
        this.proxy = new MqttProxy(client, s -> SwingUtilities.invokeLater(() -> {
            proxyStatus.setText(s);
            proxyBtn.setText(proxy != null && proxy.isRunning() ? "Stop MQTT proxy" : "Start MQTT proxy");
        }));
        setLayout(new BorderLayout());

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JPanel head = row();
        head.add(loaded);
        JButton reload = new JButton("Reload from radio");
        reload.addActionListener(e -> populate());
        head.add(reload);
        JButton reboot = new JButton("Reboot radio");
        reboot.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Reboot the radio now?", "Reboot", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
                run(() -> client.reboot(2));
        });
        head.add(reboot);
        box.add(head);

        // ---- owner
        JPanel owner = section("Owner");
        owner.add(new JLabel("Long name:")); owner.add(longName);
        owner.add(new JLabel("Short (≤4):")); owner.add(shortName);
        owner.add(button("Save owner", () -> client.setOwner(longName.getText().trim(), shortName.getText().trim())));
        box.add(owner);

        // ---- lora
        JPanel lora = section("LoRa");
        for (Config.LoRaConfig.RegionCode r : Config.LoRaConfig.RegionCode.values()) if (r != Config.LoRaConfig.RegionCode.UNRECOGNIZED) region.addItem(r);
        for (Config.LoRaConfig.ModemPreset p : Config.LoRaConfig.ModemPreset.values()) if (p != Config.LoRaConfig.ModemPreset.UNRECOGNIZED) preset.addItem(p);
        lora.add(new JLabel("Region:")); lora.add(region);
        lora.add(new JLabel("Preset:")); lora.add(preset);
        lora.add(new JLabel("Hop limit:")); lora.add(hopLimit);
        lora.add(new JLabel("Frequency slot (0 = preset default):")); lora.add(freqSlot);
        freqSlot.setToolTipText("LoRa channel number. 0 = the default slot for the preset (LongFast US = slot 20). Regional meshes may use another slot, e.g. NoVa-Mesh = 9. Nodes on different slots cannot hear each other.");
        lora.add(okToMqtt); lora.add(ignoreMqtt);
        JPanel lora2 = section(null);
        lora2.add(new JLabel("TX power dBm (0=max):")); lora2.add(txPower);
        lora2.add(new JLabel("Override MHz (0=off):")); lora2.add(overrideFreq);
        lora2.add(txEnabled);
        lora2.add(paFanOff); lora2.add(rxBoost);
        paFanOff.setToolTipText("lora.pa_fan_disabled – the PA fan on Station G2/G3 and T-Beam 1W");
        rxBoost.setToolTipText("lora.sx126x_rx_boosted_gain – about +2 dB from the SX1262's own LNA, a few mA more. With an external LNA in front (G2, G3, 1W) it adds little and costs linearity next to a strong interferer; MeshCore's G3 build turns it off.");
        lora2.add(button("Save LoRa", () -> {
            Config base = state.config(Config.PayloadVariantCase.LORA);
            if (base == null) throw new IOException("LoRa settings have not been received from this radio yet – wait for the config to load (or press Reload from radio), then save.");
            Config.LoRaConfig.Builder b = base == null ? Config.LoRaConfig.newBuilder() : base.getLora().toBuilder();
            b.setUsePreset(true)
             .setRegion((Config.LoRaConfig.RegionCode) region.getSelectedItem())
             .setModemPreset((Config.LoRaConfig.ModemPreset) preset.getSelectedItem())
             .setHopLimit((Integer) hopLimit.getValue())
             .setChannelNum((Integer) freqSlot.getValue())
             .setConfigOkToMqtt(okToMqtt.isSelected())
             .setIgnoreMqtt(ignoreMqtt.isSelected())
             .setTxPower((Integer) txPower.getValue())
             .setTxEnabled(txEnabled.isSelected())
             .setPaFanDisabled(paFanOff.isSelected())
             .setSx126XRxBoostedGain(rxBoost.isSelected());
            try { b.setOverrideFrequency(Float.parseFloat(overrideFreq.getText().trim())); } catch (NumberFormatException e) { b.setOverrideFrequency(0); }
            client.setConfig(Config.newBuilder().setLora(b).build());
        }));
        box.add(lora);
        box.add(lora2);

        // ---- device
        JPanel dev = section("Device");
        for (Config.DeviceConfig.Role r : Config.DeviceConfig.Role.values()) if (r != Config.DeviceConfig.Role.UNRECOGNIZED) role.addItem(r);
        dev.add(new JLabel("Role:")); dev.add(role);
        dev.add(button("Save device", () -> {
            Config base = state.config(Config.PayloadVariantCase.DEVICE);
            if (base == null) throw new IOException("Device settings have not been received from this radio yet.");
            Config.DeviceConfig.Builder b = base == null ? Config.DeviceConfig.newBuilder() : base.getDevice().toBuilder();
            b.setRole((Config.DeviceConfig.Role) role.getSelectedItem());
            client.setConfig(Config.newBuilder().setDevice(b).build());
        }));
        box.add(dev);

        JPanel net = section("WiFi / network (ESP32 boards: Station G2, Heltec, T-Deck…)");
        net.add(wifiOn); net.add(new JLabel("SSID:")); net.add(wifiSsid); net.add(new JLabel("Password:")); net.add(wifiPsk);
        net.add(ethOn); net.add(new JLabel("NTP:")); net.add(ntp);
        net.add(button("Save network", () -> {
            Config base = state.config(Config.PayloadVariantCase.NETWORK);
            if (base == null) throw new IOException("Network settings have not been received from this radio yet (nRF52 boards have none).");
            Config.NetworkConfig.Builder b = base == null ? Config.NetworkConfig.newBuilder() : base.getNetwork().toBuilder();
            b.setWifiEnabled(wifiOn.isSelected()).setWifiSsid(wifiSsid.getText().trim()).setEthEnabled(ethOn.isSelected());
            String pw = new String(wifiPsk.getPassword());
            if (!pw.isEmpty() && !pw.equals("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022")) b.setWifiPsk(pw);
            if (!ntp.getText().isBlank()) b.setNtpServer(ntp.getText().trim());
            client.setConfig(Config.newBuilder().setNetwork(b).build());
        }));
        box.add(net);
        JPanel netNote = section(null);
        netNote.add(wifiNote);
        box.add(netNote);

        JPanel posP = section("Fixed position (for nodes without GPS)");
        posP.add(new JLabel("Latitude:")); posP.add(posLat);
        posP.add(new JLabel("Longitude:")); posP.add(posLon);
        posP.add(new JLabel("Altitude m:")); posP.add(posAlt);
        posP.add(button("Set fixed position", () -> {
            double lat, lon; int alt;
            try { lat = Double.parseDouble(posLat.getText().trim()); lon = Double.parseDouble(posLon.getText().trim()); alt = posAlt.getText().isBlank() ? 0 : (int) Double.parseDouble(posAlt.getText().trim()); }
            catch (NumberFormatException e) { throw new IOException("Enter decimal degrees, e.g. 38.8895 and -77.0353"); }
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new IOException("Latitude must be -90..90 and longitude -180..180");
            client.setFixedPosition(lat, lon, alt);
        }));
        posP.add(button("Remove fixed position", client::removeFixedPosition));
        box.add(posP);
        JPanel posNote = section(null);
        posNote.add(new JLabel("Tip: right-click the Map and choose \"Set as my fixed position\" to pick the spot visually. The radio advertises this position to the mesh and uses it for distances, coverage and weather-station lookup."));
        box.add(posNote);

        JPanel sec = section("Logging");
        sec.add(debugLogApi);
        sec.add(button("Save logging", () -> {
            Config base = state.config(Config.PayloadVariantCase.SECURITY);
            Config.SecurityConfig.Builder b = base == null ? Config.SecurityConfig.newBuilder() : base.getSecurity().toBuilder();
            b.setDebugLogApiEnabled(debugLogApi.isSelected());
            client.setConfig(Config.newBuilder().setSecurity(b).build());
        }));
        box.add(sec);

        JPanel moved = section("Radio-specific settings");
        moved.add(new JLabel("<html>Transmit power checked against your antenna, LNA, chip RX boost, PA fan, role, the Station G3's jumpers or FEM firmware, "
                + "the supply monitor and firmware are on the <b>radio's own tab</b> (titled after the radio, e.g. <b>Station G3</b>, next to Status &amp; signal).</html>"));
        box.add(moved);

        JPanel pw = section("Power monitor & radio control");
        pw.add(powerTelemetry);
        pw.add(button("Save power telemetry", () -> {
            ModuleConfig base = state.moduleConfig(ModuleConfig.PayloadVariantCase.TELEMETRY);
            ModuleConfig.TelemetryConfig.Builder b = base == null ? ModuleConfig.TelemetryConfig.newBuilder() : base.getTelemetry().toBuilder();
            b.setPowerMeasurementEnabled(powerTelemetry.isSelected()); if (b.getPowerUpdateInterval() == 0) b.setPowerUpdateInterval(300);
            client.setModuleConfig(ModuleConfig.newBuilder().setTelemetry(b).build());
            state.emitLog("Power telemetry " + (powerTelemetry.isSelected() ? "enabled (INA219 on I2C, every 5 min; shows on Status as Supply)" : "disabled"));
        }));
        pw.add(new JLabel("<html><i>Station G3: the INA219 on the motherboard reports the supply rail – watch the current on transmit to check the supply is big enough. the TX enabled box off in the LoRa row makes a radio receive-only (a pure listener never needs an EIRP calculation and never desenses the radio next to it).</i></html>"));
        box.add(pw);

        JPanel ra = section("Remote admin (who may configure THIS radio over the mesh)");
        ra.add(new JLabel("<html>Pick one of your other nodes; its public key is added to this radio's security.admin_key. Afterwards, connect to that other node and use the config editor's <b>Target</b> selector to edit this radio from there.</html>"));
        JComboBox<String> manager = new JComboBox<>();
        JButton refreshMgr = new JButton("↻"); refreshMgr.addActionListener(e -> { manager.removeAllItems(); for (NodeEntry n : state.nodes()) if (n.num != state.myNodeNum() && n.publicKey.length > 0 && (meshconsole.mesh.MeshState.isMine(n.num) || n.isFavorite)) manager.addItem(n.displayName() + " (" + n.idString() + ")"); if (manager.getItemCount() == 0) manager.addItem("(no other node of yours with a known key – mark one as mine on the Nodes tab first)"); });
        refreshMgr.doClick();
        ra.add(new JLabel("Manager node:")); ra.add(manager); ra.add(refreshMgr);
        ra.add(button("Allow this node to manage me", () -> {
            String sel = String.valueOf(manager.getSelectedItem()); int i = sel.lastIndexOf("(!"); if (i < 0) return;
            int num = (int) Long.parseLong(sel.substring(i + 2, sel.length() - 1), 16);
            NodeEntry n = state.node(num); if (n == null || n.publicKey.length == 0) return;
            Config base = state.config(Config.PayloadVariantCase.SECURITY);
            Config.SecurityConfig.Builder b = base == null ? Config.SecurityConfig.newBuilder() : base.getSecurity().toBuilder();
            com.google.protobuf.ByteString key = com.google.protobuf.ByteString.copyFrom(n.publicKey);
            if (!b.getAdminKeyList().contains(key)) {
                java.util.List<com.google.protobuf.ByteString> keys = new java.util.ArrayList<>(b.getAdminKeyList());
                while (keys.size() >= 3) keys.remove(0);   // the firmware holds at most three admin keys
                keys.add(key);
                b.clearAdminKey(); b.addAllAdminKey(keys);
            }
            client.setConfig(Config.newBuilder().setSecurity(b).build());
            state.emitLog("Admin key added: " + n.displayName() + " may now configure this radio remotely");
        }));
        box.add(ra);

        // ---- mqtt
        JPanel mq = section("MQTT module (stored on the radio)");
        mq.add(mqttEnabled);
        mq.add(new JLabel("Broker host[:port]:")); mq.add(mqttAddress);
        mq.add(new JLabel("User:")); mq.add(mqttUser);
        mq.add(new JLabel("Password:")); mq.add(mqttPass);
        mq.add(new JLabel("Root topic:")); mq.add(mqttRoot);
        JPanel mq2 = section(null);
        mq2.add(mqttEncrypt); mq2.add(mqttJson); mq2.add(mqttTls); mq2.add(mqttMap); mq2.add(mqttProxy);
        mq2.add(button("Save MQTT", () -> {
            ModuleConfig base = state.moduleConfig(ModuleConfig.PayloadVariantCase.MQTT);
            if (base == null) throw new IOException("MQTT settings have not been received from this radio yet.");
            ModuleConfig.MQTTConfig.Builder b = base == null ? ModuleConfig.MQTTConfig.newBuilder() : base.getMqtt().toBuilder();
            b.setEnabled(mqttEnabled.isSelected()).setAddress(mqttAddress.getText().trim()).setUsername(mqttUser.getText().trim())
             .setPassword(new String(mqttPass.getPassword())).setRoot(mqttRoot.getText().trim())
             .setEncryptionEnabled(mqttEncrypt.isSelected()).setJsonEnabled(mqttJson.isSelected()).setTlsEnabled(mqttTls.isSelected())
             .setProxyToClientEnabled(mqttProxy.isSelected()).setMapReportingEnabled(mqttMap.isSelected());
            client.setModuleConfig(ModuleConfig.newBuilder().setMqtt(b).build());
        }));
        JPanel mq3 = section(null);
        mq3.add(new JLabel("Client proxy (this PC relays the radio's MQTT traffic over its internet):"));
        mq3.add(proxyBtn);
        mq3.add(proxyStatus);
        // The proxy remembers whether it was on (per data folder) and starts itself once the radio's config has loaded,
        // so an unattended station keeps uplinking after reboots and updates.
        final java.util.prefs.Preferences pp = java.util.prefs.Preferences.userRoot().node("meshconsole-mqttproxy-" + Integer.toHexString(meshconsole.DataDir.get().toString().toLowerCase().hashCode()));
        proxyBtn.addActionListener(e -> {
            if (proxy.isRunning()) { proxy.close(); pp.putBoolean("auto", false); }
            else { pp.putBoolean("auto", true); run(proxy::start); }
        });
        state.addListener(new MeshState.Listener() {
            private boolean tried;
            @Override public void onStatusChanged() {
                if (!state.configComplete()) { tried = false; return; }
                // 1.26.1: the radio's MQTT settings changed (new server, password, proxy switched off…) – follow them
                if (proxy.isRunning() && state.configComplete() && !MqttProxy.currentKey(state).isEmpty() && !proxy.startedWith().equals(MqttProxy.currentKey(state))) {
                    String now = MqttProxy.currentKey(state);
                    proxy.close();
                    if (now.startsWith("true|true|")) {
                        state.emitLog("MQTT proxy: radio settings changed – reconnecting");
                        new Thread(() -> { try { Thread.sleep(2000); proxy.start(); } catch (Exception ex) { state.emitLog("MQTT proxy reconnect failed: " + ex.getMessage()); } }, "mqtt-proxy-restart").start();
                    } else state.emitLog("MQTT proxy: switched off on the radio – stopped");
                    return;
                }
                if (tried || !pp.getBoolean("auto", false) || proxy.isRunning()) return;
                tried = true;
                new Thread(() -> { try { Thread.sleep(3000); if (!proxy.isRunning()) proxy.start(); } catch (Exception ex) { state.emitLog("MQTT proxy auto-start failed: " + ex.getMessage()); } }, "mqtt-proxy-auto").start();
            }
        });
        // 1.29.2: keep the proxy up. Before, it started once per radio connection; when the broker connection dropped
        // (weak WiFi: "Connection timed out") or the broker wasn't reachable at start, it stayed down until the next
        // restart of Mesh Console – at the lake that was days. Now, while it is meant to be on and the radio is
        // connected with MQTT + proxy enabled, a stopped proxy is started again every minute until it works.
        java.util.Timer proxyWatchdog = new java.util.Timer("mqtt-proxy-watchdog", true);
        proxyWatchdog.scheduleAtFixedRate(new java.util.TimerTask() {
            private int fails;
            @Override public void run() {
                try {
                    if (!pp.getBoolean("auto", false) || proxy.isRunning() || !client.isConnected() || !state.configComplete()) { if (proxy.isRunning()) fails = 0; return; }
                    if (!MqttProxy.currentKey(state).startsWith("true|true|")) return;
                    try {
                        proxy.start();
                        state.emitLog("MQTT proxy: reconnected" + (fails > 0 ? " after " + (fails + 1) + " attempts" : ""));
                        fails = 0;
                    } catch (Exception ex) {
                        if (fails++ % 30 == 0) state.emitLog("MQTT proxy: can't reach the broker (" + ex.getMessage() + ") – retrying every minute");
                    }
                } catch (Exception ignored) { }
            }
        }, 60_000L, 60_000L);
        box.add(mq);
        box.add(mq2);
        box.add(mq3);

        // ---- channels
        JPanel chHead = section("Channels (edit cells, then Save selected)");
        chHead.add(button("Save selected channel", () -> {
            int r = chTable.getSelectedRow();
            if (r < 0) throw new IOException("Select a channel row first");
            if (chTable.isEditing()) chTable.getCellEditor().stopCellEditing();
            client.setChannel(chModel.rows.get(r).toChannel());
        }));
        JButton rnd = new JButton("Random 256-bit key → selected");
        rnd.addActionListener(e -> {
            int r = chTable.getSelectedRow();
            if (r < 0) return;
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            chModel.rows.get(r).psk = Base64.getEncoder().encodeToString(k);
            chModel.fireTableRowsUpdated(r, r);
        });
        chHead.add(rnd);
        chHead.add(new JLabel("PSK is base64; \"AQ==\" = default key, empty = no encryption"));
        box.add(chHead);
        chTable.setRowHeight(20);
        chTable.setPreferredScrollableViewportSize(new Dimension(800, 8 * 20 + 4));
        JComboBox<Channel.Role> roleEditor = new JComboBox<>(new Channel.Role[]{Channel.Role.DISABLED, Channel.Role.PRIMARY, Channel.Role.SECONDARY});
        chTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(roleEditor));
        chTable.getColumnModel().getColumn(0).setMaxWidth(40);
        chTable.getColumnModel().getColumn(1).setPreferredWidth(110); chTable.getColumnModel().getColumn(1).setMaxWidth(140);
        chTable.getColumnModel().getColumn(2).setPreferredWidth(140); chTable.getColumnModel().getColumn(2).setMaxWidth(220);
        chTable.getColumnModel().getColumn(3).setMaxWidth(70); chTable.getColumnModel().getColumn(4).setMaxWidth(80);
        chTable.getColumnModel().getColumn(5).setPreferredWidth(320);
        JScrollPane chScroll = new JScrollPane(chTable);
        chScroll.setAlignmentX(LEFT_ALIGNMENT);
        box.add(chScroll);
        box.add(Box.createVerticalGlue());

        JTabbedPane sub = new JTabbedPane();
        sub.addTab("Common", new JScrollPane(box));
        sub.addTab("All settings", new ConfigEditorPanel(client));
        add(sub, BorderLayout.CENTER);

        state.addListener(new MeshState.Listener() {
            @Override public void onStatusChanged() {
                SwingUtilities.invokeLater(SettingsPanel.this::populateIfFresh);
            }
        });
    }

    private boolean populated;



    private int populatedFrom;   // node number the fields were last filled from

    /** Re-reads every field from the radio's current config (called after another tab saved settings). */
    public void refreshFromRadio() { populated = false; populateIfFresh(); }

    private void populateIfFresh() {
        int now = state.myNodeNum();
        if (now != populatedFrom) { populated = false; populatedFrom = now; }   // a different radio (or a reconnect) – the old values are not this radio's
        if (!state.configComplete()) { populated = false; if (now != 0) loaded.setText("Loading settings from " + state.nodeName(now) + "…"); else loaded.setText("Settings not loaded – connect to a radio."); return; }   // re-read after every reconnect (e.g. the reboot that follows a write in All settings)
        if (!populated) populate();
    }

    void populate() {
        if (state.myNodeNum() == 0) { loaded.setText("Settings not loaded – connect to a radio."); return; }
        populated = state.config(Config.PayloadVariantCase.LORA) != null;   // keep re-populating until the LoRa section has actually arrived
        loaded.setText("Loaded from " + state.nodeName(state.myNodeNum()) + ".");
        { ModuleConfig t = state.moduleConfig(ModuleConfig.PayloadVariantCase.TELEMETRY); if (t != null) powerTelemetry.setSelected(t.getTelemetry().getPowerMeasurementEnabled()); }
        NodeEntry me = state.myNode();
        if (me != null) {
            longName.setText(me.longName); shortName.setText(me.shortName);
            if (me.hasPosition) { posLat.setText(String.format(java.util.Locale.ROOT, "%.5f", me.lat)); posLon.setText(String.format(java.util.Locale.ROOT, "%.5f", me.lon)); posAlt.setText(String.valueOf(me.altitude)); }
        }
        Config lc = state.config(Config.PayloadVariantCase.LORA);
        if (lc != null) {
            Config.LoRaConfig l = lc.getLora();
            region.setSelectedItem(l.getRegion());
            preset.setSelectedItem(l.getModemPreset());
            hopLimit.setValue(Math.max(1, Math.min(7, l.getHopLimit() == 0 ? 3 : l.getHopLimit())));
            txPower.setValue(Math.max(0, Math.min(40, l.getTxPower())));
            overrideFreq.setText(l.getOverrideFrequency() == 0 ? "0" : String.valueOf(l.getOverrideFrequency()));
            txEnabled.setSelected(l.getTxEnabled()); paFanOff.setSelected(l.getPaFanDisabled()); rxBoost.setSelected(l.getSx126XRxBoostedGain());
            freqSlot.setValue(l.getChannelNum());
            okToMqtt.setSelected(l.getConfigOkToMqtt());
            ignoreMqtt.setSelected(l.getIgnoreMqtt());
        }
        Config dc = state.config(Config.PayloadVariantCase.DEVICE);
        if (dc != null) role.setSelectedItem(dc.getDevice().getRole());
        Config nc = state.config(Config.PayloadVariantCase.NETWORK);
        if (nc != null) {
            Config.NetworkConfig n = nc.getNetwork();
            wifiOn.setSelected(n.getWifiEnabled()); wifiSsid.setText(n.getWifiSsid()); wifiPsk.setText(n.getWifiPsk().isEmpty() ? "" : "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022");
            ethOn.setSelected(n.getEthEnabled()); ntp.setText(n.getNtpServer());
            wifiNote.setText(n.getWifiEnabled() ? "WiFi is on. The radio reboots after saving; once it has joined, connect with TCP… using the IP your router (or the phone app) shows for it. Bluetooth is disabled while WiFi is on."
                                              : "Turn WiFi on and the radio can be reached with TCP… (port 4403) instead of USB – no cable, and it can sit by the antenna.");
        } else wifiNote.setText("Network settings not received – this radio may not have WiFi (nRF52 boards such as the RAK4631 don't).");
        Config sc = state.config(Config.PayloadVariantCase.SECURITY);
        if (sc != null) debugLogApi.setSelected(sc.getSecurity().getDebugLogApiEnabled());
        ModuleConfig mc = state.moduleConfig(ModuleConfig.PayloadVariantCase.MQTT);
        if (mc != null) {
            ModuleConfig.MQTTConfig m = mc.getMqtt();
            mqttEnabled.setSelected(m.getEnabled());
            mqttAddress.setText(m.getAddress());
            mqttUser.setText(m.getUsername());
            mqttPass.setText(m.getPassword());
            mqttRoot.setText(m.getRoot());
            mqttEncrypt.setSelected(m.getEncryptionEnabled());
            mqttJson.setSelected(m.getJsonEnabled());
            mqttTls.setSelected(m.getTlsEnabled());
            mqttProxy.setSelected(m.getProxyToClientEnabled());
            mqttMap.setSelected(m.getMapReportingEnabled());
        }
        chModel.load(state.allChannels());
    }

    void onDisconnected() {
        populated = false;
        if (proxy.isRunning()) proxy.close();
    }

    // ---- helpers ---------------------------------------------------------

    private interface Action { void run() throws IOException; }

    private void run(Action a) {
        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(this, "Connect to the radio first.", "Not connected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // admin round-trips wait for a session key; keep the UI responsive
        new Thread(() -> {
            try { a.run(); }
            catch (Exception e) { SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE)); }
        }, "admin-op").start();
    }

    private JButton button(String label, Action a) {
        JButton b = new JButton(label);
        b.addActionListener(e -> run(a));
        return b;
    }

    private static JPanel row() {
        JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 3));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel section(String title) {
        JPanel p = row();
        if (title != null) p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    /** Editable channel row. */
    static class ChRow {
        int index; Channel.Role role; String name; String psk; boolean uplink, downlink; Channel original;

        static ChRow of(Channel c) {
            ChRow r = new ChRow();
            r.index = c.getIndex(); r.role = c.getRole(); r.name = c.getSettings().getName();
            r.psk = Base64.getEncoder().encodeToString(c.getSettings().getPsk().toByteArray());
            r.uplink = c.getSettings().getUplinkEnabled(); r.downlink = c.getSettings().getDownlinkEnabled();
            r.original = c;
            return r;
        }

        Channel toChannel() throws IOException {
            byte[] key;
            try { key = psk.isBlank() ? new byte[0] : Base64.getDecoder().decode(psk.trim()); }
            catch (IllegalArgumentException e) { throw new IOException("PSK must be base64 (e.g. AQ== for the default key)"); }
            if (key.length != 0 && key.length != 1 && key.length != 16 && key.length != 32)
                throw new IOException("PSK must be empty, 1 byte (preset index), 16 or 32 bytes");
            ChannelSettings.Builder s = original == null ? ChannelSettings.newBuilder() : original.getSettings().toBuilder();
            s.setName(name == null ? "" : name.trim()).setPsk(ByteString.copyFrom(key)).setUplinkEnabled(uplink).setDownlinkEnabled(downlink);
            return Channel.newBuilder().setIndex(index).setRole(role).setSettings(s).build();
        }
    }

    static class ChannelModel extends AbstractTableModel {
        final String[] cols = {"#", "Role", "Name", "Uplink", "Downlink", "PSK (base64)"};
        List<ChRow> rows = new ArrayList<>();

        void load(List<Channel> chs) {
            rows = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int idx = i;
                Channel c = chs.stream().filter(x -> x.getIndex() == idx).findFirst().orElse(null);
                if (c == null) { ChRow r = new ChRow(); r.index = i; r.role = Channel.Role.DISABLED; r.name = ""; r.psk = ""; rows.add(r); }
                else rows.add(ChRow.of(c));
            }
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public boolean isCellEditable(int r, int c) { return c > 0; }
        @Override public Class<?> getColumnClass(int c) { return c == 3 || c == 4 ? Boolean.class : c == 1 ? Channel.Role.class : Object.class; }
        @Override public Object getValueAt(int r, int c) {
            ChRow x = rows.get(r);
            return switch (c) { case 0 -> x.index; case 1 -> x.role; case 2 -> x.name; case 3 -> x.uplink; case 4 -> x.downlink; case 5 -> x.psk; default -> ""; };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            ChRow x = rows.get(r);
            switch (c) {
                case 1 -> x.role = (Channel.Role) v;
                case 2 -> x.name = String.valueOf(v);
                case 3 -> x.uplink = (Boolean) v;
                case 4 -> x.downlink = (Boolean) v;
                case 5 -> x.psk = String.valueOf(v);
                default -> { }
            }
        }
    }
}
