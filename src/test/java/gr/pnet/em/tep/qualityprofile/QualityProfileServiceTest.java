package gr.pnet.em.tep.qualityprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.support.Slice;

class QualityProfileServiceTest {

    private static TepQualityProfile profile(String name) {
        return new TepQualityProfile(name, TepQualityProfile.StatusEnum.ACTIVE).fiveQi(9);
    }

    private final QualityProfileService service = new QualityProfileService(
            new QualityProfileProperties(List.of(profile("QOS_S"), profile("QOS_M"), profile("QOS_L"))));

    @Test
    void pagesTheProfilesInConfigurationOrder() {
        assertThat(service.list(2, 0)).isEqualTo(new Slice<>(List.of(profile("QOS_S"), profile("QOS_M")), 3));
        assertThat(service.list(2, 2)).isEqualTo(new Slice<>(List.of(profile("QOS_L")), 3));
        assertThat(service.list(50, 5)).isEqualTo(new Slice<>(List.of(), 3));
    }

    @Test
    void findsAProfileByName() {
        assertThat(service.get("QOS_M")).isEqualTo(profile("QOS_M"));
        assertThatThrownBy(() -> service.get("QOS_X"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Quality profile QOS_X not found");
    }

    @Test
    void noConfiguredProfilesIsAnEmptyList() {
        QualityProfileService none = new QualityProfileService(new QualityProfileProperties(null));

        assertThat(none.list(50, 0)).isEqualTo(new Slice<>(List.of(), 0));
    }
}
