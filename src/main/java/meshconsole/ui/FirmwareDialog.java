package meshconsole.ui;

import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import meshconsole.tools.FirmwareUpdater;
import meshconsole.tools.FirmwareUpdater.*;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;

/** Download a firmware release and flash it: nRF52 boards directly (UF2 DFU), ESP32 boards through esptool. */
class FirmwareDialog extends JDialog {
    private final MeshClient client;
    private final MeshState state;
    private final JComboBox<Object> release = new JComboBox<>();
    private final JCheckBox pre = new JCheckBox("Include pre-releases");
    private final JComboBox<Object> file = new JComboBox<>();
    private final JCheckBox fullInstall = new JCheckBox("Full install (erase settings) – ESP32 only");
    private final JTextArea log = new JTextArea(14, 80);
    private final JLabel info = new JLabel(" ");
    private final JButton fetch = new JButton("Fetch releases"), download = new JButton("Download & list files"), flash = new JButton("Flash");
    private final JTextField portField = new JTextField(8);
    private Path zip;
    private Release current;
    private Family family = Family.OTHER;
    private String target = "";

    FirmwareDialog(Window owner, MeshClient client, String portName) {
        super(owner, "Firmware update", ModalityType.MODELESS);
        this.client = client; this.state = client.state();
        NodeEntry me = state.myNode();
        String hw = me == null ? "" : me.hwModel;
        family = FirmwareUpdater.family(hw); target = FirmwareUpdater.target(hw);
        portField.setText(portName == null ? "" : portName);
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        info.setText("<html>Connected: <b>" + (me == null ? "no radio" : me.displayName() + " (" + hw + ")") + "</b> · firmware " + state.firmware() + " · family " + family + " · file target '" + target + "'"
                + "<br>" + (family == Family.NRF52 ? "nRF52: flashed directly – the radio is put into DFU mode and the .uf2 copied to its bootloader drive. Keeps settings."
                : family == Family.OTHER ? "Unknown board family – download here, flash with the web flasher."
                : "ESP32: flashed with <b>esptool</b> if installed (pip install esptool); otherwise the file is downloaded for the web flasher. Update keeps settings; full install erases.") + "</html>");
        JPanel r0 = row(); r0.add(info);
        JPanel r1 = row(); r1.add(fetch); r1.add(pre); r1.add(new JLabel("Release:")); release.setPrototypeDisplayValue("2.7.26.54e0d8d (stable)   "); r1.add(release); r1.add(download);
        JPanel r2 = row(); r2.add(new JLabel("File:")); file.setPrototypeDisplayValue("firmware-station-g2-2.7.26.54e0d8d-update.bin      "); r2.add(file); r2.add(fullInstall); r2.add(new JLabel("Port:")); r2.add(portField); r2.add(flash);
        JPanel r3 = row(); r3.add(new JLabel("Before flashing: Tools → Export channel URL (to restore settings after a full install). Disconnect other programs from the port. Do not unplug during the flash."));
        top.add(r0); top.add(r1); top.add(r2); top.add(r3);
        add(top, BorderLayout.NORTH);
        log.setEditable(false); log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        add(new JScrollPane(log), BorderLayout.CENTER);
        fetch.addActionListener(e -> fetchReleases());
        download.addActionListener(e -> downloadRelease());
        flash.addActionListener(e -> doFlash());
        download.setEnabled(false); flash.setEnabled(false);
        pack(); setLocationRelativeTo(owner);
    }

    private static JPanel row() { return new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3)); }
    private void say(String s) { SwingUtilities.invokeLater(() -> { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); }); }

    private void fetchReleases() {
        fetch.setEnabled(false); say("Fetching releases from GitHub…");
        new Thread(() -> {
            try {
                List<Release> rs = FirmwareUpdater.releases(pre.isSelected());
                SwingUtilities.invokeLater(() -> {
                    release.removeAllItems();
                    for (Release r : rs) release.addItem(new Object() { final Release rel = r; @Override public String toString() { return rel.tag() + (rel.prerelease() ? " (pre-release)" : " (stable)"); } });
                    download.setEnabled(release.getItemCount() > 0); fetch.setEnabled(true);
                    say(rs.size() + " release(s). Installed: " + state.firmware());
                });
            } catch (Exception ex) { say("Failed: " + ex.getMessage()); SwingUtilities.invokeLater(() -> fetch.setEnabled(true)); }
        }, "fw-releases").start();
    }

    private Release selectedRelease() {
        Object o = release.getSelectedItem();
        if (o == null) return null;
        try { return (Release) o.getClass().getDeclaredField("rel").get(o); } catch (Exception e) { return null; }
    }

    private void downloadRelease() {
        current = selectedRelease();
        if (current == null) return;
        Asset a = FirmwareUpdater.pickAsset(current, family);
        if (a == null) { say("No suitable zip in this release for family " + family + "; assets: " + current.assets().stream().map(Asset::name).toList()); return; }
        download.setEnabled(false);
        new Thread(() -> {
            try {
                zip = FirmwareUpdater.download(a, this::say);
                List<String> files = FirmwareUpdater.listFiles(zip, target);
                SwingUtilities.invokeLater(() -> {
                    file.removeAllItems();
                    for (String f : files) file.addItem(f);
                    // preselect: nRF52 -> .uf2 for target; ESP32 -> -update.bin for target (or factory .bin if full install)
                    for (int i = 0; i < file.getItemCount(); i++) {
                        String f = String.valueOf(file.getItemAt(i));
                        boolean mine = f.contains("-" + target + "-");
                        if (mine && (family == Family.NRF52 ? f.endsWith(".uf2") : f.endsWith("-update.bin"))) { file.setSelectedIndex(i); break; }
                    }
                    flash.setEnabled(file.getItemCount() > 0); download.setEnabled(true);
                    say(files.size() + " firmware files in the zip; best guess for '" + target + "' preselected – check it matches your board.");
                });
            } catch (Exception ex) { say("Failed: " + ex.getMessage()); SwingUtilities.invokeLater(() -> download.setEnabled(true)); }
        }, "fw-download").start();
    }

    private void doFlash() {
        String entry = String.valueOf(file.getSelectedItem());
        if (zip == null || entry == null || entry.equals("null")) return;
        boolean full = fullInstall.isSelected();
        if (family == Family.ESP32S3 || family == Family.ESP32 || family == Family.ESP32C3) {
            if (full && entry.endsWith("-update.bin")) { say("Full install needs the factory image (the .bin without '-update'). Pick it in the File list."); return; }
            if (!full && !entry.endsWith("-update.bin")) { say("Update (keep settings) needs the '-update.bin' file. Pick it, or tick Full install."); return; }
        }
        if (JOptionPane.showConfirmDialog(this, "Flash " + entry + " to " + state.nodeName(state.myNodeNum()) + "?" + (full ? "\nThis ERASES all settings on the radio." : ""), "Flash firmware", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        flash.setEnabled(false);
        new Thread(() -> {
            try {
                Path bin = FirmwareUpdater.extract(zip, entry);
                say("Extracted " + bin);
                if (family == Family.NRF52) {
                    say("Putting the radio into DFU mode…");
                    FirmwareUpdater.enterDfu(client);
                    Thread.sleep(1500);
                    client.disconnect("firmware update (DFU)");
                    Path drive = FirmwareUpdater.waitForUf2Drive(60, this::say);
                    FirmwareUpdater.flashUf2(bin, drive, this::say);
                    say("Done. The radio reboots into " + current.tag() + "; reconnect in ~20 s (auto-reconnect will pick it up).");
                } else if (family == Family.OTHER) {
                    say("Unknown board family. The file is at " + bin + " – flash it with https://flasher.meshtastic.org (or the board's own method).");
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI.create("https://flasher.meshtastic.org"));
                } else {
                    List<String> esptool = FirmwareUpdater.findEsptool();
                    String port = portField.getText().trim();
                    if (esptool == null) {
                        say("esptool not found. Install it with:  pip install esptool   (needs Python), then retry — or use https://flasher.meshtastic.org with " + bin);
                        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI.create("https://flasher.meshtastic.org"));
                    } else if (port.isEmpty()) {
                        say("Enter the serial port (e.g. COM6) the radio is on.");
                    } else {
                        say("Disconnecting so esptool can use " + port + "…");
                        client.disconnect("firmware update (esptool)"); Thread.sleep(1000);
                        int rc = FirmwareUpdater.flashEsp(esptool, port, bin, !full, this::say);
                        say(rc == 0 ? "Done. The radio reboots; reconnect in ~20 s." : "esptool exited with code " + rc + ". If it said 'failed to connect', hold BOOT, tap RESET, release BOOT, and retry.");
                    }
                }
            } catch (Exception ex) { say("Failed: " + ex.getMessage()); }
            SwingUtilities.invokeLater(() -> flash.setEnabled(true));
        }, "fw-flash").start();
    }
}
