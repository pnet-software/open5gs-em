package gr.pnet.em.tep.swapevent;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A row of {@code swap_events}, written by open5gs-swap-events (read-only here; that schema is the contract
 * between the two). Keyed by the MSISDN exactly as the subscriber document stores it.
 */
@Document("swap_events")
record SwapEventDocument(@Id String msisdn, String imsi, String imeisv, Instant lastUpdatedImsi,
        Instant lastUpdatedImeisv) {
}
