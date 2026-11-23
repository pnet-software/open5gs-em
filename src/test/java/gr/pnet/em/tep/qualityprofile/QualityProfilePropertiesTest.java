package gr.pnet.em.tep.qualityprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.generated.tep.model.TepQualityProfileRate;

/** Configured profiles are bound in the shape of the TEP body, and a bad one stops the app from starting. */
class QualityProfilePropertiesTest {

    @EnableConfigurationProperties(QualityProfileProperties.class)
    static class Configuration {
    }

    private static final String[] QOS_S = { "tep.quality-profiles[0].name=QOS_S",
            "tep.quality-profiles[0].status=ACTIVE", "tep.quality-profiles[0].five-qi=67",
            "tep.quality-profiles[0].max-downstream-rate.value=10",
            "tep.quality-profiles[0].max-downstream-rate.unit=Mbps" };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Configuration.class);

    @Test
    void bindsProfilesInTheShapeOfTheTepBody() {
        runner.withPropertyValues(QOS_S)
                .withPropertyValues("tep.quality-profiles[0].priority=20",
                        "tep.quality-profiles[0].preemption-capability=MAY_PREEMPT",
                        "tep.quality-profiles[1].name=QOS_D", "tep.quality-profiles[1].status=DEPRECATED",
                        "tep.quality-profiles[1].five-qi=9")
                .run(context -> {
                    List<TepQualityProfile> profiles = context.getBean(QualityProfileProperties.class)
                            .qualityProfiles();
                    assertThat(profiles).extracting(TepQualityProfile::getName).containsExactly("QOS_S", "QOS_D");
                    assertThat(profiles.get(0).getMaxDownstreamRate())
                            .isEqualTo(new TepQualityProfileRate(10, TepQualityProfileRate.UnitEnum.MBPS));
                    assertThat(profiles.get(0).getPriority()).isEqualTo(20);
                    assertThat(profiles.get(0).getFiveQi()).isEqualTo(67);
                    assertThat(profiles.get(0).getPreemptionCapability())
                            .isEqualTo(TepQualityProfile.PreemptionCapabilityEnum.MAY_PREEMPT);
                    assertThat(profiles.get(1).getStatus()).isEqualTo(TepQualityProfile.StatusEnum.DEPRECATED);
                });
    }

    @Test
    void noProfilesIsAnEmptyList() {
        runner.run(context -> assertThat(context.getBean(QualityProfileProperties.class).qualityProfiles()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "max-downstream-rate.unit=PBPS", "max-downstream-rate.value=2000", "priority=0",
            "status=RETIRED", "name=no spaces", "preemption-capability=ALWAYS", "five-qi=0", "five-qi=256" })
    void rejectsAnInvalidProfileAtStartup(String setting) {
        runner.withPropertyValues(QOS_S)
                .withPropertyValues("tep.quality-profiles[0]." + setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAProfileWithoutAFiveQi() {
        runner.withPropertyValues("tep.quality-profiles[0].name=QOS_S", "tep.quality-profiles[0].status=ACTIVE")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("QOS_S has no fiveQi"));
    }

    @Test
    void rejectsAProfileWithoutAStatus() {
        runner.withPropertyValues("tep.quality-profiles[0].name=QOS_S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsTwoProfilesWithOneName() {
        runner.withPropertyValues(QOS_S)
                .withPropertyValues("tep.quality-profiles[1].name=QOS_S", "tep.quality-profiles[1].status=INACTIVE",
                        "tep.quality-profiles[1].five-qi=9")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessage("Quality profile QOS_S is configured twice"));
    }
}
