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
    private final AnalysisPanel.BarChart dash = new AnalysisPanel.BarChart();
    private boolean loadWarned;
    private final JLabel dashText = new JLabel(" ");

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
        JPanel r2b = row();
        JTextField wmata = new JTextField(store.wmataKey, 14), discord = new JTextField(store.discordWebhook, 28), station = new JTextField(store.station, 5);
        r2b.add(new JLabel("Weather station:")); r2b.add(station); r2b.add(new JLabel("WMATA API key (developer.wmata.com):")); r2b.add(wmata); r2b.add(new JLabel("Discord webhook (bulletins mirror):")); r2b.add(discord);
        JButton saveX = new JButton("Save"); saveX.addActionListener(e -> { store.station = station.getText().trim().toUpperCase(); store.wmataKey = wmata.getText().trim(); store.discordWebhook = discord.getText().trim(); store.save(); }); r2b.add(saveX);
        JButton postEvent = new JButton("Post event…");
        postEvent.addActionListener(e -> {
            JTextField when = new JTextField("", 12), text = new JTextField("", 30);
            JPanel form = new JPanel(new GridLayout(0, 2, 4, 4)); form.add(new JLabel("When (yyyy-MM-dd HH:mm, blank = none):")); form.add(when); form.add(new JLabel("Event:")); form.add(text);
            if (JOptionPane.showConfirmDialog(this, form, "Post event", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION || text.getText().isBlank()) return;
            BbsStore.Event ev = new BbsStore.Event(); ev.id = store.nextId(); ev.time = System.currentTimeMillis(); ev.text = text.getText().trim();
            try { if (!when.getText().isBlank()) ev.when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").parse(when.getText().trim()).getTime(); } catch (java.text.ParseException ex) { ev.when = 0; }
            store.events.add(ev); store.events.sort((a, b) -> Long.compare(a.when, b.when)); store.save(); engine.bridge("📅 " + ev.text); reload();
        });
        JButton clearEvents = new JButton("Clear past events"); clearEvents.addActionListener(e -> { long now = System.currentTimeMillis(); store.events.removeIf(ev -> ev.when > 0 && ev.when < now); store.save(); reload(); });
        r2b.add(postEvent); r2b.add(clearEvents);
        JPanel r2c = row();
        JCheckBox advOn = new JCheckBox("Advertise daily at", store.advertOn); JTextField advTime = new JTextField(store.advertTime, 5); JTextField advText = new JTextField(store.advertText.isBlank() ? store.defaultAdvert() : store.advertText, 50);
        r2c.add(advOn); r2c.add(advTime); r2c.add(new JLabel("text:")); r2c.add(advText);
        JButton advSave = new JButton("Save advert"); advSave.addActionListener(e -> { store.advertOn = advOn.isSelected(); store.advertTime = advTime.getText().trim(); store.advertText = advText.getText().trim(); store.save(); });
        JButton advNow = new JButton("Send now"); advNow.addActionListener(e -> { store.advertText = advText.getText().trim(); store.save(); if (!store.enabled) { JOptionPane.showMessageDialog(this, "Turn the BBS on first – advertising a board that's off just annoys people."); return; } engine.advertiseNow(); });
        r2c.add(advSave); r2c.add(advNow);
        // ---- 2.1: MOTD, poll, sysop password, digest time
        JPanel r2d = row();
        JTextField motd = new JTextField(store.motd, 30), pw = new JPasswordField(store.sysopPassword, 10), digest = new JTextField(store.digestTime, 5);
        r2d.add(new JLabel("MOTD:")); r2d.add(motd); r2d.add(new JLabel("Sysop DM password:")); r2d.add(pw); r2d.add(new JLabel("Digest at:")); r2d.add(digest);
        JButton save2d = new JButton("Save"); save2d.addActionListener(e -> { store.motd = motd.getText().trim(); store.sysopPassword = new String(((JPasswordField) pw).getPassword()).trim(); store.digestTime = digest.getText().trim(); store.save(); }); r2d.add(save2d);
        JButton poll = new JButton("New poll…");
        poll.addActionListener(e -> {
            JTextField q = new JTextField(store.pollQuestion, 30), opts = new JTextField(String.join("; ", store.pollOptions), 30);
            JPanel form = new JPanel(new GridLayout(0, 2, 4, 4)); form.add(new JLabel("Question:")); form.add(q); form.add(new JLabel("Options (semicolon-separated):")); form.add(opts);
            if (JOptionPane.showConfirmDialog(this, form, "Poll", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            store.pollQuestion = q.getText().trim(); store.pollOptions.clear(); for (String o : opts.getText().split(";")) if (!o.isBlank()) store.pollOptions.add(o.trim()); store.pollVotes.clear(); store.save(); reload();
        });
        JButton closePoll = new JButton("Close poll"); closePoll.addActionListener(e -> { store.pollQuestion = ""; store.pollOptions.clear(); store.pollVotes.clear(); store.save(); reload(); });
        r2d.add(poll); r2d.add(closePoll);
        // ---- MeshNet peers + Discord bot
        JPanel r2e = row();
        JCheckBox netOn = new JCheckBox("MeshNet exchange daily at", store.netOn); JTextField netTime = new JTextField(store.netTime, 5);
        StringBuilder peerStr = new StringBuilder(); for (BbsStore.Peer pr : store.peers) peerStr.append(String.format("!%08x", pr.num)).append("=").append(pr.name).append(pr.secret.isBlank() ? "" : "=" + pr.secret).append("; ");
        JTextField peers = new JTextField(peerStr.toString().trim(), 30);
        r2e.add(netOn); r2e.add(netTime); r2e.add(new JLabel("peers (!id=Name[=secret]; …):")); r2e.add(peers);
        JButton savePeers = new JButton("Save peers"); savePeers.addActionListener(e -> {
            store.netOn = netOn.isSelected(); store.netTime = netTime.getText().trim();
            java.util.Map<Integer, BbsStore.Peer> old = new java.util.HashMap<>(); for (BbsStore.Peer pr : store.peers) old.put(pr.num, pr);
            store.peers.clear();
            for (String tok : peers.getText().split(";")) { tok = tok.trim(); if (tok.isEmpty()) continue; String[] kv = tok.split("=", 3); try { int num = (int) Long.parseLong(kv[0].trim().replace("!", ""), 16); BbsStore.Peer pr = old.getOrDefault(num, new BbsStore.Peer()); pr.num = num; if (kv.length > 1) pr.name = kv[1].trim(); pr.secret = kv.length > 2 ? kv[2].trim() : ""; store.peers.add(pr); } catch (NumberFormatException ignored) { } }
            store.save(); reload();
        }); r2e.add(savePeers);
        JButton netNow = new JButton("Exchange now"); netNow.addActionListener(e -> { if (!store.enabled) { JOptionPane.showMessageDialog(this, "Turn the BBS on first."); return; } int n = engine.netExchangeNow(); JOptionPane.showMessageDialog(this, "Sent HELLO to " + store.peers.size() + " peer(s) and " + n + " post(s)."); }); r2e.add(netNow);
        JPanel r2f = row();
        JTextField botTok = new JPasswordField(store.discordBotToken, 14), botCh = new JTextField(store.discordChannelId, 14);
        r2f.add(new JLabel("Discord → board (bot token / channel id; messages starting '!post' become bulletins):")); r2f.add(botTok); r2f.add(botCh);
        JButton saveBot = new JButton("Save"); saveBot.addActionListener(e -> { store.discordBotToken = new String(((JPasswordField) botTok).getPassword()).trim(); store.discordChannelId = botCh.getText().trim(); store.save(); }); r2f.add(saveBot);
        JPanel r3 = row(); r3.add(new JLabel("<html>Users DM this node: <b>?</b> menu · <b>B</b> bulletins · <b>R n</b> read · <b>P text</b> post · <b>D n</b> delete own · <b>M</b> read mail · <b>M name text</b> leave mail (delivered when they're next heard) · <b>N</b> nodes/1h · <b>S</b> signal · <b>T</b> traceroute · <b>STATS</b> · <b>TOP</b> · <b>WHO n</b> · <b>W/TAF/METRO</b> · <b>C call</b> · <b>E</b> events · <b>POLL / V n</b> · <b>GB</b> guestbook · <b>SUB WX|EVENTS</b> digest · <b>TRIVIA/GUESS/ADV</b> · <b>NET</b> peers · <b>SYSOP pw</b> then <b>X …</b> admin by DM</html>"));
        JPanel r4 = row(); r4.add(stats);
        top.add(r1); top.add(r2); top.add(r2b); top.add(r2c); top.add(r2d); top.add(r2e); top.add(r2f); top.add(r3); top.add(r4);
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
        dash.setPreferredSize(new Dimension(600, 120));
        JPanel dashBox = new JPanel(new BorderLayout()); dashBox.setBorder(BorderFactory.createTitledBorder("Usage – calls per day (last 30), busiest hours, top commands")); dashBox.add(dash, BorderLayout.CENTER); dashBox.add(dashText, BorderLayout.SOUTH);
        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, act, dashBox); lower.setResizeWeight(0.6);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tables, lower); main.setResizeWeight(0.55);
        add(main, BorderLayout.CENTER);

        enabled.setSelected(store.enabled); name.setText(store.name); welcome.setText(store.welcome); channelTrigger.setSelected(store.channelTrigger);
        trigger.setText(store.triggerWord); cooldown.setValue(store.cooldownSec); maxPosts.setValue(store.maxPosts);
        enabled.addActionListener(e -> { applySettings(); engine.setEnabled(enabled.isSelected()); });
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
        if (store.loadFailed && !loadWarned) { loadWarned = true; SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "bbs.json could not be read, so the BBS is showing defaults. The original was kept as bbs.json.broken-<date> in the data folder and will NOT be overwritten; send it to the developer or restore bbs.json.bak, then restart.", "BBS settings", JOptionPane.WARNING_MESSAGE)); }
        enabled.setSelected(store.enabled);
        java.util.List<String> days = new java.util.ArrayList<>(store.callsPerDay.keySet());
        int[] bars = new int[Math.min(30, days.size())];
        for (int i = 0; i < bars.length; i++) bars[i] = store.callsPerDay.get(days.get(days.size() - bars.length + i));
        dash.set(bars, null);
        int busiest = 0; for (int h = 1; h < 24; h++) if (store.hourCounts[h] > store.hourCounts[busiest]) busiest = h;
        java.util.List<java.util.Map.Entry<String, Integer>> top = new java.util.ArrayList<>(store.cmdCounts.entrySet()); top.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder tc = new StringBuilder(); for (int i = 0; i < Math.min(6, top.size()); i++) tc.append(top.get(i).getKey()).append(' ').append(top.get(i).getValue()).append("  ");
        long week = System.currentTimeMillis() - 7L * 86400_000L; int weekly = 0; for (BbsStore.Caller c : store.callers.values()) if (c.last > week) weekly++;
        dashText.setText("  " + store.callers.size() + " callers all time, " + weekly + " this week · busiest hour " + String.format("%02d:00", busiest) + " · top commands: " + (tc.length() == 0 ? "–" : tc));
        posts.rows = new ArrayList<>(store.posts); posts.fireTableDataChanged();
        mail.rows = new ArrayList<>(store.mail); mail.fireTableDataChanged();
        stats.setText((store.enabled ? "Running" : "Stopped") + "  ·  " + store.posts.size() + " posts, " + store.mail.size() + " mail, " + store.commandsServed + " commands served, " + store.repliesSent + " replies, " + store.banned.size() + " banned");
    }

    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }
}
