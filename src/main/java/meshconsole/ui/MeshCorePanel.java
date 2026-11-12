package meshconsole.ui;

import com.fazecast.jSerialComm.SerialPort;
import meshconsole.meshcore.McClient;
import meshconsole.meshcore.McService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * The MeshCore tab: pick the MeshCore radio (Companion firmware) from the list of USB ports – or type tcp:address:5000 –
 * and click Connect. Then: chat (channels and direct messages), contacts, every packet heard, and the radio's details.
 * Same radio and data as the /meshcore page of the web console.
 */
final class MeshCorePanel extends JPanel {
    private final JComboBox<String> port = new JComboBox<>();
    private final JLabel status = new JLabel();
    private final DefaultListModel<Peer> peers = new DefaultListModel<>();
    private final JList<Peer> peerList = new JList<>(peers);
    private final JTextArea chat = new JTextArea();
    private final JTextField input = new JTextField();
    private final JLabel note = new JLabel(" ");
    private final DefaultTableModel contacts = ro("Name", "Type", "Last heard", "Hops", "Heard directly", "RSSI", "SNR", "Position", "Key");
    private final DefaultTableModel packets = ro("Time", "Type", "Hops", "RSSI", "SNR", "Sender", "Last repeater");
    private final JLabel sigSummary = new JLabel(" ");
    private final JTextArea radio = new JTextArea();
    private final JTextField fName = new JTextField(18), fLat = new JTextField(11), fLon = new JTextField(11),
            fFreq = new JTextField(8), fBw = new JTextField(6), fSf = new JTextField(3), fCr = new JTextField(3);
    private final JSpinner fTx = new JSpinner(new SpinnerNumberModel(7, -9, 22, 1));
    private final JCheckBox fShare = new JCheckBox("Include position in adverts");
    private final JLabel fTxNote = new JLabel();
    private String formFor = "";
    private String shownChat = "";
    private int shownMsgCount = -1;

    record Peer(String id, String label) { @Override public String toString() { return label; } }

    MeshCorePanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // ---- connection row
        port.setEditable(true);
        port.setPrototypeDisplayValue("serial:/dev/serial/by-id/usb-Espressif_Systems_Station_G3_ESP32_XXXXXXXXXXXX-if00 — Station G3");
        JButton refresh = new JButton("↻"); refresh.setToolTipText("Look for radios again");
        JButton connect = new JButton("Connect"), disconnect = new JButton("Disconnect");
        refresh.addActionListener(e -> listPorts());
        connect.addActionListener(e -> {
            String spec = specOf(String.valueOf(port.getEditor().getItem()));
            if (spec.isEmpty()) { note("Pick the MeshCore radio from the list first."); return; }
            note(McService.configure(spec));
        });
        disconnect.addActionListener(e -> note(McService.configure("")));
        JPanel top = new JPanel(new BorderLayout(6, 2));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel("MeshCore radio:")); row.add(port); row.add(refresh); row.add(connect); row.add(disconnect); row.add(status);
        top.add(row, BorderLayout.NORTH);
        JLabel help = new JLabel("<html><span style='color:gray'>Plug the MeshCore radio (Companion <b>USB</b> firmware) into this computer, pick it from the list and click Connect. "
                + "A radio on WiFi: type <b>tcp:</b><i>address</i><b>:5000</b>. Mesh Console reconnects to it by itself after that. "
                + "This is separate from the Meshtastic radio at the top of the window – don't pick the MeshCore radio up there.</span></html>");
        top.add(help, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        // ---- chat
        peerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        peerList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) { shownMsgCount = -1; refreshChat(); } });
        chat.setEditable(false); chat.setLineWrap(true); chat.setWrapStyleWord(true);
        chat.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        JButton send = new JButton("Send");
        send.addActionListener(e -> send());
        input.addActionListener(e -> send());
        JPanel bar = new JPanel(new BorderLayout(4, 2));
        bar.add(input, BorderLayout.CENTER); bar.add(send, BorderLayout.EAST); bar.add(note, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.add(new JScrollPane(chat), BorderLayout.CENTER); right.add(bar, BorderLayout.SOUTH);
        JScrollPane left = new JScrollPane(peerList); left.setPreferredSize(new Dimension(230, 100));
        JSplitPane chatPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);

        // ---- contacts, signal, radio
        JTable ct = new JTable(contacts); ct.setAutoCreateRowSorter(true);
        JPanel sig = new JPanel(new BorderLayout(4, 4));
        sig.add(sigSummary, BorderLayout.NORTH);
        sig.add(new JScrollPane(new JTable(packets)), BorderLayout.CENTER);
        sig.add(new JLabel("<html><span style='color:gray'>Every packet the radio heard (newest first). For relayed packets the RSSI/SNR is the last repeater's, not the sender's.</span></html>"), BorderLayout.SOUTH);
        radio.setEditable(false); radio.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); radio.setRows(9);
        JPanel rp = new JPanel(new BorderLayout(6, 6));
        rp.add(new JScrollPane(radio), BorderLayout.NORTH);
        rp.add(new JScrollPane(settingsForm()), BorderLayout.CENTER);
        JTabbedPane sub = new JTabbedPane();
        sub.addTab("Chat", chatPane); sub.addTab("Contacts", new JScrollPane(ct)); sub.addTab("Signal", sig); sub.addTab("Radio", rp);
        add(sub, BorderLayout.CENTER);

        listPorts();
        new javax.swing.Timer(3000, e -> refresh()).start();
        refresh();
    }

    // ------------------------------------------------------------------ connection
    private void listPorts() {
        String current = McService.spec();
        port.removeAllItems();
        Set<String> seen = new HashSet<>();
        try (var st = Files.list(Path.of("/dev/serial/by-id"))) {          // Linux: names that never change
            st.sorted().forEach(p -> {
                String n = p.getFileName().toString();
                port.addItem("serial:" + p + " — " + (n.contains("Station_G3") ? "Station G3" : n.contains("Station_G2") ? "Station G2" : "USB serial"));
                try { seen.add(p.toRealPath().toString()); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
        for (SerialPort sp : SerialPort.getCommPorts()) {
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            String path = win ? sp.getSystemPortName() : "/dev/" + sp.getSystemPortName();
            if (seen.contains(path) || (!win && !path.matches("/dev/tty(ACM|USB)\\d+"))) continue;
            port.addItem("serial:" + path + " — " + sp.getDescriptivePortName());
        }
        if (!current.isEmpty()) {
            boolean found = false;
            for (int i = 0; i < port.getItemCount(); i++) if (specOf(port.getItemAt(i)).equals(current)) { port.setSelectedIndex(i); found = true; }
            if (!found) { port.insertItemAt(current, 0); port.setSelectedIndex(0); }
        } else if (port.getItemCount() == 0) port.getEditor().setItem("");
    }

    private static String specOf(String item) {
        if (item == null) return "";
        int i = item.indexOf(" — ");
        return (i > 0 ? item.substring(0, i) : item).trim();
    }

    // ------------------------------------------------------------------ refresh
    private void refresh() {
        McClient c = McService.client();
        boolean on = c != null && c.isConnected();
        status.setText(on ? "● connected – " + c.selfName : McService.spec().isEmpty() ? "● not set up" : "● connecting to " + McService.spec() + "…");
        status.setForeground(on ? new Color(0x2e9e4f) : McService.spec().isEmpty() ? Color.GRAY : new Color(0xc9302c));
        if (c == null) return;
        refreshPeers(c); refreshChat(); refreshContacts(c); refreshSignal(c); refreshRadio(c); fillForm(c);
    }

    private void refreshPeers(McClient c) {
        List<Peer> want = new ArrayList<>();
        for (McClient.Channel ch : c.channels) want.add(new Peer("#" + ch.idx, ch.name.startsWith("#") ? ch.name : "# " + ch.name));
        List<McClient.Contact> cs = new ArrayList<>(c.contacts.values());
        cs.removeIf(x -> x.type != 1 && x.type != 3);
        Map<String, Long> last = new HashMap<>();
        for (McClient.Message m : c.messages) last.merge(m.peer, m.time, Math::max);
        cs.sort(Comparator.comparingLong((McClient.Contact x) -> last.getOrDefault(x.key.substring(0, 12), 0L)).reversed()
                .thenComparing(Comparator.comparingLong((McClient.Contact x) -> Math.max(x.lastHeard, x.lastAdvert)).reversed()));
        for (McClient.Contact x : cs) want.add(new Peer(x.key.substring(0, 12), (x.type == 3 ? "🏠 " : "") + x.name));
        boolean same = want.size() == peers.size();
        for (int i = 0; same && i < want.size(); i++) same = want.get(i).equals(peers.get(i));
        if (same) return;
        Peer sel = peerList.getSelectedValue();
        peers.clear(); want.forEach(peers::addElement);
        int idx = -1;
        for (int i = 0; i < peers.size(); i++) if (sel != null && peers.get(i).id().equals(sel.id())) idx = i;
        peerList.setSelectedIndex(idx >= 0 ? idx : (peers.isEmpty() ? -1 : 0));
    }

    private void refreshChat() {
        McClient c = McService.client();
        Peer p = peerList.getSelectedValue();
        if (c == null || p == null) { chat.setText(""); return; }
        List<McClient.Message> ms = new ArrayList<>();
        for (McClient.Message m : c.messages) if (m.peer.equals(p.id())) ms.add(m);
        if (p.id().equals(shownChat) && ms.size() == shownMsgCount && !anyPending(ms)) return;
        SimpleDateFormat f = new SimpleDateFormat("MMM d HH:mm");
        StringBuilder b = new StringBuilder();
        for (McClient.Message m : ms.subList(Math.max(0, ms.size() - 400), ms.size())) {
            b.append(f.format(new Date(m.time))).append("  ").append(m.out ? "me" : m.from);
            if (!m.out && m.hops >= 0) b.append(m.hops == 0 ? " (direct" : " (" + m.hops + " hop" + (m.hops > 1 ? "s" : "")).append(Double.isNaN(m.snr) ? ")" : ", SNR " + m.snr + ")");
            if (m.out && !m.status.isEmpty()) b.append("  [").append(m.status).append(']');
            b.append(":\n    ").append(m.text).append("\n");
        }
        if (ms.isEmpty()) b.append("No messages with ").append(p.label()).append(" yet.");
        chat.setText(b.toString());
        chat.setCaretPosition(chat.getDocument().getLength());
        shownChat = p.id(); shownMsgCount = ms.size();
        input.putClientProperty("JTextField.placeholderText", "Message " + p.label());
    }
    private static boolean anyPending(List<McClient.Message> ms) {   // keep redrawing until "sent" turns into "delivered"
        for (int i = Math.max(0, ms.size() - 5); i < ms.size(); i++) if (ms.get(i).out && ms.get(i).status.startsWith("sent")) return true;
        return false;
    }

    private void refreshContacts(McClient c) {
        List<McClient.Contact> cs = new ArrayList<>(c.contacts.values());
        cs.sort(Comparator.comparingLong((McClient.Contact x) -> Math.max(x.lastHeard, x.lastAdvert)).reversed());
        contacts.setRowCount(0);
        for (McClient.Contact x : cs)
            contacts.addRow(new Object[]{x.name, McClient.TYPES[Math.min(Math.max(x.type, 0), 4)], ago(Math.max(x.lastHeard, x.lastAdvert)),
                    x.hops >= 0 ? x.hops : "", x.sigCount > 0 ? x.sigCount : "",
                    x.sigCount > 0 ? String.format(Locale.US, "%.0f", x.rssiSum / x.sigCount) : "",
                    x.sigCount > 0 ? String.format(Locale.US, "%.1f", x.snrSum / x.sigCount) : "",
                    x.lat != 0 || x.lon != 0 ? String.format(Locale.US, "%.4f, %.4f", x.lat, x.lon) : "", x.key.substring(0, 12)});
    }

    private void refreshSignal(McClient c) {
        List<McClient.Sample> all = new ArrayList<>(c.samples);
        long since = System.currentTimeMillis() - 86_400_000L; int n = 0, direct = 0;
        for (McClient.Sample s : all) if (s.time >= since) { n++; if (s.hops == 0) direct++; }
        sigSummary.setText(n + " packets heard in the last 24 h, " + direct + " directly");
        packets.setRowCount(0);
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss");
        for (int i = all.size() - 1, k = 0; i >= 0 && k < 200; i--, k++) {
            McClient.Sample s = all.get(i);
            McClient.Contact who = s.sender.isEmpty() ? null : c.byPrefix(s.sender);
            packets.addRow(new Object[]{f.format(new Date(s.time)), typeName(s.ptype), s.hops == 0 ? "direct" : s.hops, s.rssi, s.snr, who != null ? who.name : s.sender, s.lastHop});
        }
    }
    private static String typeName(int t) {
        String[] n = {"request", "response", "text", "ack", "advert", "channel text", "channel data", "anon request", "path", "trace", "multipart", "control", "?", "?", "?", "raw"};
        return t >= 0 && t < n.length ? n[t] : "?";
    }

    private void refreshRadio(McClient c) {
        if (!c.isConnected()) { radio.setText(McService.spec().isEmpty() ? "No MeshCore radio set up yet – pick it in the list above and click Connect." : "Connecting to " + McService.spec() + " …\n\nIf this doesn't change within a minute, look in the log (Status & signal) for lines starting with \"MeshCore:\"."); return; }
        String t = String.format(Locale.US,
                "Name        %s%nConnection  %s%nFirmware    %s %s%nBoard       %s%nRadio       %.3f MHz, %.1f kHz, SF%d, CR%d%nTX power    %d dBm setting (max %d) – on a Station G2/G3 this drives the amplifier%nPosition    %s%nBattery     %s%nContacts    %d     Channels %d%nPublic key  %s%n",
                c.selfName, McService.spec(), c.firmware, c.buildDate.isEmpty() ? "" : "(" + c.buildDate + ")", c.board,
                c.freqMhz, c.bwKhz, c.sf, c.cr, c.txPower, c.maxTxPower,
                c.selfLat != 0 || c.selfLon != 0 ? String.format(Locale.US, "%.5f, %.5f%s", c.selfLat, c.selfLon, c.selfLon > 0 && c.selfLat > 20 && c.selfLat < 50 ? "   <- longitude should be NEGATIVE in the US" : "") : "not set",
                c.batteryMv > 0 ? String.format(Locale.US, "%.2f V", c.batteryMv / 1000.0) : "–", c.contacts.size(), c.channels.size(), c.selfKey);
        if (!t.equals(radio.getText())) radio.setText(t);
    }

    // ------------------------------------------------------------------ settings
    private JPanel settingsForm() {
        JPanel f = new JPanel(new GridBagLayout());
        f.setBorder(BorderFactory.createTitledBorder("Settings on the radio (each saved to the radio when you click its button)"));
        GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(4, 6, 4, 6); c.anchor = GridBagConstraints.WEST;
        JButton bName = new JButton("Save name"), bTx = new JButton("Save power"), bPos = new JButton("Save position"),
                bRadio = new JButton("Save radio settings"), adv1 = new JButton("Send flood advert"), adv0 = new JButton("Zero-hop advert"), boot = new JButton("Reboot radio");
        int y = 0;
        c.gridy = y++; c.gridx = 0; f.add(new JLabel("Name"), c); c.gridx = 1; f.add(fName, c); c.gridx = 2; f.add(bName, c);
        c.gridx = 3; f.add(new JLabel("<html><span style='color:gray'>How other MeshCore users see this radio</span></html>"), c);
        c.gridy = y++; c.gridx = 0; f.add(new JLabel("TX power (dBm)"), c); c.gridx = 1; f.add(fTx, c); c.gridx = 2; f.add(bTx, c); c.gridx = 3; f.add(fTxNote, c);
        JPanel pos = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pos.add(new JLabel("lat")); pos.add(fLat); pos.add(new JLabel("lon")); pos.add(fLon);
        c.gridy = y++; c.gridx = 0; f.add(new JLabel("Position"), c); c.gridx = 1; f.add(pos, c); c.gridx = 2; f.add(bPos, c); c.gridx = 3; f.add(fShare, c);
        JPanel rad = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rad.add(fFreq); rad.add(new JLabel("MHz  BW")); rad.add(fBw); rad.add(new JLabel("kHz  SF")); rad.add(fSf); rad.add(new JLabel("CR")); rad.add(fCr);
        c.gridy = y++; c.gridx = 0; f.add(new JLabel("Radio"), c); c.gridx = 1; f.add(rad, c); c.gridx = 2; f.add(bRadio, c);
        c.gridx = 3; f.add(new JLabel("<html><span style='color:gray'>Must match the other MeshCore nodes around you – leave as is unless you know the local settings</span></html>"), c);
        JPanel acts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); acts.add(adv1); acts.add(adv0); acts.add(boot);
        c.gridy = y++; c.gridx = 0; f.add(new JLabel("Actions"), c); c.gridx = 1; c.gridwidth = 3; f.add(acts, c); c.gridwidth = 1;
        c.gridy = y; c.weighty = 1; c.gridx = 0; f.add(Box.createVerticalGlue(), c);

        bName.addActionListener(e -> run(() -> client().setName(fName.getText())));
        bTx.addActionListener(e -> {
            int v = (Integer) fTx.getValue(); McClient m = client();
            if (m != null && (m.board.contains("G3") || m.board.contains("G2")) && v > 7
                    && JOptionPane.showConfirmDialog(this, "On a Station G2/G3 this number is the power fed INTO its amplifier, not what leaves the antenna.\n"
                    + "MeshCore's own default for the G3 is 7 dBm. Higher settings raise the real output steeply and can overdrive\n"
                    + "the amplifier or exceed the legal limit (1 W into an antenna up to 6 dBi).\n\nSet " + v + " dBm anyway?", "TX power", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            run(() -> client().setTxPower(v));
        });
        bPos.addActionListener(e -> {
            double lat, lon;
            try { lat = Double.parseDouble(fLat.getText().trim()); lon = Double.parseDouble(fLon.getText().trim()); } catch (NumberFormatException ex) { note("Latitude and longitude must be numbers, e.g. 38.8895 and -77.0353"); return; }
            if (lon > 0 && lat > 20 && lat < 50) {
                int a = JOptionPane.showConfirmDialog(this, "Longitudes in the US are negative (west of Greenwich).\nUse " + (-lon) + " instead of " + lon + "?", "Position", JOptionPane.YES_NO_CANCEL_OPTION);
                if (a == JOptionPane.CANCEL_OPTION || a == JOptionPane.CLOSED_OPTION) return;
                if (a == JOptionPane.YES_OPTION) { lon = -lon; fLon.setText(String.valueOf(lon)); }
            }
            final double la = lat, lo = lon; final boolean share = fShare.isSelected();
            run(() -> { String r = client().setPosition(la, lo); if (r.endsWith("saved") && (client().locPolicy == 1) != share) r += " · " + client().setSharePosition(share); return r; });
        });
        fShare.addActionListener(e -> { boolean share = fShare.isSelected(); run(() -> client().setSharePosition(share)); });
        bRadio.addActionListener(e -> {
            double fr, bw; int sf, cr;
            try { fr = Double.parseDouble(fFreq.getText().trim()); bw = Double.parseDouble(fBw.getText().trim()); sf = Integer.parseInt(fSf.getText().trim()); cr = Integer.parseInt(fCr.getText().trim()); }
            catch (NumberFormatException ex) { note("Frequency, bandwidth, SF and CR must be numbers"); return; }
            if (JOptionPane.showConfirmDialog(this, String.format(Locale.US, "Change to %.3f MHz, %.1f kHz, SF%d, CR%d?%n%nIf these don't match the MeshCore nodes around you, this radio stops hearing them.", fr, bw, sf, cr),
                    "Radio settings", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            run(() -> client().setRadio(fr, bw, sf, cr));
        });
        adv1.addActionListener(e -> note(advert(true)));
        adv0.addActionListener(e -> note(advert(false)));
        boot.addActionListener(e -> run(() -> client().reboot()));
        return f;
    }

    private McClient client() { return McService.client(); }

    /** Run a radio command off the screen thread, show its result, refresh the form from what the radio now reports. */
    private void run(java.util.function.Supplier<String> job) {
        McClient m = client();
        if (m == null || !m.isConnected()) { note("Not connected to the MeshCore radio."); return; }
        note("talking to the radio…");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() { return job.get(); }
            @Override protected void done() { String r; try { r = get(); } catch (Exception e) { r = String.valueOf(e.getMessage()); } note(r); formFor = ""; refresh(); }
        }.execute();
    }

    private void fillForm(McClient c) {
        String key = c.isConnected() ? c.selfKey + "|" + c.selfName + "|" + c.txPower + "|" + c.selfLat + "|" + c.selfLon + "|" + c.freqMhz + "|" + c.locPolicy : "";
        if (key.isEmpty() || key.equals(formFor)) return;
        formFor = key;
        fName.setText(c.selfName);
        ((SpinnerNumberModel) fTx.getModel()).setMaximum(Math.max(c.maxTxPower, 1));
        fTx.setValue(Math.max(-9, Math.min(c.txPower, Math.max(c.maxTxPower, 1))));
        fTxNote.setText("<html><span style='color:gray'>" + (c.board.contains("G3") || c.board.contains("G2")
                ? "Station G2/G3: this drives the amplifier – MeshCore's G3 default is 7. Max " + c.maxTxPower + "." : "Max " + c.maxTxPower + " dBm.") + "</span></html>");
        fLat.setText(c.selfLat == 0 && c.selfLon == 0 ? "" : String.valueOf(c.selfLat));
        fLon.setText(c.selfLat == 0 && c.selfLon == 0 ? "" : String.valueOf(c.selfLon));
        fShare.setSelected(c.locPolicy == 1);
        fFreq.setText(String.format(Locale.US, "%.3f", c.freqMhz)); fBw.setText(String.format(Locale.US, "%.1f", c.bwKhz));
        fSf.setText(String.valueOf(c.sf)); fCr.setText(String.valueOf(c.cr));
    }

    // ------------------------------------------------------------------ actions
    private void send() {
        McClient c = McService.client();
        Peer p = peerList.getSelectedValue();
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        if (c == null || !c.isConnected()) { note("Not connected to the MeshCore radio."); return; }
        if (p == null) { note("Pick a channel or contact on the left first."); return; }
        input.setEnabled(false); note("sending…");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() { return p.id().startsWith("#") ? c.sendChannel(Integer.parseInt(p.id().substring(1)), text) : c.sendDm(p.id(), text); }
            @Override protected void done() {
                String r; try { r = get(); } catch (Exception e) { r = e.getMessage(); }
                note(r); input.setEnabled(true); input.requestFocusInWindow();
                if (r != null && r.startsWith("sent")) input.setText("");
                shownMsgCount = -1; refreshChat();
            }
        }.execute();
    }

    private String advert(boolean flood) {
        McClient c = McService.client();
        return c == null || !c.isConnected() ? "Not connected to the MeshCore radio." : c.advert(flood);
    }

    private void note(String s) { note.setText(s == null || s.isEmpty() ? " " : s); }

    private static String ago(long t) {
        if (t <= 0) return "never";
        long s = (System.currentTimeMillis() - t) / 1000;
        return s < 90 ? s + " s ago" : s < 5400 ? s / 60 + " min ago" : s < 172800 ? s / 3600 + " h ago" : s / 86400 + " d ago";
    }

    private static DefaultTableModel ro(String... cols) {
        return new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
    }
}
