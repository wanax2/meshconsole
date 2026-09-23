package meshconsole.ui;

import meshconsole.analysis.*;
import meshconsole.mesh.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Derived analytics over the collected data. */
class AnalysisPanel extends JPanel {
    private final MeshState state;
    private final SignalHistory history;
    private final UtilHistory utilHistory;
    private final AntennaLog antennaLog = new AntennaLog(meshconsole.DataDir.file("antenna_log.csv"));
    private WeatherPanel weatherPanel;

    // link quality
    private final JComboBox<NodeItem> nodePick = new JComboBox<>();
    private final Heatmap heatmap = new Heatmap();
    private final SimpleModel margin = new SimpleModel(new String[]{"Node", "Avg SNR", "Link margin dB", "Verdict", "Avg RSSI", "Samples"});
    // structure
    private final SimpleModel structure = new SimpleModel(new String[]{"Node", "Degree", "Hops from me", "Critical relay", "Relayed packets", "Relay share"});
    private final JLabel structureSummary = new JLabel(" ");
    private final JLabel churnSummary = new JLabel(" ");
    private final BarChart churnChart = new BarChart();
    // channel
    private final BarChart utilChart = new BarChart();
    private final SimpleModel budget = new SimpleModel(new String[]{"Node", "Airtime", "Air-time %", "Over 10 % budget", "Packets"});
    private final JLabel dupeSummary = new JLabel(" ");
    private final JLabel noiseSummary = new JLabel(" ");
    private final BarChart noiseChart = new BarChart();
    // delivery
    private final SimpleModel delivery = new SimpleModel(new String[]{"Group", "Bucket", "Sent", "Delivered", "Success"});
    // antenna
    private final AntennaDb antennaDb = new AntennaDb(meshconsole.DataDir.file("antennas.json"));
    private final RadioDb radioDb = new RadioDb(meshconsole.DataDir.file("radios.json"));
    private final SimpleModel radios = new SimpleModel(new String[]{"Radio", "ID", "Hardware", "Firmware", "TX dBm", "Role", "Current antenna", "Antenna placement", "Height m", "Radio location", "Hours connected", "Samples heard", "Avg RSSI", "Avg SNR", "Nodes heard", "Direct %", "DMs sent", "Delivered", "Last connected", "Notes"});
    private final SetupLog setupLog = new SetupLog(meshconsole.DataDir.file("setup_log.csv"));
    private final SimpleModel setups = new SimpleModel(new String[]{"Radio", "Antenna", "Antenna placement", "Height m", "Radio location", "Since", "Hours", "Samples", "Avg RSSI", "Avg SNR", "Nodes heard", "Direct %", "Notes"});
    private final JComboBox<String> setupAntenna = new JComboBox<>();
    private final JComboBox<String> setupPlacement = new JComboBox<>(SetupLog.PLACEMENTS);
    private final JSpinner setupHeight = new JSpinner(new SpinnerNumberModel(2.0, 0.0, 300.0, 0.5));
    private final JTextField setupRadioLoc = new JTextField(14), setupNotes = new JTextField(14);
    private final JLabel setupCurrent = new JLabel(" ");
    private final JTable radiosTable = table(radios);
    private final JComboBox<String> antennaPick = new JComboBox<>();
    private final JLabel antennaCurrent = new JLabel(" ");
    private final SimpleModel library = new SimpleModel(new String[]{"Antenna", "Type", "Gain dBi", "Band", "Connector", "Mounting", "Best SWR", "@ MHz", "SWR worst", "Sweep date", "Hours used", "Avg RSSI", "Avg SNR", "Nodes", "Notes"});
    private final JTable libraryTable = table(library);
    private final SimpleModel slots = new SimpleModel(new String[]{"Frequency slot", "Samples", "Avg RSSI", "Avg SNR", "Distinct nodes", "From", "To"});
    private final SimpleModel antenna = new SimpleModel(new String[]{"Antenna", "Samples", "Avg RSSI (all)", "Avg SNR (all)", "Nodes", "Avg RSSI (common nodes)", "Avg SNR (common)", "Common nodes"});

    record NodeItem(int num, String label) { @Override public String toString() { return label; } }

    static class SimpleModel extends AbstractTableModel {
        final String[] cols; List<Object[]> rows = new ArrayList<>();
        SimpleModel(String[] c) { cols = c; }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
        void set(List<Object[]> r) { rows = r; fireTableDataChanged(); }
    }

    AnalysisPanel(MeshState state, SignalHistory history, UtilHistory utilHistory) {
        this.state = state;
        this.history = history;
        this.utilHistory = utilHistory;
        setLayout(new BorderLayout());
        JTabbedPane tabs = new JTabbedPane();

        // ---- link quality
        JPanel lq = new JPanel(new BorderLayout(6, 6));
        JPanel lqTop = row();
        lqTop.add(new JLabel("Node:")); lqTop.add(nodePick);
        nodePick.addActionListener(e -> refreshHeatmap());
        JButton refresh = new JButton("Refresh all");
        refresh.addActionListener(e -> refreshAll());
        lqTop.add(refresh);
        lq.add(lqTop, BorderLayout.NORTH);
        heatmap.setPreferredSize(new Dimension(700, 230));
        JPanel hmBox = new JPanel(new BorderLayout()); hmBox.setBorder(BorderFactory.createTitledBorder("Average RSSI by day of week × hour (last year; darker = weaker)")); hmBox.add(heatmap);
        JScrollPane mt = new JScrollPane(table(margin)); mt.setBorder(BorderFactory.createTitledBorder("Link margin = average SNR minus the modem's demodulation limit; < 3 dB is fragile, < 0 means packets are already being lost"));
        JSplitPane lqSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, hmBox, mt); lqSplit.setResizeWeight(0.5);
        lq.add(lqSplit, BorderLayout.CENTER);
        tabs.addTab("Link quality", lq);

        // ---- structure
        JPanel st = new JPanel(new BorderLayout(6, 6));
        JPanel stTop = new JPanel(); stTop.setLayout(new BoxLayout(stTop, BoxLayout.Y_AXIS));
        JPanel s1 = row(); s1.add(structureSummary); JPanel s2 = row(); s2.add(churnSummary);
        stTop.add(s1); stTop.add(s2);
        st.add(stTop, BorderLayout.NORTH);
        JScrollPane stT = new JScrollPane(table(structure)); stT.setBorder(BorderFactory.createTitledBorder("Mesh graph from neighbour reports, traceroutes and direct reception. 'Critical relay' = removing it splits the mesh"));
        churnChart.setPreferredSize(new Dimension(600, 150));
        JPanel cc = new JPanel(new BorderLayout()); cc.setBorder(BorderFactory.createTitledBorder("Node churn, last 30 days: active nodes per day (blue) and new arrivals (orange)")); cc.add(churnChart);
        JSplitPane stSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stT, cc); stSplit.setResizeWeight(0.65);
        st.add(stSplit, BorderLayout.CENTER);
        tabs.addTab("Mesh structure", st);

        // ---- channel
        JPanel ch = new JPanel(new BorderLayout(6, 6));
        utilChart.setPreferredSize(new Dimension(600, 150));
        JPanel uc = new JPanel(new BorderLayout()); uc.setBorder(BorderFactory.createTitledBorder("Channel utilisation by hour of day, % (this radio, up to 90 days)")); uc.add(utilChart);
        noiseChart.setPreferredSize(new Dimension(600, 120));
        JPanel nc = new JPanel(new BorderLayout()); nc.setBorder(BorderFactory.createTitledBorder("Noise floor reported by this radio, last 48 samples (bar height = dB above -130; healthy ≈ -110 to -115 dBm, -100 means ~10-15 dB of local interference)")); nc.add(noiseChart);
        JPanel chTop = new JPanel(new BorderLayout());
        JPanel charts = new JPanel(new GridLayout(2, 1)); charts.add(uc); charts.add(nc);
        chTop.add(charts, BorderLayout.CENTER);
        JPanel d = new JPanel(); d.setLayout(new BoxLayout(d, BoxLayout.Y_AXIS)); JPanel d1 = row(); d1.add(dupeSummary); JPanel d2 = row(); d2.add(noiseSummary); d.add(d1); d.add(d2);
        chTop.add(d, BorderLayout.SOUTH);
        JScrollPane bt = new JScrollPane(table(budget)); bt.setBorder(BorderFactory.createTitledBorder("Air-time budget per node since counters were reset (Meshtastic guideline: keep each node under 10 %)"));
        JSplitPane chSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chTop, bt); chSplit.setResizeWeight(0.45);
        ch.add(chSplit, BorderLayout.CENTER);
        tabs.addTab("Channel health", ch);

        // ---- delivery
        JPanel dv = new JPanel(new BorderLayout(6, 6));
        JScrollPane dt = new JScrollPane(table(delivery)); dt.setBorder(BorderFactory.createTitledBorder("Direct-message delivery success by hops, distance and time of day (retries count as one message)"));
        dv.add(dt, BorderLayout.CENTER);
        tabs.addTab("Delivery", dv);

        // ---- antenna
        JPanel an = new JPanel(new BorderLayout(6, 6));
        JPanel anTop = new JPanel(); anTop.setLayout(new BoxLayout(anTop, BoxLayout.Y_AXIS));
        JPanel a1 = row(); a1.add(new JLabel("Antenna now in use:"));
        antennaPick.setPrototypeDisplayValue("A fairly long antenna name here");
        a1.add(antennaPick);
        JButton setAnt = new JButton("Start using this antenna");
        setAnt.addActionListener(e -> {
            String n = (String) antennaPick.getSelectedItem();
            if (n == null || n.isBlank()) return;
            try { antennaLog.set(n); antennaCurrent.setText("Current: " + n + " since " + Fmt.time(System.currentTimeMillis())); refreshAntenna(); }
            catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); }
        });
        a1.add(setAnt); a1.add(antennaCurrent);
        JPanel a2 = row();
        JButton addAnt = new JButton("Add antenna…"), editAnt = new JButton("Edit…"), delAnt = new JButton("Remove");
        addAnt.addActionListener(e -> editAntenna(null));
        editAnt.addActionListener(e -> { int r = libraryTable.getSelectedRow(); if (r >= 0) editAntenna(antennaDb.get((String) library.rows.get(libraryTable.convertRowIndexToModel(r))[0])); });
        delAnt.addActionListener(e -> {
            int r = libraryTable.getSelectedRow(); if (r < 0) return;
            String n = (String) library.rows.get(libraryTable.convertRowIndexToModel(r))[0];
            if (JOptionPane.showConfirmDialog(this, "Remove '" + n + "' from the library? (Its history in antenna_log.csv is kept.)", "Remove antenna", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try { antennaDb.remove(n); refreshAntenna(); } catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); }
        });
        a2.add(addAnt); a2.add(editAnt); a2.add(delAnt);
        a2.add(new JLabel("Every signal sample is attributed to the antenna in use; SWR sweeps on the Antenna SWR tab can be saved to an antenna. Compare on 'common nodes' for a fair A/B."));
        anTop.add(a1); anTop.add(a2);
        an.add(anTop, BorderLayout.NORTH);
        libraryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] lw = {160, 90, 60, 90, 70, 120, 60, 60, 60, 100, 70, 70, 60, 50, 240};
        for (int i = 0; i < lw.length; i++) libraryTable.getColumnModel().getColumn(i).setPreferredWidth(lw[i]);
        JScrollPane libT = new JScrollPane(libraryTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        libT.setBorder(BorderFactory.createTitledBorder("Antenna library (antennas.json) with measured SWR and on-air results"));
        JScrollPane antT = new JScrollPane(table(antenna)); antT.setBorder(BorderFactory.createTitledBorder("On-air A/B by antenna (all samples vs. nodes heard under every antenna)"));
        JSplitPane libSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, libT, antT); libSplit.setResizeWeight(0.5);
        JScrollPane slotT = new JScrollPane(table(slots)); slotT.setBorder(BorderFactory.createTitledBorder("By frequency slot (0 = preset default, e.g. LongFast 20; NoVa-Mesh = 9) – from signal history, last 7 days at full resolution"));
        JSplitPane anSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, libSplit, slotT); anSplit.setResizeWeight(0.7);
        an.add(anSplit, BorderLayout.CENTER);
        refreshAntennaPick();
        if (!antennaLog.current().isEmpty()) { antennaPick.setSelectedItem(antennaLog.current()); antennaCurrent.setText("Current: " + antennaLog.current() + " since " + Fmt.time(antennaLog.starts.get(antennaLog.starts.size() - 1)[0])); }
        tabs.addTab("Antenna / slot A/B", an);

        // ---- my radios
        JPanel rd = new JPanel(new BorderLayout(6, 6));
        JPanel rdTop = row();
        JButton rdEdit = new JButton("Edit selected…"), rdDel = new JButton("Remove");
        rdEdit.addActionListener(e -> { int r = radiosTable.getSelectedRow(); if (r >= 0) editRadio(radioDb.get(parseId((String) radios.rows.get(radiosTable.convertRowIndexToModel(r))[1]))); });
        rdDel.addActionListener(e -> {
            int r = radiosTable.getSelectedRow(); if (r < 0) return;
            int num = parseId((String) radios.rows.get(radiosTable.convertRowIndexToModel(r))[1]);
            if (JOptionPane.showConfirmDialog(this, "Remove this radio from the library? It is re-added automatically next time you connect to it.", "Remove radio", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try { radioDb.remove(num); refreshRadios(); } catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); }
        });
        rdTop.add(rdEdit); rdTop.add(rdDel);
        rdTop.add(new JLabel("Every node you connect is registered automatically. Performance columns: what that radio heard while connected, and what it delivered."));
        JPanel setupBox = new JPanel(); setupBox.setLayout(new BoxLayout(setupBox, BoxLayout.Y_AXIS)); setupBox.setAlignmentX(LEFT_ALIGNMENT);
        setupBox.setBorder(BorderFactory.createTitledBorder("Record the physical setup of the connected radio (each change starts a new test period)"));
        JPanel setupRow = row();
        setupRow.add(new JLabel("Antenna:")); setupAntenna.setEditable(true); setupRow.add(setupAntenna);
        setupRow.add(new JLabel("placed:")); setupRow.add(setupPlacement);
        setupRow.add(new JLabel("height above ground m:")); setupRow.add(setupHeight);
        setupRow.add(new JLabel("radio location:")); setupRow.add(setupRadioLoc);
        JPanel setupRow2 = row();
        setupRow2.add(new JLabel("notes:")); setupRow2.add(setupNotes);
        JButton recordSetup = new JButton("Record setup now");
        recordSetup.addActionListener(e -> recordSetup());
        setupRow2.add(recordSetup); setupRow2.add(setupCurrent);
        setupBox.add(setupRow); setupBox.add(setupRow2);
        JPanel rdNorth = new JPanel(); rdNorth.setLayout(new BoxLayout(rdNorth, BoxLayout.Y_AXIS)); rdNorth.add(rdTop); rdNorth.add(setupBox);
        rd.add(rdNorth, BorderLayout.NORTH);
        radiosTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] rw = {150, 90, 130, 110, 55, 90, 130, 130, 90, 90, 70, 60, 80, 60, 60, 70, 110, 240};
        for (int i = 0; i < rw.length; i++) radiosTable.getColumnModel().getColumn(i).setPreferredWidth(rw[i]);
        JScrollPane rdT = new JScrollPane(radiosTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        rdT.setBorder(BorderFactory.createTitledBorder("Radios"));
        JTable setupTable = table(setups);
        setupTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] sw = {150, 130, 120, 60, 130, 110, 50, 70, 70, 60, 80, 60, 200};
        for (int i = 0; i < sw.length; i++) setupTable.getColumnModel().getColumn(i).setPreferredWidth(sw[i]);
        JScrollPane setupT = new JScrollPane(setupTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        setupT.setBorder(BorderFactory.createTitledBorder("By setup — one row per recorded combination of radio, antenna, placement and height (best average RSSI first)"));
        JSplitPane rdSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rdT, setupT); rdSplit.setResizeWeight(0.45);
        rd.add(rdSplit, BorderLayout.CENTER);
        JLabel rdNote = new JLabel("  To test one variable, change only it and press 'Record setup now': same radio + antenna at 2 m vs 6 m tests height; same everything with another radio tests the receiver. Give each setup a few hours at a similar time of day.");
        rdNote.setFont(rdNote.getFont().deriveFont(11f));
        rd.add(rdNote, BorderLayout.SOUTH);
        tabs.addTab("My radios", rd);

        // ---- report
        JPanel rp = new JPanel(new BorderLayout(6, 6));
        JTextArea rpText = new JTextArea("Generates mesh_report.html: new/gone nodes, busiest talkers, weakest links, critical relays, utilisation curve, delivery stats, weather correlation, recent alerts. Opens in your browser; share it with your local mesh group.");
        rpText.setEditable(false); rpText.setLineWrap(true); rpText.setWrapStyleWord(true); rpText.setBackground(getBackground());
        rp.add(rpText, BorderLayout.NORTH);
        JPanel rpBtns = row();
        JButton gen = new JButton("Generate report");
        gen.addActionListener(e -> generateReport());
        rpBtns.add(gen);
        rp.add(rpBtns, BorderLayout.CENTER);
        tabs.addTab("Report", rp);

        add(tabs, BorderLayout.CENTER);
        tabs.addChangeListener(e -> refreshAll());
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentShown(java.awt.event.ComponentEvent e) { refreshAll(); }
        });
        state.addListener(new MeshState.Listener() {
            @Override public void onStatusChanged() { if (state.configComplete()) SwingUtilities.invokeLater(AnalysisPanel.this::refreshNodePick); }
        });
        new javax.swing.Timer(60_000, e -> { if (isShowing()) { lastRefresh = 0; refreshAll(); } }).start();
    }

    void setWeatherPanel(WeatherPanel w) { weatherPanel = w; }

    private static JTable table(AbstractTableModel m) { JTable t = new JTable(m); t.setRowHeight(20); t.setAutoCreateRowSorter(true); return t; }
    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }

    private long lastRefresh;
    void refreshAll() {
        if (System.currentTimeMillis() - lastRefresh < 2000) return;
        lastRefresh = System.currentTimeMillis();
        refreshNodePick(); refreshHeatmap(); refreshMargin(); refreshStructure(); refreshChannel(); refreshDelivery(); refreshAntenna();
    }

    private void refreshNodePick() {
        NodeItem sel = (NodeItem) nodePick.getSelectedItem();
        List<NodeItem> items = new ArrayList<>();
        int me = state.myNodeNum();
        for (NodeEntry n : state.nodes()) if (n.num != me && (n.rssiCount > 0 || n.packetsSeen > 0)) items.add(new NodeItem(n.num, n.displayName()));
        boolean same = items.size() == nodePick.getItemCount();
        if (same) for (int i = 0; i < items.size(); i++) if (!items.get(i).equals(nodePick.getItemAt(i))) { same = false; break; }
        if (same) return;
        nodePick.removeAllItems();
        for (NodeItem it : items) { nodePick.addItem(it); if (sel != null && it.num() == sel.num()) nodePick.setSelectedItem(it); }
    }

    private void refreshHeatmap() {
        NodeItem sel = (NodeItem) nodePick.getSelectedItem();
        if (sel == null || history == null) { heatmap.set(null, ""); return; }
        List<SignalSample> s = new ArrayList<>();
        for (SignalSample x : history.since(System.currentTimeMillis() - 365L * 86400_000L)) if (x.from() == sel.num() && x.hops() != -1) s.add(x);
        heatmap.set(Analysis.heatmap(s), sel.label() + " – " + s.size() + " packets");
    }

    private void refreshMargin() {
        List<Object[]> rows = new ArrayList<>();
        int me = state.myNodeNum();
        for (NodeEntry n : state.nodes()) {
            if (n.num == me || n.snrCount == 0) continue;
            double m = Analysis.linkMargin(n.avgSnr(), state.lora());
            rows.add(new Object[]{n.displayName(), String.format("%.1f", n.avgSnr()), String.format("%+.1f", m), m < 0 ? "losing packets" : m < 3 ? "fragile" : m < 8 ? "ok" : "solid", n.rssiCount == 0 ? "" : String.format("%.0f", n.avgRssi()), n.snrCount});
        }
        rows.sort(Comparator.comparingDouble(r -> Double.parseDouble(((String) r[2]).replace("+", ""))));
        margin.set(rows);
    }

    private void refreshStructure() {
        Map<Integer, Set<Integer>> adj = state.graphEdges();
        int me = state.myNodeNum();
        Analysis.GraphStats g = Analysis.graph(adj, me);
        Map<Integer, Long> relays = state.relayCounts();
        long relayTotal = relays.values().stream().mapToLong(Long::longValue).sum();
        List<Object[]> rows = new ArrayList<>();
        for (NodeEntry n : state.nodes()) {
            long relayed = relays.getOrDefault(n.num & 0xFF, 0L);
            Integer deg = g.degree().get(n.num), hops = g.hopsFromMe().get(n.num);
            if (deg == null && relayed == 0) continue;
            rows.add(new Object[]{n.displayName() + (n.num == me ? " (me)" : ""), deg == null ? 0 : deg, hops == null ? "" : String.valueOf(hops), g.articulation().contains(n.num) ? "YES" : "",
                    relayed == 0 ? "" : String.valueOf(relayed), relayed == 0 || relayTotal == 0 ? "" : String.format("%.0f%%", 100.0 * relayed / relayTotal)});
        }
        rows.sort((a, b) -> Integer.compare((Integer) b[1], (Integer) a[1]));
        structure.set(rows);
        structureSummary.setText(String.format("Graph: %d nodes with known links, %d component(s), %d reachable from me, %d critical relay(s). Relay attribution uses the packet's relay_node byte, so nodes sharing a last ID byte are listed together.",
                adj.size(), g.components(), g.reachable(), g.articulation().size()));
        Analysis.Churn c = Analysis.churn(state.nodes(), me, 30);
        churnChart.set(c.activePerDay(), c.arrivalsPerDay());
        churnSummary.setText(String.format("Churn: %d nodes known, %d not heard in 24 h, median node lifetime %s, %d new in the last 7 days.",
                c.total(), c.departed(), Fmt.ago(System.currentTimeMillis() - c.medianLifetimeMs()), Arrays.stream(c.arrivalsPerDay(), 23, 30).sum()));
    }

    private void refreshChannel() {
        List<UtilSample> u = utilHistory != null ? utilHistory.util() : state.utilHistory();
        double[] byHour = Analysis.byHour(u);
        int[] vals = new int[24];
        for (int h = 0; h < 24; h++) vals[h] = Double.isNaN(byHour[h]) ? 0 : (int) Math.round(byHour[h]);
        utilChart.set(vals, null);
        long elapsed = Math.max(1, System.currentTimeMillis() - state.trafficSince());
        List<Object[]> rows = new ArrayList<>();
        for (NodeEntry n : state.nodes()) {
            if (n.airtimeMs == 0) continue;
            double pct = 100.0 * n.airtimeMs / elapsed;
            rows.add(new Object[]{n.displayName(), String.format("%.1f s", n.airtimeMs / 1000.0), String.format("%.2f%%", pct), pct > 10 ? "YES" : "", n.packetsSeen});
        }
        rows.sort((a, b) -> Double.compare(Double.parseDouble(((String) b[2]).replace("%", "")), Double.parseDouble(((String) a[2]).replace("%", ""))));
        budget.set(rows);
        List<UtilHistory.NoiseSample> ns = utilHistory != null ? utilHistory.noise() : List.of();
        if (ns.isEmpty()) { noiseSummary.setText("Noise floor: waiting for the radio's local statistics."); noiseChart.set(new int[0], null); }
        else {
            List<UtilHistory.NoiseSample> recent = ns.subList(Math.max(0, ns.size() - 48), ns.size());
            int[] bars = new int[recent.size()];
            for (int i = 0; i < bars.length; i++) bars[i] = Math.max(0, recent.get(i).noiseFloorDbm() + 130);
            noiseChart.set(bars, null);
            long day = System.currentTimeMillis() - 86400_000L;
            List<Integer> last24 = new ArrayList<>(); int best = 0, worst = -200;
            for (UtilHistory.NoiseSample n : ns) { if (n.time() > day) last24.add(n.noiseFloorDbm()); best = Math.min(best, n.noiseFloorDbm()); worst = Math.max(worst, n.noiseFloorDbm()); }
            Collections.sort(last24);
            noiseSummary.setText(String.format("Noise floor: now %d dBm, 24 h median %s, best ever %d, worst ever %d (%d samples). Move the radio/antenna and watch this: lower is better.",
                    ns.get(ns.size() - 1).noiseFloorDbm(), last24.isEmpty() ? "–" : last24.get(last24.size() / 2) + " dBm", best, worst, ns.size()));
        }
        List<UtilHistory.DupeSample> ds = utilHistory != null ? utilHistory.dupes() : List.of();
        if (ds.size() >= 2) {
            UtilHistory.DupeSample a = ds.get(0), b = ds.get(ds.size() - 1);
            long rx = b.rx() - a.rx(), dupe = b.dupe() - a.dupe();
            dupeSummary.setText(String.format("Duplicates: radio has seen %d duplicate packets out of %d received since %s (%.0f%%); rising share with a steady node count usually means hop limits are set too high somewhere.",
                    dupe, rx, Fmt.time(a.time()), rx == 0 ? 0 : 100.0 * dupe / rx));
        } else dupeSummary.setText("Duplicates: waiting for the radio's local statistics (sent every few minutes).");
    }

    private void refreshDelivery() {
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, List<Analysis.Bucket>> e : Analysis.deliveryBuckets(state.messages(), state).entrySet())
            for (Analysis.Bucket b : e.getValue()) if (b.sent() > 0) rows.add(new Object[]{e.getKey(), b.label(), b.sent(), b.delivered(), b.pct() + "%"});
        delivery.set(rows);
    }

    AntennaDb antennaDb() { return antennaDb; }

    private void refreshAntennaPick() {
        Object sel = antennaPick.getSelectedItem();
        antennaPick.removeAllItems();
        for (AntennaDb.Antenna a : antennaDb.all()) antennaPick.addItem(a.name);
        // antennas that only exist in the usage log (from older versions) are still selectable
        for (String l : new LinkedHashSet<>(antennaLog.labels)) if (antennaDb.get(l) == null) antennaPick.addItem(l);
        if (sel != null) antennaPick.setSelectedItem(sel);
    }

    private void editAntenna(AntennaDb.Antenna existing) {
        AntennaDb.Antenna a = existing == null ? new AntennaDb.Antenna() : existing;
        JTextField name = new JTextField(a.name, 18), connector = new JTextField(a.connector, 8), band = new JTextField(a.band.isEmpty() && existing == null ? "902–928 MHz" : a.band, 12), mounting = new JTextField(a.mounting, 18);
        JComboBox<String> type = new JComboBox<>(new String[]{"stock whip", "rubber duck", "dipole", "half-wave whip", "collinear / fiberglass", "ground plane", "yagi", "patch / panel", "other"});
        if (!a.type.isEmpty()) type.setSelectedItem(a.type); type.setEditable(true);
        JTextField gain = new JTextField(Double.isNaN(a.gainDbi) ? "" : String.valueOf(a.gainDbi), 5), length = new JTextField(Double.isNaN(a.lengthCm) ? "" : String.valueOf(a.lengthCm), 5);
        JTextArea notes = new JTextArea(a.notes, 3, 30);
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
        form.add(new JLabel("Name:")); form.add(name);
        form.add(new JLabel("Type:")); form.add(type);
        form.add(new JLabel("Gain (dBi, as advertised):")); form.add(gain);
        form.add(new JLabel("Length (cm):")); form.add(length);
        form.add(new JLabel("Band:")); form.add(band);
        form.add(new JLabel("Connector (SMA / RP-SMA / N…):")); form.add(connector);
        form.add(new JLabel("Mounting / height / location:")); form.add(mounting);
        form.add(new JLabel("Notes:")); form.add(new JScrollPane(notes));
        if (existing != null) name.setEditable(false);
        if (JOptionPane.showConfirmDialog(this, form, existing == null ? "Add antenna" : "Edit antenna", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (name.getText().isBlank()) return;
        a.name = name.getText().trim(); a.type = String.valueOf(type.getSelectedItem()); a.connector = connector.getText().trim(); a.band = band.getText().trim(); a.mounting = mounting.getText().trim(); a.notes = notes.getText().trim();
        try { a.gainDbi = gain.getText().isBlank() ? Double.NaN : Double.parseDouble(gain.getText().trim()); } catch (NumberFormatException e) { a.gainDbi = Double.NaN; }
        try { a.lengthCm = length.getText().isBlank() ? Double.NaN : Double.parseDouble(length.getText().trim()); } catch (NumberFormatException e) { a.lengthCm = Double.NaN; }
        try { antennaDb.put(a); refreshAntennaPick(); antennaPick.setSelectedItem(a.name); refreshAntenna(); }
        catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); }
    }

    private void refreshLibrary() {
        // usage hours and on-air averages per antenna from the log + history
        Map<String, double[]> onAir = new HashMap<>();
        if (history != null && !antennaLog.labels.isEmpty())
            for (Analysis.AntennaResult r : Analysis.antennaAB(antennaLog.starts, antennaLog.labels, history.since(System.currentTimeMillis() - 365L * 86400_000L)))
                onAir.put(r.label().toLowerCase(), new double[]{r.avgRssi(), r.avgSnr(), r.nodes()});
        Map<String, Long> hours = new HashMap<>();
        for (int i = 0; i < antennaLog.starts.size(); i++) {
            long start = antennaLog.starts.get(i)[0], end = i + 1 < antennaLog.starts.size() ? antennaLog.starts.get(i + 1)[0] : System.currentTimeMillis();
            hours.merge(antennaLog.labels.get(i).toLowerCase(), end - start, Long::sum);
        }
        List<Object[]> rows = new ArrayList<>();
        for (AntennaDb.Antenna a : antennaDb.all()) {
            double[] o = onAir.get(a.name.toLowerCase());
            rows.add(new Object[]{a.name, a.type, Double.isNaN(a.gainDbi) ? "" : String.valueOf(a.gainDbi), a.band, a.connector, a.mounting,
                    Double.isNaN(a.swrBest) ? "" : String.format("%.2f", a.swrBest), Double.isNaN(a.swrBestMhz) ? "" : String.format("%.1f", a.swrBestMhz),
                    Double.isNaN(a.swrWorst) ? "" : String.format("%.2f", a.swrWorst), a.lastSwrTime == 0 ? "" : Fmt.time(a.lastSwrTime),
                    hours.containsKey(a.name.toLowerCase()) ? String.format("%.1f", hours.get(a.name.toLowerCase()) / 3600_000.0) : "",
                    o == null ? "" : String.format("%.1f", o[0]), o == null ? "" : String.format("%.1f", o[1]), o == null ? "" : String.valueOf((int) o[2]), a.notes});
        }
        library.set(rows);
    }

    RadioDb radioDb() { return radioDb; }

    private static int parseId(String id) { return (int) Long.parseLong(id.replace("!", ""), 16); }

    private void editRadio(RadioDb.Radio r) {
        if (r == null) return;
        JTextField name = new JTextField(r.name, 18), location = new JTextField(r.location, 18);
        JComboBox<String> antennaBox = new JComboBox<>();
        antennaBox.addItem("");
        for (AntennaDb.Antenna a : antennaDb.all()) antennaBox.addItem(a.name);
        antennaBox.setEditable(true); antennaBox.setSelectedItem(r.antenna);
        JTextArea notes = new JTextArea(r.notes, 3, 30);
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
        form.add(new JLabel("Name:")); form.add(name);
        form.add(new JLabel("Node ID / hardware:")); form.add(new JLabel(r.idString() + "  " + r.hardware + "  " + r.firmware));
        form.add(new JLabel("Antenna fitted:")); form.add(antennaBox);
        form.add(new JLabel("Location / height:")); form.add(location);
        form.add(new JLabel("Notes:")); form.add(new JScrollPane(notes));
        if (JOptionPane.showConfirmDialog(this, form, "Edit radio", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        r.name = name.getText().trim(); r.location = location.getText().trim(); r.antenna = String.valueOf(antennaBox.getSelectedItem()).trim(); r.notes = notes.getText().trim();
        try { radioDb.put(r); refreshRadios(); } catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); }
    }

    /** Called when a radio finishes its config dump: register / refresh it. */
    void registerConnectedRadio() {
        NodeEntry me = state.myNode();
        if (me == null || state.myNodeNum() == 0) return;
        try {
            radioDb.touch(state.myNodeNum(), me.displayName(), me.hwModel, state.firmware(), state.lora() == null ? 0 : state.lora().getTxPower(), me.role);
            refreshRadios();
        } catch (IOException ignored) { }
    }

    void addConnectedTime(int num, long ms) { try { radioDb.addConnectedTime(num, ms); } catch (IOException ignored) { } }

    private void recordSetup() {
        if (!state.configComplete() || state.myNodeNum() == 0) { JOptionPane.showMessageDialog(this, "Connect to the radio first – the setup is recorded for the connected node."); return; }
        String ant = String.valueOf(setupAntenna.getSelectedItem()).trim();
        if (ant.isEmpty() || ant.equals("null")) { JOptionPane.showMessageDialog(this, "Choose or type the antenna in use."); return; }
        try {
            SetupLog.Setup st = new SetupLog.Setup(System.currentTimeMillis(), state.myNodeNum(), ant, String.valueOf(setupPlacement.getSelectedItem()),
                    ((Number) setupHeight.getValue()).doubleValue(), setupRadioLoc.getText().trim(), setupNotes.getText().trim());
            setupLog.add(st);
            // keep the antenna log in step so the antenna A/B stays consistent
            if (!ant.equalsIgnoreCase(antennaLog.current())) antennaLog.set(ant);
            RadioDb.Radio r = radioDb.get(state.myNodeNum());
            if (r != null) { r.antenna = ant; r.location = st.radioLocation(); radioDb.put(r); }
            state.emitLog("Setup recorded for " + state.nodeName(state.myNodeNum()) + ": " + st.label());
            refreshAntennaPick(); antennaPick.setSelectedItem(ant);
            refreshAntenna();
        } catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage()); }
    }

    private void refreshSetupControls() {
        Object sel = setupAntenna.getSelectedItem();
        setupAntenna.removeAllItems();
        for (AntennaDb.Antenna a : antennaDb.all()) setupAntenna.addItem(a.name);
        if (sel != null) setupAntenna.setSelectedItem(sel);
        SetupLog.Setup cur = state.myNodeNum() == 0 ? null : setupLog.current(state.myNodeNum());
        if (cur != null) {
            setupCurrent.setText("Current: " + cur.label() + " since " + Fmt.time(cur.time()));
            if (sel == null) { setupAntenna.setSelectedItem(cur.antenna()); setupPlacement.setSelectedItem(cur.placement()); if (!Double.isNaN(cur.heightM())) setupHeight.setValue(cur.heightM()); setupRadioLoc.setText(cur.radioLocation()); }
        } else setupCurrent.setText(state.myNodeNum() == 0 ? "(connect a radio)" : "No setup recorded yet for this radio");
    }

    private void refreshRadios() {
        refreshSetupControls();
        List<Object[]> srows = new ArrayList<>();
        if (history != null)
            for (Analysis.SetupResult r : Analysis.bySetup(setupLog, history.since(System.currentTimeMillis() - 365L * 86400_000L))) {
                RadioDb.Radio rd = radioDb.get(r.setup().radio());
                srows.add(new Object[]{rd == null || rd.name.isEmpty() ? String.format("!%08x", r.setup().radio()) : rd.name, r.setup().antenna(), r.setup().placement(),
                        Double.isNaN(r.setup().heightM()) ? "" : String.format("%.1f", r.setup().heightM()), r.setup().radioLocation(), Fmt.time(r.setup().time()), r.hours(),
                        r.samples(), String.format("%.1f", r.avgRssi()), String.format("%.1f", r.avgSnr()), r.nodes(), String.format("%.0f%%", r.directPct()), r.setup().notes()});
            }
        setups.set(srows);
        Map<Integer, double[]> heard = history == null ? Map.of() : Analysis.byRadio(history.since(System.currentTimeMillis() - 365L * 86400_000L));
        Map<Integer, int[]> del = Analysis.deliveryByRadio(state.messages());
        List<Object[]> rows = new ArrayList<>();
        for (RadioDb.Radio r : radioDb.all()) {
            double[] h = heard.get(r.num); int[] d = del.get(r.num);
            long ms = r.connectedMs;
            SetupLog.Setup cur = setupLog.current(r.num);
            rows.add(new Object[]{r.name, r.idString(), r.hardware, r.firmware, r.txPower == 0 ? "" : String.valueOf(r.txPower), r.role,
                    cur != null ? cur.antenna() : r.antenna, cur == null ? "" : cur.placement(), cur == null || Double.isNaN(cur.heightM()) ? "" : String.format("%.1f", cur.heightM()), cur != null ? cur.radioLocation() : r.location,
                    String.format("%.1f", ms / 3600_000.0), h == null ? "" : String.valueOf((int) h[0]), h == null ? "" : String.format("%.1f", h[1]), h == null ? "" : String.format("%.1f", h[2]),
                    h == null ? "" : String.valueOf((int) h[3]), h == null ? "" : String.format("%.0f%%", h[4]),
                    d == null ? "" : String.valueOf(d[0]), d == null || d[0] == 0 ? "" : Math.round(100.0 * d[1] / d[0]) + "%", r.lastConnected == 0 ? "" : Fmt.time(r.lastConnected), r.notes});
        }
        radios.set(rows);
    }

    private void refreshAntenna() {
        refreshLibrary();
        refreshRadios();
        if (history != null) {
            List<Object[]> sr = new ArrayList<>();
            for (Map.Entry<Integer, double[]> e : Analysis.bySlot(history.since(System.currentTimeMillis() - 7L * 86400_000L)).entrySet()) {
                double[] a = e.getValue();
                sr.add(new Object[]{e.getKey() == 0 ? "default" : String.valueOf(e.getKey()), (int) a[0], String.format("%.1f", a[1]), String.format("%.1f", a[2]), (int) a[3], Fmt.time((long) a[4]), Fmt.time((long) a[5])});
            }
            slots.set(sr);
        }
        if (history == null || antennaLog.labels.isEmpty()) { antenna.set(List.of()); return; }
        List<Object[]> rows = new ArrayList<>();
        for (Analysis.AntennaResult r : Analysis.antennaAB(antennaLog.starts, antennaLog.labels, history.since(System.currentTimeMillis() - 365L * 86400_000L)))
            rows.add(new Object[]{r.label(), r.samples(), String.format("%.1f", r.avgRssi()), String.format("%.1f", r.avgSnr()), r.nodes(),
                    Double.isNaN(r.commonRssi()) ? "" : String.format("%.1f", r.commonRssi()), Double.isNaN(r.commonSnr()) ? "" : String.format("%.1f", r.commonSnr()), r.commonNodes()});
        antenna.set(rows);
    }

    private void generateReport() {
        refreshAll();
        try {
            Path p = meshconsole.DataDir.file("mesh_report.html");
            Files.writeString(p, Report.html(state, history, utilHistory, weatherPanel == null ? null : weatherPanel.history(), antennaLog, meshconsole.DataDir.file("alerts.log")), StandardCharsets.UTF_8);
            state.emitLog("Report written to " + p.toAbsolutePath());
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(p.toAbsolutePath().toUri());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Report", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 7 × 24 heat map. */
    static class Heatmap extends JComponent {
        private double[][][] data; private String title = "";
        void set(double[][][] d, String t) { data = d; title = t; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            int w = getWidth(), h = getHeight();
            g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK); g.setFont(g.getFont().deriveFont(11f)); g.drawString(title, 6, 14);
            if (data == null) return;
            String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
            int left = 40, top = 34, cw = Math.max(8, (w - left - 10) / 24), chh = Math.max(8, (h - top - 20) / 7);
            g.setFont(g.getFont().deriveFont(9f));
            for (int hr = 0; hr < 24; hr += 2) g.drawString(String.valueOf(hr), left + hr * cw + 2, top - 3);
            for (int d = 0; d < 7; d++) {
                g.setColor(Color.BLACK); g.drawString(days[d], 6, top + d * chh + chh / 2 + 4);
                for (int hr = 0; hr < 24; hr++) {
                    double v = data[0][d][hr];
                    if (Double.isNaN(v)) g.setColor(new Color(240, 240, 240)); else g.setColor(MapPanel.rssiColor((int) v, 255));
                    g.fillRect(left + hr * cw, top + d * chh, cw - 1, chh - 1);
                    if (!Double.isNaN(v) && cw >= 26) { g.setColor(Color.BLACK); g.drawString(String.valueOf((int) v), left + hr * cw + 2, top + d * chh + chh / 2 + 4); }
                }
            }
        }
    }

    /** Small bar chart with an optional second series. */
    static class BarChart extends JComponent {
        private int[] a, b;
        void set(int[] x, int[] y) { a = x; b = y; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            int w = getWidth(), h = getHeight();
            g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
            if (a == null || a.length == 0) return;
            int max = 1; for (int v : a) max = Math.max(max, v); if (b != null) for (int v : b) max = Math.max(max, v);
            int left = 30, bottom = h - 16, top = 10, bw = Math.max(2, (w - left - 10) / a.length);
            g.setFont(g.getFont().deriveFont(9f));
            g.setColor(Color.DARK_GRAY); g.drawString(String.valueOf(max), 4, top + 8); g.drawString("0", 4, bottom);
            for (int i = 0; i < a.length; i++) {
                int x = left + i * bw;
                int ha = (int) ((bottom - top) * (double) a[i] / max);
                g.setColor(new Color(40, 90, 200, 180)); g.fillRect(x, bottom - ha, bw - 2, ha);
                if (b != null && i < b.length) { int hb = (int) ((bottom - top) * (double) b[i] / max); g.setColor(new Color(220, 120, 20, 200)); g.fillRect(x + bw / 3, bottom - hb, bw / 3, hb); }
                if (a.length <= 30 && (i % (a.length > 24 ? 5 : 2) == 0)) { g.setColor(Color.DARK_GRAY); g.drawString(String.valueOf(a.length == 24 ? i : i - a.length + 1), x, h - 4); }
            }
        }
    }
}
