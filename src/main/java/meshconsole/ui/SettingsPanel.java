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
    // device
    private final JComboBox<Config.DeviceConfig.Role> role = new JComboBox<>();
    private final JTextField posLat = new JTextField(10), posLon = new JTextField(10), posAlt = new JTextField(5);
    private final JCheckBox debugLogApi = new JCheckBox("Stream firmware debug log to this app (security.debug_log_api_enabled)");
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
        JPanel lora2 = section(null);
        lora2.add(new JLabel("TX power dBm (0=max):")); lora2.add(txPower);
        lora2.add(new JLabel("Override MHz (0=off):")); lora2.add(overrideFreq);
        lora2.add(txEnabled);
        lora2.add(button("Save LoRa", () -> {
            Config base = state.config(Config.PayloadVariantCase.LORA);
            Config.LoRaConfig.Builder b = base == null ? Config.LoRaConfig.newBuilder() : base.getLora().toBuilder();
            b.setUsePreset(true)
             .setRegion((Config.LoRaConfig.RegionCode) region.getSelectedItem())
             .setModemPreset((Config.LoRaConfig.ModemPreset) preset.getSelectedItem())
             .setHopLimit((Integer) hopLimit.getValue())
             .setTxPower((Integer) txPower.getValue())
             .setTxEnabled(txEnabled.isSelected());
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
            Config.DeviceConfig.Builder b = base == null ? Config.DeviceConfig.newBuilder() : base.getDevice().toBuilder();
            b.setRole((Config.DeviceConfig.Role) role.getSelectedItem());
            client.setConfig(Config.newBuilder().setDevice(b).build());
        }));
        box.add(dev);

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
        proxyBtn.addActionListener(e -> {
            if (proxy.isRunning()) proxy.close();
            else run(proxy::start);
        });
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
        chTable.getColumnModel().getColumn(3).setPreferredWidth(320);
        JScrollPane chScroll = new JScrollPane(chTable);
        chScroll.setAlignmentX(LEFT_ALIGNMENT);
        box.add(chScroll);
        box.add(Box.createVerticalGlue());

        add(new JScrollPane(box), BorderLayout.CENTER);

        state.addListener(new MeshState.Listener() {
            @Override public void onStatusChanged() {
                if (state.configComplete()) SwingUtilities.invokeLater(SettingsPanel.this::populateIfFresh);
            }
        });
    }

    private boolean populated;

    private void populateIfFresh() {
        if (!populated) populate();
    }

    void populate() {
        if (state.myNodeNum() == 0) { loaded.setText("Settings not loaded – connect to a radio."); return; }
        populated = true;
        loaded.setText("Loaded from " + state.nodeName(state.myNodeNum()) + ".");
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
            txEnabled.setSelected(l.getTxEnabled());
        }
        Config dc = state.config(Config.PayloadVariantCase.DEVICE);
        if (dc != null) role.setSelectedItem(dc.getDevice().getRole());
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
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
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
        final String[] cols = {"#", "Role", "Name", "PSK (base64)", "Uplink", "Downlink"};
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
        @Override public Class<?> getColumnClass(int c) { return c >= 4 ? Boolean.class : c == 1 ? Channel.Role.class : Object.class; }
        @Override public Object getValueAt(int r, int c) {
            ChRow x = rows.get(r);
            return switch (c) { case 0 -> x.index; case 1 -> x.role; case 2 -> x.name; case 3 -> x.psk; case 4 -> x.uplink; case 5 -> x.downlink; default -> ""; };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            ChRow x = rows.get(r);
            switch (c) {
                case 1 -> x.role = (Channel.Role) v;
                case 2 -> x.name = String.valueOf(v);
                case 3 -> x.psk = String.valueOf(v);
                case 4 -> x.uplink = (Boolean) v;
                case 5 -> x.downlink = (Boolean) v;
                default -> { }
            }
        }
    }
}
