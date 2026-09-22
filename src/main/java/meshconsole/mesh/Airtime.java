package meshconsole.mesh;

import org.meshtastic.proto.ConfigProtos.Config;

/** LoRa time-on-air for Meshtastic packets (Semtech SX126x formula, 16-symbol preamble, explicit header, CRC). */
public final class Airtime {
    private Airtime() { }

    /** {sf, bandwidthHz, codingRateDenominator(5..8)} for the radio's LoRa config. */
    public static double[] params(Config.LoRaConfig lora) {
        if (lora == null) return new double[]{11, 250_000, 5};
        if (!lora.getUsePreset()) {
            double bw = lora.getBandwidth() == 0 ? 250_000 : (lora.getBandwidth() == 31 ? 31_250 : lora.getBandwidth() == 62 ? 62_500 : lora.getBandwidth() * 1000.0);
            return new double[]{lora.getSpreadFactor() == 0 ? 11 : lora.getSpreadFactor(), bw, lora.getCodingRate() == 0 ? 5 : lora.getCodingRate()};
        }
        return switch (lora.getModemPreset()) {
            case SHORT_TURBO -> new double[]{7, 500_000, 5};
            case SHORT_FAST -> new double[]{7, 250_000, 5};
            case SHORT_SLOW -> new double[]{8, 250_000, 5};
            case MEDIUM_FAST -> new double[]{9, 250_000, 5};
            case MEDIUM_SLOW -> new double[]{10, 250_000, 5};
            case LONG_MODERATE -> new double[]{11, 125_000, 8};
            case LONG_SLOW -> new double[]{12, 125_000, 8};
            case VERY_LONG_SLOW -> new double[]{12, 62_500, 8};
            default -> new double[]{11, 250_000, 5};        // LONG_FAST
        };
    }

    /** Milliseconds a packet of the given over-the-air size (header + payload bytes) occupies the channel. */
    public static double millis(int airBytes, Config.LoRaConfig lora) {
        double[] p = params(lora);
        double sf = p[0], bw = p[1], cr = p[2] - 4;
        double tSym = Math.pow(2, sf) / bw * 1000.0;
        int de = tSym > 16 ? 1 : 0;                       // low data-rate optimisation
        double tmp = Math.ceil((8.0 * airBytes - 4 * sf + 28 + 16) / (4 * (sf - 2 * de))) * (cr + 4);
        double payloadSymbols = 8 + Math.max(tmp, 0);
        double preambleSymbols = 16 + 4.25;
        return (preambleSymbols + payloadSymbols) * tSym;
    }

    public static String presetName(Config.LoRaConfig lora) {
        double[] p = params(lora);
        return String.format("SF%d / %.0f kHz / 4:%d", (int) p[0], p[1] / 1000, (int) p[2]);
    }
}
