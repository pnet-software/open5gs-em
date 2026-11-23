package gr.pnet.em.tep.qualityprofile;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import gr.pnet.em.generated.tep.QualityProfilesApiDelegate;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.generated.tep.model.TepQualityProfilePage;
import gr.pnet.em.support.Slice;

/** The quality profiles, read-only: they come from configuration. */
@Service
public class QualityProfileDelegate implements QualityProfilesApiDelegate {

    private final QualityProfileService profiles;

    public QualityProfileDelegate(QualityProfileService profiles) {
        this.profiles = profiles;
    }

    @Override
    public ResponseEntity<TepQualityProfilePage> listQualityProfiles(String xCorrelator, Integer limit, Integer offset) {
        Slice<TepQualityProfile> page = profiles.list(limit, offset);
        return ResponseEntity.ok(new TepQualityProfilePage(page.total(), limit, offset, page.items()));
    }

    @Override
    public ResponseEntity<TepQualityProfile> getQualityProfile(String name, String xCorrelator) {
        return ResponseEntity.ok(profiles.get(name));
    }
}
