package gr.pnet.em.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SliceDifferentiatorsTest {

    @ParameterizedTest
    @CsvSource(value = { "null,null", "null,ffffff", "null,FFFFFF", "'',ffffff", "000001,000001", "00000a,00000A" },
            nullValues = "null")
    void aMissingSdAndFfffffAreBothNoSd(String sd, String other) {
        assertThat(SliceDifferentiators.same(sd, other)).isTrue();
        assertThat(SliceDifferentiators.same(other, sd)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(value = { "null,000001", "ffffff,000001", "000001,000002" }, nullValues = "null")
    void differentSdsAreNotTheSame(String sd, String other) {
        assertThat(SliceDifferentiators.same(sd, other)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(value = { "null,null", "ffffff,null", "FFFFFF,null", "'',null", "000001,000001", "00000A,00000A" },
            nullValues = "null")
    void reportsNoSdAsNone(String sd, String reported) {
        assertThat(SliceDifferentiators.present(sd)).isEqualTo(reported);
    }
}
