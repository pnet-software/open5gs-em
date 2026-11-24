package gr.pnet.em.tep.qualityprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class GbrFiveQisTest {

    private final GbrFiveQis standard = new GbrFiveQis(null);

    @Test
    void theStandardizedGbrAndDelayCriticalGbrFiveQisAreGbr() {
        assertThat(List.of(1, 2, 3, 4, 65, 66, 67, 71, 72, 73, 74, 76, 82, 85, 90))
                .allMatch(standard::isGbr);
    }

    @Test
    void theStandardizedNonGbrFiveQisAreNot() {
        assertThat(List.of(5, 6, 7, 8, 9, 10, 69, 70, 79, 80)).noneMatch(standard::isGbr);
    }

    @Test
    void anOperatorSpecificFiveQiIsGbrOnlyWhenListed() {
        assertThat(standard.isGbr(130)).isFalse();
        assertThat(new GbrFiveQis(List.of(130)).isGbr(130)).isTrue();
    }

    @Test
    void noFiveQiIsNotGbr() {
        assertThat(standard.isGbr(null)).isFalse();
    }
}
