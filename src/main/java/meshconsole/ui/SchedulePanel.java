package meshconsole.ui;

import meshconsole.mesh.ChatMessage;
import meshconsole.tools.Scheduler;

import javax.swing.*;
import java.awt.*;

/** Scheduled jobs settings. */
class SchedulePanel extends JPanel {
    SchedulePanel(Scheduler s) {
        setLayout(new BorderLayout());
        JPanel box = new JPanel(); box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JCheckBox rep = new JCheckBox("Write mesh_report.html daily at", s.reportOn); JTextField repT = new JTextField(s.reportTime, 5);
        JCheckBox exp = new JCheckBox("Export all logs as zip nightly at", s.exportOn); JTextField expT = new JTextField(s.exportTime, 5); JTextField expD = new JTextField(s.exportDir, 28);
        JButton pick = new JButton("Folder…"); pick.addActionListener(e -> { JFileChooser fc = new JFileChooser(); fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) expD.setText(fc.getSelectedFile().getPath()); });
        JCheckBox tr = new JCheckBox("Traceroute every watched node weekly on", s.traceOn); JComboBox<String> day = new JComboBox<>(new String[]{"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"}); day.setSelectedIndex(Math.max(0, Math.min(6, s.traceWeekday - 1))); JTextField trT = new JTextField(s.traceTime, 5);
        JCheckBox bc = new JCheckBox("Send a beacon message every", s.beaconOn); JSpinner bcMin = new JSpinner(new SpinnerNumberModel(s.beaconMinutes, 5, 1440, 5)); JTextField bcText = new JTextField(s.beaconText, 22); JTextField bcTo = new JTextField(s.beaconTo == 0xFFFFFFFF ? "" : String.format("!%08x", s.beaconTo), 10);
        JPanel r1 = row(); r1.add(rep); r1.add(repT);
        JPanel r2 = row(); r2.add(exp); r2.add(expT); r2.add(new JLabel("to")); r2.add(expD); r2.add(pick);
        JPanel r3 = row(); r3.add(tr); r3.add(day); r3.add(new JLabel("at")); r3.add(trT);
        JPanel r4 = row(); r4.add(bc); r4.add(bcMin); r4.add(new JLabel("min:")); r4.add(bcText); r4.add(new JLabel("to (blank = broadcast):")); r4.add(bcTo);
        JPanel r5 = row(); r5.add(new JLabel("<html>The beacon is an automated range test: each send is tracked like any message (delivery %, time to ack in Stats), so a day of hourly beacons to a fixed node charts how the link behaves by time of day. Keep the interval ≥ 30 min on a busy mesh.</html>"));
        JButton save = new JButton("Save schedule");
        save.addActionListener(e -> {
            s.reportOn = rep.isSelected(); s.reportTime = repT.getText().trim(); s.exportOn = exp.isSelected(); s.exportTime = expT.getText().trim(); s.exportDir = expD.getText().trim();
            s.traceOn = tr.isSelected(); s.traceWeekday = day.getSelectedIndex() + 1; s.traceTime = trT.getText().trim();
            s.beaconOn = bc.isSelected(); s.beaconMinutes = (Integer) bcMin.getValue(); s.beaconText = bcText.getText().trim();
            String to = bcTo.getText().trim().replace("!", ""); s.beaconTo = to.isEmpty() ? ChatMessage.BROADCAST : (int) Long.parseLong(to, 16);
            s.save();
            JOptionPane.showMessageDialog(this, "Schedule saved. Jobs run while the app (or headless mode) is running.");
        });
        JPanel r6 = row(); r6.add(save);
        box.add(r1); box.add(r2); box.add(r3); box.add(r4); box.add(r5); box.add(r6);
        add(box, BorderLayout.NORTH);
    }
    private static JPanel row() { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3)); p.setAlignmentX(LEFT_ALIGNMENT); return p; }
}
