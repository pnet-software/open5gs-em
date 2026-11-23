package gr.pnet.em.nef.pcf;

import gr.pnet.em.generated.pcfclient.model.ReservPriority;

/**
 * A TEP profile priority (1 to 100, 1 the highest) as a 3GPP reservation priority (PRIO_1 to PRIO_16, PRIO_16 the
 * highest), e.g. for a media component's resPrio: the 100 priorities in 16 even bands, 1–7 being PRIO_16 and
 * 95–100 PRIO_1. The Open5GS PCF turns it into an ARP priority level with its reservation_priorities config.
 */
final class ReservationPriorities {

    private static final int PRIORITIES = 100;
    private static final int RESERVATION_PRIORITIES = 16;

    private ReservationPriorities() {
    }

    static ReservPriority of(Integer priority) {
        if (priority == null || priority < 1 || priority > PRIORITIES) {
            return null;
        }
        int band = (priority - 1) * RESERVATION_PRIORITIES / PRIORITIES;
        return ReservPriority.fromValue("PRIO_" + (RESERVATION_PRIORITIES - band));
    }
}
