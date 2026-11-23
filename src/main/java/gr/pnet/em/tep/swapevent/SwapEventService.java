package gr.pnet.em.tep.swapevent;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Service;

import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.TepSwaps;
import gr.pnet.em.tep.subscriber.SubscriberService;

/**
 * A subscriber's swaps: its current SIM (IMSI) and device (IMEISV), and when each last changed, from the swap event
 * open5gs-swap-events records for it.
 */
@Service
public class SwapEventService {

    private final SubscriberService subscribers;
    private final MongoOperations mongo;

    public SwapEventService(SubscriberService subscribers, MongoOperations mongo) {
        this.subscribers = subscribers;
        this.mongo = mongo;
    }

    /** The swaps of the subscriber with this phone number. */
    public TepSwaps get(String phoneNumber) {
        // swap_events is keyed by the stored MSISDN, which may differ from the one asked by a leading '+'
        String msisdn = subscribers.findByPhoneNumber(phoneNumber).getList("msisdn", String.class).get(0);
        SwapEventDocument event = mongo.findById(msisdn, SwapEventDocument.class);
        // Nothing recorded doesn't mean nothing changed: open5gs-swap-events may not have seen the subscriber yet
        if (event == null) {
            throw new ResourceNotFoundException("Nothing is recorded on the swaps of subscriber " + phoneNumber
                    + ", so whether it swapped is unknown");
        }
        return new TepSwaps()
                .phoneNumber(event.msisdn())
                .imsi(event.imsi())
                .latestSimChange(utc(event.lastUpdatedImsi()))
                .imeisv(event.imeisv())
                .latestDeviceChange(utc(event.lastUpdatedImeisv()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
