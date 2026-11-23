package gr.pnet.em.tep.qualityprofile;

import java.util.List;

import org.springframework.stereotype.Service;

import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.support.Slice;

/** The configured quality profiles ({@link QualityProfileProperties}), read-only and in configuration order. */
@Service
public class QualityProfileService {

    private final List<TepQualityProfile> profiles;

    public QualityProfileService(QualityProfileProperties properties) {
        this.profiles = properties.qualityProfiles();
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
