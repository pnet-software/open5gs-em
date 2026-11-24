package gr.pnet.em.tep.qualityprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.generated.tep.model.TepQualityProfileRate;
import gr.pnet.em.support.Slice;

class QualityProfileServiceTest {

    private static final GbrFiveQis GBR = new GbrFiveQis(null);

    private static TepQualityProfile profile(String name) {
        return new TepQualityProfile(name, TepQualityProfile.StatusEnum.ACTIVE).fiveQi(9);
    }

    private static TepQualityProfileRate rate(int value, TepQualityProfileRate.UnitEnum unit) {
        return new TepQualityProfileRate(value, unit);
    }

    private static TepQualityProfileRate mbps(int value) {
        return rate(value, TepQualityProfileRate.UnitEnum.MBPS);
    }

    // A GBR profile (5QI 2) with all four flow bit rates
    private static TepQualityProfile gbr() {
        return new TepQualityProfile("QOS_M", TepQualityProfile.StatusEnum.ACTIVE).fiveQi(2)
                .maxDownstreamRate(mbps(40)).targetMinDownstreamRate(mbps(10))
                .maxUpstreamRate(mbps(40)).targetMinUpstreamRate(mbps(10));
    }

    private static QualityProfileService service(TepQualityProfile... profiles) {
        return new QualityProfileService(new QualityProfileProperties(List.of(profiles)), GBR);
    }

    private final QualityProfileService service = service(profile("QOS_S"), profile("QOS_M"), profile("QOS_L"));

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
        QualityProfileService none = new QualityProfileService(new QualityProfileProperties(null), GBR);

        assertThat(none.list(50, 0)).isEqualTo(new Slice<>(List.of(), 0));
    }

    @Test
    void aGbrProfileWithAllFourRatesStarts() {
        assertThatCode(() -> service(gbr())).doesNotThrowAnyException();
    }

    @Test
    void aGbrProfileWithoutAnUplinkMinimumStopsTheApp() {
        assertThatThrownBy(() -> service(gbr().targetMinUpstreamRate(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Quality profile QOS_M has the GBR 5QI 2 but no targetMinUpstreamRate, its guaranteed"
                        + " flow bit rate: without one, the whole maxUpstreamRate would be guaranteed");
    }

    @Test
    void aGbrProfileWithoutAMaximumStopsTheApp() {
        assertThatThrownBy(() -> service(gbr().maxDownstreamRate(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Quality profile QOS_M has the GBR 5QI 2 but no maxDownstreamRate, its maximum flow"
                        + " bit rate");
    }

    @Test
    void aMinimumAboveItsMaximumStopsTheAppWhateverTheUnits() {
        // 50 Mbps > 40000 Kbps
        assertThatThrownBy(() -> service(gbr().maxUpstreamRate(rate(40000, TepQualityProfileRate.UnitEnum.KBPS))
                .targetMinUpstreamRate(mbps(50))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Quality profile QOS_M has the GBR 5QI 2, and its targetMinUpstreamRate (50 Mbps) is"
                        + " above its maxUpstreamRate (40000 Kbps)");
        // 1 Gbps = 1000 Mbps: equal is fine
        assertThatCode(() -> service(gbr().maxDownstreamRate(rate(1, TepQualityProfileRate.UnitEnum.GBPS))
                .targetMinDownstreamRate(mbps(1000)))).doesNotThrowAnyException();
    }

    @Test
    void aNonGbrProfileNeedsNoRates() {
        // 5QI 9 is non-GBR: its QoS flow has no flow bit rates
        assertThatCode(() -> service(profile("QOS_D"))).doesNotThrowAnyException();
    }

    @Test
    void anOperatorSpecificGbrFiveQiNeedsItsRatesToo() {
        TepQualityProfile op = new TepQualityProfile("QOS_OP", TepQualityProfile.StatusEnum.ACTIVE).fiveQi(130);

        assertThatCode(() -> new QualityProfileService(new QualityProfileProperties(List.of(op)), GBR))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new QualityProfileService(new QualityProfileProperties(List.of(op)),
                new GbrFiveQis(List.of(130))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QOS_OP has the GBR 5QI 130 but no maxDownstreamRate");
    }
}
