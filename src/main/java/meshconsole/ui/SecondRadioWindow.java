package meshconsole.ui;

import com.fazecast.jSerialComm.SerialPort;
import meshconsole.mesh.*;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

/**
 * A second radio connected at the same time, in its own window: live signal, node count, packet log.
 * Its samples go into the shared signal history tagged with its node id, so "My radios" compares both.
 */
class SecondRadioWindow extends JFrame {
    private final MeshState state;
    private final MeshClient client;
    private final JComboBox<Object> ports = new JComboBox<>();
    private final JButton connect = new JButton("Connect");
    private final JLabel status = new JLabel("Not connected");
    private final SignalChart chart = new SignalChart();
    private final JTextArea log = new JTextArea();
    private final JLabel compare = new JLabel(" ");
    private final MeshState primary;

    record PortItem(SerialPort port) { @Override public String toString() { return port.getSystemPortName() + " — " + port.getDescriptivePortName(); } }

    SecondRadioWindow(MeshState primary, meshconsole.mesh.SignalHistory sharedHistory) {
        super("Second radio");
        this.primary = primary;
        state = new MeshState(new MessageLog(meshconsole.DataDir.file("messages_radio2.log")));
        state.setSignalHistory(sharedHistory);
        client = new MeshClient(state);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        top.add(new JLabel("Port:")); top.add(ports);
        JButton refresh = new JButton("↻"); refresh.addActionListener(e -> refreshPorts()); top.add(refresh);
        top.add(connect);
        JButton tcp = new JButton("TCP…"); tcp.addActionListener(e -> { String s = JOptionPane.showInputDialog(this, "Host[:port]", "meshpi.local:4403"); if (s != null && !s.isBlank()) connectTcp(s.trim()); }); top.add(tcp);
        top.add(status);
        root.add(top, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout());
        JPanel cmp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); cmp.add(compare); center.add(cmp, BorderLayout.NORTH);
        chart.setPreferredSize(new Dimension(700, 200)); center.add(chart, BorderLayout.CENTER);
        log.setEditable(false); log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane ls = new JScrollPane(log); ls.setPreferredSize(new Dimension(700, 220)); ls.setBorder(BorderFactory.createTitledBorder("Packets"));
        center.add(ls, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);
        setContentPane(root);
        connect.addActionListener(e -> { if (client.isConnected()) client.disconnect(); else { Object o = ports.getSelectedItem(); if (o instanceof PortItem pi) try { client.connect(pi.port()); } catch (IOException ex) { status.setText(ex.getMessage()); } } });
        client.setConnectionListener((c, d) -> SwingUtilities.invokeLater(() -> { connect.setText(c ? "Disconnect" : "Connect"); status.setText(c ? "Connected on " + d : "Not connected"); }));
        state.addListener(new MeshState.Listener() {
            @Override public void onLog(String line) { if (!line.startsWith("[serial]")) SwingUtilities.invokeLater(() -> { log.append(Fmt.time(System.currentTimeMillis()) + "  " + line + "\n"); log.setCaretPosition(log.getDocument().getLength()); }); }
            @Override public void onNodesChanged() { SwingUtilities.invokeLater(SecondRadioWindow.this::refresh); }
            @Override public void onStatusChanged() { SwingUtilities.invokeLater(SecondRadioWindow.this::refresh); }
        });
        new Timer(2000, e -> refresh()).start();
        refreshPorts();
        setSize(760, 560);
        setLocationByPlatform(true);
    }

    private void connectTcp(String s) {
        String host = s; int port = 4403; int c = s.lastIndexOf(':');
        if (c > 0) { try { port = Integer.parseInt(s.substring(c + 1)); host = s.substring(0, c); } catch (NumberFormatException ignored) { } }
        try { client.connectTcp(host, port); } catch (IOException ex) { status.setText(ex.getMessage()); }
    }

    private void refreshPorts() { ports.removeAllItems(); for (SerialPort p : SerialPort.getCommPorts()) ports.addItem(new PortItem(p)); }

    private void refresh() {
        chart.setData(state.signalHistory(), 0);
        if (!client.isConnected()) return;
        NodeEntry me = state.myNode();
        java.util.List<SignalSample> a = primary.signalHistory(), b = state.signalHistory();
        long cutoff = System.currentTimeMillis() - 15 * 60_000L;
        double sa = 0, sb = 0; int na = 0, nb = 0;
        for (SignalSample s : a) if (s.time() > cutoff) { sa += s.rssi(); na++; }
        for (SignalSample s : b) if (s.time() > cutoff) { sb += s.rssi(); nb++; }
        setTitle("Second radio – " + (me == null ? "" : me.displayName() + " " + me.idString()));
        compare.setText(String.format("<html>Last 15 min &nbsp; <b>main radio</b>: %d packets, avg RSSI %s &nbsp;·&nbsp; <b>this radio</b>: %d packets, avg RSSI %s &nbsp;·&nbsp; %d nodes here (%s)</html>",
                na, na == 0 ? "–" : String.format("%.0f", sa / na), nb, nb == 0 ? "–" : String.format("%.0f", sb / nb), state.nodes().size(), me == null ? "" : MeshState.loraSummary(state.lora())));
    }

    MeshClient client() { return client; }
    MeshState state() { return state; }
}
