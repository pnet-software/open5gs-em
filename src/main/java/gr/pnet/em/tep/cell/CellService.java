package gr.pnet.em.tep.cell;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Service;

import gr.pnet.em.error.InvalidRequestException;
import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.TepCell;
import gr.pnet.em.generated.tep.model.TepSubscriberLocation;
import gr.pnet.em.support.MongoStore;
import gr.pnet.em.support.Slice;
import gr.pnet.em.tep.status.SubscriberStatusService;
import gr.pnet.em.tep.status.UeContexts;

/** Cells, and the cell a subscriber is in. */
@Service
public class CellService {

    private final MongoOperations mongo;
    private final MongoStore<CellDocument> store;
    private final SubscriberStatusService statuses;

    public CellService(MongoOperations mongo, SubscriberStatusService statuses) {
        this.mongo = mongo;
        this.store = new MongoStore<>(mongo, CellDocument.class, "Cell");
        this.statuses = statuses;
    }

    public Slice<TepCell> list(int limit, int offset) {
        return store.page(limit, offset).map(CellDocument::toTep);
    }

    public TepCell get(String id) {
        return store.get(id).toTep();
    }

    /** Stores the cell under the id its plmn and nci give, and returns that id. */
    public String create(TepCell cell) {
        CellDocument document = CellDocument.from(cell);
        store.insert(document.id(), document);
        return document.id();
    }

    public void update(String id, TepCell cell) {
        CellDocument document = CellDocument.from(cell);
        if (!id.equals(document.id())) {
            throw new InvalidRequestException(
                    "The plmn and nci give the cell id " + document.id() + ", not the path id " + id);
        }
        store.replace(id, document);
    }

    public void delete(String id) {
        store.delete(id);
    }

    /**
     * Where the UE of the subscriber with this phone number last reported from: the cell with the NCGI the AMF has for it, with that cell's
     * coverage area if TEP has the cell. A UE that isn't registered in an NR cell has no answer (404).
     */
    public TepSubscriberLocation locate(String phoneNumber) {
        UeContexts.UeContext context = statuses.ueContext(phoneNumber);
        UeContexts.Location location = context == null ? null : context.location();
        UeContexts.NrCgi cgi = location == null ? null : location.nrCgi();
        if (cgi == null || cgi.plmn() == null || cgi.nci() == null) {
            throw new ResourceNotFoundException(
                    "Subscriber " + phoneNumber + " isn't registered in an NR cell, so it has no location");
        }
        String id = CellDocument.id(cgi.plmn(), cgi.nci());
        TepSubscriberLocation answer = new TepSubscriberLocation(id).lastUpdated(time(location.timestamp()));
        CellDocument cell = mongo.findById(id, CellDocument.class);
        if (cell != null && cell.center() != null) {
            answer.center(cell.center().toTep()).radius(cell.radius());
        }
        return answer;
    }

    // The AMF times a UE's location in microseconds since the epoch
    private static OffsetDateTime time(Long micros) {
        return micros == null ? null : Instant.EPOCH.plus(micros, ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
