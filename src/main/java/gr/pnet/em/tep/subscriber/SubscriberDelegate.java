package gr.pnet.em.tep.subscriber;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import gr.pnet.em.generated.tep.SubscribersApiDelegate;
import gr.pnet.em.generated.tep.model.TepDevice;
import gr.pnet.em.generated.tep.model.TepSubscriber;
import gr.pnet.em.generated.tep.model.TepSubscriberLocation;
import gr.pnet.em.generated.tep.model.TepSubscriberRef;
import gr.pnet.em.generated.tep.model.TepSubscriberRefPage;
import gr.pnet.em.generated.tep.model.TepSubscriberStatus;
import gr.pnet.em.generated.tep.model.TepSwaps;
import gr.pnet.em.support.Locations;
import gr.pnet.em.support.Slice;
import gr.pnet.em.tep.cell.CellService;
import gr.pnet.em.tep.status.SubscriberStatusService;
import gr.pnet.em.tep.swapevent.SwapEventService;

/**
 * The x-correlator each operation takes is handled for every request by {@link gr.pnet.em.web.CorrelatorFilter}.
 * <p>
 * Everything under /subscriber: provisioning by TEP id, and the lookups by the phone number of a device in the body
 * (the subscriber itself, real-time status, location and SIM and device swaps).
 */
@Service
public class SubscriberDelegate implements SubscribersApiDelegate {

    private final SubscriberService subscribers;
    private final SubscriberStatusService statuses;
    private final CellService cells;
    private final SwapEventService swapEvents;

    public SubscriberDelegate(SubscriberService subscribers, SubscriberStatusService statuses,
            CellService cells, SwapEventService swapEvents) {
        this.subscribers = subscribers;
        this.statuses = statuses;
        this.cells = cells;
        this.swapEvents = swapEvents;
    }

    @Override
    public ResponseEntity<TepSubscriberRefPage> listSubscribers(String xCorrelator, Integer limit, Integer offset) {
        Slice<TepSubscriberRef> page = subscribers.list(limit, offset);
        return ResponseEntity.ok(new TepSubscriberRefPage(page.total(), limit, offset, page.items()));
    }

    @Override
    public ResponseEntity<TepSubscriber> getSubscriber(String id, String xCorrelator) {
        return ResponseEntity.ok(subscribers.get(id));
    }

    @Override
    public ResponseEntity<Void> createSubscriber(TepSubscriber subscriber, String xCorrelator) {
        return Locations.created(subscribers.create(subscriber));
    }

    @Override
    public ResponseEntity<Void> updateSubscriber(String id, TepSubscriber subscriber, String xCorrelator) {
        subscribers.update(id, subscriber);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deleteSubscriber(String id, String xCorrelator) {
        subscribers.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<TepSubscriber> retrieveSubscriber(TepDevice device, String xCorrelator) {
        return ResponseEntity.ok(subscribers.getByPhoneNumber(device.getPhoneNumber()));
    }

    @Override
    public ResponseEntity<TepSubscriberStatus> getSubscriberStatus(TepDevice device, String xCorrelator) {
        return ResponseEntity.ok(statuses.status(device.getPhoneNumber()));
    }

    @Override
    public ResponseEntity<TepSubscriberLocation> getSubscriberLocation(TepDevice device, String xCorrelator) {
        return ResponseEntity.ok(cells.locate(device.getPhoneNumber()));
    }

    @Override
    public ResponseEntity<TepSwaps> getSwaps(TepDevice device, String xCorrelator) {
        return ResponseEntity.ok(swapEvents.get(device.getPhoneNumber()));
    }
}
