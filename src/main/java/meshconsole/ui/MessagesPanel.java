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
    private final MeshClient client;
    private final MeshState state;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JComboBox<Dest> dest = new JComboBox<>();
    private final JComboBox<String> channel = new JComboBox<>();
    private final JTextField input = new JTextField();
    private final JButton send = new JButton("Send");
    private final JButton resend = new JButton("Resend selected");
    private final JLabel hint = new JLabel(" ");

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
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        JPanel line1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        line1.add(new JLabel("To:"));
        dest.setPrototypeDisplayValue(new Dest(0, "A reasonably long node name here"));
        line1.add(dest);
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
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(MessagesPanel.this::refreshDests); }
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

    private void refreshDests() {
        Dest sel = (Dest) dest.getSelectedItem();
        int selNum = sel == null ? ChatMessage.BROADCAST : sel.num();
        List<Dest> wanted = new ArrayList<>();
        wanted.add(new Dest(ChatMessage.BROADCAST, "Broadcast (everyone)"));
        int my = state.myNodeNum();
        for (NodeEntry n : state.nodes()) if (n.num != my) wanted.add(new Dest(n.num, n.displayName() + "  " + n.idString()));
        boolean same = wanted.size() == dest.getItemCount();
        if (same) for (int i = 0; i < wanted.size(); i++) if (!wanted.get(i).equals(dest.getItemAt(i))) { same = false; break; }
        if (same) return;
        dest.removeAllItems();
        for (Dest d : wanted) {
            dest.addItem(d);
            if (d.num() == selNum) dest.setSelectedItem(d);
        }
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

    void reload() {
        int sel = table.getSelectedRow();
        boolean atBottom = sel < 0 || sel == model.rows.size() - 1;
        model.rows = state.messages();
        model.fireTableDataChanged();
        if (!model.rows.isEmpty() && atBottom) {
            int last = model.rows.size() - 1;
            table.scrollRectToVisible(table.getCellRect(last, 0, true));
        } else if (sel >= 0 && sel < model.rows.size()) {
            table.setRowSelectionInterval(sel, sel);
        }
    }
}
