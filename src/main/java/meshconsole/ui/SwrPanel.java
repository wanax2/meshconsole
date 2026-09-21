package meshconsole.ui;

import com.fazecast.jSerialComm.SerialPort;
import meshconsole.mesh.MeshState;
import meshconsole.swr.NanoVna;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Antenna SWR via a NanoVNA on a second USB port. The Meshtastic radio itself has
 * no forward/reflected power sensor, so this is the only way to get a real SWR reading.
 */
class SwrPanel extends JPanel {
    private final MeshState state;
    private final JComboBox<PortItem> ports = new JComboBox<>();
    private final JTextField start = new JTextField("902.0", 7);
    private final JTextField stop = new JTextField("928.0", 7);
    private final JSpinner points = new JSpinner(new SpinnerNumberModel(101, 11, 401, 10));
    private final JComboBox<String> band = new JComboBox<>(new String[]{
            "US / 915 MHz (902–928)", "EU 868 (863–870)", "EU 433 (433–434.8)", "ANZ 915 (915–928)",
            "CN 470 (470–510)", "JP 920 (920.8–927.8)", "TW 923 (920–925)", "KR 920 (920–923)",
            "IN 865 (865–867)", "RU 868 (868.7–869.2)", "Custom"});
    private final JButton sweep = new JButton("Sweep");
    private final JCheckBox continuous = new JCheckBox("Continuous");
    private final JLabel result = new JLabel("No sweep yet.");
    private final SwrChart chart = new SwrChart();
    private NanoVna vna;
    private volatile boolean sweeping;

    record PortItem(SerialPort port) {
        @Override public String toString() { return port.getSystemPortName() + "  —  " + port.getDescriptivePortName(); }
    }

    SwrPanel(MeshState state) {
        this.state = state;
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new GridLayout(2, 1));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        row1.add(new JLabel("NanoVNA port:"));
        row1.add(ports);
        JButton refresh = new JButton("↻");
        refresh.setToolTipText("Rescan serial ports");
        refresh.addActionListener(e -> refreshPorts());
        row1.add(refresh);
        row1.add(new JLabel("Band:"));
        row1.add(band);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        row2.add(new JLabel("Start MHz:"));
        row2.add(start);
        row2.add(new JLabel("Stop MHz:"));
        row2.add(stop);
        row2.add(new JLabel("Points:"));
        row2.add(points);
        row2.add(sweep);
        row2.add(continuous);
        top.add(row1);
        top.add(row2);
        add(top, BorderLayout.NORTH);

        add(chart, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        result.setFont(result.getFont().deriveFont(Font.BOLD, 13f));
        result.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        south.add(result, BorderLayout.NORTH);
        JTextArea note = new JTextArea(
                "The RAK / Meshtastic radio cannot measure SWR – the LoRa transceiver has no forward/reflected power sensor. "
              + "Connect a NanoVNA (or any VNA with the NanoVNA shell protocol) to the antenna instead of the radio and sweep here. "
              + "Aim for SWR < 2 across the band your region uses; the frequency actually in use depends on region, preset and channel name. "
              + "Never sweep with the antenna attached to a transmitting radio.");
        note.setEditable(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setBackground(getBackground());
        note.setFont(note.getFont().deriveFont(11f));
        south.add(note, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        band.addActionListener(e -> applyBand());
        sweep.addActionListener(e -> {
            if (sweeping) { sweeping = false; sweep.setText("Sweep"); }
            else startSweep();
        });
        refreshPorts();
    }

    private void applyBand() {
        String[][] ranges = {
                {"902.0", "928.0"}, {"863.0", "870.0"}, {"433.0", "434.8"}, {"915.0", "928.0"},
                {"470.0", "510.0"}, {"920.8", "927.8"}, {"920.0", "925.0"}, {"920.0", "923.0"},
                {"865.0", "867.0"}, {"868.7", "869.2"}};
        int i = band.getSelectedIndex();
        if (i >= 0 && i < ranges.length) { start.setText(ranges[i][0]); stop.setText(ranges[i][1]); }
    }

    void refreshPorts() {
        ports.removeAllItems();
        for (SerialPort p : SerialPort.getCommPorts()) ports.addItem(new PortItem(p));
        // Prefer something that looks like a NanoVNA (ChibiOS CDC shows up as "ChibiOS/RT Virtual COM Port").
        for (int i = 0; i < ports.getItemCount(); i++) {
            String d = ports.getItemAt(i).port().getDescriptivePortName().toLowerCase();
            if (d.contains("chibios") || d.contains("nanovna")) { ports.setSelectedIndex(i); break; }
        }
    }

    private void startSweep() {
        PortItem pi = (PortItem) ports.getSelectedItem();
        if (pi == null) { result.setText("No serial port selected."); return; }
        long f0, f1;
        try {
            f0 = Math.round(Double.parseDouble(start.getText().trim()) * 1e6);
            f1 = Math.round(Double.parseDouble(stop.getText().trim()) * 1e6);
        } catch (NumberFormatException e) { result.setText("Bad frequency."); return; }
        int n = (Integer) points.getValue();
        sweeping = true;
        sweep.setText("Stop");
        result.setText("Sweeping…");
        Thread t = new Thread(() -> {
            try (NanoVna v = new NanoVna(pi.port())) {
                do {
                    NanoVna.Sweep s = v.sweep(f0, f1, n);
                    int mi = s.minIndex();
                    double mid = (f0 + f1) / 2.0;
                    int ci = 0;
                    for (int i = 1; i < s.freqHz().length; i++) if (Math.abs(s.freqHz()[i] - mid) < Math.abs(s.freqHz()[ci] - mid)) ci = i;
                    double maxSwr = 0;
                    for (double x : s.swr()) maxSwr = Math.max(maxSwr, x);
                    final String txt = String.format("Best SWR %.2f at %.3f MHz   ·   nearest centre %.3f MHz: SWR %.2f (RL %.1f dB)   ·   worst measured %.2f",
                            s.swr()[mi], s.freqHz()[mi] / 1e6, s.freqHz()[ci] / 1e6, s.swr()[ci], s.returnLossDb()[ci], maxSwr)
                            + (s.note().isEmpty() ? "" : "   ·   " + s.note());
                    SwingUtilities.invokeLater(() -> { chart.setSweep(s); result.setText(txt); });
                } while (sweeping && continuous.isSelected());
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> result.setText("Sweep failed: " + e.getMessage()));
            } finally {
                sweeping = false;
                SwingUtilities.invokeLater(() -> sweep.setText("Sweep"));
            }
        }, "swr-sweep");
        t.setDaemon(true);
        t.start();
    }

    /** Chart of SWR vs frequency with 1.5 / 2 / 3 guide lines. */
    static class SwrChart extends JComponent {
        private NanoVna.Sweep sweep;

        SwrChart() { setPreferredSize(new Dimension(600, 300)); }

        void setSweep(NanoVna.Sweep s) { sweep = s; repaint(); }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            int left = 48, right = w - 16, top = 22, bottom = h - 30;
            g.setColor(Color.GRAY);
            g.drawRect(left, top, right - left, bottom - top);
            double sMax = 10, sMin = 1;
            g.setFont(g.getFont().deriveFont(10f));
            for (double v : new double[]{1, 1.5, 2, 3, 5, 10}) {
                int y = (int) (bottom - (Math.log10(v) - Math.log10(sMin)) / (Math.log10(sMax) - Math.log10(sMin)) * (bottom - top));
                g.setColor(v == 1.5 || v == 2 ? new Color(120, 200, 120) : v == 3 ? new Color(230, 160, 120) : new Color(220, 220, 220));
                g.drawLine(left + 1, y, right - 1, y);
                g.setColor(Color.DARK_GRAY);
                g.drawString(v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v), 8, y + 4);
            }
            g.setColor(Color.DARK_GRAY);
            g.drawString("SWR", 8, 12);
            if (sweep == null || sweep.freqHz().length < 2) {
                g.drawString("Connect a NanoVNA and press Sweep", left + 10, top + 20);
                return;
            }
            double f0 = sweep.freqHz()[0], f1 = sweep.freqHz()[sweep.freqHz().length - 1];
            for (int i = 0; i <= 5; i++) {
                double f = f0 + (f1 - f0) * i / 5;
                int x = left + (right - left) * i / 5;
                g.setColor(new Color(220, 220, 220));
                g.drawLine(x, top + 1, x, bottom - 1);
                g.setColor(Color.DARK_GRAY);
                String s = String.format("%.2f", f / 1e6);
                g.drawString(s, x - g.getFontMetrics().stringWidth(s) / 2, bottom + 14);
            }
            g.drawString("MHz", right - 24, bottom + 26);
            int n = sweep.freqHz().length;
            int[] xs = new int[n], ys = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = left + (int) ((sweep.freqHz()[i] - f0) / (f1 - f0) * (right - left));
                double v = Math.max(sMin, Math.min(sMax, sweep.swr()[i]));
                ys[i] = (int) (bottom - (Math.log10(v) - Math.log10(sMin)) / (Math.log10(sMax) - Math.log10(sMin)) * (bottom - top));
            }
            g.setColor(new Color(40, 90, 200));
            g.setStroke(new BasicStroke(2f));
            g.drawPolyline(xs, ys, n);
            int mi = sweep.minIndex();
            g.setColor(Color.RED);
            g.fillOval(xs[mi] - 4, ys[mi] - 4, 8, 8);
            g.drawString(String.format("%.2f @ %.3f MHz", sweep.swr()[mi], sweep.freqHz()[mi] / 1e6), Math.min(xs[mi] + 8, right - 120), Math.max(ys[mi] - 8, top + 12));
        }
    }
}
