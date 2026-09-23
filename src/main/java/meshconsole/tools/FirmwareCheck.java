package meshconsole.tools;

import meshconsole.analysis.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Latest Meshtastic firmware release from GitHub, compared with the connected radio's version. */
public final class FirmwareCheck {
    private FirmwareCheck() { }
    public record Result(String latest, String url, boolean prerelease) { }

    public static Result latest() throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("https://api.github.com/repos/meshtastic/firmware/releases?per_page=10").toURL().openConnection();
        c.setRequestProperty("User-Agent", "MeshConsole"); c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(8000); c.setReadTimeout(10000);
        if (c.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + c.getResponseCode());
        String body; try (InputStream in = c.getInputStream()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        Object root = Json.parse(body);
        if (root instanceof List<?> arr) for (Object o : arr) if (o instanceof Map<?, ?> m) {
            boolean pre = Boolean.TRUE.equals(m.get("prerelease"));
            if (pre) continue;
            String tag = Json.str(m.get("tag_name")).replaceFirst("^v", "");
            return new Result(tag, Json.str(m.get("html_url")), false);
        }
        throw new IOException("No stable release found");
    }

    /** "2.7.26.54e0d8d" vs "2.7.26.1234" → compares the numeric part only. */
    public static int compare(String installed, String latest) {
        String[] a = installed.replaceFirst("^v", "").split("[.\\-]"), b = latest.replaceFirst("^v", "").split("[.\\-]");
        for (int i = 0; i < 3; i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("[^0-9].*", "")); } catch (NumberFormatException e) { return 0; } }
}
