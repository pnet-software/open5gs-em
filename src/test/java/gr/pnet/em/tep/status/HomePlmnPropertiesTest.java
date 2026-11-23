package gr.pnet.em.tep.status;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Home PLMNs are a list of MCC + MNC, and a malformed one stops the app from starting. */
class HomePlmnPropertiesTest {

    @EnableConfigurationProperties(HomePlmnProperties.class)
    static class Configuration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Configuration.class);

    @Test
    void bindsAListOfPlmns() {
        runner.withPropertyValues("tep.home-plmns[0]=00101", "tep.home-plmns[1]=310410", "tep.home-plmns[2]=00101")
                .run(context -> {
                    HomePlmnProperties properties = context.getBean(HomePlmnProperties.class);
                    assertThat(properties.homePlmns()).containsExactly("00101", "310410", "00101");
                    assertThat(properties.plmns()).containsExactlyInAnyOrder("00101", "310410");
                });
    }

    @Test
    void noHomePlmnsIsAnEmptyList() {
        runner.run(context -> assertThat(context.getBean(HomePlmnProperties.class).plmns()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "0010", "0010100", "001-01", "abcde" })
    void rejectsAPlmnThatIsNotAnMccAndMncAtStartup(String plmn) {
        runner.withPropertyValues("tep.home-plmns[0]=" + plmn)
                .run(context -> assertThat(context).hasFailed());
    }
}
