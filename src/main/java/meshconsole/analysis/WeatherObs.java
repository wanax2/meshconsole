package meshconsole.analysis;

/** One METAR observation. NaN = not reported. */
public record WeatherObs(long time, String station, double tempC, double dewpointC, double humidityPct, double windKt, double gustKt,
                         double windDir, double pressureHpa, double visibilityMi, String wx, String raw) {
    public boolean precip() {
        String w = wx.toUpperCase();
        return w.contains("RA") || w.contains("SN") || w.contains("DZ") || w.contains("TS") || w.contains("SH") || w.contains("GR") || w.contains("PL");
    }
    public boolean fog() { String w = wx.toUpperCase(); return w.contains("FG") || w.contains("BR") || w.contains("HZ"); }
}
