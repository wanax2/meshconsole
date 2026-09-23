package meshconsole.tools;

import meshconsole.analysis.Analysis;
import meshconsole.mesh.CoverageSample;
import meshconsole.mesh.MeshState;
import meshconsole.mesh.NodeEntry;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Google Earth export: nodes, tracks, coverage grid. */
public final class Kml {
    private Kml() { }

    public static void write(Path p, MeshState state) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8))) {
            w.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Mesh Console</name>");
            w.println("<Style id=\"me\"><IconStyle><color>ffdc6e1e</color><scale>1.3</scale></IconStyle></Style><Style id=\"fresh\"><IconStyle><color>ff3caa1e</color></IconStyle></Style><Style id=\"old\"><IconStyle><color>ff8c8c8c</color></IconStyle></Style><Style id=\"track\"><LineStyle><color>b4b43c78</color><width>3</width></LineStyle></Style>");
            long now = System.currentTimeMillis(); int me = state.myNodeNum();
            w.println("<Folder><name>Nodes</name>");
            for (NodeEntry n : state.nodes()) {
                if (!n.hasPosition) continue;
                String style = n.num == me ? "me" : now - n.lastHeardMillis() < 2 * 3600_000L ? "fresh" : "old";
                w.printf(Locale.ROOT, "<Placemark><name>%s</name><styleUrl>#%s</styleUrl><description><![CDATA[%s<br>hardware %s<br>last heard %s<br>SNR %.1f RSSI %d<br>hops %d]]></description><Point><coordinates>%.6f,%.6f,%d</coordinates></Point></Placemark>%n",
                        esc(n.displayName()), style, n.idString(), esc(n.hwModel), java.time.Instant.ofEpochMilli(n.lastHeardMillis()), n.snr, n.rssi, n.hopsAway, n.lon, n.lat, n.altitude);
            }
            w.println("</Folder><Folder><name>Tracks</name>");
            for (NodeEntry n : state.nodes()) {
                if (n.track.size() < 2) continue;
                w.printf("<Placemark><name>%s track</name><styleUrl>#track</styleUrl><LineString><tessellate>1</tessellate><coordinates>", esc(n.displayName()));
                for (double[] t : n.track) w.printf(Locale.ROOT, "%.6f,%.6f,0 ", t[2], t[1]);
                w.println("</coordinates></LineString></Placemark>");
            }
            w.println("</Folder><Folder><name>Coverage (100 m cells, median RSSI)</name>");
            List<CoverageSample> cov = state.coverage();
            for (Analysis.Cell c : Analysis.coverageGrid(cov, 100)) {
                double dLat = 50 / 111_320.0, dLon = 50 / (111_320.0 * Math.cos(Math.toRadians(c.lat())));
                String color = c.medianRssi() > -85 ? "9600c800" : c.medianRssi() > -100 ? "9600c8c8" : c.medianRssi() > -115 ? "960080ff" : "960000ff";   // aabbggrr
                w.printf(Locale.ROOT, "<Placemark><name>%.0f dBm (%d)</name><Style><PolyStyle><color>%s</color></PolyStyle><LineStyle><width>0</width></LineStyle></Style><Polygon><outerBoundaryIs><LinearRing><coordinates>%.6f,%.6f,0 %.6f,%.6f,0 %.6f,%.6f,0 %.6f,%.6f,0 %.6f,%.6f,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>%n",
                        c.medianRssi(), c.n(), color, c.lon() - dLon, c.lat() - dLat, c.lon() + dLon, c.lat() - dLat, c.lon() + dLon, c.lat() + dLat, c.lon() - dLon, c.lat() + dLat, c.lon() - dLon, c.lat() - dLat);
            }
            w.println("</Folder></Document></kml>");
        }
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
