package meshconsole.ui;

import meshconsole.bbs.BbsEngine;
import meshconsole.bbs.BbsStore;
import meshconsole.mesh.MeshState;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Bulletin-board bot: settings, posts, mail, activity. */
class BbsPanel extends JPanel {
    private final BbsEngine engine;
    private final BbsStore store;
    private final MeshState state;
    private final JCheckBox enabled = new JCheckBox("BBS enabled (answers direct messages to this node)");
    private final JTextField name = new JTextField(14), welcome = new JTextField(30), trigger = new JTextField(8);
    private final JCheckBox channelTrigger = new JCheckBox("Also answer this word on the public channel:");
    private final JSpinner cooldown = new JSpinner(new SpinnerNumberModel(20, 5, 600, 5));
    private final JSpinner maxPosts = new JSpinner(new SpinnerNumberModel(200, 10, 5000, 10));
    private final PostModel posts = new PostModel();
    private final MailModel mail = new MailModel();
    private final JTable postTable = new JTable(posts), mailTable = new JTable(mail);
    private final JTextArea activity = new JTextArea();
    private final JLabel stats = new JLabel(" ");

    private class PostModel extends AbstractTableModel {
        final String[] cols = {"#", "Time", "Author", "Text"};
        List<BbsStore.Post> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) { BbsStore.Post p = rows.get(r); return switch (c) { case 0 -> p.id; case 1 -> Fmt.time(p.time); case 2 -> p.authorName; default -> p.text; }; }
    }
    private class MailModel extends AbstractTableModel {
        final String[] cols = {"#", "Time", "From", "To", "Status", "Text"};
        List<BbsStore.Mail> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) { BbsStore.Mail m = rows.get(r); return switch (c) { case 0 -> m.id; case 1 -> Fmt.time(m.time); case 2 -> m.fromName; case 3 -> state.nodeName(m.to); case 4 -> m.delivered == 0 ? "waiting" : m.delivered < 0 ? "notified" : "read"; default -> m.text; }; }
    }

    BbsPanel(BbsEngine engine, MeshState state) {
        this.engine = engine;
        this.store = engine.store();
        this.state = state;
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel r1 = row(); r1.add(enabled); r1.add(new JLabel("Name:")); r1.add(name); r1.add(new JLabel("Welcome:")); r1.add(welcome);
        JPanel r2 = row(); r2.add(channelTrigger); r2.add(trigger); r2.add(new JLabel("   Cooldown per node (s):")); r2.add(cooldown); r2.add(new JLabel("   Keep posts:")); r2.add(maxPosts);
        JButton save = new JButton("Save settings"); save.addActionListener(e -> applySettings()); r2.add(save);
        JPanel r3 = row(); r3.add(new JLabel("<html>Users DM this node: <b>?</b> menu · <b>B</b> bulletins · <b>R n</b> read · <b>P text</b> post · <b>D n</b> delete own · <b>M</b> read mail · <b>M name text</b> leave mail (delivered when they're next heard) · <b>N</b> nodes/1h · <b>S</b> their signal · <b>W</b> weather · <b>I</b> info · <b>PING</b></html>"));
        JPanel r4 = row(); r4.add(stats);
        top.add(r1); top.add(r2); top.add(r3); top.add(r4);
        add(top, BorderLayout.NORTH);

        postTable.setRowHeight(20); mailTable.setRowHeight(20);
        postTable.getColumnModel().getColumn(0).setMaxWidth(40); postTable.getColumnModel().getColumn(3).setPreferredWidth(500);
        mailTable.getColumnModel().getColumn(0).setMaxWidth(40); mailTable.getColumnModel().getColumn(5).setPreferredWidth(400);
        JPanel postBox = new JPanel(new BorderLayout()); postBox.setBorder(BorderFactory.createTitledBorder("Bulletins"));
        postBox.add(new JScrollPane(postTable), BorderLayout.CENTER);
        JPanel pb = row();
        JButton delPost = new JButton("Delete selected"), sysopPost = new JButton("Post as sysop…"), ban = new JButton("Ban author");
        delPost.addActionListener(e -> { int r = postTable.getSelectedRow(); if (r >= 0) { store.posts.remove(posts.rows.get(r)); store.save(); reload(); } });
        sysopPost.addActionListener(e -> {
            String t = JOptionPane.showInputDialog(this, "Bulletin text (≤ 180 characters):");
            if (t == null || t.isBlank()) return;
            BbsStore.Post p = new BbsStore.Post(); p.id = store.nextId(); p.time = System.currentTimeMillis(); p.author = state.myNodeNum(); p.authorName = "sysop"; p.text = t.trim();
            store.posts.add(p); store.save(); reload();
        });
        ban.addActionListener(e -> { int r = postTable.getSelectedRow(); if (r >= 0) { store.banned.add(posts.rows.get(r).author); store.save(); reload(); } });
        pb.add(delPost); pb.add(sysopPost); pb.add(ban);
        postBox.add(pb, BorderLayout.SOUTH);
        JPanel mailBox = new JPanel(new BorderLayout()); mailBox.setBorder(BorderFactory.createTitledBorder("Mail"));
        mailBox.add(new JScrollPane(mailTable), BorderLayout.CENTER);
        JPanel mb = row();
        JButton delMail = new JButton("Delete selected");
        delMail.addActionListener(e -> { int r = mailTable.getSelectedRow(); if (r >= 0) { store.mail.remove(mail.rows.get(r)); store.save(); reload(); } });
        JButton unban = new JButton("Clear ban list");
        unban.addActionListener(e -> { store.banned.clear(); store.save(); reload(); });
        mb.add(delMail); mb.add(unban);
        mailBox.add(mb, BorderLayout.SOUTH);
        JSplitPane tables = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, postBox, mailBox); tables.setResizeWeight(0.6);
        activity.setEditable(false); activity.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane act = new JScrollPane(activity); act.setBorder(BorderFactory.createTitledBorder("Activity"));
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tables, act); main.setResizeWeight(0.65);
        add(main, BorderLayout.CENTER);

        enabled.setSelected(store.enabled); name.setText(store.name); welcome.setText(store.welcome); channelTrigger.setSelected(store.channelTrigger);
        trigger.setText(store.triggerWord); cooldown.setValue(store.cooldownSec); maxPosts.setValue(store.maxPosts);
        enabled.addActionListener(e -> applySettings());
        for (String l : engine.activity()) activity.append(l + "\n");
        engine.setListener(l -> SwingUtilities.invokeLater(() -> { activity.append(l + "\n"); activity.setCaretPosition(activity.getDocument().getLength()); reload(); }));
        reload();
    }

    private void applySettings() {
        store.enabled = enabled.isSelected(); store.name = name.getText().trim().isEmpty() ? "Mesh BBS" : name.getText().trim(); store.welcome = welcome.getText().trim();
        store.channelTrigger = channelTrigger.isSelected(); store.triggerWord = trigger.getText().trim().isEmpty() ? "bbs?" : trigger.getText().trim();
        store.cooldownSec = (Integer) cooldown.getValue(); store.maxPosts = (Integer) maxPosts.getValue();
        store.save();
        reload();
    }

    void reload() {
        posts.rows = new ArrayList<>(store.posts); posts.fireTableDataChanged();
        mail.rows = new ArrayList<>(store.mail); mail.fireTableDataChanged();
        stats.setText((store.enabled ? "Running" : "Stopped") + "  ·  " + store.posts.size() + " posts, " + store.mail.size() + " mail, " + store.commandsServed + " commands served, " + store.repliesSent + " replies, " + store.banned.size() + " banned");
    }

    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }
}
