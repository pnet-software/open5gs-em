package gr.pnet.em.tep.status;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Pattern;

/**
 * The home PLMNs of the deployment ({@code tep.home-plmns}): a UE served by any other PLMN is roaming. Each is the
 * MCC followed by the MNC at its own length, as the AMF reports a tracking area's PLMN: {@code 00101}, or
 * {@code 310410} for a 3-digit MNC. List the home PLMN and any others to treat as home. Empty, the subscriber status
 * has no {@code roaming}.
 */
@Validated
@ConfigurationProperties("tep")
public record HomePlmnProperties(
        List<@Pattern(regexp = "\\d{5,6}", message = "must be an MCC + MNC, e.g. 00101") String> homePlmns) {

    public HomePlmnProperties {
        homePlmns = homePlmns == null ? List.of() : List.copyOf(homePlmns);
    }

    /** The home PLMNs, for lookups. */
    public Set<String> plmns() {
        return Set.copyOf(homePlmns);
    }
}
