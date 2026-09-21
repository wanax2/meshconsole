package meshconsole.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class Fmt {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    static String time(long epochMillis) {
        return epochMillis <= 0 ? "" : TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    static String ago(long epochMillis) {
        if (epochMillis <= 0) return "never";
        long s = (System.currentTimeMillis() - epochMillis) / 1000;
        if (s < 0) s = 0;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        return (s / 86400) + "d " + ((s % 86400) / 3600) + "h";
    }

    /** Great-circle distance in metres. */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static String distance(double m) {
        return m < 1000 ? String.format("%.0f m", m) : String.format("%.1f km", m / 1000);
    }

    private Fmt() { }
}
