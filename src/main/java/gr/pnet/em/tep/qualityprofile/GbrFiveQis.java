package gr.pnet.em.tep.qualityprofile;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Which 5QIs are GBR. Only a GBR QoS flow has flow bit rates (MFBR, GFBR; TS 23.501 clause 5.7.2.5): a non-GBR flow
 * is limited by the Session-AMBR and UE-AMBR alone. The standardized ones come from TS 23.501 table 5.7.4-1 (GBR and
 * delay-critical GBR); operator-specific 5QIs that are GBR are listed in {@code tep.gbr-five-qis}. Every other 5QI
 * is non-GBR.
 */
@Validated
@ConfigurationProperties("tep")
public record GbrFiveQis(List<@Min(1) @Max(255) Integer> gbrFiveQis) {

    /** TS 23.501 table 5.7.4-1: the GBR and delay-critical GBR 5QIs (75 is deprecated, but still GBR). */
    static final Set<Integer> STANDARDIZED = Set.of(1, 2, 3, 4, 65, 66, 67, 71, 72, 73, 74, 75, 76,
            82, 83, 84, 85, 86, 87, 88, 89, 90);

    public GbrFiveQis {
        gbrFiveQis = gbrFiveQis == null ? List.of() : List.copyOf(gbrFiveQis);
    }

    /** Whether QoS flows with this 5QI are GBR, and so have flow bit rates. */
    public boolean isGbr(Integer fiveQi) {
        return fiveQi != null && (STANDARDIZED.contains(fiveQi) || gbrFiveQis.contains(fiveQi));
    }
}
