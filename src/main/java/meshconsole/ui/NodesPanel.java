package meshconsole.ui;

import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

class NodesPanel extends JPanel {
    private final MeshClient client;
    private final MeshState state;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextArea results = new JTextArea(5, 40);
    private final JLabel dbInfo = new JLabel(" ");
    private IntConsumer onMessageNode = n -> { };
    private IntConsumer onShowOnMap = n -> { };

    private static final String[] COLS = {"Name", "Short", "ID", "Last heard", "Hops", "SNR", "RSSI", "Battery", "Distance", "Position", "Hardware",
            "Role", "Flags", "Pkts", "Direct %", "Avg RSSI", "Avg SNR", "First seen", "Last seen", "Sessions", "Airtime", "Neighbours"};

    private class Model extends AbstractTableModel {
        List<NodeEntry> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            NodeEntry n = rows.get(r);
            NodeEntry me = state.myNode();
            boolean isMe = n.num == state.myNodeNum();
            return switch (c) {
                case 0 -> (isMe ? "★ " : "") + n.displayName();
                case 1 -> n.shortName;
                case 2 -> n.idString();
                case 3 -> isMe ? "(this radio)" : Fmt.ago(n.lastHeardMillis());
                case 4 -> isMe ? "" : n.hopsAway < 0 ? "?" : n.hopsAway == 0 ? "direct" : String.valueOf(n.hopsAway);
                case 5 -> isMe || n.snr == 0 ? "" : String.format("%.1f dB", n.snr);
                case 6 -> isMe || n.rssi == 0 ? "" : n.rssi + " dBm";
                case 7 -> n.battery < 0 ? "" : n.battery > 100 ? "ext" : n.battery + "%";
                case 8 -> (me != null && me.hasPosition && n.hasPosition && !isMe)
                        ? Fmt.distance(Fmt.distanceM(me.lat, me.lon, n.lat, n.lon)) : "";
                case 9 -> n.hasPosition ? String.format("%.4f, %.4f", n.lat, n.lon) : "";
                case 10 -> n.hwModel;
                case 11 -> n.role.isEmpty() || n.role.equals("CLIENT") ? (n.role.isEmpty() ? "" : "client") : n.role.toLowerCase();
                case 12 -> (n.watched ? "👁 " : "") + (n.isFavorite ? "★" : "") + (n.isLicensed ? " ham" : "") + (n.isUnmessagable ? " no-msg" : "") + (n.viaMqtt ? " mqtt" : "") + (n.isIgnored ? " ignored" : "");
                case 13 -> isMe ? "" : String.valueOf(n.packetsSeen);
                case 14 -> isMe || n.directPercent() < 0 ? "" : n.directPercent() + "%";
                case 15 -> isMe || n.rssiCount == 0 ? "" : String.format("%.0f dBm", n.avgRssi());
                case 16 -> isMe || n.snrCount == 0 ? "" : String.format("%.1f dB", n.avgSnr());
                case 17 -> n.firstSeen == 0 ? "" : Fmt.time(n.firstSeen) + " (" + Fmt.ago(n.firstSeen) + ")";
                case 18 -> isMe || n.lastHeardMillis() == 0 ? "" : Fmt.time(n.lastHeardMillis());
                case 19 -> n.sessionsSeen == 0 ? "" : String.valueOf(n.sessionsSeen);
                case 20 -> n.airtimeMs == 0 ? "" : String.format("%.1f s", n.airtimeMs / 1000.0);
                case 21 -> n.neighbors.isEmpty() ? "" : String.valueOf(n.neighbors.size());
                default -> "";
            };
        }
    }

    NodesPanel(MeshClient client) {
        this.client = client;
        this.state = client.state();
        setLayout(new BorderLayout(6, 6));

        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        int[] widths = {150, 45, 85, 80, 45, 60, 65, 55, 70, 140, 120, 60, 80, 40, 60, 70, 60, 150, 100, 55, 60, 60};
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        DefaultTableCellRenderer ageRenderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel && row < model.rows.size()) {
                    NodeEntry n = model.rows.get(row);
                    long age = System.currentTimeMillis() - n.lastHeardMillis();
                    if (col == 3) c.setForeground(age < 15 * 60_000 ? new Color(0, 130, 0) : age < 2 * 3600_000 ? new Color(180, 120, 0) : age < 24 * 3600_000 ? Color.GRAY : new Color(150, 90, 90));
                    else c.setForeground(n.fromDb ? Color.GRAY : Color.BLACK);
                    c.setFont(c.getFont().deriveFont(n.fromDb ? Font.ITALIC : Font.PLAIN));
                }
                return c;
            }
        };
        for (int i = 0; i < COLS.length; i++) table.getColumnModel().getColumn(i).setCellRenderer(ageRenderer);
        JScrollPane tableScroll = new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(tableScroll, BorderLayout.CENTER);

        JPanel dbBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        dbBar.add(dbInfo);
        JButton save = new JButton("Save DB now");
        JButton reset = new JButton("Reset DB…");
        save.setToolTipText("The node database is saved automatically on disconnect and every 5 minutes; this saves it right now");
        reset.setToolTipText("Forget nodes that were only remembered from earlier sessions and zero all counters");
        save.addActionListener(e -> { try { state.saveNodeDb(); refreshDbInfo(); } catch (IOException ex) { error(ex); } });
        reset.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Delete nodes.json and forget nodes not heard in this session?\nCounters (packets, averages, first seen, sessions) are reset for all nodes.", "Reset node database", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            try { state.resetNodeDb(); refreshDbInfo(); } catch (IOException ex) { error(ex); }
        });
        dbBar.add(save); dbBar.add(reset);
        add(dbBar, BorderLayout.NORTH);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton dm = new JButton("Message node");
        JButton tr = new JButton("Traceroute");
        JButton pos = new JButton("Request position");
        JButton map = new JButton("Show on map");
        JButton info = new JButton("Request node info");
        JButton remote = new JButton("Remote info (admin)");
        JButton sf = new JButton("S&F history…");
        JButton watch = new JButton("Watch / unwatch"), fav = new JButton("★ Favourite on radio");
        watch.setToolTipText("Watch-list: alert when this node goes quiet for an hour or comes back");
        fav.setToolTipText("Toggle the favourite flag on the radio itself (the phone app shows the same star)");
        watch.addActionListener(e -> withSelected(n -> { n.watched = !n.watched; state.notifyNodesChanged(); state.emitLog((n.watched ? "Watching " : "Stopped watching ") + n.displayName()); }));
        fav.addActionListener(e -> withSelected(n -> { try { client.setFavoriteOnRadio(n.num, !n.isFavorite); } catch (IOException ex) { error(ex); } }));
        info.setToolTipText("Ask the node to resend its name, hardware, role and key");
        remote.setToolTipText("Ask the node for its firmware/metadata and LoRa config – only answered if it trusts this node (admin key)");
        sf.setToolTipText("Ask a store-and-forward server node to replay recent messages you missed");
        buttons.add(dm); buttons.add(tr); buttons.add(pos); buttons.add(map); buttons.add(info); buttons.add(remote); buttons.add(sf); buttons.add(watch); buttons.add(fav);
        south.add(buttons, BorderLayout.NORTH);
        results.setEditable(false);
        results.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        results.setLineWrap(true);
        JScrollPane rs = new JScrollPane(results);
        rs.setBorder(BorderFactory.createTitledBorder("Traceroute / neighbor results"));
        south.add(rs, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        dm.addActionListener(e -> withSelected(n -> onMessageNode.accept(n.num)));
        map.addActionListener(e -> withSelected(n -> onShowOnMap.accept(n.num)));
        tr.addActionListener(e -> withSelected(n -> {
            try { client.traceroute(n.num); } catch (IOException ex) { error(ex); }
        }));
        pos.addActionListener(e -> withSelected(n -> {
            try { client.requestPosition(n.num); } catch (IOException ex) { error(ex); }
        }));
        info.addActionListener(e -> withSelected(n -> {
            try { client.requestNodeInfo(n.num); } catch (IOException ex) { error(ex); }
        }));
        remote.addActionListener(e -> withSelected(n -> {
            try { client.requestRemoteInfo(n.num); } catch (IOException ex) { error(ex); }
        }));
        sf.addActionListener(e -> withSelected(n -> {
            String w = JOptionPane.showInputDialog(this, "Replay messages from the last how many minutes?", "240");
            if (w == null) return;
            try { client.requestStoreForwardHistory(n.num, Integer.parseInt(w.trim())); }
            catch (NumberFormatException ex) { error(new IOException("Enter a number of minutes")); }
            catch (IOException ex) { error(ex); }
        }));

        state.addListener(new MeshState.Listener() {
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(NodesPanel.this::reload); }
            @Override public void onTraceroute(String summary) {
                SwingUtilities.invokeLater(() -> results.append(Fmt.time(System.currentTimeMillis()) + "  " + summary + "\n"));
            }
            @Override public void onLog(String line) {
                if (line.startsWith("Neighbors of") || line.startsWith("Remote ") || line.startsWith("S&F "))
                    SwingUtilities.invokeLater(() -> results.append(Fmt.time(System.currentTimeMillis()) + "  " + line + "\n"));
            }
        });
    }

    void setOnMessageNode(IntConsumer c) { onMessageNode = c; }
    void setOnShowOnMap(IntConsumer c) { onShowOnMap = c; }

    private void withSelected(java.util.function.Consumer<NodeEntry> action) {
        int r = table.getSelectedRow();
        if (r < 0 || r >= model.rows.size()) {
            JOptionPane.showMessageDialog(this, "Select a node first.", "No node selected", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        NodeEntry n = model.rows.get(r);
        if (n.num == state.myNodeNum()) {
            JOptionPane.showMessageDialog(this, "That's this radio.", "Own node", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        action.accept(n);
    }

    private void error(Exception ex) {
        JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    void refreshDbInfo() {
        int total = 0, dbOnly = 0;
        for (NodeEntry n : state.nodes()) { total++; if (n.fromDb) dbOnly++; }
        String file = state.nodeDb() == null ? "no database" : state.nodeDb().path().toString();
        long saved = state.nodeDb() == null ? 0 : state.nodeDb().lastSaved();
        dbInfo.setText(String.format("Node database: %s  ·  %d nodes (%d remembered from earlier sessions, shown grey)  ·  saved %s",
                file, total, dbOnly, saved == 0 ? "never" : Fmt.ago(saved) + " ago"));
    }

    void reload() {
        refreshDbInfo();
        int selNum = 0;
        int r = table.getSelectedRow();
        if (r >= 0 && r < model.rows.size()) selNum = model.rows.get(r).num;
        model.rows = state.nodes();
        model.fireTableDataChanged();
        for (int i = 0; i < model.rows.size(); i++) {
            if (model.rows.get(i).num == selNum) { table.setRowSelectionInterval(i, i); break; }
        }
    }

    /** Cheap periodic refresh so the "last heard" column keeps ticking. */
    void tick() {
        if (model.rows.isEmpty()) return;
        model.fireTableRowsUpdated(0, model.rows.size() - 1);
    }
}
