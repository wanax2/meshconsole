package meshconsole.ui;

import meshconsole.mesh.AlertEngine;
import meshconsole.mesh.MeshState;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Health alerts with thresholds, tray notifications and an alerts.log. */
class AlertsPanel extends JPanel {
    record Alert(long time, String kind, String text) { }

    private final AlertEngine engine;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JCheckBox enabled = new JCheckBox("Alerts enabled", true);
    private final JCheckBox tray = new JCheckBox("Windows/desktop notifications", true);
    private final JCheckBox beep = new JCheckBox("Beep", false);
    private final JSpinner silentH = new JSpinner(new SpinnerNumberModel(6, 1, 240, 1));
    private final JCheckBox favOnly = new JCheckBox("favourites only", true);
    private final JSpinner batt = new JSpinner(new SpinnerNumberModel(20, 1, 99, 1));
    private final JSpinner util = new JSpinner(new SpinnerNumberModel(30, 5, 100, 5));
    private final JCheckBox dmAlert = new JCheckBox("New direct message", true);
    private final JTextField webhook = new JTextField(meshconsole.tools.Notifier.webhook(), 28), poToken = new JTextField(meshconsole.tools.Notifier.pushoverToken(), 10), poUser = new JTextField(meshconsole.tools.Notifier.pushoverUser(), 10);
    private final JCheckBox pushOut = new JCheckBox("Push alerts out (webhook / Pushover)", false);
    private TrayIcon trayIcon;
    private final Path logFile = meshconsole.DataDir.file("alerts.log");

    private static final String[] COLS = {"Time", "Kind", "Alert"};

    private class Model extends AbstractTableModel {
        List<Alert> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) {
            Alert a = rows.get(r);
            return switch (c) { case 0 -> Fmt.time(a.time()); case 1 -> a.kind(); default -> a.text(); };
        }
    }

    AlertsPanel(MeshState state, AlertEngine engine) {
        this.engine = engine;
        setLayout(new BorderLayout(6, 6));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel r1 = row(); r1.add(enabled); r1.add(tray); r1.add(beep); r1.add(dmAlert);
        JPanel r2 = row(); r2.add(new JLabel("Node silent for more than")); r2.add(silentH); r2.add(new JLabel("hours,")); r2.add(favOnly);
        JPanel r3 = row(); r3.add(new JLabel("Battery below")); r3.add(batt); r3.add(new JLabel("%      Channel utilisation above")); r3.add(util); r3.add(new JLabel("%"));
        JPanel r4 = row(); r4.add(new JLabel("Always: radio reboot, public-key change of a known node, admin commands seen between other nodes, detection-sensor triggers, range-test packets. Alerts are also written to alerts.log."));
        JPanel r5 = row(); r5.add(pushOut); r5.add(new JLabel("Webhook URL (Discord/Slack):")); r5.add(webhook); r5.add(new JLabel("Pushover token:")); r5.add(poToken); r5.add(new JLabel("user:")); r5.add(poUser);
        JButton saveN = new JButton("Save"), testN = new JButton("Test");
        saveN.addActionListener(e -> meshconsole.tools.Notifier.set(webhook.getText(), poToken.getText(), poUser.getText()));
        testN.addActionListener(e -> { meshconsole.tools.Notifier.set(webhook.getText(), poToken.getText(), poUser.getText()); new Thread(() -> { String r = meshconsole.tools.Notifier.send("Mesh Console test", "Notifications are working."); SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, r.isEmpty() ? "Sent." : "Failed: " + r)); }).start(); });
        r5.add(saveN); r5.add(testN);
        top.add(r1); top.add(r2); top.add(r3); top.add(r4); top.add(r5);
        add(top, BorderLayout.NORTH);
        table.setRowHeight(20);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(700);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bottom = row();
        JButton clear = new JButton("Clear list");
        clear.addActionListener(e -> { model.rows.clear(); model.fireTableDataChanged(); });
        bottom.add(clear);
        add(bottom, BorderLayout.SOUTH);

        java.awt.event.ActionListener apply = e -> {
            engine.enabled = enabled.isSelected();
            engine.silentHours = (Integer) silentH.getValue();
            engine.silentFavoritesOnly = favOnly.isSelected();
            engine.batteryPct = (Integer) batt.getValue();
            engine.utilPct = (Integer) util.getValue();
        };
        enabled.addActionListener(apply); favOnly.addActionListener(apply);
        silentH.addChangeListener(e -> apply.actionPerformed(null));
        batt.addChangeListener(e -> apply.actionPerformed(null));
        util.addChangeListener(e -> apply.actionPerformed(null));

        if (SystemTray.isSupported()) {
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.setColor(new Color(40, 120, 70)); g.fillOval(1, 1, 14, 14);
                g.setColor(Color.WHITE); g.fillOval(5, 5, 6, 6);
                g.dispose();
                trayIcon = new TrayIcon(img, "Mesh Console");
                trayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(trayIcon);
            } catch (Exception e) { trayIcon = null; }
        } else tray.setEnabled(false);

        state.addListener(new MeshState.Listener() {
            @Override public void onAlert(String kind, String text) { SwingUtilities.invokeLater(() -> add(kind, text)); }
            @Override public void onMessage(meshconsole.mesh.ChatMessage m, boolean isNew) {
                if (isNew && !m.outgoing && !m.isBroadcast() && dmAlert.isSelected())
                    SwingUtilities.invokeLater(() -> add("MESSAGE", "DM from " + state.nodeName(m.from) + ": " + m.text));
            }
        });
    }

    private void add(String kind, String text) {
        if (!enabled.isSelected() && !kind.equals("MESSAGE")) return;
        Alert a = new Alert(System.currentTimeMillis(), kind, text);
        model.rows.add(0, a);
        if (model.rows.size() > 500) model.rows.remove(model.rows.size() - 1);
        model.fireTableDataChanged();
        try {
            Files.writeString(logFile, Fmt.time(a.time()) + "  " + kind + "  " + text + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
        if (kind.equals("RANGE_TEST")) return;   // too frequent for pop-ups
        if (pushOut.isSelected() && !kind.equals("INFO")) new Thread(() -> meshconsole.tools.Notifier.send("Mesh " + kind, text), "notify").start();
        if (tray.isSelected() && trayIcon != null) trayIcon.displayMessage("Mesh Console – " + kind, text, kind.equals("MESSAGE") ? TrayIcon.MessageType.INFO : TrayIcon.MessageType.WARNING);
        if (beep.isSelected()) Toolkit.getDefaultToolkit().beep();
    }

    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }
}
