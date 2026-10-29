package meshconsole.ui;

import meshconsole.mesh.ChatMessage;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;
import org.meshtastic.proto.ChannelProtos.Channel;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class MessagesPanel extends JPanel {
    private final Coalesce nodesCoalesce = new Coalesce(2000, MessagesPanel.this::refreshDests);
    private final MeshClient client;
    private final MeshState state;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JComboBox<Dest> dest = new JComboBox<>();
    private final JTextField destFilter = new JTextField(9);
    private final JComboBox<String> channel = new JComboBox<>();
    private final JTextField input = new JTextField();
    private final JButton send = new JButton("Send");
    private final JButton resend = new JButton("Resend selected");
    private final JLabel hint = new JLabel(" ");
    private final JTextField search = new JTextField(16);
    private final JTextArea thread = new JTextArea(4, 40);

    record Dest(int num, String label) {
        @Override public String toString() { return label; }
    }

    private static final String[] COLS = {"Time", "Dir", "From", "To", "Ch", "Message", "Status", "RSSI", "SNR", "Hops", "Air ms"};

    private class Model extends AbstractTableModel {
        List<ChatMessage> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            ChatMessage m = rows.get(r);
            return switch (c) {
                case 0 -> Fmt.time(m.time);
                case 1 -> m.outgoing ? "→" : "←";
                case 2 -> state.nodeName(m.from);
                case 3 -> state.nodeName(m.to);
                case 4 -> m.channel;
                case 5 -> messageText(m);
                case 6 -> statusText(m);
                case 7 -> m.rssi == 0 ? "" : m.rssi + " dBm";
                case 8 -> m.outgoing || (m.rssi == 0 && m.snr == 0) ? "" : String.format("%.1f", m.snr);
                case 9 -> m.hops < 0 ? "" : String.valueOf(m.hops);
                case 10 -> m.airtimeMs == 0 ? "" : String.valueOf(m.airtimeMs);
                default -> "";
            };
        }
    }

    private String messageText(ChatMessage m) {
        String t = m.text;
        if (m.emoji) {
            String target = quoted(m.replyId);
            return t + "  (reaction" + (target.isEmpty() ? "" : " to \u201c" + target + "\u201d") + ")";
        }
        if (m.replyId != 0) {
            String target = quoted(m.replyId);
            if (!target.isEmpty()) t = "\u21a9 \u201c" + target + "\u201d: " + t;
        }
        if (m.fromStoreForward) t = "[S&F] " + t;
        return t;
    }

    private String quoted(int packetId) {
        if (packetId == 0) return "";
        for (ChatMessage x : model.rows) if (x.packetId == packetId) return x.text.length() > 30 ? x.text.substring(0, 30) + "…" : x.text;
        return "";
    }

    static String statusText(ChatMessage m) {
        return switch (m.status) {
            case QUEUED -> "⏳ queued";
            case TRANSMITTED -> "📡 " + (m.statusDetail.isEmpty() ? "sent" : m.statusDetail);
            case DELIVERED -> "✔ " + m.statusDetail;
            case FAILED -> "✖ " + m.statusDetail;
            case RECEIVED -> "received";
            case HISTORY -> m.statusDetail;
        };
    }

    MessagesPanel(MeshClient client) {
        this.client = client;
        this.state = client.state();
        setLayout(new BorderLayout(6, 6));

        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        int[] widths = {100, 30, 120, 120, 30, 400, 200, 60, 50, 40, 50};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.getColumnModel().getColumn(6).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel && row < model.rows.size()) {
                    ChatMessage m = model.rows.get(row);
                    c.setForeground(switch (m.status) {
                        case DELIVERED -> new Color(0, 130, 0);
                        case FAILED -> Color.RED;
                        case QUEUED -> Color.GRAY;
                        default -> Color.BLACK;
                    });
                } else if (sel) c.setForeground(t.getSelectionForeground());
                return c;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> resend.setEnabled(selectedOutgoing() != null));
        JPanel searchRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        searchRow.add(new JLabel("Search:")); searchRow.add(search);
        search.setToolTipText("Filter by text, node name or channel; empty shows all");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { reload(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { reload(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { reload(); }
        });
        JCheckBox onlyDm = new JCheckBox("DMs only"); onlyDm.addActionListener(e -> { dmOnly = onlyDm.isSelected(); reload(); }); searchRow.add(onlyDm);
        thread.setEditable(false); thread.setLineWrap(true); thread.setWrapStyleWord(true);
        JScrollPane threadScroll = new JScrollPane(thread); threadScroll.setBorder(BorderFactory.createTitledBorder("Thread (replies and reactions to the selected message)"));
        table.getSelectionModel().addListSelectionListener(e -> showThread());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) messagePopup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) messagePopup(e); }
        });
        JPanel center = new JPanel(new BorderLayout());
        center.add(searchRow, BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), threadScroll); split.setResizeWeight(0.8);
        center.add(split, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        JPanel line1 = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        line1.add(new JLabel("To:"));
        dest.setPrototypeDisplayValue(new Dest(0, "A reasonably long node name here"));
        line1.add(dest);
        destFilter.setToolTipText("Type part of a node name, short name or !id to narrow the list (nodes are listed alphabetically)");
        destFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshDests(true); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshDests(true); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshDests(true); }
        });
        line1.add(new JLabel("find:")); line1.add(destFilter);
        line1.add(new JLabel("Channel:"));
        channel.addItem("0");
        line1.add(channel);
        resend.setEnabled(false);
        resend.setToolTipText("Send the selected outgoing message again (new packet id) — use when it failed or was never delivered");
        line1.add(resend);
        JCheckBox retry = new JCheckBox("Auto-retry DMs", client.autoRetry());
        retry.setToolTipText("When the radio reports MAX_RETRANSMIT / NO_ROUTE, or a direct message gets no delivery ack, send it again after a pause");
        JSpinner attempts = new JSpinner(new SpinnerNumberModel(client.maxAttempts(), 1, 50, 1));
        JSpinner delay = new JSpinner(new SpinnerNumberModel(client.retryDelaySec(), 5, 600, 5));
        java.awt.event.ActionListener apply = e -> client.setAutoRetry(retry.isSelected(), (Integer) attempts.getValue(), (Integer) delay.getValue());
        retry.addActionListener(apply);
        attempts.addChangeListener(e -> apply.actionPerformed(null));
        delay.addChangeListener(e -> apply.actionPerformed(null));
        line1.add(retry);
        line1.add(new JLabel("max attempts:")); line1.add(attempts);
        line1.add(new JLabel("every (s):")); line1.add(delay);
        line1.add(hint);
        bottom.add(line1, BorderLayout.NORTH);
        JPanel line2 = new JPanel(new BorderLayout(4, 0));
        line2.add(input, BorderLayout.CENTER);
        line2.add(send, BorderLayout.EAST);
        bottom.add(line2, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        send.addActionListener(e -> doSend());
        input.addActionListener(e -> doSend());
        resend.addActionListener(e -> {
            ChatMessage m = selectedOutgoing();
            if (m == null) return;
            try {
                client.resend(m);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Resend failed", JOptionPane.ERROR_MESSAGE);
            }
        });
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateHint(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateHint(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateHint(); }
        });

        dest.addItem(new Dest(ChatMessage.BROADCAST, "Broadcast (everyone)"));
        reload();

        state.addListener(new MeshState.Listener() {
            @Override public void onMessage(ChatMessage m, boolean isNew) { SwingUtilities.invokeLater(MessagesPanel.this::reload); }
            @Override public void onNodesChanged() { nodesCoalesce.request(); }
            @Override public void onStatusChanged() { SwingUtilities.invokeLater(MessagesPanel.this::refreshChannels); }
        });
    }

    private void updateHint() {
        int bytes = input.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        hint.setText(bytes + " / 200 bytes");
        hint.setForeground(bytes > 200 ? Color.RED : Color.DARK_GRAY);
    }

    private ChatMessage selectedOutgoing() {
        int r = table.getSelectedRow();
        if (r < 0 || r >= model.rows.size()) return null;
        ChatMessage m = model.rows.get(r);
        return m.outgoing ? m : null;
    }

    private void doSend() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        if (!client.isConnected()) {
            JOptionPane.showMessageDialog(this, "Connect to the radio first.", "Not connected", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200) {
            JOptionPane.showMessageDialog(this, "Meshtastic text messages are limited to ~200 bytes.", "Too long", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Dest d = (Dest) dest.getSelectedItem();
        int ch = channel.getSelectedIndex() < 0 ? 0 : Integer.parseInt((String) channel.getSelectedItem());
        try {
            client.sendText(text, d == null ? ChatMessage.BROADCAST : d.num(), ch);
            input.setText("");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Send failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Called from the Nodes tab: pre-select a direct-message destination. */
    void selectDestination(int num) {
        for (int i = 0; i < dest.getItemCount(); i++) {
            if (dest.getItemAt(i).num() == num) { dest.setSelectedIndex(i); return; }
        }
        Dest d = new Dest(num, state.nodeName(num));
        dest.addItem(d);
        dest.setSelectedItem(d);
    }

    private void refreshDests() { refreshDests(false); }

    private void refreshDests(boolean fromFilter) {
        Dest sel = (Dest) dest.getSelectedItem();
        int selNum = sel == null ? ChatMessage.BROADCAST : sel.num();
        List<Dest> wanted = new ArrayList<>();
        wanted.add(new Dest(ChatMessage.BROADCAST, "Broadcast (everyone)"));
        int my = state.myNodeNum();
        List<NodeEntry> sorted = new ArrayList<>(state.nodes());
        sorted.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        String q = destFilter.getText().trim().toLowerCase();
        for (NodeEntry n : sorted) if (n.num != my && (q.isEmpty() || n.displayName().toLowerCase().contains(q) || n.idString().contains(q) || n.shortName.toLowerCase().contains(q))) wanted.add(new Dest(n.num, n.displayName() + "  " + n.idString()));
        boolean same = wanted.size() == dest.getItemCount();
        if (same) for (int i = 0; i < wanted.size(); i++) if (!wanted.get(i).equals(dest.getItemAt(i))) { same = false; break; }
        if (same) return;
        dest.removeAllItems();
        for (Dest d : wanted) {
            dest.addItem(d);
            if (d.num() == selNum) dest.setSelectedItem(d);
        }
        // when filtering to a single node, pick it straight away
        if (fromFilter && wanted.size() == 2) dest.setSelectedIndex(1);
    }

    private void refreshChannels() {
        List<Channel> chs = state.channels();
        if (chs.isEmpty()) return;
        Object sel = channel.getSelectedItem();
        channel.removeAllItems();
        for (Channel c : chs) channel.addItem(String.valueOf(c.getIndex()));
        if (sel != null) channel.setSelectedItem(sel);
        StringBuilder tip = new StringBuilder("<html>");
        for (Channel c : chs) tip.append(c.getIndex()).append(": ").append(c.getSettings().getName().isEmpty() ? "(default)" : c.getSettings().getName()).append("<br>");
        channel.setToolTipText(tip.toString());
    }

    private boolean dmOnly;
    private static final String[] REACTIONS = {"👍", "👎", "❤️", "😂", "😮", "😢", "🔥", "👋", "✅", "❓"};

    private void messagePopup(java.awt.event.MouseEvent e) {
        int r = table.rowAtPoint(e.getPoint());
        if (r < 0 || r >= model.rows.size()) return;
        table.setRowSelectionInterval(r, r);
        ChatMessage m = model.rows.get(r);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem head = new JMenuItem((m.outgoing ? "Your message" : "From " + state.nodeName(m.from)) + ": " + (m.text.length() > 40 ? m.text.substring(0, 40) + "…" : m.text)); head.setEnabled(false); menu.add(head);
        menu.addSeparator();
        boolean canReact = m.packetId != 0 && client.isConnected();
        if (!canReact) { JMenuItem why = new JMenuItem(m.packetId == 0 ? "(no packet id on this message – can't react or reply)" : "(not connected to a radio)"); why.setEnabled(false); menu.add(why); }
        // Reactions directly in the menu (a submenu opens on hover and is easy to miss or push off-screen)
        java.awt.Font emojiFont = new java.awt.Font(System.getProperty("os.name", "").toLowerCase().contains("win") ? "Segoe UI Emoji" : "Dialog", java.awt.Font.PLAIN, 16);
        JPanel strip = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 2)); strip.setOpaque(false);
        for (String emo : REACTIONS) {
            JButton b = new JButton(emo); b.setFont(emojiFont); b.setMargin(new java.awt.Insets(2, 6, 2, 6)); b.setFocusable(false); b.setEnabled(canReact);
            b.setToolTipText("React " + emo); b.addActionListener(a -> { menu.setVisible(false); react(m, emo); }); strip.add(b);
        }
        JButton other = new JButton("…"); other.setFont(emojiFont); other.setMargin(new java.awt.Insets(2, 6, 2, 6)); other.setFocusable(false); other.setEnabled(canReact);
        other.setToolTipText("React with any emoji"); other.addActionListener(a -> { menu.setVisible(false); String s = JOptionPane.showInputDialog(this, "Emoji:"); if (s != null && !s.isBlank()) react(m, s.trim()); }); strip.add(other);
        JLabel reactLabel = new JLabel("  React:"); reactLabel.setEnabled(canReact); strip.add(reactLabel, 0);
        menu.add(strip);
        JMenuItem reply = new JMenuItem("Reply…"); reply.setEnabled(canReact); reply.addActionListener(a -> replyTo(m)); menu.add(reply);
        JMenuItem dm = new JMenuItem("Direct message " + state.nodeName(m.from) + "…"); dm.setEnabled(!m.outgoing); dm.addActionListener(a -> selectDestination(m.from)); menu.add(dm);
        JMenuItem copy = new JMenuItem("Copy text"); copy.addActionListener(a -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(m.text), null)); menu.add(copy);
        menu.show(table, e.getX(), e.getY());
    }

    /** A reaction goes where the original went: to the channel for a broadcast, to the sender for a DM. */
    private void react(ChatMessage m, String emo) {
        int to = m.isBroadcast() ? ChatMessage.BROADCAST : (m.outgoing ? m.to : m.from);
        try { client.sendText(emo, to, m.channel, m.packetId, true); state.emitLog("Reaction " + emo + " sent to " + (to == ChatMessage.BROADCAST ? "channel " + m.channel : state.nodeName(to)) + " for message " + Integer.toUnsignedString(m.packetId)); }
        catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "Reaction", JOptionPane.ERROR_MESSAGE); }
    }

    private void replyTo(ChatMessage m) {
        String s = JOptionPane.showInputDialog(this, "Reply to " + (m.outgoing ? "your message" : state.nodeName(m.from)) + " (\"" + (m.text.length() > 30 ? m.text.substring(0, 30) + "…" : m.text) + "\"):");
        if (s == null || s.isBlank()) return;
        int to = m.isBroadcast() ? ChatMessage.BROADCAST : (m.outgoing ? m.to : m.from);
        try { client.sendText(s.trim(), to, m.channel, m.packetId, false); }
        catch (IOException ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "Reply", JOptionPane.ERROR_MESSAGE); }
    }

    private void showThread() {
        int r = table.getSelectedRow();
        if (r < 0 || r >= model.rows.size()) { thread.setText(""); return; }
        ChatMessage root = model.rows.get(r);
        // walk up to the root of the thread
        List<ChatMessage> all = state.messages();
        ChatMessage top = root;
        for (int guard = 0; guard < 20 && top.replyId != 0; guard++) { ChatMessage parent = null; for (ChatMessage x : all) if (x.packetId == top.replyId) parent = x; if (parent == null) break; top = parent; }
        StringBuilder sb = new StringBuilder();
        appendThread(sb, top, all, 0);
        thread.setText(sb.toString());
        thread.setCaretPosition(0);
    }

    private void appendThread(StringBuilder sb, ChatMessage m, List<ChatMessage> all, int depth) {
        sb.append("  ".repeat(depth)).append(Fmt.time(m.time)).append(' ').append(state.nodeName(m.from)).append(": ").append(m.emoji ? "(reaction) " : "").append(m.text).append('\n');
        if (depth > 10) return;
        for (ChatMessage x : all) if (x.replyId != 0 && x.replyId == m.packetId && x != m) appendThread(sb, x, all, depth + 1);
    }

    void reload() {
        int sel = table.getSelectedRow();
        boolean atBottom = sel < 0 || sel == model.rows.size() - 1;
        List<ChatMessage> src = state.messages();
        String q = search.getText().trim().toLowerCase();
        if (!q.isEmpty() || dmOnly) {
            List<ChatMessage> f = new java.util.ArrayList<>();
            for (ChatMessage m : src) {
                if (dmOnly && m.isBroadcast()) continue;
                if (!q.isEmpty() && !(m.text.toLowerCase().contains(q) || state.nodeName(m.from).toLowerCase().contains(q) || state.nodeName(m.to).toLowerCase().contains(q) || String.valueOf(m.channel).equals(q))) continue;
                f.add(m);
            }
            src = f;
        }
        model.rows = src;
        model.fireTableDataChanged();
        if (!model.rows.isEmpty() && atBottom) {
            int last = model.rows.size() - 1;
            table.scrollRectToVisible(table.getCellRect(last, 0, true));
        } else if (sel >= 0 && sel < model.rows.size()) {
            table.setRowSelectionInterval(sel, sel);
        }
    }
}
