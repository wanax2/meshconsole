package meshconsole.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.prefs.Preferences;

/** Pushes alerts out of the machine: generic JSON webhook (Discord/Slack-compatible) and Pushover. */
public final class Notifier {
    private static final Preferences PREFS = Preferences.userNodeForPackage(Notifier.class);
    private Notifier() { }

    public static String webhook() { return PREFS.get("webhook", ""); }
    public static String pushoverToken() { return PREFS.get("poToken", ""); }
    public static String pushoverUser() { return PREFS.get("poUser", ""); }
    public static void set(String webhook, String poToken, String poUser) { PREFS.put("webhook", webhook.trim()); PREFS.put("poToken", poToken.trim()); PREFS.put("poUser", poUser.trim()); }

    /** Sends to whatever is configured; never throws (failures go to the returned string). */
    public static String send(String title, String text) {
        StringBuilder err = new StringBuilder();
        String wh = webhook();
        if (!wh.isEmpty()) {
            try {
                String json = "{\"content\":" + q("**" + title + "** " + text) + ",\"text\":" + q(title + ": " + text) + ",\"username\":\"Mesh Console\"}";
                post(wh, "application/json", json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) { err.append("webhook: ").append(e.getMessage()).append(' '); }
        }
        if (!pushoverToken().isEmpty() && !pushoverUser().isEmpty()) {
            try {
                String body = "token=" + enc(pushoverToken()) + "&user=" + enc(pushoverUser()) + "&title=" + enc(title) + "&message=" + enc(text);
                post("https://api.pushover.net/1/messages.json", "application/x-www-form-urlencoded", body.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) { err.append("pushover: ").append(e.getMessage()); }
        }
        return err.toString().trim();
    }

    private static void post(String url, String type, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(8000); c.setReadTimeout(10000);
        c.setRequestProperty("Content-Type", type); c.setRequestProperty("User-Agent", "MeshConsole");
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        int code = c.getResponseCode();
        if (code >= 300) throw new IOException("HTTP " + code);
    }

    private static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
