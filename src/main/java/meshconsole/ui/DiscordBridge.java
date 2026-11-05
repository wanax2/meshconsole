package meshconsole.ui;

import meshconsole.analysis.Json;
import meshconsole.mesh.ChatMessage;
import meshconsole.mesh.MeshClient;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Two-way bridge between one mesh channel and one Discord channel.
 *
 *  mesh → Discord: every public message on the chosen channel is posted through a webhook, under the sender's node
 *                  name, with hops and signal in small print.
 *  Discord → mesh: messages people type in the Discord channel are sent to the mesh as "[D] name: text" (cut to fit
 *                  200 bytes) – needs a bot token with read access to that channel. Airtime is protected: at most N
 *                  relays an hour (default 10) and one per person a minute; anything held back gets a note in Discord.
 *                  Bot and webhook messages are never relayed (no loops), and "[D] " messages are never sent back.
 * Settings live in discord-bridge.properties in the data folder (web console → Settings).
 */
final class DiscordBridge {
    final Properties p = new Properties();
    private final MeshClient client;
    private final Path file;
    private final LinkedBlockingQueue<String[]> toDiscord = new LinkedBlockingQueue<>(200);   // {username, content}
    private final Deque<Long> sentTimes = new ArrayDeque<>();
    private final Map<String, Long> lastByUser = new HashMap<>();
    private volatile String status = "off";
    volatile int toDiscordCount, toMeshCount, heldBack;
    private long lastHoldNote;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
    static final String PREFIX = "[D] ";

    DiscordBridge(MeshClient client, Path dataDir) {
        this.client = client;
        this.file = dataDir.resolve("discord-bridge.properties");
        try (var r = Files.newBufferedReader(file)) { p.load(r); } catch (Exception ignored) { }
        MeshState st = client.state();
        st.addListener(new MeshState.Listener() {
            @Override public void onMessage(ChatMessage m, boolean isNew) { if (isNew) fromMesh(m); }
        });
        Thread sender = new Thread(this::senderLoop, "discord-send"); sender.setDaemon(true); sender.start();
        Thread poller = new Thread(this::pollLoop, "discord-poll"); poller.setDaemon(true); poller.start();
        status = on() ? "starting" : "off";
    }

    String get(String k, String d) { return p.getProperty(k, d); }
    boolean on() { return Boolean.parseBoolean(get("enabled", "false")); }
    int channel() { try { return Integer.parseInt(get("meshChannel", "0")); } catch (NumberFormatException e) { return 0; } }

    void save(Map<String, String> f) throws java.io.IOException {
        for (String k : List.of("enabled", "webhook", "botToken", "channelId", "meshChannel", "toMesh", "toDiscord", "maxPerHour", "showSignal"))
            if (f.containsKey(k) && !(k.equals("botToken") && f.get(k).isBlank())) p.setProperty(k, f.get(k).trim());
        p.remove("lastId");   // start fresh: don't replay old Discord history after a settings change
        try (var w = Files.newBufferedWriter(file)) { p.store(w, "Mesh Console Discord bridge"); }
        status = on() ? "starting" : "off";
    }

    String json() {
        return "{\"enabled\":" + on() + ",\"webhook\":" + WebConsole.q(get("webhook", "")) + ",\"hasBotToken\":" + !get("botToken", "").isBlank()
            + ",\"channelId\":" + WebConsole.q(get("channelId", "")) + ",\"meshChannel\":" + channel()
            + ",\"toMesh\":" + get("toMesh", "true") + ",\"toDiscord\":" + get("toDiscord", "true") + ",\"maxPerHour\":" + get("maxPerHour", "10")
            + ",\"showSignal\":" + get("showSignal", "true") + ",\"status\":" + WebConsole.q(status)
            + ",\"toDiscordCount\":" + toDiscordCount + ",\"toMeshCount\":" + toMeshCount + ",\"heldBack\":" + heldBack + "}";
    }

    // ------------------------------------------------------------------ mesh → Discord
    void fromMesh(ChatMessage m) {
        if (!on() || !Boolean.parseBoolean(get("toDiscord", "true")) || get("webhook", "").isBlank()) return;
        if (!m.isBroadcast() || m.emoji || m.channel != channel() || m.text == null || m.text.isBlank()) return;
        if (m.text.startsWith(PREFIX)) return;   // came from Discord in the first place
        MeshState st = client.state();
        NodeEntry n = st.node(m.from);
        String name = m.from == 0 ? "Mesh Console" : st.nodeName(m.from) + (n != null && n.shortName != null && !n.shortName.isBlank() ? " (" + n.shortName + ")" : "");
        StringBuilder c = new StringBuilder(m.text.replace("@", "@\u200b"));   // no accidental @everyone pings
        if (Boolean.parseBoolean(get("showSignal", "true")) && !m.outgoing) {
            List<String> meta = new ArrayList<>();
            if (m.hops == 0) meta.add("direct"); else if (m.hops > 0) meta.add(m.hops + " hop" + (m.hops == 1 ? "" : "s"));
            if (m.rssi != 0) meta.add(m.rssi + " dBm / " + m.snr + " dB");
            if (!meta.isEmpty()) c.append("\n-# ").append(String.join(" · ", meta));
        }
        toDiscord.offer(new String[]{name.length() > 78 ? name.substring(0, 78) : name, c.toString()});
    }

    private void senderLoop() {
        while (true) {
            try {
                String[] msg = toDiscord.take();
                String body = "{\"username\":" + WebConsole.q(msg[0]) + ",\"content\":" + WebConsole.q(msg[1]) + ",\"allowed_mentions\":{\"parse\":[]}}";
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(get("webhook", ""))).timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 429) { toDiscord.offer(msg); Thread.sleep(5000); continue; }   // Discord's rate limit: wait and retry
                if (r.statusCode() < 300) { toDiscordCount++; status = "running"; } else status = "webhook error HTTP " + r.statusCode();
                Thread.sleep(1200);
            } catch (InterruptedException e) { return; }
            catch (Exception e) { status = "webhook error: " + e.getMessage(); }
        }
    }

    // ------------------------------------------------------------------ Discord → mesh
    private void pollLoop() {
        while (true) {
            try { Thread.sleep(15_000); } catch (InterruptedException e) { return; }
            if (!on() || !Boolean.parseBoolean(get("toMesh", "true"))) continue;
            String token = get("botToken", ""), chan = get("channelId", "");
            if (token.isBlank() || chan.isBlank()) continue;
            try {
                String last = get("lastId", "");
                String url = "https://discord.com/api/v10/channels/" + chan + "/messages?limit=" + (last.isEmpty() ? "1" : "20" + "&after=" + last);
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token).header("User-Agent", "MeshConsole (bridge)").build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) { status = "Discord read error HTTP " + r.statusCode() + (r.statusCode() == 403 ? " (bot can't read that channel)" : ""); continue; }
                List<?> msgs = (List<?>) Json.parse(r.body());
                List<Map<?, ?>> list = new ArrayList<>();
                for (Object o : msgs) list.add((Map<?, ?>) o);
                list.sort(Comparator.comparing(mm -> Long.parseUnsignedLong(Json.str(mm.get("id")))));   // oldest first
                boolean firstRun = last.isEmpty();
                for (Map<?, ?> mm : list) {
                    String id = Json.str(mm.get("id"));
                    p.setProperty("lastId", id);
                    if (firstRun) continue;   // first contact: just remember where we are
                    Map<?, ?> author = (Map<?, ?>) mm.get("author");
                    if (author == null || Boolean.TRUE.equals(author.get("bot")) || mm.get("webhook_id") != null) continue;
                    String content = Json.str(mm.get("content")).trim();
                    if (content.isEmpty()) continue;
                    String user = Json.str(author.get("global_name")).isBlank() ? Json.str(author.get("username")) : Json.str(author.get("global_name"));
                    relayToMesh(user, content);
                }
                try (var w = Files.newBufferedWriter(file)) { p.store(w, "Mesh Console Discord bridge"); }
                if (status.startsWith("Discord read error") || status.equals("starting")) status = "running";
            } catch (Exception e) { status = "Discord read error: " + e.getMessage(); }
        }
    }

    void relayToMesh(String user, String content) {
        long now = System.currentTimeMillis();
        int max; try { max = Integer.parseInt(get("maxPerHour", "10")); } catch (NumberFormatException e) { max = 10; }
        while (!sentTimes.isEmpty() && sentTimes.peekFirst() < now - 3600_000L) sentTimes.pollFirst();
        Long prev = lastByUser.get(user);
        String why = !client.isConnected() ? "radio not connected"
            : sentTimes.size() >= max ? "mesh limit of " + max + " an hour reached"
            : prev != null && now - prev < 60_000L ? "one message a minute per person" : null;
        if (why != null) {
            heldBack++;
            if (now - lastHoldNote > 60_000L && !get("webhook", "").isBlank()) {
                lastHoldNote = now;
                toDiscord.offer(new String[]{"Mesh bridge", "⏳ Not sent to the mesh (" + why + ") – the radio channel is shared airtime."});
            }
            return;
        }
        String text = PREFIX + user + ": " + content.replaceAll("\\s+", " ");
        while (text.getBytes(StandardCharsets.UTF_8).length > 200) text = text.substring(0, text.length() - 2) + "…";
        try {
            client.sendText(text, ChatMessage.BROADCAST, channel());
            sentTimes.addLast(now); lastByUser.put(user, now); toMeshCount++;
            client.state().emitLog("[discord] → mesh: " + text);
        } catch (Exception e) { status = "send to mesh failed: " + e.getMessage(); }
    }
}
