package gr.pnet.em.nef.pcf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import gr.pnet.em.generated.pcfclient.model.ReservPriority;

class ReservationPrioritiesTest {

    @ParameterizedTest
    @CsvSource({ "1,PRIO_16", "7,PRIO_16", "8,PRIO_15", "20,PRIO_13", "50,PRIO_9", "94,PRIO_2", "95,PRIO_1",
            "100,PRIO_1" })
    void bandsTheHundredPrioritiesIntoSixteen(int priority, ReservPriority reservationPriority) {
        assertThat(ReservationPriorities.of(priority)).isEqualTo(reservationPriority);
    }

    @ParameterizedTest
    @CsvSource(value = { "null", "0", "101" }, nullValues = "null")
    void hasNoReservationPriorityOutsideOneToAHundred(Integer priority) {
        assertThat(ReservationPriorities.of(priority)).isNull();
    }
}
