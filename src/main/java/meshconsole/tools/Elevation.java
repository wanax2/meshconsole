package meshconsole.tools;

import meshconsole.analysis.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Terrain profile between two points (Open-Elevation / Open Topo Data, no key) and line-of-sight analysis. */
public final class Elevation {
    private Elevation() { }

    public record Profile(double[] distKm, double[] elevM, double totalKm) { }
    public record Los(boolean clear, double worstClearanceM, double worstAtKm, double fresnelRadiusMidM, double neededHeightM) { }

    /** Samples n points along the great circle and fetches elevations. */
    public static Profile profile(double lat1, double lon1, double lat2, double lon2, int n) throws IOException {
        double total = meshconsole.analysis.Metar.distanceKm(lat1, lon1, lat2, lon2);
        double[] lats = new double[n], lons = new double[n], dist = new double[n];
        for (int i = 0; i < n; i++) { double t = (double) i / (n - 1); lats[i] = lat1 + (lat2 - lat1) * t; lons[i] = lon1 + (lon2 - lon1) * t; dist[i] = total * t; }
        double[] elev = fetch(lats, lons);
        return new Profile(dist, elev, total);
    }

    private static double[] fetch(double[] lats, double[] lons) throws IOException {
        StringBuilder loc = new StringBuilder();
        for (int i = 0; i < lats.length; i++) { if (i > 0) loc.append('|'); loc.append(String.format(Locale.ROOT, "%.5f,%.5f", lats[i], lons[i])); }
        IOException last = null;
        for (String base : new String[]{"https://api.opentopodata.org/v1/srtm30m?locations=", "https://api.open-elevation.com/api/v1/lookup?locations="}) {
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(base + loc).toURL().openConnection();
                c.setRequestProperty("User-Agent", "MeshConsole"); c.setConnectTimeout(8000); c.setReadTimeout(20000);
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " from " + base);
                String body; try (InputStream in = c.getInputStream()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                Object root = Json.parse(body);
                List<?> results = (List<?>) ((Map<?, ?>) root).get("results");
                double[] out = new double[lats.length];
                for (int i = 0; i < out.length && i < results.size(); i++) out[i] = Json.num(((Map<?, ?>) results.get(i)).get("elevation"), 0);
                return out;
            } catch (IOException | RuntimeException e) { last = e instanceof IOException io ? io : new IOException(e.getMessage()); }
        }
        throw last;
    }

    /** Line of sight with 4/3-earth curvature and 60 % first Fresnel zone at freqMHz; antenna heights above ground. */
    public static Los analyse(Profile p, double h1, double h2, double freqMHz) {
        int n = p.distKm.length;
        double e1 = p.elevM[0] + h1, e2 = p.elevM[n - 1] + h2, D = p.totalKm;
        double worst = Double.MAX_VALUE, worstAt = 0, needed = 0;
        double lambda = 300.0 / freqMHz;
        for (int i = 1; i < n - 1; i++) {
            double d1 = p.distKm[i], d2 = D - d1;
            double bulge = (d1 * d2) / (2 * 4.0 / 3.0 * 6371.0) * 1000.0;               // earth curvature, metres
            double ray = e1 + (e2 - e1) * (d1 / D);
            double fresnel = Math.sqrt(lambda * d1 * 1000 * d2 * 1000 / (D * 1000));     // first Fresnel radius, metres
            double clearance = ray - (p.elevM[i] + bulge);
            double margin = clearance - 0.6 * fresnel;
            if (margin < worst) { worst = margin; worstAt = d1; }
            if (margin < 0) needed = Math.max(needed, -margin * D / d2);                 // raise end 1 to clear this point
        }
        double fresMid = Math.sqrt(lambda * (D / 2) * 1000 * (D / 2) * 1000 / (D * 1000));
        return new Los(worst >= 0, worst, worstAt, fresMid, needed);
    }
}
