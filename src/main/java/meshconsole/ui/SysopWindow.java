package meshconsole.ui;

import meshconsole.bbs.BbsEngine;
import meshconsole.bbs.BbsStore;
import meshconsole.mesh.MeshState;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** Classic sysop console: status line, caller list, live activity, function keys. */
class SysopWindow extends JFrame {
    private static final Color BG = new Color(0, 0, 40), FG = new Color(200, 200, 200), HI = new Color(255, 255, 80), OK = new Color(80, 255, 80), BAD = new Color(255, 90, 90), BAR = new Color(0, 90, 160);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private final BbsEngine engine;
    private final BbsStore store;
    private final MeshState state;
    private final JLabel status = new JLabel(" ");
    private final JLabel waiting = new JLabel(" ");
    private final CallerModel callers = new CallerModel();
    private final JTable callerTable = new JTable(callers);
    private final JTextArea activity = new JTextArea();
    private final JTextField chat = new JTextField();
    private final JLabel chatTo = new JLabel("(select a caller)");

    private class CallerModel extends AbstractTableModel {
        final String[] cols = {"Caller", "ID", "Last", "Cmds", "Last command", "Banned"};
        List<BbsStore.Caller> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            BbsStore.Caller x = rows.get(r);
            return switch (c) { case 0 -> x.name; case 1 -> String.format("!%08x", x.num); case 2 -> Fmt.ago(x.last) + " ago"; case 3 -> x.commands; case 4 -> x.lastCommand; default -> store.banned.contains(x.num) ? "YES" : ""; };
        }
    }

    SysopWindow(BbsEngine engine, MeshState state) {
        super("Sysop – " + engine.store().name);
        this.engine = engine;
        this.store = engine.store();
        this.state = state;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);

        JPanel top = new JPanel(new GridLayout(2, 1));
        top.setBackground(BAR);
        status.setFont(MONO.deriveFont(Font.BOLD)); status.setForeground(Color.WHITE); status.setBorder(BorderFactory.createEmptyBorder(3, 8, 0, 8));
        waiting.setFont(MONO); waiting.setForeground(HI); waiting.setBorder(BorderFactory.createEmptyBorder(0, 8, 3, 8));
        top.add(status); top.add(waiting);
        root.add(top, BorderLayout.NORTH);

        callerTable.setFont(MONO); callerTable.setBackground(BG); callerTable.setForeground(FG); callerTable.setGridColor(new Color(40, 40, 90));
        callerTable.setSelectionBackground(new Color(0, 120, 200)); callerTable.setRowHeight(20);
        callerTable.getTableHeader().setFont(MONO.deriveFont(Font.BOLD)); callerTable.getTableHeader().setBackground(BAR); callerTable.getTableHeader().setForeground(Color.WHITE);
        DefaultTableCellRenderer dr = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) { c.setBackground(BG); c.setForeground(col == 5 && "YES".equals(String.valueOf(v)) ? BAD : FG); }
                return c;
            }
        };
        for (int i = 0; i < callers.cols.length; i++) callerTable.getColumnModel().getColumn(i).setCellRenderer(dr);
        callerTable.getColumnModel().getColumn(0).setPreferredWidth(160); callerTable.getColumnModel().getColumn(4).setPreferredWidth(260);
        callerTable.getSelectionModel().addListSelectionListener(e -> { BbsStore.Caller c = selected(); chatTo.setText(c == null ? "(select a caller)" : "Chat with " + c.name + ":"); });
        JScrollPane callerScroll = new JScrollPane(callerTable); callerScroll.getViewport().setBackground(BG);
        callerScroll.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BAR), "Callers", 0, 0, MONO, HI));

        activity.setEditable(false); activity.setFont(MONO); activity.setBackground(BG); activity.setForeground(OK); activity.setCaretColor(FG);
        JScrollPane actScroll = new JScrollPane(activity); actScroll.getViewport().setBackground(BG);
        actScroll.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BAR), "Activity", 0, 0, MONO, HI));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, callerScroll, actScroll); split.setResizeWeight(0.35); split.setBackground(BG); split.setBorder(null);
        root.add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout()); bottom.setBackground(BG);
        JPanel chatRow = new JPanel(new BorderLayout(6, 0)); chatRow.setBackground(BG);
        chatTo.setFont(MONO); chatTo.setForeground(HI); chatRow.add(chatTo, BorderLayout.WEST);
        chat.setFont(MONO); chat.setBackground(new Color(0, 0, 70)); chat.setForeground(Color.WHITE); chat.setCaretColor(Color.WHITE);
        chat.addActionListener(e -> doChat());
        chatRow.add(chat, BorderLayout.CENTER);
        bottom.add(chatRow, BorderLayout.NORTH);
        JLabel keys = new JLabel(" F1 On/Off   F2 Chat (Enter sends)   F3 Post bulletin   F4 Ban/unban caller   F5 Clear activity   F6 Mail list   F9 Save   Esc Close ");
        keys.setFont(MONO); keys.setForeground(Color.WHITE); keys.setOpaque(true); keys.setBackground(BAR);
        bottom.add(keys, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);

        bind("F1", () -> engine.setEnabled(!store.enabled));
        bind("F2", () -> chat.requestFocusInWindow());
        bind("F3", this::postBulletin);
        bind("F4", this::toggleBan);
        bind("F5", () -> activity.setText(""));
        bind("F6", this::showMail);
        bind("F9", () -> { store.save(); appendActivity("saved bbs.json"); });
        bind("ESCAPE", () -> setVisible(false));

        for (String l : engine.activity()) activity.append(l + "\n");
        engine.setListener(new BbsEngine.Listener() {
            @Override public void onActivity(String line) { SwingUtilities.invokeLater(() -> { appendActivity(line); refresh(); }); }
            @Override public void onCaller(int num, String command) { SwingUtilities.invokeLater(() -> { waiting.setText("Caller: " + state.nodeName(num) + "  →  " + command); refresh(); }); }
            @Override public void onStateChanged() { SwingUtilities.invokeLater(SysopWindow.this::refresh); }
        });
        new Timer(5000, e -> refresh()).start();
        refresh();
        setSize(960, 640);
        setLocationRelativeTo(null);
    }

    private void bind(String key, Runnable r) {
        JRootPane rp = getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        rp.getActionMap().put(key, new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); } });
    }

    private BbsStore.Caller selected() {
        int r = callerTable.getSelectedRow();
        return r < 0 || r >= callers.rows.size() ? null : callers.rows.get(r);
    }

    private void appendActivity(String line) {
        activity.append(line + "\n");
        if (activity.getDocument().getLength() > 100_000) { try { activity.getDocument().remove(0, 20_000); } catch (Exception ignored) { } }
        activity.setCaretPosition(activity.getDocument().getLength());
    }

    void refresh() {
        status.setText(engine.stateLine());
        status.setBackground(store.enabled ? new Color(0, 110, 40) : new Color(130, 20, 20)); status.setOpaque(true);
        if (waiting.getText().isBlank() || waiting.getText().startsWith("Waiting") || waiting.getText().startsWith("BBS")) waiting.setText(store.enabled ? "Waiting for caller…" : "BBS is OFF – F1 to bring it online");
        List<BbsStore.Caller> l = new ArrayList<>(store.callers.values());
        l.sort((a, b) -> Long.compare(b.last, a.last));
        BbsStore.Caller sel = selected();
        callers.rows = l; callers.fireTableDataChanged();
        if (sel != null) for (int i = 0; i < l.size(); i++) if (l.get(i).num == sel.num) { callerTable.setRowSelectionInterval(i, i); break; }
        setTitle("Sysop – " + store.name + (store.enabled ? "  [ONLINE]" : "  [OFFLINE]"));
    }

    private void doChat() {
        BbsStore.Caller c = selected();
        String t = chat.getText().trim();
        if (c == null || t.isEmpty()) return;
        engine.sysopChat(c.num, t);
        chat.setText("");
    }

    private void postBulletin() {
        String t = JOptionPane.showInputDialog(this, "Bulletin text (≤ 180 characters):");
        if (t == null || t.isBlank()) return;
        BbsStore.Post p = new BbsStore.Post(); p.id = store.nextId(); p.time = System.currentTimeMillis(); p.author = state.myNodeNum(); p.authorName = "sysop"; p.text = t.trim();
        store.posts.add(p); store.save(); appendActivity("sysop posted #" + p.id + ": " + p.text); refresh();
    }

    private void toggleBan() {
        BbsStore.Caller c = selected();
        if (c == null) return;
        if (store.banned.remove(c.num)) appendActivity("unbanned " + c.name); else { store.banned.add(c.num); appendActivity("BANNED " + c.name); }
        store.save(); refresh();
    }

    private void showMail() {
        StringBuilder sb = new StringBuilder();
        for (BbsStore.Mail m : store.mail) sb.append(String.format("#%d %s → %s [%s]: %s%n", m.id, m.fromName, state.nodeName(m.to), m.delivered == 0 ? "waiting" : m.delivered < 0 ? "notified" : "read", m.text));
        JTextArea a = new JTextArea(sb.length() == 0 ? "No mail." : sb.toString(), 12, 60); a.setEditable(false); a.setFont(MONO);
        JOptionPane.showMessageDialog(this, new JScrollPane(a), "Mail", JOptionPane.PLAIN_MESSAGE);
    }
}
