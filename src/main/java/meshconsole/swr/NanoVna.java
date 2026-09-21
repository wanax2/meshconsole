package meshconsole.swr;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to a NanoVNA (H / H4 / V2-with-CDC-shell, edy555 or DiSlord firmware)
 * over its USB serial shell and turns S11 into SWR.
 *
 * Shell protocol: commands end with CR, the reply is followed by the prompt "ch> ".
 *   sweep <startHz> <stopHz> [points]
 *   frequencies           -> one frequency per line
 *   data 0                -> one "re im" S11 pair per line
 */
public class NanoVna implements AutoCloseable {

    public record Sweep(double[] freqHz, double[] swr, double[] returnLossDb, String note) {
        public int minIndex() {
            int best = 0;
            for (int i = 1; i < swr.length; i++) if (swr[i] < swr[best]) best = i;
            return best;
        }
    }

    private final SerialPort port;
    private final InputStream in;
    private final OutputStream out;

    public NanoVna(SerialPort port) throws IOException {
        this.port = port;
        port.setBaudRate(115200);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 200, 2000);
        if (!port.openPort()) throw new IOException("Could not open " + port.getSystemPortName());
        in = port.getInputStream();
        out = port.getOutputStream();
        // Sync to the prompt; discard whatever was buffered.
        try {
            command("", 1500);
        } catch (IOException e) {
            close();
            throw new IOException("No NanoVNA shell prompt on " + port.getSystemPortName());
        }
    }

    /**
     * Runs one complete sweep synchronously via the "scan" command (the device answers only when the
     * pass is done, and it works even when its screen sweep is paused), then reads S11.
     */
    public Sweep sweep(long startHz, long stopHz, int points) throws IOException, InterruptedException {
        if (stopHz <= startHz) throw new IOException("stop must be above start");
        String r = command("scan " + startHz + " " + stopHz + " " + points, 60000);
        if (r.toLowerCase().contains("usage") || r.toLowerCase().contains("error")) {
            // older firmware without a point-count argument
            command("scan " + startHz + " " + stopHz, 60000);
        }
        double[] freqs = parseDoubles(command("frequencies", 5000), 1);
        double[][] s11 = parsePairs(command("data 0", 8000));
        try { command("resume", 2000); } catch (IOException ignored) { }   // let the device's own display sweep continue
        int total = Math.min(freqs.length, s11.length);
        if (total == 0) throw new IOException("Empty response from NanoVNA");
        // Points the instrument could not measure come back as exactly 0+0j (typically above the
        // unit's maximum frequency). Keep the measured ones and report the gap.
        List<Integer> keep = new ArrayList<>();
        double skippedMin = Double.MAX_VALUE, skippedMax = 0;
        for (int i = 0; i < total; i++) {
            if (s11[i][0] == 0 && s11[i][1] == 0) { skippedMin = Math.min(skippedMin, freqs[i]); skippedMax = Math.max(skippedMax, freqs[i]); }
            else keep.add(i);
        }
        if (keep.isEmpty())
            throw new IOException("NanoVNA returned no measurement data (all zeros). Check it is a NanoVNA-H/H4 with the text shell (V2 uses a different protocol) and try power-cycling it.");
        int n = keep.size();
        double[] f = new double[n], swr = new double[n], rl = new double[n];
        for (int k = 0; k < n; k++) {
            int i = keep.get(k);
            double gamma = Math.hypot(s11[i][0], s11[i][1]);
            if (gamma >= 0.9999) gamma = 0.9999;
            f[k] = freqs[i];
            swr[k] = (1 + gamma) / (1 - gamma);
            rl[k] = gamma > 0 ? -20 * Math.log10(gamma) : 99;
        }
        String note = n == total ? "" : String.format("%d points (%.1f–%.1f MHz) not measured – beyond this VNA's range?", total - n, skippedMin / 1e6, skippedMax / 1e6);
        return new Sweep(f, swr, rl, note);
    }

    private static double[] parseDoubles(String text, int cols) {
        List<Double> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            if (p.length < cols) continue;
            try { out.add(Double.parseDouble(p[0])); } catch (NumberFormatException ignored) { }
        }
        double[] a = new double[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static double[][] parsePairs(String text) {
        List<double[]> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 2) continue;
            try { out.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])}); }
            catch (NumberFormatException ignored) { }
        }
        return out.toArray(new double[0][]);
    }

    /** Sends a command and returns everything up to (not including) the next "ch>" prompt, minus the echo. */
    public synchronized String command(String cmd, long timeoutMs) throws IOException {
        try { while (in.available() > 0) in.read(); } catch (IOException ignored) { }
        byte[] c = (cmd + "\r").getBytes(StandardCharsets.US_ASCII);
        if (port.writeBytes(c, c.length) != c.length) throw new IOException("write to NanoVNA failed");
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[1024];
        while (System.currentTimeMillis() < deadline) {
            // port API returns 0 (or on some Windows setups -1) when nothing arrived yet; the stream
            // wrapper would throw SerialPortTimeoutException instead, so don't use it here.
            int n = port.readBytes(buf, buf.length);
            if (n < 0 && !port.isOpen()) throw new IOException("NanoVNA port closed");
            if (n > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                int idx = sb.indexOf("ch>");
                if (idx >= 0) {
                    String body = sb.substring(0, idx);
                    // strip the echoed command line
                    int nl = body.indexOf('\n');
                    if (nl >= 0 && body.substring(0, nl).trim().equals(cmd.trim())) body = body.substring(nl + 1);
                    return body.replace("\r", "");
                }
            }
        }
        throw new IOException("Timeout waiting for NanoVNA prompt (got: " + sb.toString().trim() + ")");
    }

    @Override
    public void close() {
        try { in.close(); } catch (IOException ignored) { }
        port.closePort();
    }
}
