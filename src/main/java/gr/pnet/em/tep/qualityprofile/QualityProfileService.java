package gr.pnet.em.tep.qualityprofile;

import java.util.List;

import org.springframework.stereotype.Service;

import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.generated.tep.model.TepQualityProfileRate;
import gr.pnet.em.support.Slice;

/**
 * The configured quality profiles ({@link QualityProfileProperties}), read-only and in configuration order. A GBR
 * profile must give its flow bit rates, or the app doesn't start (see {@link #requireFlowBitRates}).
 */
@Service
public class QualityProfileService {

    private final List<TepQualityProfile> profiles;

    public QualityProfileService(QualityProfileProperties properties, GbrFiveQis gbrFiveQis) {
        this.profiles = properties.qualityProfiles();
        requireFlowBitRates(profiles, gbrFiveQis);
    }

    /**
     * Every profile with a GBR 5QI gives its four flow bit rates, each minimum no higher than its maximum. A GBR QoS
     * flow needs all four (MFBR and GFBR, uplink and downlink; TS 24.501 clause 6.4.1.3). Without a minimum, the
     * Open5GS PCF guarantees the whole maximum; without a maximum, the SMF makes it 4 Tbps.
     *
     * @throws IllegalStateException naming the first profile that doesn't
     */
    static void requireFlowBitRates(List<TepQualityProfile> profiles, GbrFiveQis gbrFiveQis) {
        for (TepQualityProfile profile : profiles) {
            if (!gbrFiveQis.isGbr(profile.getFiveQi())) {
                continue;
            }
            requireRates(profile, "Downstream", profile.getTargetMinDownstreamRate(), profile.getMaxDownstreamRate());
            requireRates(profile, "Upstream", profile.getTargetMinUpstreamRate(), profile.getMaxUpstreamRate());
        }
    }

    private static void requireRates(TepQualityProfile profile, String direction, TepQualityProfileRate min,
            TepQualityProfileRate max) {
        String name = "Quality profile " + profile.getName() + " has the GBR 5QI " + profile.getFiveQi();
        if (max == null) {
            throw new IllegalStateException(name + " but no max" + direction + "Rate, its maximum flow bit rate");
        }
        if (min == null) {
            throw new IllegalStateException(name + " but no targetMin" + direction + "Rate, its guaranteed flow bit"
                    + " rate: without one, the whole max" + direction + "Rate would be guaranteed");
        }
        if (bps(min) > bps(max)) {
            throw new IllegalStateException(name + ", and its targetMin" + direction + "Rate (" + text(min)
                    + ") is above its max" + direction + "Rate (" + text(max) + ")");
        }
    }

    private static double bps(TepQualityProfileRate rate) {
        double factor = switch (rate.getUnit()) {
            case BPS -> 1;
            case KBPS -> 1e3;
            case MBPS -> 1e6;
            case GBPS -> 1e9;
            case TBPS -> 1e12;
        };
        return rate.getValue() * factor;
    }

    private static String text(TepQualityProfileRate rate) {
        return rate.getValue() + " " + rate.getUnit().getValue();
    }

    public Slice<TepQualityProfile> list(int limit, int offset) {
        int from = Math.min(offset, profiles.size());
        int to = Math.min(from + limit, profiles.size());
        return new Slice<>(profiles.subList(from, to), profiles.size());
    }

    /** The profile with this name, for the TEP API and for an AS session whose qosReference names it. */
    public TepQualityProfile get(String name) {
        return profiles.stream()
                .filter(profile -> profile.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Quality profile " + name + " not found"));
    }
}
