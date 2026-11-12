package meshconsole.meshcore;

import com.fazecast.jSerialComm.SerialPort;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * A MeshCore radio running Companion firmware, over USB serial or WiFi (TCP port 5000).
 *
 * Protocol (checked against meshcore-dev/MeshCore examples/companion_radio/MyMesh.cpp):
 *   app → radio frames:  '<' len_lo len_hi payload        radio → app:  '>' len_lo len_hi payload
 *   payload[0] is a command (app → radio), a response code (< 0x80) or a push code (≥ 0x80, unsolicited).
 * Commands get exactly one reply (or a CONTACTS_START … END run), so they're sent one at a time.
 */
public final class McClient {
    // commands
    static final int CMD_APP_START = 1, CMD_SEND_TXT_MSG = 2, CMD_SEND_CHANNEL_TXT_MSG = 3, CMD_GET_CONTACTS = 4,
            CMD_SET_DEVICE_TIME = 6, CMD_SEND_SELF_ADVERT = 7, CMD_SET_ADVERT_NAME = 8, CMD_SYNC_NEXT_MESSAGE = 10,
            CMD_SET_RADIO_PARAMS = 11, CMD_SET_RADIO_TX_POWER = 12, CMD_SET_ADVERT_LATLON = 14, CMD_REBOOT = 19, CMD_SET_OTHER_PARAMS = 38,
            CMD_GET_BATT_AND_STORAGE = 20, CMD_DEVICE_QUERY = 22, CMD_GET_CHANNEL = 31;
    // responses
    static final int RESP_OK = 0, RESP_ERR = 1, RESP_CONTACTS_START = 2, RESP_CONTACT = 3, RESP_END_OF_CONTACTS = 4,
            RESP_SELF_INFO = 5, RESP_SENT = 6, RESP_CONTACT_MSG = 7, RESP_CHANNEL_MSG = 8, RESP_NO_MORE_MESSAGES = 10,
            RESP_BATT = 12, RESP_DEVICE_INFO = 13, RESP_CONTACT_MSG_V3 = 16, RESP_CHANNEL_MSG_V3 = 17, RESP_CHANNEL_INFO = 18,
            RESP_CHANNEL_DATA = 27;
    // pushes
    static final int PUSH_ADVERT = 0x80, PUSH_PATH_UPDATED = 0x81, PUSH_SEND_CONFIRMED = 0x82, PUSH_MSG_WAITING = 0x83,
            PUSH_LOG_RX_DATA = 0x88, PUSH_NEW_ADVERT = 0x8A, PUSH_CONTACT_DELETED = 0x8F, PUSH_CONTACTS_FULL = 0x90;

    public static final String[] TYPES = {"?", "chat", "repeater", "room", "sensor"};
    static final String[] PKT = {"REQ", "RESP", "TXT", "ACK", "ADVERT", "GRP_TXT", "GRP_DATA", "ANON_REQ", "PATH", "TRACE", "MULTI", "CTRL", "?", "?", "?", "RAW"};

    /** A contact the radio knows (keyed by full public key, hex). */
    public static final class Contact {
        public String key, name = ""; public int type, outPathLen = -1; public double lat, lon;
        public long lastAdvert, lastHeard, heard; public double rssiSum, snrSum; public int sigCount; public int lastRssi, hops = -1; public double lastSnr;
        String prefix() { return key.substring(0, 12); }
    }
    public static final class Channel { public int idx; public String name; Channel(int i, String n) { idx = i; name = n; } }
    /** One message, in or out. peer = 12-hex key prefix for DMs, "#n" for channel n. */
    public static final class Message {
        public long time; public boolean out; public String peer, from = "", text, status = ""; public double snr = Double.NaN; public int hops = -1; long ack;
    }
    public static final class Sample { public long time; public int rssi, ptype, route, hops; public double snr; public String sender = "", lastHop = ""; }

    private final Path dir;
    private final Consumer<String> log;
    private volatile InputStream in; private volatile OutputStream outStream; private volatile Closeable conn;
    private volatile boolean connected; private volatile String where = "";
    private volatile SerialPort serial;                 // for spotting a USB radio that vanished (reset, cable)
    public volatile long lastRx;                        // when the radio last sent anything
    private final Object cmdLock = new Object();
    private final BlockingQueue<byte[]> replies = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "meshcore-cmd"); t.setDaemon(true); return t; });

    // radio state
    public volatile String selfName = "", selfKey = "", firmware = "", board = "", buildDate = "";
    public volatile double selfLat, selfLon, freqMhz, bwKhz; public volatile int sf, cr, txPower, maxTxPower, maxChannels = 8, batteryMv;
    public volatile int multiAcks, locPolicy, telemetryModes, manualAdd;   // from SELF_INFO, so SET_OTHER_PARAMS can change one and keep the rest
    public final Map<String, Contact> contacts = new ConcurrentHashMap<>();
    public final List<Channel> channels = new CopyOnWriteArrayList<>();
    public final List<Message> messages = new CopyOnWriteArrayList<>();
    public final Deque<Sample> samples = new ConcurrentLinkedDeque<>();
    private final Map<Long, Message> awaitingAck = new ConcurrentHashMap<>();
    private final List<Consumer<Message>> messageListeners = new CopyOnWriteArrayList<>();

    public McClient(Path dataDir, Consumer<String> log) {
        this.dir = dataDir; this.log = log;
        loadContacts(); loadMessages();
    }

    public boolean isConnected() { return connected; }
    public String where() { return where; }
    public void onMessage(Consumer<Message> l) { messageListeners.add(l); }

    // ------------------------------------------------------------------ connect
    /** "tcp:host[:port]" or "serial:<port name>" (e.g. serial:/dev/ttyACM1, serial:COM7). */
    public void connect(String spec) throws IOException {
        disconnect();
        if (spec.startsWith("tcp:")) {
            String hp = spec.substring(4); int p = 5000; int c = hp.lastIndexOf(':');
            if (c > 0) { p = Integer.parseInt(hp.substring(c + 1)); hp = hp.substring(0, c); }
            Socket s = new Socket(); s.connect(new InetSocketAddress(hp, p), 8000); s.setTcpNoDelay(true);
            in = s.getInputStream(); outStream = s.getOutputStream(); conn = s;
        } else if (spec.startsWith("serial:")) {
            SerialPort port = SerialPort.getCommPort(spec.substring(7));
            port.setBaudRate(115200); port.setNumDataBits(8); port.setNumStopBits(SerialPort.ONE_STOP_BIT);
            port.setParity(SerialPort.NO_PARITY); port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 250, 2000);
            if (!port.openPort()) throw new IOException("could not open " + spec.substring(7) + " (in use, or not in the dialout group)");
            port.setDTR(); port.setRTS();   // both asserted: no ESP32 auto-reset, and USB-CDC boards see a terminal attached
            in = port.getInputStream(); outStream = port.getOutputStream(); conn = port::closePort; serial = port;
        } else throw new IOException("connection must start with tcp: or serial: – got '" + spec + "'");
        where = spec; connected = true; replies.clear(); lastRx = System.currentTimeMillis();
        Thread rd = new Thread(this::readLoop, "meshcore-read"); rd.setDaemon(true); rd.start();
        log.accept("MeshCore: connected to " + spec);
        worker.submit(this::handshake);
    }

    public void disconnect() {
        boolean was = connected; connected = false;
        try { if (conn != null) conn.close(); } catch (Exception ignored) { }
        conn = null; in = null; outStream = null; serial = null;
        if (was) { log.accept("MeshCore: disconnected from " + where); saveContacts(); }
    }

    // ------------------------------------------------------------------ framing
    private void readLoop() {
        InputStream is = in;
        try {
            while (connected && is != null) {
                int c = read1(is);
                if (c < 0) break;
                if (c != '>') continue;                       // resync on the frame marker
                int lo = read1(is), hi = read1(is);
                if (lo < 0 || hi < 0) break;
                int len = lo | (hi << 8);
                if (len == 0 || len > 300) continue;
                byte[] f = new byte[len];
                for (int k = 0; k < len; k++) { int b = read1(is); if (b < 0) { f = null; break; } f[k] = (byte) b; }
                if (f == null) break;
                try { dispatch(f); } catch (Exception e) { log.accept("MeshCore: bad frame (" + e + ")"); }
            }
        } catch (IOException e) {
            if (connected) log.accept("MeshCore: link lost (" + e.getMessage() + ")");
        }
        if (connected) disconnect();
    }

    /** One byte. A quiet serial port reports "timed out" every 250 ms – that's silence, not a lost link, so keep waiting. */
    private int read1(InputStream is) throws IOException {
        while (connected) {
            try { return is.read(); }
            catch (com.fazecast.jSerialComm.SerialPortTimeoutException quiet) {   // nothing arrived yet – unless the device is gone
                SerialPort sp = serial;
                if (sp != null && (!sp.isOpen() || sp.bytesAvailable() < 0)) throw new IOException("USB device went away (radio reset or cable)");
            }
        }
        return -1;
    }

    private void write(byte[] payload) throws IOException {
        OutputStream o = outStream;
        if (o == null) throw new IOException("not connected");
        byte[] f = new byte[payload.length + 3];
        f[0] = '<'; f[1] = (byte) payload.length; f[2] = (byte) (payload.length >> 8);
        System.arraycopy(payload, 0, f, 3, payload.length);
        synchronized (this) { o.write(f); o.flush(); }
    }

    /** Send one command and wait for its reply (null on timeout). */
    private byte[] request(byte[] cmd, int timeoutMs) throws IOException {
        synchronized (cmdLock) {
            replies.clear();
            write(cmd);
            try { return replies.poll(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return null; }
        }
    }

    private void dispatch(byte[] f) {
        lastRx = System.currentTimeMillis();
        int code = f[0] & 0xFF;
        if (code >= 0x80) { push(code, f); return; }
        switch (code) {     // these arrive as replies but carry state worth keeping whoever asked
            case RESP_CONTACT -> { contact(f, false); return; }
            case RESP_CONTACTS_START -> { return; }
            default -> replies.offer(f);
        }
    }

    // ------------------------------------------------------------------ handshake & sync
    private void handshake() {
        try {
            byte[] r = request(new byte[]{CMD_DEVICE_QUERY, 3}, 4000);
            if (r != null && (r[0] & 0xFF) == RESP_DEVICE_INFO) deviceInfo(r);
            byte[] start = new byte[8 + "MeshConsole".length()];
            start[0] = CMD_APP_START;
            System.arraycopy("MeshConsole".getBytes(StandardCharsets.US_ASCII), 0, start, 8, "MeshConsole".length());
            r = request(start, 4000);
            if (r == null || (r[0] & 0xFF) != RESP_SELF_INFO) { log.accept("MeshCore: no reply to APP_START – is this Companion firmware? (repeaters and room servers don't talk to apps)"); return; }
            selfInfo(r);
            byte[] t = new byte[5]; t[0] = CMD_SET_DEVICE_TIME; le32(t, 1, System.currentTimeMillis() / 1000);
            request(t, 2000);                                // radio refuses to go backwards – an ERR here is fine
            r = request(new byte[]{CMD_GET_CONTACTS}, 8000);  // CONTACT frames are absorbed by dispatch(); this waits for END
            log.accept("MeshCore: " + selfName + " – " + contacts.size() + " contacts, " + firmware + " on " + board);
            channels.clear();
            for (int i = 0; i < Math.max(1, maxChannels); i++) {
                r = request(new byte[]{CMD_GET_CHANNEL, (byte) i}, 2000);
                if (r == null || (r[0] & 0xFF) != RESP_CHANNEL_INFO) break;
                String n = str(r, 2, 32);
                if (!n.isEmpty()) channels.add(new Channel(r[1] & 0xFF, n));
            }
            battery();
            syncMessages();
            saveContacts();
        } catch (IOException e) { log.accept("MeshCore: handshake failed (" + e.getMessage() + ")"); }
    }

    /** Battery level; also the liveness probe – false if the radio didn't answer. */
    public boolean battery() {
        try { byte[] r = request(new byte[]{CMD_GET_BATT_AND_STORAGE}, 3000); if (r != null && (r[0] & 0xFF) == RESP_BATT) { batteryMv = u16(r, 1); return true; } return r != null; }
        catch (IOException e) { return false; }
    }

    /** Called every 20 s: after 3 min of silence, ask the radio something; two unanswered asks = reconnect. */
    private int unanswered;
    public void checkAlive() {
        if (!connected || System.currentTimeMillis() - lastRx < 180_000) { unanswered = 0; return; }
        if (battery()) { unanswered = 0; return; }
        if (++unanswered >= 2) { log.accept("MeshCore: radio stopped answering – reconnecting"); unanswered = 0; disconnect(); }
    }

    private void syncMessages() throws IOException {
        for (int n = 0; n < 200 && connected; n++) {
            byte[] r = request(new byte[]{CMD_SYNC_NEXT_MESSAGE}, 3000);
            if (r == null) return;
            int code = r[0] & 0xFF;
            if (code == RESP_NO_MORE_MESSAGES) return;
            if (code == RESP_CONTACT_MSG || code == RESP_CONTACT_MSG_V3) dm(r, code == RESP_CONTACT_MSG_V3);
            else if (code == RESP_CHANNEL_MSG || code == RESP_CHANNEL_MSG_V3) chan(r, code == RESP_CHANNEL_MSG_V3);
            else if (code != RESP_CHANNEL_DATA) return;
        }
    }

    // ------------------------------------------------------------------ parsing
    private void deviceInfo(byte[] r) {
        // [13][fw_ver][max_contacts/2][max_channels][ble_pin 4][build date 12][manufacturer 40][version 20]...
        if (r.length > 3) maxChannels = r[3] & 0xFF;
        if (r.length >= 8 + 12) buildDate = str(r, 8, 12);
        if (r.length >= 20 + 40) board = str(r, 20, 40);
        if (r.length >= 60 + 20) firmware = str(r, 60, 20);
    }

    private void selfInfo(byte[] r) {
        // [5][adv type][tx power][max tx][pub 32][lat 4][lon 4][multi acks][loc policy][telemetry][manual add][freq 4][bw 4][sf][cr][name…]
        txPower = r[2]; maxTxPower = r[3];
        multiAcks = r[44] & 0xFF; locPolicy = r[45] & 0xFF; telemetryModes = r[46] & 0xFF; manualAdd = r[47] & 0xFF;
        selfKey = hex(r, 4, 32);
        selfLat = i32(r, 36) / 1e6; selfLon = i32(r, 40) / 1e6;
        freqMhz = u32(r, 48) / 1000.0; bwKhz = u32(r, 52) / 1000.0; sf = r[56]; cr = r[57];   // sent as MHz×1000 and kHz×1000
        selfName = r.length > 58 ? new String(r, 58, r.length - 58, StandardCharsets.UTF_8).trim() : "";
    }

    private Contact contact(byte[] f, boolean isNew) {
        // [code][pub 32][type][flags][out_path_len][out_path 64][name 32][last_advert 4][lat 4][lon 4][lastmod 4]
        if (f.length < 1 + 32 + 3 + 64 + 32 + 12) return null;
        String key = hex(f, 1, 32);
        Contact c = contacts.computeIfAbsent(key, k -> { Contact n = new Contact(); n.key = k; return n; });
        c.type = f[33] & 0xFF; c.outPathLen = f[35]; c.name = str(f, 100, 32);
        c.lastAdvert = u32(f, 132) * 1000L; c.lat = i32(f, 136) / 1e6; c.lon = i32(f, 140) / 1e6;
        if (isNew) { c.lastHeard = System.currentTimeMillis(); log.accept("MeshCore: new " + TYPES[Math.min(c.type, 4)] + " heard – " + c.name); }
        return c;
    }

    private void dm(byte[] r, boolean v3) {
        // v3: [16][snr*4][r][r][prefix 6][path_len][txt_type][ts 4][extra (4 if signed)][text]
        int i = 1; double snr = Double.NaN;
        if (v3) { snr = r[1] / 4.0; i = 4; }
        String prefix = hex(r, i, 6); i += 6;
        int pathLen = r[i++] & 0xFF, txtType = r[i++] & 0xFF; long ts = u32(r, i) * 1000L; i += 4;
        String postedBy = "";
        if (txtType == 2 && r.length >= i + 4) { postedBy = hex(r, i, 4); i += 4; }   // room-server post: who wrote it
        Message m = new Message();
        m.time = ts > 0 ? ts : System.currentTimeMillis(); m.peer = prefix; m.snr = snr; m.hops = pathLen == 0xFF ? 0 : (pathLen & 63);
        Contact c = byPrefix(prefix);
        m.from = c != null ? c.name : prefix;
        if (!postedBy.isEmpty()) { Contact a = byPrefix(postedBy); m.from += " / " + (a != null ? a.name : postedBy); }
        m.text = new String(r, i, r.length - i, StandardCharsets.UTF_8);
        add(m);
    }

    private void chan(byte[] r, boolean v3) {
        // v3: [17][snr*4][r][r][channel idx][path_len][txt_type][ts 4][text "name: msg"]
        int i = 1; double snr = Double.NaN;
        if (v3) { snr = r[1] / 4.0; i = 4; }
        int idx = r[i++] & 0xFF, pathLen = r[i++] & 0xFF; i++; long ts = u32(r, i) * 1000L; i += 4;
        String t = new String(r, i, r.length - i, StandardCharsets.UTF_8);
        Message m = new Message();
        m.time = ts > 0 ? ts : System.currentTimeMillis(); m.peer = "#" + idx; m.snr = snr; m.hops = pathLen == 0xFF ? 0 : (pathLen & 63);
        int c = t.indexOf(": ");
        if (c > 0 && c < 33) { m.from = t.substring(0, c); m.text = t.substring(c + 2); } else m.text = t;
        add(m);
    }

    private void push(int code, byte[] f) {
        switch (code) {
            case PUSH_MSG_WAITING -> worker.submit(() -> { try { syncMessages(); } catch (IOException ignored) { } });
            case PUSH_NEW_ADVERT -> { contact(f, true); saveContacts(); }
            case PUSH_ADVERT, PUSH_PATH_UPDATED -> {
                if (f.length >= 33) { Contact c = contacts.get(hex(f, 1, 32)); if (c != null) c.lastHeard = System.currentTimeMillis(); }
                if (code == PUSH_PATH_UPDATED) worker.submit(() -> { try { request(new byte[]{CMD_GET_CONTACTS}, 8000); } catch (IOException ignored) { } });
            }
            case PUSH_SEND_CONFIRMED -> {
                long ack = u32(f, 1); long trip = u32(f, 5);
                Message m = awaitingAck.remove(ack);
                if (m != null) { m.status = "delivered (" + String.format(Locale.US, "%.1f", trip / 1000.0) + " s)"; rewriteMessages(); }
            }
            case PUSH_LOG_RX_DATA -> rx(f);
            case PUSH_CONTACTS_FULL -> log.accept("MeshCore: the radio's contact list is full");
            default -> { }
        }
    }

    /** Every packet the radio hears, with its SNR and RSSI: the raw source for signal history. */
    private void rx(byte[] f) {
        if (f.length < 5) return;
        Sample s = new Sample();
        s.time = System.currentTimeMillis(); s.snr = f[1] / 4.0; s.rssi = f[2];
        int i = 3, hdr = f[i++] & 0xFF; s.route = hdr & 3; s.ptype = (hdr >> 2) & 15;
        if (s.route == 0 || s.route == 3) i += 4;                    // transport codes
        if (i >= f.length) return;
        int pl = f[i++] & 0xFF, hsz = (pl >> 6) + 1, cnt = pl & 63;
        s.hops = cnt;
        if (cnt > 0 && i + cnt * hsz <= f.length) s.lastHop = hex(f, i + (cnt - 1) * hsz, hsz);
        i += cnt * hsz;
        if (s.ptype == 4 && f.length >= i + 32) {                  // ADVERT: payload starts with the sender's public key
            String key = hex(f, i, 32); s.sender = key.substring(0, 12);
            Contact c = contacts.get(key);
            if (c != null) {
                c.lastHeard = s.time; c.heard++; c.hops = s.route == 1 || s.route == 0 ? cnt : c.hops;
                if (cnt == 0) { c.lastRssi = s.rssi; c.lastSnr = s.snr; c.rssiSum += s.rssi; c.snrSum += s.snr; c.sigCount++; }
            }
        }
        samples.addLast(s);
        while (samples.size() > 5000) samples.pollFirst();
        try {
            Files.writeString(dir.resolve("meshcore_signal.csv"), s.time + "," + s.rssi + "," + s.snr + "," + PKT[s.ptype] + "," + s.route + "," + s.hops + "," + s.sender + "," + s.lastHop + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ sending
    /** Direct message to a contact (12-hex key prefix or full key). */
    public String sendDm(String keyOrPrefix, String text) {
        Contact c = byPrefix(keyOrPrefix.length() > 12 ? keyOrPrefix.substring(0, 12) : keyOrPrefix);
        if (c == null) return "unknown contact";
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        if (t.length > 150) return "too long (" + t.length + " bytes – 150 max)";
        byte[] cmd = new byte[1 + 1 + 1 + 4 + 6 + t.length];
        long now = System.currentTimeMillis() / 1000;
        cmd[0] = CMD_SEND_TXT_MSG; cmd[1] = 0; cmd[2] = 0; le32(cmd, 3, now); unhex(c.prefix(), cmd, 7); System.arraycopy(t, 0, cmd, 13, t.length);
        Message m = new Message(); m.time = now * 1000; m.out = true; m.peer = c.prefix(); m.from = selfName; m.text = text; m.status = "sending";
        try {
            byte[] r = request(cmd, 5000);
            if (r == null) m.status = "no reply from radio";
            else if ((r[0] & 0xFF) == RESP_SENT) { m.ack = u32(r, 2); m.status = (r[1] == 1 ? "sent (flood)" : "sent (direct)"); if (m.ack != 0) awaitingAck.put(m.ack, m); }
            else m.status = "radio refused (" + ((r[0] & 0xFF) == RESP_ERR && r.length > 1 ? "error " + r[1] : "code " + (r[0] & 0xFF)) + ")";
        } catch (IOException e) { m.status = "not connected"; }
        add(m);
        return m.status;
    }

    public String sendChannel(int idx, String text) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        if (t.length > 140) return "too long (" + t.length + " bytes – 140 max)";
        byte[] cmd = new byte[1 + 1 + 1 + 4 + t.length];
        long now = System.currentTimeMillis() / 1000;
        cmd[0] = CMD_SEND_CHANNEL_TXT_MSG; cmd[1] = 0; cmd[2] = (byte) idx; le32(cmd, 3, now); System.arraycopy(t, 0, cmd, 7, t.length);
        Message m = new Message(); m.time = now * 1000; m.out = true; m.peer = "#" + idx; m.from = selfName; m.text = text;
        try { byte[] r = request(cmd, 5000); m.status = r == null ? "no reply from radio" : (r[0] & 0xFF) == RESP_OK ? "sent" : "radio refused"; }
        catch (IOException e) { m.status = "not connected"; }
        add(m);
        return m.status;
    }

    /** A typed-in position (no GPS needed), and whether adverts carry it. */
    public String setPosition(double lat, double lon, boolean share) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return "that isn't a valid latitude/longitude";
        try {
            byte[] c = new byte[13]; c[0] = CMD_SET_ADVERT_LATLON;
            le32(c, 1, Math.round(lat * 1e6)); le32(c, 5, Math.round(lon * 1e6)); le32(c, 9, 0);
            byte[] r = request(c, 4000);
            if (r == null || (r[0] & 0xFF) != RESP_OK) return "radio refused the position";
            byte[] o = new byte[]{CMD_SET_OTHER_PARAMS, (byte) manualAdd, (byte) telemetryModes, (byte) (share ? 1 : 0), (byte) multiAcks};
            r = request(o, 4000);
            if (r == null || (r[0] & 0xFF) != RESP_OK) return "position saved, but couldn't change whether adverts include it";
            locPolicy = share ? 1 : 0; selfLat = lat; selfLon = lon;
            log.accept("MeshCore: position set to " + lat + ", " + lon + (share ? " (shared in adverts)" : " (not shared)"));
            return "position saved" + (share ? " – send an advert so others see it" : "");
        } catch (IOException e) { return "not connected"; }
    }

    // ------------------------------------------------------------------ settings (each saved on the radio, then re-read)
    private String simple(byte[] cmd, String what) {
        try {
            byte[] r = request(cmd, 4000);
            if (r == null) return what + ": no reply from the radio";
            if ((r[0] & 0xFF) != RESP_OK) return what + ": radio refused" + ((r[0] & 0xFF) == RESP_ERR && r.length > 1 && r[1] == 6 ? " (value out of range)" : "");
            reloadSelf();
            log.accept("MeshCore: " + what + " changed");
            return what + " saved";
        } catch (IOException e) { return "not connected"; }
    }
    /** Ask for SELF_INFO again so the displayed settings show what the radio now has. */
    public void reloadSelf() {
        try {
            byte[] start = new byte[8 + "MeshConsole".length()];
            start[0] = CMD_APP_START;
            System.arraycopy("MeshConsole".getBytes(StandardCharsets.US_ASCII), 0, start, 8, "MeshConsole".length());
            byte[] r = request(start, 4000);
            if (r != null && (r[0] & 0xFF) == RESP_SELF_INFO) selfInfo(r);
        } catch (IOException ignored) { }
    }
    public String setName(String name) {
        byte[] n = name.trim().getBytes(StandardCharsets.UTF_8);
        if (n.length == 0 || n.length > 31) return "Name must be 1–31 bytes";
        byte[] cmd = new byte[1 + n.length]; cmd[0] = CMD_SET_ADVERT_NAME; System.arraycopy(n, 0, cmd, 1, n.length);
        return simple(cmd, "Name");
    }
    public String setTxPower(int dbm) {
        if (dbm < -9 || dbm > Math.max(maxTxPower, 1)) return "TX power must be between -9 and " + maxTxPower + " dBm";
        return simple(new byte[]{CMD_SET_RADIO_TX_POWER, (byte) dbm}, "TX power");
    }
    public String setPosition(double lat, double lon) {
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return "Latitude must be within ±90 and longitude within ±180";
        byte[] cmd = new byte[9]; cmd[0] = CMD_SET_ADVERT_LATLON;
        le32(cmd, 1, Math.round(lat * 1e6)); le32(cmd, 5, Math.round(lon * 1e6));
        return simple(cmd, "Position");
    }
    /** Whether adverts carry the position (ADVERT_LOC_SHARE) – the other "other params" are sent back unchanged. */
    public String setSharePosition(boolean share) {
        return simple(new byte[]{CMD_SET_OTHER_PARAMS, (byte) manualAdd, (byte) telemetryModes, (byte) (share ? 1 : 0), (byte) multiAcks}, share ? "Position sharing on" : "Position sharing off");
    }
    public String setRadio(double freqMhz, double bwKhz, int sf, int cr) {
        byte[] cmd = new byte[11]; cmd[0] = CMD_SET_RADIO_PARAMS;
        le32(cmd, 1, Math.round(freqMhz * 1000)); le32(cmd, 5, Math.round(bwKhz * 1000)); cmd[9] = (byte) sf; cmd[10] = (byte) cr;
        return simple(cmd, "Radio settings");
    }
    public String reboot() {
        try { write(new byte[]{CMD_REBOOT, 'r', 'e', 'b', 'o', 'o', 't'}); log.accept("MeshCore: radio rebooting"); return "rebooting – it reconnects by itself in about 20 s"; }
        catch (IOException e) { return "not connected"; }
    }

    public String advert(boolean flood) {
        try { byte[] r = request(new byte[]{CMD_SEND_SELF_ADVERT, (byte) (flood ? 1 : 0)}, 4000); return r != null && (r[0] & 0xFF) == RESP_OK ? "advert sent" + (flood ? " (flood)" : " (zero hop)") : "radio refused"; }
        catch (IOException e) { return "not connected"; }
    }

    // ------------------------------------------------------------------ store
    public Contact byPrefix(String p) {
        if (p == null || p.isEmpty()) return null;
        for (Contact c : contacts.values()) if (c.key.startsWith(p)) return c;
        return null;
    }

    private void add(Message m) {
        if (!m.out) {   // a message the radio hands over again (reconnect mid-sync) is the same message, not a new one
            int n = messages.size();
            for (int k = Math.max(0, n - 100); k < n; k++) { Message o = messages.get(k); if (!o.out && o.time == m.time && o.peer.equals(m.peer) && o.text.equals(m.text)) return; }
        }
        messages.add(m);
        while (messages.size() > 3000) messages.remove(0);
        try { Files.writeString(dir.resolve("meshcore_messages.log"), line(m) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { }
        if (!m.out) log.accept("MeshCore " + (m.peer.startsWith("#") ? "channel " + channelName(m.peer) : "DM") + " – " + m.from + ": " + m.text);
        for (Consumer<Message> l : messageListeners) try { l.accept(m); } catch (Exception ignored) { }
    }

    public String channelName(String peer) {
        try { int n = Integer.parseInt(peer.substring(1)); for (Channel c : channels) if (c.idx == n) return c.name; } catch (Exception ignored) { }
        return peer;
    }

    private static String line(Message m) {
        return String.join("\t", String.valueOf(m.time), m.out ? "out" : "in", m.peer, clean(m.from), clean(m.text), clean(m.status),
                Double.isNaN(m.snr) ? "" : String.valueOf(m.snr), String.valueOf(m.hops));
    }
    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }

    private void loadMessages() {
        Path p = dir.resolve("meshcore_messages.log");
        if (!Files.exists(p)) return;
        try {
            List<String> ls = Files.readAllLines(p, StandardCharsets.UTF_8);
            Map<String, Message> latest = new LinkedHashMap<>();   // a status rewrite appends a newer line for the same message
            for (String l : ls.subList(Math.max(0, ls.size() - 4000), ls.size())) {
                String[] a = l.split("\t", -1);
                if (a.length < 6) continue;
                Message m = new Message();
                m.time = Long.parseLong(a[0]); m.out = a[1].equals("out"); m.peer = a[2]; m.from = a[3]; m.text = a[4]; m.status = a[5];
                if (a.length > 6 && !a[6].isEmpty()) m.snr = Double.parseDouble(a[6]);
                if (a.length > 7) m.hops = Integer.parseInt(a[7]);
                latest.put(m.time + "|" + m.peer + "|" + m.text, m);
            }
            messages.addAll(latest.values());
        } catch (Exception e) { log.accept("MeshCore: couldn't read meshcore_messages.log (" + e.getMessage() + ")"); }
    }
    private void rewriteMessages() {   // delivery confirmations: append the updated line; loadMessages() keeps the newest
        Message last = null;
        for (Message m : messages) if (m.out && m.status.startsWith("delivered")) last = m;
        if (last != null) try { Files.writeString(dir.resolve("meshcore_messages.log"), line(last) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { }
    }

    synchronized void saveContacts() {
        StringBuilder b = new StringBuilder();
        for (Contact c : contacts.values())
            b.append(String.join("\t", c.key, clean(c.name), String.valueOf(c.type), String.valueOf(c.lat), String.valueOf(c.lon), String.valueOf(c.lastAdvert),
                    String.valueOf(c.lastHeard), String.valueOf(c.heard), String.valueOf(c.rssiSum), String.valueOf(c.snrSum), String.valueOf(c.sigCount), String.valueOf(c.hops))).append('\n');
        try { Files.writeString(dir.resolve("meshcore_contacts.tsv"), b.toString(), StandardCharsets.UTF_8); } catch (IOException ignored) { }
    }
    private void loadContacts() {
        try {
            for (String l : Files.readAllLines(dir.resolve("meshcore_contacts.tsv"), StandardCharsets.UTF_8)) {
                String[] a = l.split("\t", -1);
                if (a.length < 12) continue;
                Contact c = new Contact(); c.key = a[0]; c.name = a[1]; c.type = Integer.parseInt(a[2]); c.lat = Double.parseDouble(a[3]); c.lon = Double.parseDouble(a[4]);
                c.lastAdvert = Long.parseLong(a[5]); c.lastHeard = Long.parseLong(a[6]); c.heard = Long.parseLong(a[7]);
                c.rssiSum = Double.parseDouble(a[8]); c.snrSum = Double.parseDouble(a[9]); c.sigCount = Integer.parseInt(a[10]); c.hops = Integer.parseInt(a[11]);
                contacts.put(c.key, c);
            }
        } catch (NoSuchFileException ignored) { } catch (Exception e) { log.accept("MeshCore: couldn't read meshcore_contacts.tsv (" + e.getMessage() + ")"); }
    }

    // ------------------------------------------------------------------ bytes
    static long u32(byte[] b, int i) { return (b[i] & 0xFFL) | (b[i + 1] & 0xFFL) << 8 | (b[i + 2] & 0xFFL) << 16 | (b[i + 3] & 0xFFL) << 24; }
    static int i32(byte[] b, int i) { return (int) u32(b, i); }
    static int u16(byte[] b, int i) { return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8; }
    static void le32(byte[] b, int i, long v) { b[i] = (byte) v; b[i + 1] = (byte) (v >> 8); b[i + 2] = (byte) (v >> 16); b[i + 3] = (byte) (v >> 24); }
    static String hex(byte[] b, int i, int n) { StringBuilder s = new StringBuilder(); for (int k = 0; k < n && i + k < b.length; k++) s.append(String.format("%02x", b[i + k])); return s.toString(); }
    static void unhex(String h, byte[] b, int at) { for (int k = 0; k + 1 < h.length(); k += 2) b[at + k / 2] = (byte) Integer.parseInt(h.substring(k, k + 2), 16); }
    static String str(byte[] b, int i, int n) {
        int e = i; while (e < Math.min(b.length, i + n) && b[e] != 0) e++;
        return i >= b.length ? "" : new String(b, i, e - i, StandardCharsets.UTF_8).trim();
    }
}
