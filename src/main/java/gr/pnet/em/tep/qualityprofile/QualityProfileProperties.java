package gr.pnet.em.tep.qualityprofile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import gr.pnet.em.generated.tep.model.TepQualityProfile;
import jakarta.validation.Valid;

/**
 * The quality profiles TEP offers ({@code tep.quality-profiles}), each in the shape of the TEP quality profile
 * body and validated by its constraints at startup. They are part of the deployment, like the PCF's qos_profiles,
 * which must map every profile name used for AS sessions to its 5QI. Each needs its fiveQi here too: an AS session
 * gives the UE's PDU session a PCC rule for it.
 */
@Validated
@ConfigurationProperties("tep")
public record QualityProfileProperties(List<@Valid TepQualityProfile> qualityProfiles) {

    public QualityProfileProperties {
        qualityProfiles = qualityProfiles == null ? List.of() : List.copyOf(qualityProfiles);
        Set<String> names = new HashSet<>();
        for (TepQualityProfile profile : qualityProfiles) {
            if (profile.getName() != null && !names.add(profile.getName())) {
                throw new IllegalArgumentException("Quality profile " + profile.getName() + " is configured twice");
            }
            if (profile.getFiveQi() == null) {
                throw new IllegalArgumentException("Quality profile " + profile.getName()
                        + " has no fiveQi, the 5QI the PCF's qos_profiles maps it to");
            }
        }
    }
}
