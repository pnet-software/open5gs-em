package gr.pnet.em.nef.pcf;

import gr.pnet.em.generated.tep.model.TepQualityProfileRate;

/**
 * TEP rates as 3GPP BitRate strings, e.g. for a media component's marBwDl/marBwUl. TEP's rate units are the 3GPP
 * ones, so a rate of 5 Mbps is "5 Mbps".
 */
final class BitRates {

    private BitRates() {
    }

    static String of(TepQualityProfileRate rate) {
        if (rate == null || rate.getValue() == null || rate.getUnit() == null) {
            return null;
        }
        return rate.getValue() + " " + rate.getUnit().getValue();
    }
}
