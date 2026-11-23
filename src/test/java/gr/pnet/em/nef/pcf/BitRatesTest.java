package gr.pnet.em.nef.pcf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import gr.pnet.em.generated.tep.model.TepQualityProfileRate;
import gr.pnet.em.generated.tep.model.TepQualityProfileRate.UnitEnum;

class BitRatesTest {

    @ParameterizedTest
    @CsvSource({ "0,BPS,0 bps", "64,KBPS,64 Kbps", "5,MBPS,5 Mbps", "1,GBPS,1 Gbps", "2,TBPS,2 Tbps" })
    void writesA3gppBitRate(int value, UnitEnum unit, String bitRate) {
        assertThat(BitRates.of(new TepQualityProfileRate(value, unit))).isEqualTo(bitRate);
    }

    @Test
    void hasNoBitRateForAnIncompleteRate() {
        assertThat(BitRates.of(null)).isNull();
        assertThat(BitRates.of(new TepQualityProfileRate().unit(UnitEnum.MBPS))).isNull();
        assertThat(BitRates.of(new TepQualityProfileRate().value(5))).isNull();
    }
}
