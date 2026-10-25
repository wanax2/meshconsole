package meshconsole.ui;

import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import org.meshtastic.proto.ConfigProtos.Config;
import org.meshtastic.proto.ModuleConfigProtos.ModuleConfig;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.prefs.Preferences;

/**
 * "This radio" tab: every setting that depends on the connected hardware, on one page — transmit power checked against
 * the antenna and the FCC limits, receive (LNA, chip boost), fan, role, the Station G3's jumpers or FEM firmware, the
 * supply monitor and firmware. One Save writes the lot. The tab is titled after the radio (e.g. "Station G3").
 */
public class RadioPanel extends JPanel {
    private final MeshClient client;
    private final MeshState state;
    private final Runnable openFirmwareDialog;
    Runnable afterSave = () -> { };

    // header
    private final JLabel header = new JLabel(" ");
    private final JLabel live = new JLabel(" ");
    // transmit
    private final JComboBox<String> antenna = new JComboBox<>();
    private final JSpinner gain = new JSpinner(new SpinnerNumberModel(10.0, -5.0, 30.0, 0.5));
    private final JSpinner loss = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 20.0, 0.1));
    private final JSpinner txPower = new JSpinner(new SpinnerNumberModel(20, 0, 40, 1));
    private final JLabel txMeaning = new JLabel(" "), legal = new JLabel(" ");
    private final JCheckBox pl1 = new JCheckBox("PA PL1 jumper fitted"), pl2 = new JCheckBox("PA PL2 jumper fitted", true);
    private final JPanel jumperRow = row();
    // receive
    private final JCheckBox lna = new JCheckBox("External LNA on"), rxBoost = new JCheckBox("Chip RX boost (+2 dB)"), fanOff = new JCheckBox("PA fan off");
    private final JLabel lnaNote = new JLabel(" "), boostNote = new JLabel(" ");
    private final JPanel gpioRow = row();
    private final JComboBox<String> gpioLevel = new JComboBox<>(new String[]{"PL1 low (lower pair)", "PL1 high (higher pair)"});
    private final JCheckBox gpioReapply = new JCheckBox("re-apply on connect");
    // role
    private final JComboBox<Config.DeviceConfig.Role> role = new JComboBox<>();
    private final JSpinner hops = new JSpinner(new SpinnerNumberModel(3, 1, 7, 1));
    private final JCheckBox txEnabled = new JCheckBox("Transmit enabled (untick = receive-only listener)", true);
    // power monitor + firmware
    private final JCheckBox powerTelemetry = new JCheckBox("Report supply voltage/current (INA219 power telemetry, every 5 min)");
    private final JLabel fwLabel = new JLabel(" ");
    private final JLabel status = new JLabel(" ");

    private int shownFor;          // node the fields were filled from
    private boolean gpioApplied;   // GPIO re-apply done for this connection
    private static final Preferences PREFS = Preferences.userNodeForPackage(RadioPanel.class);

    public RadioPanel(MeshClient client, Runnable openFirmwareDialog) {
        super(new BorderLayout());
        this.client = client; this.state = client.state(); this.openFirmwareDialog = openFirmwareDialog;
        JPanel box = new JPanel(); box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JPanel top = section("Connected radio");
        top.add(header); top.add(live);
        box.add(top);

        // ---- transmit power and antenna
        JPanel tx = section("Transmit power and antenna (US: ≤ 30 dBm into the antenna and ≤ 36 dBm EIRP)");
        JPanel a1 = row();
        a1.add(new JLabel("Antenna:")); a1.add(antenna);
        a1.add(new JLabel("gain dBi:")); a1.add(gain);
        a1.add(new JLabel("cable + filter loss dB:")); a1.add(loss);
        tx.add(a1);
        jumperRow.add(new JLabel("Station G2/G3 jumpers as they are on the board:")); jumperRow.add(pl1); jumperRow.add(pl2);
        tx.add(jumperRow);
        JPanel a2 = row();
        a2.add(new JLabel("tx_power:")); a2.add(txPower); a2.add(txMeaning);
        tx.add(a2);
        JPanel a3 = row();
        a3.add(legal);
        a3.add(button("Set to the legal maximum for this antenna", this::setLegalMax));
        tx.add(a3);
        box.add(tx);

        // ---- receive
        JPanel rx = section("Receive");
        JPanel r1 = row(); r1.add(lna); r1.add(lnaNote); rx.add(r1);
        gpioRow.add(new JLabel("Stock firmware, jumpers removed – drive the lines:")); gpioRow.add(gpioLevel); gpioRow.add(gpioReapply);
        gpioRow.add(button("Enable GPIO control (one-time, reboots)", () -> { client.enableRemoteHardware(); say("Remote Hardware enabled – the radio reboots; reconnect, then Apply lines"); }));
        gpioRow.add(button("Apply lines", this::applyGpio));
        gpioRow.add(button("Read back", () -> client.readGpio((1L << 9) | (1L << 10))));
        rx.add(gpioRow);
        JPanel r2 = row(); r2.add(rxBoost); r2.add(boostNote); rx.add(r2);
        JPanel r3 = row(); r3.add(fanOff); rx.add(r3);
        box.add(rx);

        // ---- role
        JPanel rl = section("Role on the mesh");
        for (Config.DeviceConfig.Role r : Config.DeviceConfig.Role.values()) if (r != Config.DeviceConfig.Role.UNRECOGNIZED) role.addItem(r);
        JPanel l1 = row(); l1.add(new JLabel("Role:")); l1.add(role); l1.add(new JLabel("Hop limit:")); l1.add(hops); rl.add(l1);
        JPanel l2 = row(); l2.add(txEnabled); rl.add(l2);
        rl.add(note("CLIENT_BASE for the main base (favourite your own nodes on it); CLIENT_MUTE for a second radio in the same house, so the two don't relay each other."));
        box.add(rl);

        // ---- power monitor and firmware
        JPanel pf = section("Power monitor and firmware");
        JPanel p1 = row(); p1.add(powerTelemetry); pf.add(p1);
        JPanel p2 = row(); p2.add(fwLabel);
        p2.add(button("Firmware update…", () -> openFirmwareDialog.run()));
        p2.add(button("Reboot radio", () -> { if (JOptionPane.showConfirmDialog(this, "Reboot the connected radio now?", "Reboot", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) client.reboot(2); }));
        pf.add(p2);
        box.add(pf);

        // ---- save
        JPanel sv = row();
        JButton save = new JButton("Save to radio"); save.setFont(save.getFont().deriveFont(Font.BOLD));
        save.addActionListener(e -> save());
        sv.add(save);
        sv.add(button("Reload from radio", () -> { shownFor = 0; populate(); }));
        sv.add(status);
        box.add(sv);

        JScrollPane sp = new JScrollPane(box); sp.getVerticalScrollBar().setUnitIncrement(16); sp.setBorder(null);
        add(sp, BorderLayout.CENTER);

        // live recompute
        for (JComponent c : new JComponent[]{gain, loss, txPower}) ((JSpinner) c).addChangeListener(e -> recompute());
        for (JCheckBox c : new JCheckBox[]{pl1, pl2}) c.addActionListener(e -> recompute());
        antenna.addActionListener(e -> antennaPicked());

        state.addListener(new MeshState.Listener() {
            @Override public void onStatusChanged() { SwingUtilities.invokeLater(RadioPanel.this::populate); }
            @Override public void onTelemetryChanged() { SwingUtilities.invokeLater(RadioPanel.this::refreshLive); }
        });
        loadAntennas();
        populate();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // hardware facts

    private String hw() { NodeEntry me = state.myNode(); return me == null || me.hwModel == null ? "" : me.hwModel.toUpperCase(); }
    private boolean isG3() { return hw().equals("STATION_G3"); }
    private boolean isG2() { return hw().equals("STATION_G2"); }
    private boolean isBqStation() { return isG2() || isG3(); }
    private boolean hasFan() { return isBqStation() || hw().equals("TBEAM_1_WATT"); }
    private boolean hasExternalLna() { return isBqStation() || hw().equals("TBEAM_1_WATT") || hw().startsWith("RAK"); }
    /** FEM-capable firmware reports fem_lna_mode as ENABLED/DISABLED; everything else reports NOT_PRESENT. */
    private boolean femFirmware() { Config l = state.config(Config.PayloadVariantCase.LORA); return l != null && l.getLora().getFemLnaMode() != Config.LoRaConfig.FEM_LNA_Mode.NOT_PRESENT; }
    private int pal() { return (pl2.isSelected() ? 2 : 1) + (pl1.isSelected() ? 2 : 0); }

    /** Expected output at the antenna port for a tx_power value on this radio. */
    private double outputFor(int tx) {
        if (isG3() && femFirmware()) return tx;                    // station-g3-fem: tx_power is antenna dBm
        if (isBqStation()) return g3Output(pal(), Math.min(tx, 19)); // stock G2/G3: tx_power is SX1262 drive, capped at 19
        if (hw().equals("TBEAM_1_WATT")) return Math.min(tx, 32);  // firmware subtracts its 10 dB PA gain; chip max 22
        return tx;
    }

    private String txSemantics() {
        if (isG3() && femFirmware()) return "tx_power is the output at the antenna port (station-g3-fem firmware)";
        if (isBqStation()) return "tx_power is the SX1262 drive into the PA (capped at 19); the output depends on the jumpers";
        if (hw().equals("TBEAM_1_WATT")) return "tx_power is the output at the antenna port (firmware accounts for the 10 dB PA)";
        return "tx_power is the radio's output";
    }

    /** B&Q's typical Station G3 output at 915 MHz (dBm) by PA level and SX1262 drive, interpolated. */
    static double g3Output(int level, int tx) {
        int[] txs = {7, 14, 15, 16, 18, 22};
        double[][] tbl = {{21.7, 27.7, 28.4, 29.2, 30.5, 32.8}, {22.2, 28.4, 29.2, 30.0, 31.4, 34.1}, {23.1, 29.4, 30.2, 31.0, 32.6, 35.6}, {23.3, 29.7, 30.6, 31.5, 33.3, 36.8}};
        double[] row = tbl[Math.max(1, Math.min(4, level)) - 1];
        if (tx <= txs[0]) return row[0] - (txs[0] - tx) * 0.9;
        if (tx >= txs[5]) return row[5];
        for (int i = 0; i < 5; i++) if (tx >= txs[i] && tx <= txs[i + 1]) return row[i] + (row[i + 1] - row[i]) * (tx - txs[i]) / (double) (txs[i + 1] - txs[i]);
        return row[0];
    }

    /** Highest output allowed at the antenna port: 30 dBm conducted and 36 dBm EIRP (US Part 15). */
    private double legalPortMax() { return Math.min(30.0, 36.0 - (double) gain.getValue() + (double) loss.getValue()); }

    // ---------------------------------------------------------------------------------------------------------------
    // UI logic

    private void recompute() {
        int tx = (int) txPower.getValue();
        double out = outputFor(tx), eirp = out + (double) gain.getValue() - (double) loss.getValue(), max = legalPortMax();
        txMeaning.setText(String.format("→ about %.1f dBm at the antenna port, %.1f dBm EIRP.   %s", out, eirp, txSemantics()));
        boolean over = out > max + 0.05;
        legal.setText(String.format("<html>Legal maximum with this antenna: <b>%.1f dBm</b> at the port. %s</html>", max,
                over ? "<font color='#b00000'><b>Currently over by " + String.format("%.1f", out - max) + " dB.</b></font>" : "<font color='#006000'>Currently within the limit.</font>"));
        jumperRow.setVisible(isBqStation() && !(isG3() && femFirmware()));
    }

    private void setLegalMax() {
        double max = legalPortMax();
        int best = 0;
        for (int tx = 0; tx <= 30; tx++) if (outputFor(tx) <= max + 0.05) best = tx;   // highest setting whose output stays within the limit
        txPower.setValue(best);
        recompute();
        say("tx_power set to " + best + " – press Save to radio");
    }

    private void antennaPicked() {
        Object sel = antenna.getSelectedItem(); if (sel == null) return;
        String s = sel.toString(); int i = s.lastIndexOf('('); int j = s.lastIndexOf(" dBi");
        if (i >= 0 && j > i) try { gain.setValue(Double.parseDouble(s.substring(i + 1, j))); } catch (NumberFormatException ignored) { }
        PREFS.put("antenna-" + Integer.toHexString(meshconsole.DataDir.get().toString().toLowerCase().hashCode()), s);
        recompute();
    }

    private void loadAntennas() {
        antenna.removeAllItems();
        try {
            meshconsole.analysis.AntennaDb db = new meshconsole.analysis.AntennaDb(meshconsole.DataDir.file("antennas.json"));
            for (meshconsole.analysis.AntennaDb.Antenna a : db.all()) if (!Double.isNaN(a.gainDbi)) antenna.addItem(a.name + " (" + a.gainDbi + " dBi)");
        } catch (Exception ignored) { }
        for (String p : new String[]{"10 dBi omni, Rokland (10.0 dBi)", "10 dBi yagi (10.0 dBi)", "Alfa AOA-8696-3ACM omni (3.0 dBi)", "6 dBi omni (6.0 dBi)", "Stock whip (2.0 dBi)"}) antenna.addItem(p);
        String last = PREFS.get("antenna-" + Integer.toHexString(meshconsole.DataDir.get().toString().toLowerCase().hashCode()), "");
        for (int i = 0; i < antenna.getItemCount(); i++) if (antenna.getItemAt(i).equals(last)) antenna.setSelectedIndex(i);
    }

    private void populate() {
        NodeEntry me = state.myNode();
        int now = me == null ? 0 : me.num;
        if (now != shownFor) gpioApplied = false;
        if (me == null || !state.configComplete()) { header.setText(me == null ? "Not connected." : "Loading settings from " + state.nodeName(now) + "…"); return; }
        refreshLive();
        if (now == shownFor) { reapplyGpio(); return; }   // keep the user's unsaved edits; Reload re-reads
        shownFor = now;
        Config l = state.config(Config.PayloadVariantCase.LORA), d = state.config(Config.PayloadVariantCase.DEVICE);
        if (l != null) {
            Config.LoRaConfig lc = l.getLora();
            txPower.setValue(lc.getTxPower()); hops.setValue(Math.max(1, Math.min(7, lc.getHopLimit())));
            rxBoost.setSelected(lc.getSx126XRxBoostedGain()); fanOff.setSelected(lc.getPaFanDisabled()); txEnabled.setSelected(lc.getTxEnabled());
            lna.setSelected(lc.getFemLnaMode() != Config.LoRaConfig.FEM_LNA_Mode.DISABLED);
        }
        if (d != null) role.setSelectedItem(d.getDevice().getRole());
        ModuleConfig t = state.moduleConfig(ModuleConfig.PayloadVariantCase.TELEMETRY);
        if (t != null) powerTelemetry.setSelected(t.getTelemetry().getPowerMeasurementEnabled());

        boolean fem = femFirmware();
        header.setText("<html><b>" + me.displayName() + "</b> &nbsp; " + me.hwModel + " &nbsp; firmware " + state.firmware()
                + (isG3() ? (fem ? " &nbsp; <b>(station-g3-fem: firmware controls PA PL1 and the LNA)</b>" : " &nbsp; (stock station-g3)") : "") + "</html>");
        fwLabel.setText(isG3() ? (fem ? "station-g3-fem build. Before going back to the stock build, lower tx_power to 16 (or 11 on a yagi)." : "Stock build. The station-g3-fem patch adds firmware control of the PA level and LNA.") : "Firmware " + state.firmware());
        lna.setVisible(hasExternalLna());
        lna.setEnabled(fem || isG3());   // FEM firmware: saved setting; stock G3: used by "Apply lines" below
        lnaNote.setText(fem ? "saved in the radio (fem_lna_mode); off during transmit"
                : isG3() ? "stock firmware: the LNA P jumper decides (fitted = LNA off), or remove it and use Apply lines below"
                : hasExternalLna() ? "fixed in hardware on this radio" : "");
        gpioRow.setVisible(isG3() && !fem);
        gpioReapply.setSelected(PREFS.getBoolean("g3reapply", false));
        boostNote.setText(hasExternalLna() ? "with an external LNA in front, leave this off (it adds little and costs linearity next to a strong signal)" : "worth having on a radio without an external LNA");
        fanOff.setVisible(hasFan());
        recompute();
        reapplyGpio();
        status.setText(" ");
    }

    private void refreshLive() {
        NodeEntry me = state.myNode(); if (me == null) { live.setText(" "); return; }
        StringBuilder sb = new StringBuilder("<html>");
        if (me.powerChannels > 0 && !Float.isNaN(me.powerVolts[0])) {
            float v = me.powerVolts[0], a = Float.isNaN(me.powerAmps[0]) ? Float.NaN : me.powerAmps[0] / 1000f;
            sb.append(String.format("Supply <b>%.2f V</b>", v)).append(Float.isNaN(a) ? "" : String.format(", %.2f A, %.1f W", a, v * a)).append(" (").append(Fmt.ago(me.powerTime)).append(" ago) &nbsp; ");
        } else if (isG3()) sb.append("Supply: not reported (tick the power monitor below) &nbsp; ");
        var ls = state.localStats();
        if (ls != null && ls.getNoiseFloor() != 0) sb.append("Noise floor ").append(ls.getNoiseFloor()).append(" dBm &nbsp; ");
        sb.append(String.format("Channel %.1f%%, TX air %.2f%%", me.channelUtil, me.airUtilTx)).append("</html>");
        live.setText(sb.toString());
    }

    private void applyGpio() throws IOException {
        long mask = (1L << 9) | (1L << 10);
        long value = (gpioLevel.getSelectedIndex() == 1 ? (1L << 9) : 0) | (lna.isSelected() ? 0 : (1L << 10));
        PREFS.putLong("g3mask", mask); PREFS.putLong("g3value", value); PREFS.putBoolean("g3reapply", gpioReapply.isSelected());
        client.writeGpio(mask, value);
        pl1.setSelected(gpioLevel.getSelectedIndex() == 1); recompute();
        say("Lines applied: PL1 " + (gpioLevel.getSelectedIndex() == 1 ? "high" : "low") + ", LNA " + (lna.isSelected() ? "on" : "off") + " – Read back to confirm");
    }

    private void reapplyGpio() {
        if (gpioApplied || !isG3() || femFirmware() || !PREFS.getBoolean("g3reapply", false) || PREFS.getLong("g3mask", 0) == 0) return;
        gpioApplied = true;
        new Thread(() -> { try { Thread.sleep(3000); client.writeGpio(PREFS.getLong("g3mask", 0), PREFS.getLong("g3value", 0)); state.emitLog("G3 amplifier lines re-applied on connect"); } catch (Exception ignored) { } }).start();
    }

    private void save() {
        try {
            Config l = state.config(Config.PayloadVariantCase.LORA), d = state.config(Config.PayloadVariantCase.DEVICE);
            if (l == null || d == null) throw new IOException("Settings haven't arrived from the radio yet.");
            double out = outputFor((int) txPower.getValue()), max = legalPortMax();
            if (out > max + 0.05 && JOptionPane.showConfirmDialog(this, String.format("That's about %.1f dBm at the antenna port, %.1f dB over the US limit for this antenna.%nSave anyway?", out, out - max),
                    "Over the legal limit", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            Config.LoRaConfig.Builder lb = l.getLora().toBuilder()
                    .setTxPower((int) txPower.getValue()).setHopLimit((int) hops.getValue()).setTxEnabled(txEnabled.isSelected())
                    .setSx126XRxBoostedGain(rxBoost.isSelected()).setPaFanDisabled(fanOff.isSelected());
            if (femFirmware()) lb.setFemLnaMode(lna.isSelected() ? Config.LoRaConfig.FEM_LNA_Mode.ENABLED : Config.LoRaConfig.FEM_LNA_Mode.DISABLED);
            boolean loraChanged = !lb.build().equals(l.getLora());
            boolean roleChanged = role.getSelectedItem() != d.getDevice().getRole();
            ModuleConfig t = state.moduleConfig(ModuleConfig.PayloadVariantCase.TELEMETRY);
            ModuleConfig.TelemetryConfig.Builder tb = t == null ? ModuleConfig.TelemetryConfig.newBuilder() : t.getTelemetry().toBuilder();
            boolean telemChanged = tb.getPowerMeasurementEnabled() != powerTelemetry.isSelected();
            if (telemChanged) { tb.setPowerMeasurementEnabled(powerTelemetry.isSelected()); if (tb.getPowerUpdateInterval() == 0) tb.setPowerUpdateInterval(300); }
            if (!loraChanged && !roleChanged && !telemChanged) { say("Nothing changed."); return; }
            if (loraChanged) client.setConfig(Config.newBuilder().setLora(lb).build());
            if (roleChanged) client.setConfig(Config.newBuilder().setDevice(d.getDevice().toBuilder().setRole((Config.DeviceConfig.Role) role.getSelectedItem())).build());
            if (telemChanged) client.setModuleConfig(ModuleConfig.newBuilder().setTelemetry(tb).build());
            say("Saved to radio" + (loraChanged ? " (LoRa)" : "") + (roleChanged ? " (role – the radio may reboot)" : "") + (telemChanged ? " (power monitor)" : "") + ". "
                    + (isBqStation() ? "Station G2s may need a cold power-cycle after a change." : ""));
            afterSave.run();
        } catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "Save", JOptionPane.ERROR_MESSAGE); }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // tab title for the main window

    String tabTitle() {
        String h = hw();
        return h.equals("STATION_G3") ? "Station G3" : h.equals("STATION_G2") ? "Station G2" : h.equals("TBEAM_1_WATT") ? "T-Beam 1W" : "This radio";
    }

    // ---------------------------------------------------------------------------------------------------------------
    // small helpers

    private void say(String s) { status.setText(s); state.emitLog(s); }

    private interface IoAction { void run() throws IOException; }

    private JButton button(String text, IoAction a) {
        JButton b = new JButton(text);
        b.addActionListener(e -> { try { a.run(); } catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), text, JOptionPane.ERROR_MESSAGE); } });
        return b;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title), BorderFactory.createEmptyBorder(2, 6, 6, 6)));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }

    private static JComponent note(String s) { JLabel l = new JLabel("<html><i>" + s + "</i></html>"); JPanel p = row(); p.add(l); return p; }
}
